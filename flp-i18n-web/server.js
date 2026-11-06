'use strict';

const http = require('node:http');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const { execFile } = require('node:child_process');
const { randomUUID } = require('node:crypto');

const ROOT = __dirname;
const PUBLIC_DIR = path.join(ROOT, 'public');
const DATA_DIR = path.join(ROOT, 'data');
const REPOS_DIR = path.join(DATA_DIR, 'repos');
const DB_FILE = path.join(DATA_DIR, 'projects.json');
const SETTINGS_FILE = path.join(DATA_DIR, 'settings.json');
const PORT = Number(process.env.PORT) || 4321;
const HOST = process.env.HOST || '127.0.0.1';
const MAX_BATCH = 50;
const BRANCH_RE = /^(?!-)(?!\/)(?!.*\.\.)(?!.*@\{)(?!.*\s)[A-Za-z0-9._\/+-]{1,200}$/;

fs.mkdirSync(REPOS_DIR, { recursive: true });

let projects = [];
try {
  const raw = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
  if (Array.isArray(raw)) projects = raw.filter((p) => p && p.id && p.localPath);
} catch {
  projects = [];
}

let settings = { defaultLocalDir: null };
try {
  const raw = JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8'));
  if (raw && typeof raw.defaultLocalDir === 'string' && raw.defaultLocalDir.trim()) {
    settings.defaultLocalDir = path.resolve(raw.defaultLocalDir);
  }
} catch {
  settings = { defaultLocalDir: null };
}

function cloneRoot() {
  return settings.defaultLocalDir || REPOS_DIR;
}

function settingsPayload() {
  return {
    defaultLocalDir: settings.defaultLocalDir,
    defaultDir: REPOS_DIR,
    effectiveDir: cloneRoot(),
    homeDir: os.homedir(),
    dataFile: DB_FILE,
  };
}

let writeQueue = Promise.resolve();
function enqueueWrite(file, snapshot) {
  writeQueue = writeQueue
    .then(async () => {
      const tmp = `${file}.tmp`;
      await fsp.writeFile(tmp, snapshot);
      await fsp.rename(tmp, file);
    })
    .catch((err) => console.error('[store] save failed:', err.message));
  return writeQueue;
}

function saveProjects() {
  return enqueueWrite(DB_FILE, JSON.stringify(projects, null, 2));
}

function saveSettings() {
  return enqueueWrite(SETTINGS_FILE, JSON.stringify(settings, null, 2));
}

async function setDefaultLocalDir(value) {
  const raw = String(value ?? '').trim();

  if (!raw) {
    settings.defaultLocalDir = null;
    await saveSettings();
    return settingsPayload();
  }
  if (raw.length > 1024 || /[\u0000-\u001f]/.test(raw)) {
    throw httpError(400, '目录路径格式不正确');
  }

  const resolved = path.resolve(expandHome(raw));
  if (resolved === path.parse(resolved).root) {
    throw httpError(400, '不能使用文件系统根目录');
  }

  try {
    await fsp.mkdir(resolved, { recursive: true });
    const stat = await fsp.stat(resolved);
    if (!stat.isDirectory()) throw httpError(400, `该路径不是目录：${resolved}`);
    await fsp.access(resolved, fs.constants.W_OK);
  } catch (err) {
    if (err.status) throw err;
    if (err.code === 'EEXIST' || err.code === 'ENOTDIR') {
      throw httpError(400, `该路径已存在且不是目录：${resolved}`);
    }
    if (err.code === 'EACCES' || err.code === 'EPERM') {
      throw httpError(400, `没有权限访问该目录：${resolved}`);
    }
    throw httpError(400, `目录不可用：${err.message}`);
  }

  settings.defaultLocalDir = resolved;
  await saveSettings();
  return settingsPayload();
}

function httpError(status, message) {
  const err = new Error(message);
  err.status = status;
  return err;
}

function execPicker(command, args, { timeout = 300000 } = {}) {
  return new Promise((resolve, reject) => {
    execFile(command, args, { timeout, killSignal: 'SIGKILL' }, (error, stdout, stderr) => {
      if (error) {
        error.stderrText = String(stderr || '');
        reject(error);
        return;
      }
      resolve(String(stdout).trim());
    });
  });
}

function pickerCanceled(error) {
  const text = `${error.stderrText || ''} ${error.message || ''}`;
  return /-128|user canceled|user cancelled|取消/i.test(text);
}

async function pickLocalFolder(startPath) {
  const home = os.homedir();
  const start = path.resolve(expandHome(String(startPath || '').trim()) || home);

  if (process.platform === 'darwin') {
    const script = [
      'on run argv',
      'set startPath to item 1 of argv',
      'try',
      'set startFolder to POSIX file startPath as alias',
      'on error',
      'set startFolder to path to home folder',
      'end try',
      'set chosenFolder to choose folder with prompt "选择已经下载的 Git 项目目录" default location startFolder',
      'return POSIX path of chosenFolder',
      'end run',
    ];
    const args = [];
    for (const line of script) args.push('-e', line);
    args.push(start);
    try {
      const output = await execPicker('osascript', args);
      if (!output) throw httpError(500, '目录选择器没有返回路径');
      return { path: path.resolve(output) };
    } catch (error) {
      if (error.status) throw error;
      if (error.killed) throw httpError(504, '目录选择器等待超时，请重新尝试');
      if (pickerCanceled(error)) return { canceled: true };
      throw httpError(500, `无法打开目录选择器：${(error.stderrText || error.message || '').trim().slice(0, 300)}`);
    }
  }

  if (process.platform === 'win32') {
    const script = [
      'Add-Type -AssemblyName System.Windows.Forms;',
      "$d = New-Object System.Windows.Forms.FolderBrowserDialog;",
      "$d.Description = '选择已经下载的 Git 项目目录';",
      'if ($args.Count -gt 0) { $d.SelectedPath = $args[0] };',
      "if ($d.ShowDialog() -eq 'OK') { $d.SelectedPath } else { exit 2 }",
    ].join(' ');
    try {
      const output = await execPicker('powershell.exe', ['-NoProfile', '-STA', '-Command', script, start]);
      if (!output) return { canceled: true };
      return { path: path.resolve(output) };
    } catch (error) {
      if (error.status) throw error;
      if (error.killed) throw httpError(504, '目录选择器等待超时，请重新尝试');
      if (error.code === 2) return { canceled: true };
      throw httpError(500, '无法打开目录选择器，请直接粘贴路径');
    }
  }

  const attempts = [
    ['zenity', ['--file-selection', '--directory', '--title=选择 Git 项目目录']],
    ['kdialog', ['--getexistingdirectory', start, '--title', '选择 Git 项目目录']],
  ];
  for (const [command, args] of attempts) {
    try {
      const output = await execPicker(command, args);
      if (!output) return { canceled: true };
      return { path: path.resolve(output) };
    } catch (error) {
      if (error.killed) throw httpError(504, '目录选择器等待超时，请重新尝试');
      if (error.code === 'ENOENT') continue;
      if (error.code === 1) return { canceled: true };
      throw httpError(500, `无法打开目录选择器：${(error.stderrText || error.message || '').trim().slice(0, 300)}`);
    }
  }
  throw httpError(501, '当前环境没有可用的目录选择器，请直接粘贴路径');
}

function git(args, { timeout = 60000 } = {}) {
  return new Promise((resolve, reject) => {
    execFile(
      'git',
      args,
      {
        timeout,
        killSignal: 'SIGKILL',
        maxBuffer: 20 * 1024 * 1024,
        env: { ...process.env, GIT_TERMINAL_PROMPT: '0', LC_ALL: 'C' },
      },
      (error, stdout, stderr) => {
        if (error) {
          const err = new Error(cleanGitError(stderr || error.message));
          err.raw = String(stderr || error.message || '');
          reject(err);
          return;
        }
        resolve({ stdout: String(stdout), stderr: String(stderr) });
      },
    );
  });
}

function cleanGitError(text) {
  const lines = String(text || '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  const meaningful = [...lines]
    .reverse()
    .find((line) => /^(fatal|error|remote: error|hint:)/i.test(line));
  return (meaningful || lines[lines.length - 1] || 'git command failed').slice(0, 500);
}

function lastLine(text) {
  const lines = String(text || '')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
  return lines[lines.length - 1] || '';
}

function expandHome(value) {
  if (value === '~') return os.homedir();
  if (value.startsWith('~/')) return path.join(os.homedir(), value.slice(2));
  return value;
}

function deriveName(input) {
  const cleaned = String(input || '')
    .trim()
    .replace(/[\/\\]+$/, '')
    .replace(/\.git$/i, '');
  const parts = cleaned.split(/[\/\\:]/).filter(Boolean);
  return parts[parts.length - 1] || 'repository';
}

function slugify(input) {
  const slug = String(input || '')
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-')
    .replace(/^[-.]+|[-.]+$/g, '')
    .slice(0, 80);
  return slug || 'repository';
}

async function uniqueDest(base, root = cloneRoot()) {
  let candidate = path.join(root, base);
  let counter = 2;
  while (fs.existsSync(candidate) || projects.some((p) => path.resolve(p.localPath) === candidate)) {
    candidate = path.join(root, `${base}-${counter}`);
    counter += 1;
    if (counter > 500) throw httpError(409, 'Could not allocate a destination folder');
  }
  return candidate;
}

async function mapPool(items, limit, fn) {
  const results = new Array(items.length);
  let cursor = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor;
      cursor += 1;
      results[index] = await fn(items[index], index);
    }
  });
  await Promise.all(workers);
  return results;
}

function parsePorcelainStatus(out) {
  let branch = null;
  let upstream = null;
  let ahead = 0;
  let behind = 0;
  let dirty = 0;

  for (const line of out.split('\n')) {
    if (!line) continue;
    let match;
    if ((match = line.match(/^# branch\.head (.+)$/))) {
      branch = match[1].trim();
    } else if ((match = line.match(/^# branch\.upstream (.+)$/))) {
      upstream = match[1].trim();
    } else if ((match = line.match(/^# branch\.ab \+(\d+) -(\d+)/))) {
      ahead = Number(match[1]);
      behind = Number(match[2]);
    } else if (!line.startsWith('#')) {
      dirty += 1;
    }
  }

  return { branch, upstream, ahead, behind, dirty };
}

async function readProjectState(project) {
  const base = {
    id: project.id,
    name: project.name,
    repoUrl: project.repoUrl,
    localPath: project.localPath,
    createdAt: project.createdAt || null,
    lastCheckedAt: project.lastCheckedAt || null,
  };

  if (!project.localPath || !fs.existsSync(project.localPath)) {
    return { ...base, health: 'missing', error: '本地目录不存在' };
  }

  const isRepo = await git(['-C', project.localPath, 'rev-parse', '--is-inside-work-tree'], {
    timeout: 8000,
  })
    .then((res) => res.stdout.trim() === 'true')
    .catch(() => false);

  if (!isRepo) {
    return { ...base, health: 'error', error: '该目录不是 Git 仓库' };
  }

  try {
    const [branchRes, commitRes, statusRes, remoteRes] = await Promise.all([
      git(['-C', project.localPath, 'rev-parse', '--abbrev-ref', 'HEAD'], { timeout: 8000 }).catch(
        () => ({ stdout: '' }),
      ),
      git(['-C', project.localPath, 'log', '-1', '--format=%H%x1f%h%x1f%s%x1f%an%x1f%cI'], {
        timeout: 8000,
      }).catch(() => ({ stdout: '' })),
      git(['-C', project.localPath, 'status', '--porcelain=v2', '--branch'], { timeout: 20000 }),
      git(['-C', project.localPath, 'remote', 'get-url', 'origin'], { timeout: 8000 }).catch(() => ({
        stdout: '',
      })),
    ]);

    const status = parsePorcelainStatus(statusRes.stdout);
    const fields = commitRes.stdout.trim().split('\x1f');
    const commit =
      fields.length === 5
        ? {
            hash: fields[0],
            shortHash: fields[1],
            subject: fields[2],
            author: fields[3],
            committedAt: fields[4],
          }
        : null;
    const head = branchRes.stdout.trim();

    return {
      ...base,
      health: 'ok',
      empty: !head,
      branch: status.branch && status.branch !== '(detached)' ? status.branch : head || null,
      detached: head === 'HEAD',
      upstream: status.upstream,
      ahead: status.ahead,
      behind: status.behind,
      dirty: status.dirty,
      remoteUrl: remoteRes.stdout.trim() || null,
      commit,
    };
  } catch (err) {
    return { ...base, health: 'error', error: err.message };
  }
}

async function addProject(input) {
  const repoUrl = String(input.repoUrl || '').trim();
  if (!repoUrl) throw httpError(400, '请填写仓库地址');
  if (repoUrl.length > 2048 || /[\u0000-\u001f]/.test(repoUrl)) {
    throw httpError(400, '仓库地址格式不正确');
  }

  const customName = input.name ? String(input.name).trim().slice(0, 120) : '';
  let localPath = '';
  if (input.localPath && String(input.localPath).trim()) {
    localPath = path.resolve(expandHome(String(input.localPath).trim()));
  }

  if (projects.some((p) => p.repoUrl === repoUrl)) {
    throw httpError(409, '该仓库已在列表中');
  }
  if (localPath && projects.some((p) => path.resolve(p.localPath) === localPath)) {
    throw httpError(409, '该本地目录已在列表中');
  }

  const dest = localPath || (await uniqueDest(slugify(customName || deriveName(repoUrl))));
  let cloned = false;

  if (fs.existsSync(dest)) {
    const isRepo = await git(['-C', dest, 'rev-parse', '--is-inside-work-tree'], { timeout: 8000 })
      .then((res) => res.stdout.trim() === 'true')
      .catch(() => false);
    if (!isRepo) throw httpError(409, `目录已存在但不是 Git 仓库：${dest}`);
  } else {
    await fsp.mkdir(path.dirname(dest), { recursive: true });
    await git(['clone', '--', repoUrl, dest], { timeout: 300000 });
    cloned = true;
  }

  const project = {
    id: randomUUID(),
    name: customName || deriveName(repoUrl),
    repoUrl,
    localPath: dest,
    createdAt: new Date().toISOString(),
    lastCheckedAt: null,
  };

  projects.push(project);
  await saveProjects();
  return { project: await readProjectState(project), cloned };
}

async function importLocalProject(input) {
  const rawPath = String(input.localPath || '').trim();
  if (!rawPath) throw httpError(400, '请填写本地目录');
  if (rawPath.length > 1024 || /[\u0000-\u001f]/.test(rawPath)) {
    throw httpError(400, '目录路径格式不正确');
  }

  const candidate = path.resolve(expandHome(rawPath));
  const stat = await fsp.stat(candidate).catch(() => null);
  if (!stat) throw httpError(404, `目录不存在：${candidate}`);
  if (!stat.isDirectory()) throw httpError(400, `该路径不是目录：${candidate}`);

  let root = '';
  try {
    const res = await git(['-C', candidate, 'rev-parse', '--show-toplevel'], { timeout: 8000 });
    root = path.resolve(res.stdout.trim());
  } catch {
    throw httpError(400, '该目录不是 Git 项目（未找到 .git 仓库）');
  }
  if (!root) throw httpError(400, '该目录不是 Git 项目（未找到 .git 仓库）');

  if (projects.some((p) => path.resolve(p.localPath) === root)) {
    throw httpError(409, '该 Git 项目已在列表中');
  }

  const remotes = await git(['-C', root, 'remote'], { timeout: 8000 })
    .then((res) => res.stdout.split('\n').map((line) => line.trim()).filter(Boolean))
    .catch(() => []);

  let repoUrl = '';
  for (const name of ['origin', remotes[0]].filter(Boolean)) {
    repoUrl = await git(['-C', root, 'remote', 'get-url', name], { timeout: 8000 })
      .then((res) => res.stdout.trim())
      .catch(() => '');
    if (repoUrl) break;
  }
  repoUrl = repoUrl || root;

  if (projects.some((p) => p.repoUrl === repoUrl)) {
    throw httpError(409, '该仓库已在列表中');
  }

  const customName = input.name ? String(input.name).trim().slice(0, 120) : '';
  const project = {
    id: randomUUID(),
    name: customName || deriveName(repoUrl),
    repoUrl,
    localPath: root,
    createdAt: new Date().toISOString(),
    lastCheckedAt: null,
  };

  projects.push(project);
  await saveProjects();
  return { project: await readProjectState(project), imported: true };
}

async function addBatch(items) {
  if (!Array.isArray(items) || !items.length) throw httpError(400, '没有可添加的仓库');
  if (items.length > MAX_BATCH) throw httpError(400, `单次最多添加 ${MAX_BATCH} 个仓库`);

  return mapPool(items.slice(0, MAX_BATCH), 2, async (item) => {
    try {
      const result = await addProject(item || {});
      return {
        repoUrl: item.repoUrl,
        ok: true,
        cloned: result.cloned,
        project: result.project,
      };
    } catch (err) {
      return { repoUrl: item && item.repoUrl, ok: false, error: err.message };
    }
  });
}

async function updateProject(project) {
  if (!fs.existsSync(project.localPath)) throw httpError(404, '本地目录不存在');

  const remotes = await git(['-C', project.localPath, 'remote'], { timeout: 8000 }).catch(() => ({
    stdout: '',
  }));
  if (!remotes.stdout.trim()) {
    project.lastCheckedAt = new Date().toISOString();
    await saveProjects();
    return {
      project: await readProjectState(project),
      pull: { ok: false, skipped: true, message: '没有配置远程仓库，已跳过更新' },
      upstream: null,
    };
  }

  await git(['-C', project.localPath, 'fetch', '--all', '--prune'], { timeout: 240000 });

  let pull = { ok: false, message: '已 fetch(未配置 upstream)' };
  const upstream = await git(
    ['-C', project.localPath, 'rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}'],
    { timeout: 8000 },
  )
    .then((res) => res.stdout.trim())
    .catch(() => '');

  if (upstream) {
    try {
      const res = await git(['-C', project.localPath, 'pull', '--ff-only'], { timeout: 240000 });
      pull = { ok: true, message: lastLine(res.stdout) || 'Already up to date.' };
    } catch (err) {
      pull = { ok: false, message: err.message };
    }
  }

  project.lastCheckedAt = new Date().toISOString();
  await saveProjects();
  return { project: await readProjectState(project), pull, upstream };
}

async function updateBatch(ids) {
  const list =
    Array.isArray(ids) && ids.length
      ? projects.filter((project) => ids.includes(project.id))
      : [...projects];
  if (!list.length) throw httpError(400, '没有可更新的仓库');

  return mapPool(list, 3, async (project) => {
    try {
      const result = await updateProject(project);
      return {
        id: project.id,
        name: project.name,
        ok: true,
        pull: result.pull,
        project: result.project,
      };
    } catch (err) {
      return { id: project.id, name: project.name, ok: false, error: err.message };
    }
  });
}

function validateBranchName(branch) {
  if (!BRANCH_RE.test(branch) || branch.endsWith('/') || branch.endsWith('.lock')) {
    throw httpError(400, `分支名不合法：${branch}`);
  }
}

async function refExists(project, ref) {
  return git(['-C', project.localPath, 'rev-parse', '--verify', '--quiet', ref], { timeout: 8000 })
    .then(() => true)
    .catch(() => false);
}

async function listBranches(project) {
  if (!fs.existsSync(project.localPath)) throw httpError(404, '本地目录不存在');

  // Branch names cannot contain whitespace, so tab is a safe field delimiter.
  const localFormat = '%(refname:short)\t%(HEAD)\t%(upstream:short)\t%(committerdate:short)';
  const localRes = await git(['-C', project.localPath, 'branch', `--format=${localFormat}`], {
    timeout: 20000,
  });

  const local = localRes.stdout
    .split('\n')
    .filter(Boolean)
    .map((line) => {
      const [name, head, upstream, date] = line.split('\t');
      return { name, current: head === '*', upstream: upstream || null, date: date || null };
    })
    .sort((a, b) => Number(b.current) - Number(a.current) || a.name.localeCompare(b.name));

  let remote = [];
  try {
    const remoteRes = await git(
      [
        '-C',
        project.localPath,
        'branch',
        '-r',
        '--format=%(refname:short)\t%(committerdate:short)',
      ],
      { timeout: 20000 },
    );
    remote = remoteRes.stdout
      .split('\n')
      .filter(Boolean)
      .map((line) => line.split('\t'))
      .filter(([name]) => name.startsWith('origin/') && !name.endsWith('/HEAD'))
      .map(([name, date]) => ({ name: name.slice(7), remote: 'origin', date: date || null }))
      .sort((a, b) => a.name.localeCompare(b.name));
  } catch {
    remote = [];
  }

  const current = (local.find((item) => item.current) || {}).name || null;
  return { current, local, remote };
}

async function checkoutBranch(project, branch) {
  if (!branch) throw httpError(400, '请指定分支');
  validateBranchName(String(branch));
  if (!fs.existsSync(project.localPath)) throw httpError(404, '本地目录不存在');

  const name = String(branch);
  const hasLocal = await refExists(project, `refs/heads/${name}`);
  if (hasLocal) {
    await git(['-C', project.localPath, 'checkout', name], { timeout: 60000 });
  } else {
    const hasRemote = await refExists(project, `refs/remotes/origin/${name}`);
    if (!hasRemote) throw httpError(404, `分支 "${name}" 不存在`);
    await git(['-C', project.localPath, 'checkout', '-b', name, '--track', `origin/${name}`], {
      timeout: 60000,
    });
  }

  project.lastCheckedAt = new Date().toISOString();
  await saveProjects();
  return readProjectState(project);
}

async function createBranch(project, branch, base) {
  if (!fs.existsSync(project.localPath)) throw httpError(404, '本地目录不存在');

  const hasLocal = await refExists(project, `refs/heads/${branch}`);
  if (hasLocal) {
    await git(['-C', project.localPath, 'checkout', branch], { timeout: 60000 });
    return { created: false, message: `已切换到已存在的分支 ${branch}` };
  }

  let baseRef = '';
  if (base) {
    if (await refExists(project, `refs/heads/${base}`)) baseRef = base;
    else if (await refExists(project, `refs/remotes/origin/${base}`)) baseRef = `origin/${base}`;
    else throw httpError(404, `基线分支 "${base}" 不存在`);
  }

  const args = ['-C', project.localPath, 'checkout', '-b', branch];
  if (baseRef) args.push(baseRef);
  await git(args, { timeout: 60000 });

  return { created: true, message: `已创建 ${branch}${baseRef ? `（基于 ${baseRef}）` : ''}` };
}

async function branchBatch(ids, branch, base) {
  const name = String(branch || '').trim();
  validateBranchName(name);
  const baseName = base ? String(base).trim() : '';
  if (baseName) validateBranchName(baseName);

  const list = Array.isArray(ids)
    ? projects.filter((project) => ids.includes(project.id))
    : [];
  if (!list.length) throw httpError(400, '请至少选择一个项目');

  return mapPool(list, 3, async (project) => {
    try {
      const result = await createBranch(project, name, baseName);
      project.lastCheckedAt = new Date().toISOString();
      await saveProjects();
      return {
        id: project.id,
        name: project.name,
        ok: true,
        created: result.created,
        message: result.message,
        project: await readProjectState(project),
      };
    } catch (err) {
      return { id: project.id, name: project.name, ok: false, error: err.message };
    }
  });
}

async function commitProject(project, message) {
  if (!fs.existsSync(project.localPath)) throw httpError(404, '本地目录不存在');

  const msg = String(message || '').trim();
  if (!msg) throw httpError(400, '请填写提交说明');
  if (msg.length > 4000 || msg.includes('\u0000')) throw httpError(400, '提交说明格式不正确');

  const isRepo = await git(['-C', project.localPath, 'rev-parse', '--is-inside-work-tree'], {
    timeout: 8000,
  })
    .then((res) => res.stdout.trim() === 'true')
    .catch(() => false);
  if (!isRepo) throw httpError(400, '该目录不是 Git 仓库');

  const status = await git(['-C', project.localPath, 'status', '--porcelain'], { timeout: 20000 });
  if (!status.stdout.trim()) throw httpError(400, '没有可提交的改动');

  await git(['-C', project.localPath, 'add', '-A'], { timeout: 60000 });

  const staged = await git(['-C', project.localPath, 'diff', '--cached', '--name-only'], {
    timeout: 20000,
  });
  const files = staged.stdout.split('\n').filter(Boolean);
  if (!files.length) throw httpError(400, '暂存区没有可提交的改动');

  await git(['-C', project.localPath, 'commit', '-m', msg], { timeout: 60000 });

  const head = await git(['-C', project.localPath, 'log', '-1', '--format=%H%x1f%h%x1f%s'], {
    timeout: 8000,
  });
  const [hash, shortHash, subject] = head.stdout.trim().split('\x1f');

  project.lastCheckedAt = new Date().toISOString();
  await saveProjects();

  return {
    project: await readProjectState(project),
    commit: { hash, shortHash, subject, files: files.length },
  };
}

async function pushProject(project) {
  if (!fs.existsSync(project.localPath)) throw httpError(404, '本地目录不存在');

  const isRepo = await git(['-C', project.localPath, 'rev-parse', '--is-inside-work-tree'], {
    timeout: 8000,
  })
    .then((res) => res.stdout.trim() === 'true')
    .catch(() => false);
  if (!isRepo) throw httpError(400, '该目录不是 Git 仓库');

  const hasHead = await git(['-C', project.localPath, 'rev-parse', '--verify', '--quiet', 'HEAD'], {
    timeout: 8000,
  })
    .then(() => true)
    .catch(() => false);
  if (!hasHead) throw httpError(400, '仓库还没有任何提交，无法推送');

  const head = await git(['-C', project.localPath, 'rev-parse', '--abbrev-ref', 'HEAD'], {
    timeout: 8000,
  });
  const branch = head.stdout.trim();
  if (!branch || branch === 'HEAD') throw httpError(400, '当前处于游离 HEAD，无法推送');

  const remotes = await git(['-C', project.localPath, 'remote'], { timeout: 8000 })
    .then((res) => res.stdout.split('\n').map((line) => line.trim()).filter(Boolean))
    .catch(() => []);
  if (!remotes.length) throw httpError(400, '没有配置远程仓库，无法推送');

  const upstream = await git(
    ['-C', project.localPath, 'rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}'],
    { timeout: 8000 },
  )
    .then((res) => res.stdout.trim())
    .catch(() => '');

  const args = ['-C', project.localPath, 'push'];
  let setUpstream = false;
  if (!upstream) {
    const remote = remotes.includes('origin') ? 'origin' : remotes[0];
    args.push('-u', remote, branch);
    setUpstream = true;
  }

  await git(args, { timeout: 240000 });

  project.lastCheckedAt = new Date().toISOString();
  await saveProjects();

  return {
    project: await readProjectState(project),
    branch,
    upstream: upstream || `${remotes.includes('origin') ? 'origin' : remotes[0]}/${branch}`,
    setUpstream,
  };
}

async function removeProject(id) {
  const index = projects.findIndex((project) => project.id === id);
  if (index === -1) throw httpError(404, '项目不存在');
  const [removed] = projects.splice(index, 1);
  await saveProjects();
  return removed;
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.json': 'application/json; charset=utf-8',
};

async function serveStatic(res, pathname) {
  const rel = pathname === '/' ? 'index.html' : decodeURIComponent(pathname).replace(/^\/+/, '');
  const filePath = path.resolve(PUBLIC_DIR, rel);
  if (filePath !== path.join(PUBLIC_DIR, 'index.html') && !filePath.startsWith(PUBLIC_DIR + path.sep)) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Forbidden');
    return;
  }
  try {
    const data = await fsp.readFile(filePath);
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-cache',
    });
    res.end(data);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
  }
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 2 * 1024 * 1024) {
        reject(httpError(413, '请求体过大'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        reject(httpError(400, '请求体不是合法的 JSON'));
      }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, body) {
  const payload = JSON.stringify(body);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  res.end(payload);
}

async function handleApi(req, res, url) {
  const pathname = url.pathname.replace(/\/+$/, '');
  const method = req.method || 'GET';

  if (pathname === '/api/health' && method === 'GET') {
    return sendJson(res, 200, { ok: true, projects: projects.length, dataFile: DB_FILE });
  }

  if (pathname === '/api/settings' && method === 'GET') {
    return sendJson(res, 200, { ok: true, settings: settingsPayload() });
  }

  if (pathname === '/api/settings' && method === 'PUT') {
    const body = await readJson(req);
    const payload = await setDefaultLocalDir(body.defaultLocalDir);
    return sendJson(res, 200, { ok: true, settings: payload });
  }

  if (pathname === '/api/projects' && method === 'GET') {
    const enriched = await mapPool(projects.slice(), 5, readProjectState);
    return sendJson(res, 200, { ok: true, projects: enriched });
  }

  if (pathname === '/api/projects' && method === 'POST') {
    const body = await readJson(req);
    const result = await addProject(body);
    return sendJson(res, 201, { ok: true, ...result });
  }

  if (pathname === '/api/fs/pick-folder' && method === 'POST') {
    const body = await readJson(req);
    const picked = await pickLocalFolder(body.startPath);
    if (picked.canceled) return sendJson(res, 200, { ok: true, canceled: true });

    const gitRoot = await git(['-C', picked.path, 'rev-parse', '--show-toplevel'], { timeout: 8000 })
      .then((res) => res.stdout.trim())
      .catch(() => '');
    const target = gitRoot ? path.resolve(gitRoot) : picked.path;
    return sendJson(res, 200, {
      ok: true,
      path: target,
      isGit: Boolean(gitRoot),
      name: deriveName(target),
    });
  }

  if (pathname === '/api/projects/import-local' && method === 'POST') {
    const body = await readJson(req);
    const result = await importLocalProject(body);
    return sendJson(res, 201, { ok: true, ...result });
  }

  if (pathname === '/api/projects/batch' && method === 'POST') {
    const body = await readJson(req);
    const results = await addBatch(body.items);
    return sendJson(res, 200, { ok: true, results });
  }

  if (pathname === '/api/projects/update-batch' && method === 'POST') {
    const body = await readJson(req);
    const results = await updateBatch(body.ids);
    return sendJson(res, 200, { ok: true, results });
  }

  if (pathname === '/api/projects/branch-batch' && method === 'POST') {
    const body = await readJson(req);
    const results = await branchBatch(body.ids, body.branch, body.base);
    return sendJson(res, 200, { ok: true, results });
  }

  const match = pathname.match(
    /^\/api\/projects\/([A-Za-z0-9-]+)(?:\/(branches|update|checkout|commit|push))?$/,
  );
  if (match) {
    const [, id, action] = match;
    const project = projects.find((item) => item.id === id);
    if (!project) throw httpError(404, '项目不存在');

    if (!action && method === 'GET') {
      return sendJson(res, 200, { ok: true, project: await readProjectState(project) });
    }
    if (!action && method === 'DELETE') {
      const removed = await removeProject(id);
      return sendJson(res, 200, { ok: true, removed });
    }
    if (action === 'branches' && method === 'GET') {
      return sendJson(res, 200, { ok: true, branches: await listBranches(project) });
    }
    if (action === 'update' && method === 'POST') {
      return sendJson(res, 200, { ok: true, ...(await updateProject(project)) });
    }
    if (action === 'checkout' && method === 'POST') {
      const body = await readJson(req);
      return sendJson(res, 200, { ok: true, project: await checkoutBranch(project, body.branch) });
    }
    if (action === 'commit' && method === 'POST') {
      const body = await readJson(req);
      return sendJson(res, 200, { ok: true, ...(await commitProject(project, body.message)) });
    }
    if (action === 'push' && method === 'POST') {
      return sendJson(res, 200, { ok: true, ...(await pushProject(project)) });
    }
    throw httpError(405, '请求方法不支持');
  }

  throw httpError(404, '接口不存在');
}

const server = http.createServer((req, res) => {
  let url;
  try {
    url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  } catch {
    res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Bad request');
    return;
  }

  if (url.pathname.startsWith('/api/')) {
    handleApi(req, res, url).catch((err) => {
      if (res.headersSent) {
        res.end();
        return;
      }
      const status = err.status || 500;
      if (status >= 500) console.error('[api]', err.raw || err.message);
      sendJson(res, status, { ok: false, error: err.message || '服务器错误' });
    });
    return;
  }

  serveStatic(res, url.pathname).catch((err) => {
    if (!res.headersSent) {
      res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Internal error');
    }
    console.error('[static]', err.message);
  });
});

server.on('error', (err) => {
  if (err.code === 'EADDRINUSE') {
    console.error(`\n  Port ${PORT} is already in use. Try: PORT=4400 npm start\n`);
  } else {
    console.error('[server]', err.message);
  }
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  git(['--version'])
    .then((res) => {
      console.log('\n  GIT CONTROL ROOM');
      console.log(`  → http://localhost:${PORT}`);
      console.log(`  ${res.stdout.trim()}`);
      console.log(`  store: ${path.relative(ROOT, DB_FILE)}\n`);
    })
    .catch(() => {
      console.log('\n  GIT CONTROL ROOM');
      console.log(`  → http://localhost:${PORT}`);
      console.log('  warning: git executable not found\n');
    });
});

process.on('SIGINT', () => {
  server.close(() => process.exit(0));
});
process.on('SIGTERM', () => {
  server.close(() => process.exit(0));
});

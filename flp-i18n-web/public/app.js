'use strict';

(() => {
  const ICON = {
    refresh:
      '<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M13.5 7.2A5.6 5.6 0 1 0 12 11.6"/><path d="M13.5 2.8v4.4H9.1"/></svg>',
    branch:
      '<svg viewBox="0 0 16 16" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="4.3" cy="3.6" r="1.7"/><circle cx="4.3" cy="12.4" r="1.7"/><circle cx="11.7" cy="6.6" r="1.7"/><path d="M4.3 5.3v5.4M11.7 8.3c0 2.1-1.7 4.1-4.2 4.1"/></svg>',
    trash:
      '<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 4.6h10M6.3 2.6h3.4M4.4 4.6l.6 8.3h5.7l.6-8.3"/></svg>',
    copy:
      '<svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5.6" y="5.6" width="7.4" height="7.4" rx="1"/><path d="M10.4 5.6V3.7a1 1 0 0 0-1-1h-5.7a1 1 0 0 0-1 1v5.7a1 1 0 0 0 1 1h1.9"/></svg>',
    commit:
      '<svg viewBox="0 0 16 16" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="8" cy="8" r="5.6"/><path d="m5.3 8.2 1.8 1.8 3.6-4"/></svg>',
    push:
      '<svg viewBox="0 0 16 16" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M8 10.6V3.4M5.3 6.1 8 3.4l2.7 2.7"/><path d="M3 11.2v1.3a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1v-1.3"/></svg>',
    star:
      '<svg viewBox="0 0 24 24" width="44" height="44" fill="none" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" aria-hidden="true"><path d="M12 2.5v19M3.7 7.2l16.6 9.6M20.3 7.2L3.7 16.8"/></svg>',
  };

  const BRANCH_RE = /^(?!-)(?!\/)(?!.*\.\.)(?!.*@\{)(?!.*\s)[A-Za-z0-9._\/+-]{1,200}$/;
  const DRAWERS = {
    add: '#addDrawer',
    import: '#importDrawer',
    batch: '#batchDrawer',
    branch: '#branchDrawer',
    commit: '#commitDrawer',
    settings: '#settingsDrawer',
  };
  const ACTIVITY_KINDS = ['add', 'update', 'branch', 'commit', 'push', 'settings', 'remove', 'error'];

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ESCAPES[char]);

  const pad2 = (value) => String(value).padStart(2, '0');

  const state = {
    projects: [],
    loading: true,
    firstRows: true,
    filter: 'all',
    query: '',
    sort: 'name',
    expanded: null,
    branchCache: {},
    busy: new Set(),
    batchBusy: false,
    activity: readActivity(),
    live: readLive(),
    lastLoaded: null,
    settings: null,
  };

  let modalAction = null;
  let importNameAuto = '';
  let commitProjectId = null;

  /* ── small utilities ─────────────────────── */

  function readActivity() {
    try {
      const raw = JSON.parse(localStorage.getItem('gcr:activity') || '[]');
      if (!Array.isArray(raw)) return [];
      return raw
        .filter((item) => item && typeof item.text === 'string' && Number.isFinite(item.at))
        .slice(0, 8);
    } catch {
      return [];
    }
  }

  function readLive() {
    try {
      return localStorage.getItem('gcr:live') !== '0';
    } catch {
      return true;
    }
  }

  function clockTime(timestamp) {
    const date = new Date(timestamp);
    return `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`;
  }

  function fullDate(iso) {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return '—';
    return date.toLocaleString('zh-CN', { hour12: false });
  }

  function timeAgo(iso) {
    const time = Date.parse(iso);
    if (!time) return '—';
    const diff = Math.max(0, Date.now() - time);
    const minutes = Math.floor(diff / 60000);
    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} 小时前`;
    const days = Math.floor(hours / 24);
    if (days < 30) return `${days} 天前`;
    const date = new Date(time);
    return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
  }

  function statusOf(project) {
    if (project.health !== 'ok') return 'offline';
    if (project.dirty > 0) return 'dirty';
    if (project.ahead > 0 || project.behind > 0) return 'drift';
    return 'clean';
  }

  function statusMeta(project) {
    const key = statusOf(project);
    if (key === 'dirty') return { key, label: 'DIRTY' };
    if (key === 'drift') return { key, label: 'DRIFT' };
    if (key === 'offline') return { key, label: project.health === 'missing' ? 'MISSING' : 'ERROR' };
    return { key, label: 'CLEAN' };
  }

  function countByStatus() {
    const counts = { all: state.projects.length, dirty: 0, drift: 0, clean: 0, offline: 0 };
    for (const project of state.projects) counts[statusOf(project)] += 1;
    return counts;
  }

  async function api(pathname, options = {}) {
    const init = {
      ...options,
      headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    };
    if (options.body && typeof options.body !== 'string') init.body = JSON.stringify(options.body);
    const response = await fetch(pathname, init);
    let payload = null;
    try {
      payload = await response.json();
    } catch {
      payload = null;
    }
    if (!response.ok || !payload || payload.ok === false) {
      throw new Error((payload && payload.error) || `请求失败 (${response.status})`);
    }
    return payload;
  }

  function isOverlayOpen() {
    return Boolean(document.querySelector('.drawer.is-open')) || !$('#modalOverlay').hidden;
  }

  /* ── toasts / activity ───────────────────── */

  function toast(kind, title, detail = '') {
    const stack = $('#toastStack');
    const node = document.createElement('div');
    node.className = `toast toast-${kind}`;
    const mark = kind === 'success' ? '✓' : kind === 'error' ? '!' : 'i';
    node.innerHTML = `
      <div class="toast-icon">${mark}</div>
      <div>
        <p class="toast-title">${esc(title)}</p>
        ${detail ? `<p class="toast-detail">${esc(detail)}</p>` : ''}
      </div>
      <button class="toast-close" type="button" aria-label="关闭提示">×</button>`;
    const close = () => {
      node.classList.add('is-out');
      setTimeout(() => node.remove(), 260);
    };
    node.querySelector('.toast-close').addEventListener('click', close);
    stack.appendChild(node);
    while (stack.children.length > 4) stack.firstElementChild.remove();
    setTimeout(close, kind === 'error' ? 7000 : 4300);
  }

  function logActivity(kind, text) {
    const safeKind = ACTIVITY_KINDS.includes(kind) ? kind : 'update';
    state.activity.unshift({ kind: safeKind, text, at: Date.now() });
    state.activity = state.activity.slice(0, 8);
    try {
      localStorage.setItem('gcr:activity', JSON.stringify(state.activity));
    } catch {
      /* storage is optional */
    }
    renderActivity();
  }

  /* ── rendering ───────────────────────────── */

  function render() {
    const counts = countByStatus();

    $('#heroCount').textContent = pad2(state.projects.length);
    $('#countAll').textContent = counts.all;
    $('#countDirty').textContent = counts.dirty;
    $('#countDrift').textContent = counts.drift;
    $('#countClean').textContent = counts.clean;
    $('#countOffline').textContent = counts.offline;

    $$('#filterChips .chip').forEach((chip) => {
      chip.classList.toggle('is-active', chip.dataset.filter === state.filter);
    });

    $('#updateAllBtn').disabled = state.batchBusy;
    $('#updateAllBtn').classList.toggle('is-busy', state.batchBusy);

    renderList();
    renderSide(counts);
    renderActivity();
    updateLiveUI();
  }

  function getVisibleProjects() {
    const query = state.query.trim().toLowerCase();
    const list = state.projects.filter((project) => {
      if (state.filter !== 'all' && statusOf(project) !== state.filter) return false;
      if (!query) return true;
      const haystack = [
        project.name,
        project.repoUrl,
        project.localPath,
        project.branch,
        project.commit && project.commit.subject,
      ]
        .filter(Boolean)
        .join('\n')
        .toLowerCase();
      return haystack.includes(query);
    });

    if (state.sort === 'updated') {
      list.sort(
        (a, b) =>
          (Date.parse((b.commit && b.commit.committedAt) || b.createdAt || 0) || 0) -
          (Date.parse((a.commit && a.commit.committedAt) || a.createdAt || 0) || 0),
      );
    } else if (state.sort === 'status') {
      const weight = { offline: 0, dirty: 1, drift: 2, clean: 3 };
      list.sort(
        (a, b) =>
          weight[statusOf(a)] - weight[statusOf(b)] ||
          a.name.localeCompare(b.name, 'zh-CN', { sensitivity: 'base' }),
      );
    } else {
      list.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN', { sensitivity: 'base' }));
    }
    return list;
  }

  function renderList() {
    const listNode = $('#projectList');
    const emptyNode = $('#emptyState');

    if (state.loading && !state.projects.length) {
      emptyNode.hidden = true;
      listNode.innerHTML = skeletonMarkup();
      return;
    }

    if (!state.projects.length) {
      listNode.innerHTML = '';
      emptyNode.hidden = false;
      emptyNode.innerHTML = emptyMarkup('none');
      return;
    }

    const visible = getVisibleProjects();
    if (!visible.length) {
      listNode.innerHTML = '';
      emptyNode.hidden = false;
      emptyNode.innerHTML = emptyMarkup('no-match');
      return;
    }

    emptyNode.hidden = true;
    listNode.innerHTML = visible.map((project, index) => rowMarkup(project, index)).join('');

    if (state.firstRows) {
      listNode.classList.add('stagger');
      state.firstRows = false;
      setTimeout(() => listNode.classList.remove('stagger'), 1200);
    }
  }

  function skeletonMarkup() {
    return Array.from(
      { length: 4 },
      (_, index) => `
      <div class="row is-skeleton" style="--i:${index}">
        <div class="cell idx">${pad2(index + 1)}</div>
        <div class="cell main"><div class="sk sk-title"></div><div class="sk sk-line"></div></div>
        <div class="cell branch-cell"><div class="sk sk-chip"></div></div>
        <div class="cell sync"><div class="sk sk-chip"></div></div>
        <div class="cell commit"><div class="sk sk-line" style="width:80%"></div><div class="sk sk-line" style="width:45%"></div></div>
        <div class="cell actions"></div>
      </div>`,
    ).join('');
  }

  function emptyMarkup(kind) {
    if (kind === 'no-match') {
      return `
        <div class="empty-glyph">${ICON.star}</div>
        <h3>没有匹配的仓库</h3>
        <p>换个关键词，或清除当前的状态筛选。</p>
        <div class="empty-actions">
          <button class="btn" type="button" data-action="clear-filters">清除筛选</button>
        </div>`;
    }
    return `
      <div class="empty-glyph">${ICON.star}</div>
      <h3>还没有仓库。</h3>
      <p>添加一个 Git 项目开始：自动克隆、拉取更新、切换分支，全部集中在一块面板里。</p>
      <div class="empty-actions">
        <button class="btn btn-primary" type="button" data-action="open-add">添加项目</button>
        <button class="btn" type="button" data-action="open-import">导入本地</button>
        <button class="btn" type="button" data-action="open-batch">批量导入</button>
      </div>`;
  }

  function rowMarkup(project, index) {
    const meta = statusMeta(project);
    const open = state.expanded === project.id;
    const busy = state.busy.has(project.id);
    const branch =
      project.health === 'ok' ? (project.detached ? 'detached' : project.branch || '—') : '—';

    let sync = '—';
    if (project.health === 'ok') {
      if (project.ahead || project.behind) sync = `↑${project.ahead} ↓${project.behind}`;
      else if (project.dirty) sync = `${project.dirty} 处改动`;
      else sync = 'in sync';
    } else {
      sync = project.health === 'missing' ? 'missing' : 'error';
    }

    const commit = project.commit
      ? `<p class="commit-subject" title="${esc(project.commit.subject)}">${esc(project.commit.subject)}</p>
         <p class="commit-meta">${esc(project.commit.shortHash)} · ${timeAgo(project.commit.committedAt)}</p>`
      : '<p class="commit-subject is-empty">暂无提交</p><p class="commit-meta">—</p>';

    return `
      <article class="row ${open ? 'is-open' : ''} ${busy ? 'is-busy' : ''}" data-id="${project.id}" style="--i:${index}" tabindex="0" aria-expanded="${open}">
        <div class="cell idx">${pad2(index + 1)}</div>
        <div class="cell main">
          <div class="name">${esc(project.name)}</div>
          <div class="url" title="${esc(project.repoUrl)}">${esc(project.repoUrl)}</div>
        </div>
        <div class="cell branch-cell">
          <span class="branch-chip" title="${esc(branch)}">${ICON.branch}<span class="branch-name">${esc(branch)}</span></span>
        </div>
        <div class="cell sync">
          <span class="status s-${meta.key}">${meta.label}</span>
          <span class="sync-count">${esc(sync)}</span>
        </div>
        <div class="cell commit">${commit}</div>
        <div class="cell actions">
          <button class="icon-btn" type="button" data-action="update" title="fetch & pull" aria-label="更新 ${esc(project.name)}" ${project.health !== 'ok' ? 'disabled' : ''}>${ICON.refresh}</button>
          <button class="icon-btn" type="button" data-action="branches" title="查看与切换分支" aria-label="分支 ${esc(project.name)}">${ICON.branch}</button>
          <button class="icon-btn" type="button" data-action="remove" title="从列表移除" aria-label="移除 ${esc(project.name)}">${ICON.trash}</button>
        </div>
      </article>
      ${open ? detailMarkup(project) : ''}`;
  }

  function detailMarkup(project) {
    const cache = state.branchCache[project.id];
    let branchBlock = '<div class="branch-loading">LOADING BRANCHES…</div>';

    if (cache && cache.error) {
      branchBlock = `<div class="inline-error">${esc(cache.error)}</div>`;
    } else if (cache && cache.data) {
      const data = cache.data;
      const local = data.local
        .map(
          (branch) => `
          <button class="branch-pick ${branch.current ? 'is-current' : ''}" type="button" data-action="checkout" data-branch="${esc(branch.name)}" title="${esc(branch.upstream || branch.name)}">
            ${esc(branch.name)}${branch.upstream ? `<em>${esc(branch.upstream)}</em>` : ''}
          </button>`,
        )
        .join('');
      const remote = data.remote
        .map(
          (branch) => `
          <button class="branch-pick is-remote" type="button" data-action="checkout" data-branch="${esc(branch.name)}" title="origin/${esc(branch.name)}">
            ${esc(branch.name)}
          </button>`,
        )
        .join('');
      branchBlock = `
        <div class="branch-columns">
          <div>
            <p class="micro">LOCAL · ${data.local.length}</p>
            <div class="branch-list">${local || '<span class="branch-loading">无本地分支</span>'}</div>
          </div>
          <div>
            <p class="micro">REMOTE · ${data.remote.length}</p>
            <div class="branch-list">${remote || '<span class="branch-loading">无远程分支</span>'}</div>
          </div>
        </div>`;
    }

    const commit = project.commit
      ? `${esc(project.commit.shortHash)} · ${esc(project.commit.author)} · ${esc(fullDate(project.commit.committedAt))}`
      : '—';

    const healthNote =
      project.health === 'ok'
        ? ''
        : `<div class="inline-error detail-error">${esc(project.error || '仓库不可用')}</div>`;

    return `
      <div class="row-detail" data-id="${project.id}">
        <div class="detail-grid">
          <div><span class="micro">LOCAL PATH</span><p class="detail-value">${esc(project.localPath || '—')}</p></div>
          <div><span class="micro">UPSTREAM</span><p class="detail-value ${project.upstream ? '' : 'is-dim'}">${esc(project.upstream || '未设置')}</p></div>
          <div><span class="micro">LAST COMMIT</span><p class="detail-value">${commit}</p></div>
          <div><span class="micro">REMOTE</span><p class="detail-value ${project.remoteUrl ? '' : 'is-dim'}">${esc(project.remoteUrl || project.repoUrl)}</p></div>
        </div>
        ${healthNote}
        <div class="detail-actions">
          <button class="mini-btn is-primary" type="button" data-action="commit" ${project.health !== 'ok' || !project.dirty ? 'disabled' : ''}>${ICON.commit} COMMIT CHANGES</button>
          <button class="mini-btn" type="button" data-action="push" ${project.health !== 'ok' || project.detached || project.empty ? 'disabled' : ''}>${ICON.push} PUSH BRANCH</button>
          <button class="mini-btn" type="button" data-action="update" ${project.health !== 'ok' ? 'disabled' : ''}>${ICON.refresh} FETCH &amp; PULL</button>
          <button class="mini-btn" type="button" data-action="remove">${ICON.trash} REMOVE FROM LIST</button>
        </div>
        <div class="branch-section">
          <div class="branch-section-head">
            <span class="micro">BRANCHES</span>
            <span>
              <button class="mini-btn" type="button" data-action="copy-path" data-copy="${esc(project.localPath || '')}">${ICON.copy} COPY PATH</button>
              <button class="mini-btn" type="button" data-action="reload-branches">REFRESH</button>
            </span>
          </div>
          ${branchBlock}
        </div>
      </div>`;
  }

  function renderSide(counts) {
    $('#statTotal').textContent = counts.all;
    $('#statDirty').textContent = counts.dirty;
    $('#statDrift').textContent = counts.drift;
    $('#statOffline').textContent = counts.offline;

    const percent = (value) => (counts.all ? Math.round((value / counts.all) * 100) : 0);
    $('#meterClean').style.width = `${percent(counts.clean)}%`;
    $('#meterDirty').style.width = `${percent(counts.dirty)}%`;
    $('#meterDrift').style.width = `${percent(counts.drift)}%`;
    $('#meterOffline').style.width = `${percent(counts.offline)}%`;

    $('#sideTimestamp').textContent = state.lastLoaded
      ? `SYNC ${clockTime(state.lastLoaded)}`
      : '—';
  }

  function renderActivity() {
    const list = $('#activityList');
    if (!state.activity.length) {
      list.innerHTML = `
        <li>
          <span class="act-dot"></span>
          <p class="act-text activity-empty">还没有操作。添加、同步或切换分支后会记录在这里。</p>
        </li>`;
      return;
    }
    list.innerHTML = state.activity
      .map((item) => {
        const kind = ACTIVITY_KINDS.includes(item.kind) ? item.kind : 'update';
        return `
          <li class="act-${kind}">
            <span class="act-dot"></span>
            <div>
              <p class="act-text">${esc(item.text)}</p>
              <span class="act-time">${clockTime(item.at).slice(0, 5)}</span>
            </div>
          </li>`;
      })
      .join('');
  }

  function updateLiveUI() {
    const button = $('#liveToggle');
    button.classList.toggle('is-paused', !state.live);
    button.setAttribute('aria-pressed', String(state.live));
    $('#liveLabel').textContent = state.live ? 'LIVE' : 'PAUSED';
  }

  /* ── data loading ────────────────────────── */

  async function load({ silent = false } = {}) {
    if (!silent) {
      state.loading = true;
      render();
    }
    try {
      const data = await api('/api/projects');
      state.projects = Array.isArray(data.projects) ? data.projects : [];
      state.lastLoaded = Date.now();
      if (state.expanded && !state.projects.some((item) => item.id === state.expanded)) {
        state.expanded = null;
      }
      for (const id of Object.keys(state.branchCache)) {
        if (!state.projects.some((item) => item.id === id)) delete state.branchCache[id];
      }
    } catch (error) {
      toast('error', '读取项目失败', error.message);
    } finally {
      state.loading = false;
      render();
    }
  }

  async function loadBranches(id, force = false) {
    const cached = state.branchCache[id];
    if (!force && cached && (cached.loading || cached.data)) return;
    state.branchCache[id] = { loading: true };
    render();
    try {
      const data = await api(`/api/projects/${id}/branches`);
      state.branchCache[id] = { data: data.branches, at: Date.now() };
    } catch (error) {
      state.branchCache[id] = { error: error.message };
    }
    render();
  }

  /* ── settings ────────────────────────────── */

  function prettyPath(value) {
    if (!value) return '—';
    const home = state.settings && state.settings.homeDir;
    if (home && value === home) return '~';
    if (home && value.startsWith(`${home}/`)) return `~${value.slice(home.length)}`;
    return value;
  }

  function applySettingsUI() {
    const settings = state.settings;
    if (!settings) return;
    const current = prettyPath(settings.effectiveDir);
    const isCustom = Boolean(settings.defaultLocalDir);

    $('#sideCloneRoot').textContent = isCustom ? current : 'data/repos';
    $('#sideCloneRoot').title = settings.effectiveDir;
    $('#settingsCurrent').textContent = current;
    $('#settingsCurrent').title = settings.effectiveDir;

    const badge = $('#settingsBadge');
    badge.textContent = isCustom ? 'CUSTOM' : 'DEFAULT';
    badge.classList.toggle('is-custom', isCustom);

    $('#cloneRootHint').textContent = isCustom ? current : 'data/repos/';
    $('#settingsDefaultLabel').textContent = prettyPath(settings.defaultDir);
    $('#settingsDir').placeholder = `留空使用 ${prettyPath(settings.defaultDir)}`;
  }

  async function loadSettings() {
    try {
      const data = await api('/api/settings');
      state.settings = data.settings;
      applySettingsUI();
    } catch {
      /* settings are optional; keep the built-in defaults */
    }
  }

  function resetSettingsForm() {
    const settings = state.settings;
    $('#settingsDir').value = (settings && settings.defaultLocalDir) || '';
    fieldError('#settingsError', '');
    applySettingsUI();
  }

  async function submitSettings(event) {
    event.preventDefault();
    const value = $('#settingsDir').value.trim();
    fieldError('#settingsError', '');

    const button = $('#settingsSubmit');
    setBtnBusy(button, true, '保存中…');
    try {
      const data = await api('/api/settings', {
        method: 'PUT',
        body: { defaultLocalDir: value },
      });
      state.settings = data.settings;
      applySettingsUI();
      const effective = prettyPath(data.settings.effectiveDir);
      logActivity('settings', value ? `默认克隆目录设为 ${effective}` : '默认克隆目录恢复为内置目录');
      toast('success', '设置已保存', value ? `新克隆将放入 ${effective}` : '新克隆将放入内置 data/repos。');
      closeDrawers();
    } catch (error) {
      fieldError('#settingsError', error.message);
    } finally {
      setBtnBusy(button, false);
    }
  }

  async function resetSettingsToDefault() {
    const button = $('#settingsReset');
    fieldError('#settingsError', '');
    button.disabled = true;
    try {
      const data = await api('/api/settings', {
        method: 'PUT',
        body: { defaultLocalDir: '' },
      });
      state.settings = data.settings;
      $('#settingsDir').value = '';
      applySettingsUI();
      logActivity('settings', '默认克隆目录恢复为内置目录');
      toast('success', '已恢复默认目录', prettyPath(data.settings.effectiveDir));
    } catch (error) {
      fieldError('#settingsError', error.message);
    } finally {
      button.disabled = false;
    }
  }

  /* ── row interactions ────────────────────── */

  function toggleRow(id) {
    if (state.expanded === id) {
      state.expanded = null;
      render();
      return;
    }
    state.expanded = id;
    render();
    loadBranches(id);
  }

  function openBranches(id) {
    state.expanded = id;
    render();
    loadBranches(id, true);
  }

  function setBusy(id, busy) {
    if (busy) state.busy.add(id);
    else state.busy.delete(id);
    render();
  }

  async function updateOne(id) {
    const project = state.projects.find((item) => item.id === id);
    if (!project || state.busy.has(id)) return;
    setBusy(id, true);
    try {
      const result = await api(`/api/projects/${id}/update`, { method: 'POST' });
      const message = (result.pull && result.pull.message) || '已 fetch';
      logActivity('update', `${project.name} · ${message}`);
      toast(result.pull && result.pull.ok ? 'success' : 'info', `${project.name} 已更新`, message);
      await load({ silent: true });
      if (state.expanded === id) loadBranches(id, true);
    } catch (error) {
      logActivity('error', `${project.name} 更新失败`);
      toast('error', `${project.name} 更新失败`, error.message);
    } finally {
      setBusy(id, false);
    }
  }

  async function updateAll() {
    if (state.batchBusy) return;
    if (!state.projects.length) {
      toast('info', '暂时没有项目', '先添加一个仓库，再批量更新。');
      return;
    }

    const label = $('#updateAllLabel');
    const original = label.textContent;
    state.batchBusy = true;
    label.textContent = '更新中…';
    render();

    try {
      const result = await api('/api/projects/update-batch', { method: 'POST', body: {} });
      const results = result.results || [];
      const failed = results.filter((item) => !item.ok);
      const ok = results.length - failed.length;

      logActivity(
        failed.length ? 'error' : 'update',
        `全部更新 · ${ok}/${results.length} 个仓库成功`,
      );

      if (failed.length) {
        toast(
          'error',
          `更新完成 · ${ok}/${results.length}`,
          failed
            .slice(0, 3)
            .map((item) => `${item.name}: ${item.error}`)
            .join('；'),
        );
      } else {
        toast('success', `全部更新完成 · ${results.length} 个仓库`, '已 fetch 并尝试快进合并。');
      }

      await load({ silent: true });
      if (state.expanded) loadBranches(state.expanded, true);
    } catch (error) {
      toast('error', '批量更新失败', error.message);
    } finally {
      state.batchBusy = false;
      label.textContent = original;
      render();
    }
  }

  async function checkoutBranch(id, branch) {
    const project = state.projects.find((item) => item.id === id);
    if (!project || state.busy.has(id)) return;
    setBusy(id, true);
    state.branchCache[id] = null;
    try {
      await api(`/api/projects/${id}/checkout`, { method: 'POST', body: { branch } });
      logActivity('branch', `${project.name} → ${branch}`);
      toast('success', '分支已切换', `${project.name} 现在位于 ${branch}`);
      await load({ silent: true });
      if (state.expanded === id) loadBranches(id, true);
    } catch (error) {
      logActivity('error', `${project.name} 切换分支失败`);
      toast('error', '切换分支失败', error.message);
      if (state.expanded === id) loadBranches(id, true);
    } finally {
      setBusy(id, false);
    }
  }

  async function pushOne(id) {
    const project = state.projects.find((item) => item.id === id);
    if (!project || state.busy.has(id)) return;
    setBusy(id, true);
    try {
      const result = await api(`/api/projects/${id}/push`, { method: 'POST' });
      logActivity('push', `${project.name} → ${result.branch}${result.setUpstream ? '（已设置上游）' : ''}`);
      toast('success', '已推送分支', `${project.name} · ${result.upstream}`);
      await load({ silent: true });
    } catch (error) {
      logActivity('error', `${project.name} 推送失败`);
      toast('error', '推送失败', error.message);
    } finally {
      setBusy(id, false);
    }
  }

  function requestRemove(id) {
    const project = state.projects.find((item) => item.id === id);
    if (!project) return;
    showModal(
      '移除项目？',
      `“${project.name}” 将从控制台移除，本地目录不会被删除。`,
      async () => {
        try {
          await api(`/api/projects/${id}`, { method: 'DELETE' });
          logActivity('remove', `${project.name} 已从列表移除`);
          toast('success', '已移除项目', `${project.name} 的本地文件保持不变`);
          if (state.expanded === id) state.expanded = null;
          delete state.branchCache[id];
          await load({ silent: true });
        } catch (error) {
          toast('error', '移除失败', error.message);
        }
      },
      '确认移除',
    );
  }

  async function copyPath(path) {
    if (!path) return;
    try {
      await navigator.clipboard.writeText(path);
      toast('success', '路径已复制', path);
    } catch {
      toast('error', '复制失败', '浏览器不允许访问剪贴板。');
    }
  }

  /* ── drawers ─────────────────────────────── */

  function openDrawer(name) {
    hideModal();
    closeDrawers(true);

    if (name === 'add') resetAdd();
    if (name === 'import') resetImport();
    if (name === 'batch') resetBatch();
    if (name === 'branch') {
      resetBranch();
      renderBranchPicks();
    }
    if (name === 'commit') resetCommit();
    if (name === 'settings') resetSettingsForm();

    const drawer = $(DRAWERS[name]);
    if (!drawer) return;
    const overlay = $('#overlay');
    drawer.hidden = false;
    drawer.setAttribute('aria-hidden', 'false');
    overlay.hidden = false;
    document.body.classList.add('no-scroll');

    requestAnimationFrame(() => {
      drawer.classList.add('is-open');
      overlay.classList.add('is-open');
    });

    setTimeout(() => {
      const focusable = drawer.querySelector('input, textarea');
      if (focusable) focusable.focus();
    }, 430);
  }

  function closeDrawers(immediate = false) {
    const overlay = $('#overlay');
    $$('.drawer').forEach((drawer) => {
      drawer.classList.remove('is-open');
      drawer.setAttribute('aria-hidden', 'true');
      if (immediate) {
        drawer.hidden = true;
        return;
      }
      setTimeout(() => {
        if (!drawer.classList.contains('is-open')) drawer.hidden = true;
      }, 420);
    });
    overlay.classList.remove('is-open');
    if (immediate) {
      overlay.hidden = true;
      document.body.classList.remove('no-scroll');
    } else {
      setTimeout(() => {
        if (!document.querySelector('.drawer.is-open')) overlay.hidden = true;
        document.body.classList.remove('no-scroll');
      }, 420);
    }
  }

  function showModal(title, body, action, confirmLabel = '确认') {
    $('#modalTitle').textContent = title;
    $('#modalBody').textContent = body;
    $('#modalConfirm').textContent = confirmLabel;
    modalAction = action;
    const overlay = $('#modalOverlay');
    overlay.hidden = false;
    document.body.classList.add('no-scroll');
    requestAnimationFrame(() => overlay.classList.add('is-open'));
    $('#modalConfirm').focus();
  }

  function hideModal() {
    const overlay = $('#modalOverlay');
    overlay.classList.remove('is-open');
    setTimeout(() => {
      overlay.hidden = true;
      if (!document.querySelector('.drawer.is-open')) document.body.classList.remove('no-scroll');
    }, 240);
    modalAction = null;
  }

  function fieldError(selector, message) {
    const node = $(selector);
    node.textContent = message || '';
    node.hidden = !message;
  }

  function setBtnBusy(button, busy, busyLabel) {
    if (busy) {
      button.dataset.label = button.textContent;
      button.textContent = busyLabel;
      button.disabled = true;
      button.classList.add('is-busy');
    } else {
      button.textContent = button.dataset.label || button.textContent;
      button.disabled = false;
      button.classList.remove('is-busy');
    }
  }

  function resetAdd() {
    $('#addForm').reset();
    fieldError('#addError', '');
    const button = $('#addSubmit');
    button.disabled = false;
    button.textContent = '克隆并添加';
  }

  function resetImport() {
    $('#importForm').reset();
    importNameAuto = '';
    fieldError('#importError', '');
    const button = $('#importSubmit');
    button.disabled = false;
    button.textContent = '导入项目';
  }

  function resetBatch() {
    $('#batchForm').reset();
    fieldError('#batchError', '');
    const results = $('#batchResults');
    results.hidden = true;
    results.innerHTML = '';
    const preview = $('#batchPreview');
    preview.textContent = '0 个仓库待导入';
    preview.classList.remove('is-ready');
    const button = $('#batchSubmit');
    button.disabled = false;
    button.textContent = '开始导入';
  }

  function resetBranch() {
    $('#branchForm').reset();
    fieldError('#branchError', '');
    const results = $('#branchResults');
    results.hidden = true;
    results.innerHTML = '';
    const button = $('#branchSubmit');
    button.disabled = false;
    button.textContent = '切换分支';
    updatePickCount();
  }

  function resetCommit() {
    const project = state.projects.find((item) => item.id === commitProjectId);
    $('#commitForm').reset();
    fieldError('#commitError', '');
    const button = $('#commitSubmit');
    button.disabled = false;
    button.textContent = '提交';
    if (!project) return;

    $('#commitProject').textContent = project.localPath || project.name;
    $('#commitProject').title = project.localPath || '';
    $('#commitBranch').textContent = project.detached ? 'DETACHED' : project.branch || '—';

    const dirty = project.dirty || 0;
    const usable = project.health === 'ok';
    $('#commitSummary').textContent = usable
      ? dirty
        ? `${dirty} 处改动待提交`
        : '没有可提交的改动'
      : project.error || '仓库不可用';
    button.disabled = !usable || dirty === 0;
    $('#commitPush').disabled = !usable || project.detached;
  }

  function openCommit(id) {
    commitProjectId = id;
    openDrawer('commit');
  }

  function renderBranchPicks() {
    const list = $('#branchPickList');
    if (!state.projects.length) {
      list.innerHTML = '<p class="activity-empty" style="padding:14px 12px">暂无项目，先添加仓库。</p>';
      updatePickCount();
      return;
    }
    list.innerHTML = state.projects
      .map((project) => {
        const key = statusOf(project);
        return `
          <label class="pick">
            <input type="checkbox" value="${project.id}" />
            <span class="pick-dot s-${key}"></span>
            <span class="pick-name">${esc(project.name)}</span>
            <span class="pick-branch">${esc(project.branch || '—')}</span>
          </label>`;
      })
      .join('');
    updatePickCount();
  }

  function updatePickCount() {
    const total = $$('#branchPickList input[type="checkbox"]').length;
    const checked = $$('#branchPickList input[type="checkbox"]:checked').length;
    $('#pickCount').textContent = `${checked} / ${total} 已选`;
  }

  /* ── forms ───────────────────────────────── */

  function parseBatchText(text) {
    return text
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#'))
      .map((line) => {
        const separator = line.search(/[,\t]/);
        if (separator === -1) return { repoUrl: line };
        const repoUrl = line.slice(0, separator).trim();
        const localPath = line
          .slice(separator + 1)
          .trim()
          .replace(/^["']|["']$/g, '');
        return { repoUrl, localPath: localPath || undefined };
      })
      .filter((item) => item.repoUrl);
  }

  function updateBatchPreview() {
    const items = parseBatchText($('#batchText').value);
    const preview = $('#batchPreview');
    preview.textContent = `${items.length} 个仓库待导入`;
    preview.classList.toggle('is-ready', items.length > 0);
  }

  async function submitAdd(event) {
    event.preventDefault();
    const repoUrl = $('#addUrl').value.trim();
    const localPath = $('#addPath').value.trim();
    const name = $('#addName').value.trim();

    if (!repoUrl) {
      fieldError('#addError', '请填写仓库地址。');
      return;
    }
    fieldError('#addError', '');

    const button = $('#addSubmit');
    setBtnBusy(button, true, '克隆中…');

    try {
      const payload = await api('/api/projects', {
        method: 'POST',
        body: {
          repoUrl,
          localPath: localPath || undefined,
          name: name || undefined,
        },
      });
      logActivity('add', `${payload.project.name} ${payload.cloned ? '已克隆' : '已挂载'}`);
      toast(
        'success',
        payload.cloned ? '已克隆并添加' : '已添加项目',
        payload.project.name,
      );
      closeDrawers();
      await load({ silent: true });
    } catch (error) {
      fieldError('#addError', error.message);
    } finally {
      setBtnBusy(button, false);
    }
  }

  async function browseImportPath() {
    const button = $('#importBrowse');
    const label = $('#importBrowseLabel');
    if (button.disabled) return;

    button.disabled = true;
    label.textContent = '打开中…';
    fieldError('#importError', '');

    try {
      const data = await api('/api/fs/pick-folder', {
        method: 'POST',
        body: { startPath: $('#importPath').value.trim() || undefined },
      });
      if (data.canceled) return;

      $('#importPath').value = data.path;
      if (data.isGit) {
        const nameInput = $('#importName');
        if (!nameInput.value.trim() || nameInput.value === importNameAuto) {
          nameInput.value = data.name || '';
          importNameAuto = nameInput.value;
        }
        toast('info', '已选择 Git 仓库', data.path);
      } else {
        fieldError('#importError', '所选目录不是 Git 项目（未找到 .git 仓库）');
      }
    } catch (error) {
      fieldError('#importError', error.message);
    } finally {
      button.disabled = false;
      label.textContent = '浏览…';
    }
  }

  async function submitImport(event) {
    event.preventDefault();
    const localPath = $('#importPath').value.trim();
    const name = $('#importName').value.trim();

    if (!localPath) {
      fieldError('#importError', '请填写本地目录。');
      return;
    }
    fieldError('#importError', '');

    const button = $('#importSubmit');
    setBtnBusy(button, true, '导入中…');

    try {
      const payload = await api('/api/projects/import-local', {
        method: 'POST',
        body: { localPath, name: name || undefined },
      });
      logActivity('add', `${payload.project.name} 已从本地导入`);
      toast('success', '已导入本地项目', payload.project.localPath);
      closeDrawers();
      await load({ silent: true });
    } catch (error) {
      fieldError('#importError', error.message);
    } finally {
      setBtnBusy(button, false);
    }
  }

  async function submitBatch(event) {
    event.preventDefault();
    const items = parseBatchText($('#batchText').value);
    if (!items.length) {
      fieldError('#batchError', '至少填写一个仓库地址。');
      return;
    }
    fieldError('#batchError', '');

    const button = $('#batchSubmit');
    const resultsBox = $('#batchResults');
    resultsBox.hidden = true;
    resultsBox.innerHTML = '';
    setBtnBusy(button, true, '导入中…');

    try {
      const payload = await api('/api/projects/batch', {
        method: 'POST',
        body: { items },
      });
      const results = payload.results || [];
      const ok = results.filter((item) => item.ok).length;

      resultsBox.hidden = false;
      resultsBox.innerHTML = results
        .map(
          (item) => `
          <div class="result-line ${item.ok ? '' : 'is-err'}">
            <span class="result-mark">${item.ok ? '✓' : '!'}</span>
            <code title="${esc(item.repoUrl || '')}">${esc(item.repoUrl || '')}</code>
            <span class="result-note" title="${esc(item.ok ? (item.cloned ? '已克隆' : '已挂载') : item.error)}">
              ${item.ok ? (item.cloned ? '已克隆' : '已挂载') : esc(item.error)}
            </span>
          </div>`,
        )
        .join('');

      if (ok) {
        logActivity('add', `批量导入 ${ok}/${results.length} 个仓库`);
        await load({ silent: true });
      }
      if (ok < results.length) {
        logActivity('error', `批量导入 ${results.length - ok} 个失败`);
      }

      if (ok === results.length) {
        toast('success', `已导入 ${ok} 个仓库`, '列表已更新。');
        setTimeout(() => closeDrawers(), 750);
      } else {
        toast(
          ok ? 'info' : 'error',
          `导入完成 · ${ok}/${results.length}`,
          '逐条结果见抽屉列表。',
        );
      }
    } catch (error) {
      fieldError('#batchError', error.message);
    } finally {
      setBtnBusy(button, false);
    }
  }

  async function submitBranch(event) {
    event.preventDefault();
    const branch = $('#branchName').value.trim();
    const base = $('#branchBase').value.trim();
    const ids = $$('#branchPickList input[type="checkbox"]:checked').map((input) => input.value);

    if (!ids.length) {
      fieldError('#branchError', '请至少选择一个项目。');
      return;
    }
    if (!BRANCH_RE.test(branch) || branch.endsWith('/') || branch.endsWith('.lock')) {
      fieldError('#branchError', '分支名不合法，请使用字母、数字、点、下划线、斜杠或短横线。');
      return;
    }
    fieldError('#branchError', '');

    const button = $('#branchSubmit');
    const resultsBox = $('#branchResults');
    resultsBox.hidden = true;
    resultsBox.innerHTML = '';
    setBtnBusy(button, true, '执行中…');

    try {
      const payload = await api('/api/projects/branch-batch', {
        method: 'POST',
        body: { ids, branch, base: base || undefined },
      });
      const results = payload.results || [];
      const ok = results.filter((item) => item.ok).length;

      resultsBox.hidden = false;
      resultsBox.innerHTML = results
        .map(
          (item) => `
          <div class="result-line ${item.ok ? '' : 'is-err'}">
            <span class="result-mark">${item.ok ? '✓' : '!'}</span>
            <code title="${esc(item.name)}">${esc(item.name)}</code>
            <span class="result-note" title="${esc(item.ok ? item.message : item.error)}">
              ${esc(item.ok ? item.message : item.error)}
            </span>
          </div>`,
        )
        .join('');

      logActivity(
        ok === results.length ? 'branch' : 'error',
        `批量分支 ${branch} · ${ok}/${results.length} 个仓库成功`,
      );
      await load({ silent: true });

      if (ok === results.length) {
        toast('success', `分支 ${branch} 已就绪`, `${ok} 个仓库已创建或切换。`);
        setTimeout(() => closeDrawers(), 750);
      } else {
        toast(
          ok ? 'info' : 'error',
          `批量分支完成 · ${ok}/${results.length}`,
          '逐条结果见抽屉列表。',
        );
      }
    } catch (error) {
      fieldError('#branchError', error.message);
    } finally {
      setBtnBusy(button, false);
    }
  }

  async function submitCommit(event) {
    event.preventDefault();
    const project = state.projects.find((item) => item.id === commitProjectId);
    if (!project) return;

    const message = $('#commitMessage').value.trim();
    if (!message) {
      fieldError('#commitError', '请填写提交说明。');
      return;
    }
    fieldError('#commitError', '');

    const shouldPush = $('#commitPush').checked;
    const button = $('#commitSubmit');
    setBtnBusy(button, true, '提交中…');

    try {
      const payload = await api(`/api/projects/${project.id}/commit`, {
        method: 'POST',
        body: { message },
      });
      logActivity('commit', `${project.name} 已提交 ${payload.commit.shortHash}`);

      let pushResult = null;
      let pushError = null;
      if (shouldPush) {
        button.textContent = '推送中…';
        try {
          pushResult = await api(`/api/projects/${project.id}/push`, { method: 'POST' });
          logActivity(
            'push',
            `${project.name} → ${pushResult.branch}${pushResult.setUpstream ? '（已设置上游）' : ''}`,
          );
        } catch (error) {
          pushError = error;
          logActivity('error', `${project.name} 推送失败`);
        }
      }

      await load({ silent: true });
      closeDrawers();

      if (pushError) {
        toast('error', '已提交，但推送失败', pushError.message);
      } else if (pushResult) {
        toast('success', '已提交并推送', `${payload.commit.shortHash} · ${pushResult.branch}`);
      } else {
        toast(
          'success',
          '已提交',
          `${payload.commit.shortHash} · ${payload.commit.files} 个文件`,
        );
      }
    } catch (error) {
      fieldError('#commitError', error.message);
    } finally {
      setBtnBusy(button, false);
    }
  }

  /* ── refresh / polling / clock ───────────── */

  async function manualRefresh() {
    const button = $('#refreshBtn');
    if (state.loading) return;
    button.classList.add('is-busy');
    try {
      await load({ silent: state.projects.length > 0 });
      if (state.expanded) loadBranches(state.expanded, true);
    } finally {
      button.classList.remove('is-busy');
    }
  }

  function startPolling() {
    setInterval(() => {
      if (!state.live || document.hidden || state.loading || isOverlayOpen()) return;
      load({ silent: true });
    }, 30000);
  }

  function updateClock() {
    const now = clockTime(Date.now());
    $('#railClock').textContent = now.slice(0, 5);
    $('#footClock').textContent = now;
  }

  /* ── events ──────────────────────────────── */

  function bindEvents() {
    $('#addBtn').addEventListener('click', () => openDrawer('add'));
    $('#importBtn').addEventListener('click', () => openDrawer('import'));
    $('#batchAddBtn').addEventListener('click', () => openDrawer('batch'));
    $('#branchBatchBtn').addEventListener('click', () => openDrawer('branch'));
    $('#updateAllBtn').addEventListener('click', updateAll);
    $('#refreshBtn').addEventListener('click', manualRefresh);
    $('#settingsBtn').addEventListener('click', () => openDrawer('settings'));
    $('#sideSettingsBtn').addEventListener('click', () => openDrawer('settings'));

    $('#searchInput').addEventListener('input', (event) => {
      state.query = event.target.value;
      renderList();
    });

    $('#filterChips').addEventListener('click', (event) => {
      const chip = event.target.closest('.chip');
      if (!chip) return;
      state.filter = chip.dataset.filter;
      render();
    });

    $('#sortSelect').addEventListener('change', (event) => {
      state.sort = event.target.value;
      renderList();
    });

    $('#liveToggle').addEventListener('click', () => {
      state.live = !state.live;
      try {
        localStorage.setItem('gcr:live', state.live ? '1' : '0');
      } catch {
        /* optional */
      }
      updateLiveUI();
      if (state.live) {
        toast('info', '自动刷新已开启', '每 30 秒同步一次所有仓库状态。');
        load({ silent: true });
      } else {
        toast('info', '自动刷新已暂停', '再次点击 LIVE 即可恢复。');
      }
    });

    const list = $('#projectList');

    list.addEventListener('click', (event) => {
      const actionNode = event.target.closest('[data-action]');
      const holder = event.target.closest('[data-id]');
      const id = holder && holder.dataset.id;

      if (actionNode) {
        const action = actionNode.dataset.action;
        if (action === 'update' && id) updateOne(id);
        else if (action === 'branches' && id) openBranches(id);
        else if (action === 'commit' && id) openCommit(id);
        else if (action === 'push' && id) pushOne(id);
        else if (action === 'remove' && id) requestRemove(id);
        else if (action === 'checkout' && id) checkoutBranch(id, actionNode.dataset.branch);
        else if (action === 'reload-branches' && id) loadBranches(id, true);
        else if (action === 'copy-path') copyPath(actionNode.dataset.copy);
        return;
      }

      if (holder && holder.classList.contains('row') && id) toggleRow(id);
    });

    list.addEventListener('keydown', (event) => {
      if ((event.key === 'Enter' || event.key === ' ') && event.target.classList.contains('row')) {
        event.preventDefault();
        toggleRow(event.target.dataset.id);
      }
    });

    $('#emptyState').addEventListener('click', (event) => {
      const actionNode = event.target.closest('[data-action]');
      if (!actionNode) return;
      const action = actionNode.dataset.action;
      if (action === 'open-add') openDrawer('add');
      if (action === 'open-import') openDrawer('import');
      if (action === 'open-batch') openDrawer('batch');
      if (action === 'clear-filters') {
        state.query = '';
        state.filter = 'all';
        $('#searchInput').value = '';
        render();
      }
    });

    $('#overlay').addEventListener('click', () => closeDrawers());

    document.addEventListener('click', (event) => {
      if (event.target.closest('[data-close]')) closeDrawers();
    });

    $('#addForm').addEventListener('submit', submitAdd);
    $('#importForm').addEventListener('submit', submitImport);
    $('#importBrowse').addEventListener('click', browseImportPath);
    $('#batchForm').addEventListener('submit', submitBatch);
    $('#branchForm').addEventListener('submit', submitBranch);
    $('#commitForm').addEventListener('submit', submitCommit);
    $('#settingsForm').addEventListener('submit', submitSettings);
    $('#settingsReset').addEventListener('click', resetSettingsToDefault);
    $('#batchText').addEventListener('input', updateBatchPreview);

    $('#pickAll').addEventListener('click', () => {
      $$('#branchPickList input[type="checkbox"]').forEach((input) => {
        input.checked = true;
      });
      updatePickCount();
    });

    $('#pickNone').addEventListener('click', () => {
      $$('#branchPickList input[type="checkbox"]').forEach((input) => {
        input.checked = false;
      });
      updatePickCount();
    });

    $('#branchPickList').addEventListener('change', updatePickCount);

    $('#modalCancel').addEventListener('click', hideModal);
    $('#modalConfirm').addEventListener('click', () => {
      const action = modalAction;
      hideModal();
      if (action) action();
    });
    $('#modalOverlay').addEventListener('click', (event) => {
      if (event.target === $('#modalOverlay')) hideModal();
    });

    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') {
        if (!$('#modalOverlay').hidden) hideModal();
        else closeDrawers();
        return;
      }

      const target = event.target;
      const tag = (target.tagName || '').toLowerCase();
      const typing =
        tag === 'input' || tag === 'textarea' || tag === 'select' || target.isContentEditable;
      if (typing || isOverlayOpen()) return;

      if (event.key === '/') {
        event.preventDefault();
        $('#searchInput').focus();
      } else if (event.key.toLowerCase() === 'n') {
        event.preventDefault();
        openDrawer('add');
      } else if (event.key.toLowerCase() === 'r') {
        event.preventDefault();
        manualRefresh();
      } else if (event.key.toLowerCase() === 'i') {
        event.preventDefault();
        openDrawer('import');
      } else if (event.key.toLowerCase() === 's') {
        event.preventDefault();
        openDrawer('settings');
      }
    });
  }

  function init() {
    bindEvents();
    updateClock();
    setInterval(updateClock, 1000);
    updateLiveUI();
    render();
    loadSettings();
    load();
    startPolling();
  }

  init();
})();

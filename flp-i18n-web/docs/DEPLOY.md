# Git Control Room 部署文档

> 适用版本：1.0.0  
> 配套文档：[使用文档](USAGE.md)

Git Control Room 是一个**纯本地、零第三方依赖**的 Git 仓库控制台。服务端只使用 Node.js 内置模块，前端为原生 HTML/CSS/JS，默认仅监听本机回环地址。

---

## 1. 环境要求

| 项目 | 要求 | 说明 |
| --- | --- | --- |
| Node.js | >= 18 | `package.json` 中 `engines` 要求；已在 v25.2.1 验证 |
| Git | 任意较新版本 | 需在 `PATH` 中，已用 2.45.2 验证 |
| 操作系统 | macOS / Windows / Linux | Linux 桌面环境才可使用系统目录选择器 |
| 浏览器 | Chrome / Edge / Safari / Firefox 近两年版本 | 需要支持 ES2020、`fetch`、CSS Grid |
| 目录选择器（可选） | Linux：`zenity` 或 `kdialog` | 缺失时仍可手动粘贴路径 |
| 网络（可选） | 可访问 Google Fonts | 离线时自动回退系统字体，功能不受影响 |

> 不需要执行 `npm install`：项目没有任何第三方依赖。

---

## 2. 目录结构

```text
flp-i18n-web/
├── server.js            # HTTP 服务、Git 操作、JSON 持久化
├── package.json         # 启动脚本与 Node 版本约束
├── public/
│   ├── index.html       # 单页界面
│   ├── app.js           # 前端状态与交互
│   └── styles.css       # 界面样式
├── docs/
│   ├── DEPLOY.md        # 本文档
│   └── USAGE.md         # 使用文档
└── data/                # 运行时自动创建，已被 .gitignore 忽略
    ├── projects.json    # 项目列表
    ├── settings.json    # 设置（默认克隆目录）
    └── repos/           # 内置克隆根目录
```

- 启动时自动创建 `data/repos/`。
- `projects.json`、`settings.json` 采用「临时文件 + rename」原子写入，并通过写队列串行化，避免并发写坏文件。
- 静态资源响应头为 `Cache-Control: no-cache`，修改 `public/` 下的文件刷新浏览器即可生效；修改 `server.js` 需要重启服务。

---

## 3. 快速启动

```bash
cd flp-i18n-web
npm start
# 等价于 node server.js
```

启动成功后终端输出：

```text
  GIT CONTROL ROOM
  → http://localhost:4321
  git version 2.45.2
  store: data/projects.json
```

浏览器打开 `http://127.0.0.1:4321` 即可使用。

### 开发模式

```bash
npm run dev        # node --watch server.js，修改服务端代码后自动重启
```

### 自定义端口与监听地址

```bash
PORT=4400 npm start                 # 换端口
HOST=127.0.0.1 PORT=4400 npm start  # 显式指定监听地址（默认值）
```

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `PORT` | `4321` | 监听端口；非法或为空时回退到 4321 |
| `HOST` | `127.0.0.1` | 监听地址；仅在明确需要局域网访问时才改为 `0.0.0.0` |

> 端口被占用时服务会打印 `Port 4321 is already in use. Try: PORT=4400 npm start` 并退出（退出码 1）。

### 启动自检

启动时会执行 `git --version`：

- 成功：打印 Git 版本与数据文件路径。
- 失败：打印 `warning: git executable not found`，服务仍会启动，但所有仓库操作都会失败。

---

## 4. 数据与备份

| 路径 | 内容 | 是否可删除 |
| --- | --- | --- |
| `data/projects.json` | 项目列表：`id`、`name`、`repoUrl`、`localPath`、`createdAt`、`lastCheckedAt` | 删除后列表为空，不会删除本地仓库 |
| `data/settings.json` | `defaultLocalDir`（默认克隆目录） | 删除后恢复内置目录 |
| `data/repos/` | 通过控制台克隆的仓库 | 删除会丢失仓库内容，谨慎操作 |
| 自定义克隆目录 | 设置后由 `settings.json` 指向 | 与本应用无关，需自行管理 |

**备份**：停止服务后复制整个 `data/` 目录即可；恢复时覆盖回原位置。

**注意**：

- `projects.json` 保存仓库地址，如果 HTTPS 地址中内嵌了访问令牌，该文件也会包含令牌，请按敏感文件管理。
- 两个 JSON 文件不是数据库，手工编辑前请先备份；格式损坏时服务会以空列表启动，且不会自动修复。

---

## 5. 常驻运行

### macOS（launchd，登录用户）

创建 `~/Library/LaunchAgents/com.local.git-control-room.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>com.local.git-control-room</string>
  <key>ProgramArguments</key>
  <array>
    <string>/usr/local/bin/node</string>
    <string>/path/to/flp-i18n-web/server.js</string>
  </array>
  <key>WorkingDirectory</key>
  <string>/path/to/flp-i18n-web</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key>
    <string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>/tmp/git-control-room.out.log</string>
  <key>StandardErrorPath</key>
  <string>/tmp/git-control-room.err.log</string>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/com.local.git-control-room.plist
```

注意：

- `node` 路径用 `which node` 的实际结果替换（Apple Silicon 常见为 `/opt/homebrew/bin/node`）。
- launchd 不继承终端环境，`PATH` 必须包含 Git 所在目录，否则启动自检会提示找不到 git。
- 目录选择器由服务端进程弹出系统窗口，需要处于图形登录会话；以守护方式运行且无桌面会话时请手动粘贴路径。

### Linux（systemd 用户服务）

创建 `~/.config/systemd/user/git-control-room.service`：

```ini
[Unit]
Description=Git Control Room
After=graphical-session.target

[Service]
WorkingDirectory=%h/flp-i18n-web
ExecStart=/usr/bin/node server.js
Environment=PORT=4321
Restart=on-failure

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now git-control-room
systemctl --user status git-control-room
```

- 用**用户服务**运行才能访问桌面会话并弹出目录选择器；系统级服务通常没有 `DISPLAY`，只能手动粘贴路径。
- 示例中的 `WorkingDirectory=%h/flp-i18n-web` 与 `ExecStart=/usr/bin/node` 请按实际路径调整。
- Linux 目录选择器依赖 `zenity`（优先）或 `kdialog`，都缺失时接口返回 501。

### Windows

- 前台运行：`node server.js`。
- 开机自启：使用「任务计划程序」，触发器选登录时，操作选 `node.exe` + `server.js`，起始位置设为项目目录，并勾选「只在用户登录时运行」，否则目录选择器无法弹出。
- 也可使用 PM2 等进程管理器，但本项目不需要额外依赖。

### 通用后台运行

```bash
nohup node server.js > /tmp/git-control-room.log 2>&1 &
```

注意 `nohup` 启动的进程能否弹出目录选择器取决于当前图形会话，无桌面环境时请手动输入路径。

---

## 6. 安全说明

项目定位是**本机单用户工具**，没有登录、鉴权与 CSRF 防护：

1. 默认只监听 `127.0.0.1`，请勿直接改为 `0.0.0.0` 暴露到公网或不可信局域网。
2. 如需远程使用，建议 SSH 端口转发：
   ```bash
   ssh -L 4321:127.0.0.1:4321 user@your-host
   ```
3. 该服务能够：
   - 在已登记仓库中执行 `git fetch / pull / checkout` 等命令；
   - 执行 `git add -A`、`git commit` 与 `git push`，会真实写入本地仓库并推送到远程；
   - 读取指定目录信息、创建默认克隆目录、克隆远程仓库；
   - 调用系统目录选择器（服务端进程弹出）。
   
   因此只应让可信的本机用户访问。
4. Git 子进程使用参数数组调用（无 shell 拼接），设置 `GIT_TERMINAL_PROMPT=0`，不会在服务端交互式索要账号密码。
5. 静态文件服务仅限 `public/` 目录，并做了路径穿越校验；API 请求体上限 2 MB。

---

## 7. API 清单

所有接口返回 JSON；失败格式为：

```json
{ "ok": false, "error": "错误说明" }
```

成功响应均包含 `"ok": true`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查，返回项目数量与数据文件路径 |
| GET | `/api/settings` | 读取设置 |
| PUT | `/api/settings` | 更新默认克隆目录；传空字符串恢复内置目录 |
| GET | `/api/projects` | 项目列表（含实时 Git 状态） |
| POST | `/api/projects` | 添加项目：克隆或挂载已有目录 |
| POST | `/api/projects/import-local` | 导入本地已下载的 Git 仓库 |
| POST | `/api/projects/batch` | 批量添加（单次最多 50 个） |
| POST | `/api/projects/update-batch` | 批量更新；`ids` 省略或为空表示全部 |
| POST | `/api/projects/branch-batch` | 批量切换/创建分支 |
| GET | `/api/projects/:id` | 单个项目状态 |
| DELETE | `/api/projects/:id` | 从列表移除（不删除本地文件） |
| GET | `/api/projects/:id/branches` | 本地与远程分支列表 |
| POST | `/api/projects/:id/update` | 单个项目 fetch + 快进 pull |
| POST | `/api/projects/:id/checkout` | 切换分支 |
| POST | `/api/projects/:id/commit` | 暂存全部改动并提交 |
| POST | `/api/projects/:id/push` | 推送当前分支；无 upstream 时自动 `-u` |
| POST | `/api/fs/pick-folder` | 调起系统目录选择器 |

### 请求示例

```bash
# 健康检查
curl -s http://127.0.0.1:4321/api/health

# 添加项目（不填 localPath 时克隆到默认克隆目录）
curl -s -X POST http://127.0.0.1:4321/api/projects \
  -H 'Content-Type: application/json' \
  -d '{"repoUrl":"git@github.com:org/repo.git","name":"repo"}'

# 导入本地项目
curl -s -X POST http://127.0.0.1:4321/api/projects/import-local \
  -H 'Content-Type: application/json' \
  -d '{"localPath":"~/projects/repo"}'

# 设置默认克隆目录（传 "" 恢复内置目录）
curl -s -X PUT http://127.0.0.1:4321/api/settings \
  -H 'Content-Type: application/json' \
  -d '{"defaultLocalDir":"~/projects"}'

# 批量更新全部项目
curl -s -X POST http://127.0.0.1:4321/api/projects/update-batch \
  -H 'Content-Type: application/json' -d '{}'

# 批量切换分支
curl -s -X POST http://127.0.0.1:4321/api/projects/branch-batch \
  -H 'Content-Type: application/json' \
  -d '{"ids":["<project-id>"],"branch":"feature/i18n"}'

# 单个项目切换分支
curl -s -X POST http://127.0.0.1:4321/api/projects/<id>/checkout \
  -H 'Content-Type: application/json' \
  -d '{"branch":"develop"}'

# 提交全部改动（先 git add -A，再 git commit）
curl -s -X POST http://127.0.0.1:4321/api/projects/<id>/commit \
  -H 'Content-Type: application/json' \
  -d '{"message":"fix: 修复 i18n 文案缺失"}'

# 推送当前分支（无 upstream 时自动设置）
curl -s -X POST http://127.0.0.1:4321/api/projects/<id>/push \
  -H 'Content-Type: application/json' -d '{}'
```

提交响应包含 `commit.hash`、`commit.shortHash`、`commit.subject`、`commit.files`（本次提交的文件数）；推送响应包含 `branch`、`upstream`、`setUpstream`。两者都会返回刷新后的 `project` 状态。

### 主要字段

- `GET /api/projects` 单项包含：`id`、`name`、`repoUrl`、`localPath`、`health`（`ok` / `missing` / `error`）、`branch`、`detached`、`upstream`、`ahead`、`behind`、`dirty`、`remoteUrl`、`commit`。
- `GET /api/settings` 返回：`defaultLocalDir`、`defaultDir`（内置目录）、`effectiveDir`（当前生效目录）、`homeDir`、`dataFile`。
- `POST /api/fs/pick-folder` 返回：用户取消时为 `{ "ok": true, "canceled": true }`；选择成功时返回 `path`、`isGit`、`name`；若选择的是仓库子目录，`path` 会自动提升为仓库根目录。

### 常见状态码

| 状态码 | 场景 |
| --- | --- |
| 400 | 参数不合法（路径格式、分支名、目录不是 Git 项目等） |
| 404 | 项目或分支不存在、本地目录不存在 |
| 405 | 路径存在但请求方法不支持 |
| 409 | 仓库或本地目录已在列表中、目标目录已存在且不是 Git 仓库 |
| 413 | 请求体超过 2 MB |
| 500 | Git 命令或系统调用失败 |
| 501 | 当前环境没有可用的目录选择器 |
| 504 | 目录选择器等待超时 |

---

## 8. 限制与超时

| 项目 | 数值 |
| --- | --- |
| 批量添加上限 | 50 个/次 |
| JSON 请求体上限 | 2 MB |
| 批量添加并发 | 2 |
| 批量更新并发 | 3 |
| 批量分支并发 | 3 |
| 项目状态读取并发 | 5 |
| 克隆超时 | 300 秒 |
| fetch / pull 超时 | 240 秒 |
| commit 超时 | 60 秒 |
| push 超时 | 240 秒 |
| checkout / 创建分支超时 | 60 秒 |
| 状态查询超时 | 20 秒 |
| 其他 Git 命令超时 | 8 秒 |
| 目录选择器超时 | 300 秒 |

分支名规则：以字母、数字、点、下划线、斜杠、加号、短横线组成，1–200 字符；不能以 `-` 或 `/` 开头，不能包含 `..`、`@{`、空白，不能以 `/` 或 `.lock` 结尾。

Git 子进程固定设置：

- `GIT_TERMINAL_PROMPT=0`：不会交互式询问凭据，私有仓库需提前配置 SSH key、SSH Agent 或 credential helper。
- `LC_ALL=C`：Git 输出固定为英文，便于解析。
- 超时后使用 `SIGKILL` 终止子进程。

---

## 9. 故障排查

| 现象 | 原因与处理 |
| --- | --- |
| 启动提示 `Port 4321 is already in use` | 端口被占用，使用 `PORT=4400 npm start`，或释放占用进程 |
| 启动提示 `git executable not found` | Git 未安装或不在 `PATH`（launchd/systemd 常见），补充 `PATH` 后重启 |
| 克隆私有仓库失败 | 未配置凭据；先在终端确认 `git clone` 可免交互成功（SSH Agent / credential helper） |
| `Host key verification failed` | 目标主机不在 `known_hosts`；核对指纹后手动加入 |
| 更新报 `Not possible to fast-forward` | 本地与远端已分叉或本地有提交；工具只做 `--ff-only`，需在仓库内手动 merge/rebase |
| 提交报 `Please tell me who you are` / `unable to auto-detect email address` | 该仓库未配置 Git 身份；在仓库内执行 `git config user.name "..."` 与 `git config user.email "..."` |
| 提交返回「没有可提交的改动」 | 工作区干净，无需提交 |
| 推送报 `non-fast-forward` / `rejected` | 远端有新提交；先在仓库内 `git pull --rebase`（或 merge）后再推送 |
| 推送返回「当前处于游离 HEAD，无法推送」 | 当前不在任何分支上；先 checkout 一个分支 |
| 推送报认证失败 | `GIT_TERMINAL_PROMPT=0` 下不会弹出账号密码；需提前配置 SSH key 或 credential helper |
| 更新提示“没有配置远程仓库，已跳过更新” | 仓库没有 remote，属于预期行为 |
| 切换分支失败（本地有改动） | Git 拒绝覆盖未提交改动；先 commit 或 stash，再切换 |
| 导入提示“该目录不是 Git 项目” | 目录内不存在 `.git`；确认路径正确，或在终端执行 `git -C <dir> rev-parse --show-toplevel` 排查 |
| 添加/导入返回 409 | 同一仓库地址或同一本地路径已在列表；先移除旧记录或直接使用现有记录 |
| 目录选择器返回 501 | Linux 未安装 `zenity` / `kdialog`；安装其一，或手动粘贴路径 |
| 目录选择器返回 504 | 超过 300 秒未选择；重新打开并尽快完成 |
| 无桌面/SSH/守护进程下选择器无反应或报错 | 选择器由服务端弹出，需要图形会话；改为手动输入路径 |
| 保存设置报“没有权限访问该目录” | 目标目录不可写；换用可写目录，或修正权限 |
| 保存设置报“不能使用文件系统根目录” | 不允许把 `/` 作为克隆目录 |
| 页面字体与设计稿不同 | 无法访问 Google Fonts 时回退系统字体，不影响功能 |
| 项目显示 `MISSING` / `ERROR` | 本地目录被移动、删除，或目录已不是 Git 仓库；展开详情查看具体错误 |

---

## 10. 升级与卸载

**升级**

1. 停止服务。
2. 备份 `data/`。
3. 替换代码（保留 `data/`）。
4. 重新执行 `npm start`。

本项目没有数据库迁移步骤，升级时保留 `data/` 即可继续使用原有项目列表与设置。

**卸载**

1. 停止服务并取消开机自启配置。
2. 删除应用目录。
3. 若不再需要克隆的仓库，再单独删除 `data/repos/` 或自定义克隆目录（该操作不可恢复）。

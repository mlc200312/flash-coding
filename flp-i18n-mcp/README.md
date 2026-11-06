# flp-i18n-mcp 操作手册

`flp-i18n-mcp` 是 FLP 多语言同步工具的 MCP stdio Server，封装 Java CLI（`flp-i18n-tool`）的
`status / scan / diff / validate / upload / download` 能力，供 Qoder、DeepSeek Harness、
MCP Inspector 等客户端调用。

- 语言：TypeScript，构建为单文件 CJS，运行时只依赖 Node
- 传输：stdio（stdout 只承载 JSON-RPC，日志全部写 stderr）
- Node：>= 18；Java CLI：Java 8+
- npm 包：`@flash-finance/flp-i18n-mcp`（发布到 Nexus `npm-internal`，读取走 `npm-all`）
- CLI jar：未显式配置 `cliJar` 时，按 Maven 坐标自动下载并校验

本手册按交付流程组织：打包 → 上传私服 → 组件介绍 → 安装 → 验证 → 使用。
只关心日常使用的读者可直接跳到[安装](#4-安装)和[使用](#6-使用)。

---

## 目录

1. [打包](#1-打包)
2. [上传私服](#2-上传私服)
3. [组件介绍](#3-组件介绍)
4. [安装](#4-安装)
5. [验证](#5-验证)
6. [使用](#6-使用)
7. [附录 A：环境变量](#附录-a环境变量)
8. [附录 B：错误码与故障排查](#附录-b错误码与故障排查)
9. [附录 C：安全约束](#附录-c安全约束)

---

## 1. 打包

### 1.1 前置条件

| 依赖 | 要求 | 用途 |
| --- | --- | --- |
| Node.js | >= 18 | 构建与运行 MCP Server |
| npm | 随 Node 提供 | 依赖安装、打包、发布 |
| Java | 8+（运行期） | CLI 实际执行；构建期不需要 |
| 私服网络 | 可访问 `registry.flashexpress.pub` | 拉取依赖、发布包、读取 Maven jar |

### 1.2 构建与测试

```bash
cd /path/to/flp-i18n-mcp
npm ci                 # 严格按 lock 文件安装
npm test               # 先 tsc 构建，再跑全部测试
```

`npm test` 会：

1. `tsc -p tsconfig.json` 输出 `dist/`；
2. 运行 `test/*.test.js`，包含 stdio 集成测试、配置热加载、Maven 下载与校验、
   Token 脱敏、写确认流程等，使用 fake CLI 与临时目录，不访问真实平台。

构建产物：

| 产物 | 说明 |
| --- | --- |
| `dist/` | `tsc` 输出，按模块拆分，供本地调试 |
| `dist/npm/index.js` | esbuild 单文件 CJS，npm 包与全局安装使用 |
| `../flp-i18n-plugin/server/dist/index.js` | 插件内嵌产物，由 `npm run bundle` 生成 |

### 1.3 生成 npm 包

```bash
npm run bundle:npm     # 可选：单独生成 dist/npm/index.js
npm pack --dry-run     # 预览包内容，不落盘
npm pack               # 生成 flash-finance-flp-i18n-mcp-<version>.tgz
```

`npm pack` 和 `npm publish` 会通过 `prepack` 自动执行 `build + bundle:npm`，因此不必手动构建，
但发布前仍建议先跑一次 `npm pack --dry-run` 检查内容。

当前包的 `files` 白名单只包含：

```
README.md
dist/npm/index.js
package.json          # npm 自动包含
```

不会包含 `src/`、`test/`、`node_modules/`、`.env` 或任何凭据文件。

预期包体积约 250 KB（解压后约 1.4 MB）。

### 1.4 生成插件内嵌产物

如果本次发布同时要更新 Qoder 插件：

```bash
npm run bundle
# 输出：../flp-i18n-plugin/server/dist/index.js
```

然后到 `flp-i18n-plugin/` 执行 `./package.sh` 生成插件 zip。插件与 npm 包使用同一份源码，
插件内不需要再下载 MCP 依赖。

### 1.5 发布前检查清单

- [ ] `package.json` 的 `version` 已更新；
- [ ] `src/server.ts` 的 `SERVER_VERSION` 与 `package.json` 一致；
- [ ] 默认 Maven 坐标（`src/config.ts` 的 `DEFAULT_MAVEN_COORDINATE`）指向已部署的 CLI 版本；
- [ ] `npm test` 全部通过；
- [ ] `npm pack --dry-run` 内容符合预期，无敏感文件；
- [ ] README 中的版本号、坐标、仓库地址与实际一致。

---

## 2. 上传私服

### 2.1 仓库布局

Nexus 地址：`https://registry.flashexpress.pub/`

| 用途 | 仓库 | 地址 |
| --- | --- | --- |
| npm 发布（写） | `npm-internal` | `https://registry.flashexpress.pub/repository/npm-internal/` |
| npm 读取（客户端） | `npm-all` | `https://registry.flashexpress.pub/repository/npm-all/` |
| Maven CLI jar 读取 | `maven-public` | `https://registry.flashexpress.pub/repository/maven-public/` |

`package.json` 已固定：

```json
{
  "name": "@flash-finance/flp-i18n-mcp",
  "publishConfig": {
    "registry": "https://registry.flashexpress.pub/repository/npm-internal/"
  }
}
```

因此 `npm publish` 默认写入 `npm-internal`；客户端必须从 `npm-all` 读取。

### 2.2 配置认证

发布需要对 `npm-internal` 有写权限的 Nexus 账号。任选一种方式：

**方式 A：`npm login`（私服启用 npm 认证端点时）**

```bash
npm login \
  --registry=https://registry.flashexpress.pub/repository/npm-internal/ \
  --scope=@flash-finance
```

**方式 B：写入用户级 `~/.npmrc`**

```ini
@flash-finance:registry=https://registry.flashexpress.pub/repository/npm-internal/
//registry.flashexpress.pub/repository/npm-internal/:_auth=<base64(username:password)>
always-auth=true
```

生成 base64 时避免把密码留在 shell 历史：

```bash
read -r NEXUS_USER
read -r -s NEXUS_PASS
printf '%s:%s' "$NEXUS_USER" "$NEXUS_PASS" | base64
unset NEXUS_PASS
```

写入后执行 `chmod 600 ~/.npmrc`。不要把 `_auth`、账号或密码提交到仓库、写入文档或贴到聊天中。

**方式 C：CI 使用环境变量**

```ini
//registry.flashexpress.pub/repository/npm-internal/:_auth=${NODE_AUTH_TOKEN}
```

`NODE_AUTH_TOKEN` 由 CI Secret 注入，值为上面的 base64 字符串。

> 本机首次发布前可用 `npm whoami --registry=https://registry.flashexpress.pub/repository/npm-internal/`
> 确认认证生效。

### 2.3 发布

```bash
cd /path/to/flp-i18n-mcp
npm version patch --no-git-tag-version   # 按需 patch/minor/major
npm publish --dry-run                    # 预览将上传的内容
npm publish                              # 写入 npm-internal
```

如需显式指定仓库：

```bash
npm publish --registry=https://registry.flashexpress.pub/repository/npm-internal/
```

发布后不要重复使用同一版本号。Nexus 通常拒绝覆盖已发布版本；需要修正时请递增版本重新发布。

### 2.4 上传后验证

```bash
REG=https://registry.flashexpress.pub/repository/npm-all/

npm view @flash-finance/flp-i18n-mcp version --registry=$REG
npm view @flash-finance/flp-i18n-mcp dist.tarball --registry=$REG
```

预期：

| 检查项 | 预期结果 |
| --- | --- |
| 元数据 | `https://registry.flashexpress.pub/repository/npm-all/@flash-finance%2fflp-i18n-mcp` 返回 200 |
| tarball | `https://registry.flashexpress.pub/repository/npm-all/@flash-finance/flp-i18n-mcp/-/flp-i18n-mcp-<version>.tgz` 返回 200 |
| 版本 | `npm view ... version` 等于刚发布的版本 |

也可以用 curl 快速检查：

```bash
curl -sI "https://registry.flashexpress.pub/repository/npm-all/@flash-finance%2fflp-i18n-mcp" | head -1
curl -sI "https://registry.flashexpress.pub/repository/npm-all/@flash-finance/flp-i18n-mcp/-/flp-i18n-mcp-0.1.4.tgz" | head -1
```

### 2.5 版本回滚与撤回

优先通过**发布新补丁版本**修正问题，避免删除共享仓库中的历史版本。

确需删除某个版本时，Nexus 的 `npm unpublish` 可能挂起不返回，建议使用 REST API：

```bash
# 1. 查询组件 ID（group 为 scope，不带 @）
curl -s -u "$NEXUS_USER" \
  "https://registry.flashexpress.pub/service/rest/v1/search?repository=npm-internal&name=flp-i18n-mcp"

# 2. 按组件 ID 删除（不可恢复）
curl -X DELETE -u "$NEXUS_USER" \
  "https://registry.flashexpress.pub/service/rest/v1/components/<component-id>"
```

注意：

- 删除是共享仓库的破坏性操作，执行前必须获得明确确认；
- 同名不同 scope 的组件会同时出现在搜索结果中，删除前核对 `group` 字段；
- 删除后 `npm-all` 可能有短暂缓存，稍后重试确认返回 404。

### 2.6 与 CLI jar 的版本配套

MCP 默认从 Maven 解析：

```
com.flashfin:flp-i18n-tool:0.1.0
```

对应 jar：

```
https://registry.flashexpress.pub/repository/maven-public/com/flashfin/flp-i18n-tool/0.1.0/flp-i18n-tool-0.1.0.jar
```

CLI 升级后，二选一：

1. 更新 `src/config.ts` 的 `DEFAULT_MAVEN_COORDINATE`，发布新的 MCP 版本；
2. 不升 MCP，通过 `FLP_I18N_CLI_MAVEN_COORD` 或 `i18n_config_set` 覆盖坐标。

---

## 3. 组件介绍

### 3.1 架构

```
MCP Client（Qoder / Inspector / Harness）
        │  stdio JSON-RPC
        ▼
flp-i18n-mcp（Node 单文件 CJS）
        │  spawn java -jar
        ▼
flp-i18n-tool（Maven 下载并缓存的 Java 8 CLI）
        ├── 本地仓库：scan / diff / validate / 写回 properties
        └── 翻译平台：status / upload / download（HTTPS + Token）
```

MCP 层只负责：

- 参数校验与工具编排；
- CLI 进程管理、超时、错误码映射；
- 写操作的 dry-run / planId 确认门禁；
- 日志脱敏与结果缓存。

### 3.2 工具清单

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| `i18n_status` | 无 | 配置、Token、远端连通性；Token 缺失/过期不算工具错误 |
| `i18n_config_get` | 无 | 返回当前有效配置（路径、来源、jar 模式、缓存位置），Token 只显示是否存在 |
| `i18n_config_set` | 字段补丁 + `dryRun` | 校验并写入用户配置文件；默认只预览，`dryRun=false` 才落盘 |
| `i18n_scan` | `module`, `os?` | 本地 key 扫描，含 counts / duplicates / classLoadFailures |
| `i18n_diff` | `module`, `os?`, `languages?` | 本地与平台差异，含 totals / remoteUnavailable |
| `i18n_validate` | `module`, `os?` | 本地 properties 校验 issue 列表 |
| `i18n_upload` | `module`, `os?`, `dryRun`, `planId?`, `limit?`, `rateMs?` | 上传平台缺失 key |
| `i18n_download` | `module`, `os?`, `languages?`, `dryRun`, `planId?` | 下载平台译文到本地 |

- 读工具超时 120s，写工具超时 600s；
- 同一 `module` 的写操作在进程内串行，避免并发写入同一 properties；
- `languages` 省略时使用 `modules.yaml` 中的全部语言。

### 3.3 Resources 与 Prompt

| 名称 | 类型 | 说明 |
| --- | --- | --- |
| `i18n://modules` | Resource | 当前 `modules.yaml` 内容（敏感字段已脱敏） |
| `i18n://reports/latest` | Resource | 本次会话最近一次 scan/diff/validate/upload/download 结果；无报告时返回 `{ "empty": true }`，仅存内存 |
| `sync-module` | Prompt | 参数 `module`、`os?`、`languages?`，输出 status → dry-run → 确认 → apply → 验证 git diff 的安全流程 |

### 3.4 CLI jar 解析

未配置 `cliJar` 时，Server 在首次调用业务工具前解析 Maven 坐标：

1. 检查缓存：`<cache>/<group 路径>/<artifact>/<version>/<artifact>-<version>.jar`；
2. 缓存未命中时从 `FLP_I18N_CLI_MAVEN_REPO` 下载 jar；
3. 校验 `.sha256`，缺失时回退 `.sha1`；
4. 写入同目录临时文件后原子重命名；校验失败不会覆盖缓存；
5. 复用缓存直到文件被删除。

默认缓存位置：

```
~/.cache/flp-i18n/cli/com/flashfin/flp-i18n-tool/0.1.0/flp-i18n-tool-0.1.0.jar
```

可用 `FLP_I18N_CACHE_DIR` 覆盖。

约束：

- 坐标不接受 `SNAPSHOT`；
- 仓库地址只接受 `http/https`，不允许携带用户名密码；
- jar 体积上限 256 MB，下载超时 120s；
- 校验文件缺失或校验失败时拒绝使用该 jar。

用户配置中把 `cliJar` 设为空字符串时，会忽略 `FLP_I18N_CLI_JAR` 并强制走 Maven 自动下载。
这是从「本地 jar」切回「自动下载」的开关。

### 3.5 配置优先级

逐字段解析：

```
用户配置文件 > 环境变量 > 默认值
```

用户配置文件位置（按优先级）：

1. `FLP_I18N_USER_CONFIG` 指定的绝对路径；
2. 插件场景的 `QODER_PLUGIN_DATA/config.json`；
3. `~/.qoder/plugins/data/flp-i18n-local/config.json`（Windows 为 `%USERPROFILE%\.qoder\...`）。

文件按 `mtime:size` 热加载，修改后无需重启 MCP Server。文件损坏或字段非法时 Server
降级启动，`i18n_config_get` / `i18n_status` 返回 `CONFIG_INVALID`，可继续通过
`i18n_config_set` 修复。

用户配置文件示例：

```json
{
  "version": 1,
  "cliJar": "",
  "cliMavenRepo": "https://registry.flashexpress.pub/repository/maven-public/",
  "cliMavenCoordinate": "com.flashfin:flp-i18n-tool:0.1.0",
  "configFile": "/absolute/path/modules.yaml",
  "allowedRoots": ["/absolute/path/workspace"],
  "java": "java",
  "logLevel": "info"
}
```

`cliJar` 为空字符串表示强制 Maven 模式；删除该键则回退到环境变量/默认逻辑。

---

## 4. 安装

4.1–4.5 是**并列的安装方式**，按场景任选其一，不需要依次执行；4.6 是安装后的初始化步骤。

| 场景 | 选择 |
| --- | --- |
| 只在 Qoder 中使用，不想污染全局环境 | 4.1 npx + 4.3 Qoder 配置 |
| 希望终端随时可用 `flp-i18n-mcp` | 4.2 全局安装 |
| 本地开发或调试 MCP 源码 | 4.4 从源码运行 |
| 使用打包好的 Qoder 插件 | 4.5 插件安装 |
| 首次启动、还没有配置文件 | 任选一种方式后执行 4.6 |

### 4.1 使用 npx（推荐，免安装）

先配置 scope registry，之后可省略 `--registry`：

```bash
npm config set @flash-finance:registry https://registry.flashexpress.pub/repository/npm-all/
```

最小启动：

```bash
FLP_I18N_TOKEN="$FLP_I18N_TOKEN" \
FLP_I18N_CONFIG=/absolute/path/modules.yaml \
FLP_I18N_ALLOWED_ROOTS=/absolute/path/workspace \
npx -y @flash-finance/flp-i18n-mcp
```

不修改全局 npm 配置时，直接指定仓库：

```bash
FLP_I18N_TOKEN="$FLP_I18N_TOKEN" \
FLP_I18N_CONFIG=/absolute/path/modules.yaml \
FLP_I18N_ALLOWED_ROOTS=/absolute/path/workspace \
npx --registry=https://registry.flashexpress.pub/repository/npm-all/ \
  -y @flash-finance/flp-i18n-mcp@0.1.4
```

说明：

- Server 是 stdio 进程，直接在终端运行会看起来「卡住」，这是等待 MCP 客户端输入的正常表现；
- 首次业务调用时会下载 CLI jar，需要能访问 Maven 仓库；
- 生产/CI 建议固定版本号，避免 `latest` 漂移；
- `FLP_I18N_TOKEN` 必须是原始 JWT，不要带 `Bearer ` 前缀。

### 4.2 全局安装

```bash
npm install -g @flash-finance/flp-i18n-mcp \
  --registry=https://registry.flashexpress.pub/repository/npm-all/

which flp-i18n-mcp
```

全局命令名：`flp-i18n-mcp`。

### 4.3 接入 Qoder

在工作区或 Qoder 的 MCP 配置中加入：

```json
{
  "mcpServers": {
    "flp-i18n": {
      "command": "npx",
      "args": [
        "--registry=https://registry.flashexpress.pub/repository/npm-all/",
        "-y",
        "@flash-finance/flp-i18n-mcp@0.1.4"
      ],
      "env": {
        "FLP_I18N_CONFIG": "/absolute/path/modules.yaml",
        "FLP_I18N_ALLOWED_ROOTS": "/absolute/path/workspace",
        "FLP_I18N_LOG_LEVEL": "info"
      }
    }
  }
}
```

Token 推荐从宿主进程环境继承，不要写进配置文件：

```bash
export FLP_I18N_TOKEN='<原始 JWT>'
```

然后重启 Qoder。若宿主支持变量展开，也可以写 `"FLP_I18N_TOKEN": "${FLP_I18N_TOKEN}"`；
不支持时请使用启动包装脚本或插件提供的 `env_vars` 机制。

也可先用 MCP Inspector 验证：

```bash
npx @modelcontextprotocol/inspector node /absolute/path/flp-i18n-mcp/dist/index.js
```

### 4.4 从源码运行

```bash
cd /path/to/flp-i18n-mcp
npm ci
npm run build
node dist/index.js
```

源码运行使用 `dist/index.js`（非单文件），需要保留 `node_modules`。适合开发调试，
不适合分发。

### 4.5 通过 Qoder 插件安装

`flp-i18n-plugin` 已内置同一份 Server 单文件产物，安装插件后不需要再单独安装 npm 包。
插件安装与 `/i18n-config` 命令见 `flp-i18n-plugin` 的 README。

### 4.6 无配置启动与初始化

即使没有任何配置，Server 也会启动，方便首次接入：

1. 调用 `i18n_config_get`，确认 `effective.ready` 与 `issues`；
2. 调用 `i18n_config_set`，先 `dryRun=true` 预览；
3. 确认后以 `dryRun=false` 写入用户配置文件；
4. 再次调用 `i18n_config_get`，确认 `ready: true`。

示例参数：

```json
{
  "cliJar": "",
  "configFile": "/absolute/path/modules.yaml",
  "allowedRoots": ["/absolute/path/workspace"],
  "dryRun": true
}
```

---

## 5. 验证

### 5.1 验证包可从私服安装

```bash
TMP=$(mktemp -d)
cd "$TMP"
npm init -y >/dev/null
npm install --no-audit --no-fund \
  --registry=https://registry.flashexpress.pub/repository/npm-all/ \
  @flash-finance/flp-i18n-mcp@0.1.4

node -p "require('@flash-finance/flp-i18n-mcp/package.json').version"
ls -l node_modules/@flash-finance/flp-i18n-mcp/dist/npm/index.js
```

预期版本为 `0.1.4`，且 `dist/npm/index.js` 存在。验证完成后可删除临时目录。

### 5.2 验证 MCP 握手与工具列表

在仓库根目录（已构建）执行：

```bash
printf '%s\n' \
'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0.0"}}}' \
'{"jsonrpc":"2.0","method":"notifications/initialized"}' \
'{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
| FLP_I18N_USER_CONFIG=/tmp/nonexistent-flp-i18n-config.json \
  node dist/npm/index.js 2>/tmp/flp-i18n-mcp.err
```

预期 stdout（每行一个 JSON）：

- `id: 1` 返回 `serverInfo.name = "flp-i18n-mcp"`、`serverInfo.version = "0.1.4"`；
- `id: 2` 返回 8 个工具：`i18n_config_get`、`i18n_config_set`、`i18n_status`、
  `i18n_scan`、`i18n_diff`、`i18n_validate`、`i18n_upload`、`i18n_download`。

stderr 是日志，出现「配置未就绪 / 未检测到 Token」是预期诊断，不影响握手。

从私服安装的包也可以用同样方式验证：

```bash
printf '%s\n' \
'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0.0"}}}' \
'{"jsonrpc":"2.0","method":"notifications/initialized"}' \
'{"jsonrpc":"2.0","id":2,"method":"tools/list"}' \
| node node_modules/@flash-finance/flp-i18n-mcp/dist/npm/index.js
```

### 5.3 验证配置

在 MCP 客户端中调用 `i18n_config_get`，重点确认：

| 字段 | 预期 |
| --- | --- |
| `ok` | `true` |
| `effective.ready` | `true` |
| `effective.values.cliJarMode` | `maven`（未配置本地 jar）或 `path` |
| `effective.values.cliMavenCoordinate` | `com.flashfin:flp-i18n-tool:0.1.0` |
| `effective.values.configFile` | 指向存在的 `modules.yaml` |
| `effective.values.allowedRoots` | 覆盖业务仓库根目录 |
| `effective.values.token.present` | `true` |
| `sources` | 每个字段来自 `file` / `env` / `default` |

若 `ready=false`，按 `error.hint` 使用 `i18n_config_set` 修复。

### 5.4 验证 CLI jar 下载与缓存

首次调用 `i18n_scan` / `i18n_diff` / `i18n_status` 后检查：

```bash
ls -lh ~/.cache/flp-i18n/cli/com/flashfin/flp-i18n-tool/0.1.0/
shasum -a 256 ~/.cache/flp-i18n/cli/com/flashfin/flp-i18n-tool/0.1.0/flp-i18n-tool-0.1.0.jar
```

预期：

- 文件存在且大小约 2.3 MB；
- 同目录无残留 `.part-*` 临时文件；
- 校验值与 Maven 仓库的 `.sha256` / `.sha1` 一致。

也可直接与仓库对比：

```bash
curl -s "https://registry.flashexpress.pub/repository/maven-public/com/flashfin/flp-i18n-tool/0.1.0/flp-i18n-tool-0.1.0.jar.sha1"
```

### 5.5 验证业务只读能力

按顺序调用（均为只读）：

1. `i18n_status`：确认平台连通、Token 有效；`TOKEN_MISSING` / `TOKEN_EXPIRED`
   会作为诊断返回，不算工具错误；
2. `i18n_scan`：确认能扫描到目标模块；
3. `i18n_diff`：确认能读取平台字典并返回差异；
4. `i18n_validate`：确认本地 properties 无格式问题。

### 5.6 自动化测试

```bash
npm test
```

覆盖构建、配置优先级与热加载、Token 脱敏、Maven 下载/校验、stdio 握手、工具参数与
写确认门禁。测试只使用 fake CLI 与临时目录，不触碰真实业务仓库或生产平台。

---

## 6. 使用

### 6.1 配置管理

`i18n_config_set` 默认 `dryRun=true`，只校验并返回预览，不写文件：

```json
{
  "cliMavenCoordinate": "com.flashfin:flp-i18n-tool:0.1.0",
  "configFile": "/absolute/path/modules.yaml",
  "allowedRoots": ["/absolute/path/workspace"],
  "logLevel": "info",
  "dryRun": true
}
```

确认后以 `dryRun=false` 写入：

```json
{
  "dryRun": false
}
```

返回中的 `backupPath` 为原配置文件的备份路径（首次创建时为 `null`）。写入后立即热加载，
无需重启 Server。

常用操作：

| 场景 | 参数 |
| --- | --- |
| 改用 Maven 自动下载 | `{"cliJar": "", "dryRun": false}` |
| 改回本地 jar | `{"cliJar": "/absolute/path/flp-i18n-cli.jar", "dryRun": false}` |
| 切换 CLI 版本 | `{"cliMavenCoordinate": "com.flashfin:flp-i18n-tool:<version>", "dryRun": false}` |
| 指定自定义 Maven 仓库 | `{"cliMavenRepo": "https://.../repository/maven-public/", "dryRun": false}` |
| 指定 Java 路径 | `{"java": "/opt/homebrew/bin/java", "dryRun": false}` |

`i18n_config_set` 不接受 Token，也无法修改 Token；Token 只能通过 `FLP_I18N_TOKEN` 提供。

### 6.2 只读流程

```text
i18n_config_get → i18n_status → i18n_scan → i18n_diff → i18n_validate
```

- `scan` 只读本地，不需要 Token；
- `diff` 需要 Token 和平台连通性；
- `validate` 只读本地 properties；
- 读工具不会修改仓库或平台。

### 6.3 写入确认流程

`i18n_upload` 与 `i18n_download` 都必须遵守两阶段确认：

1. **生成计划（dry-run）**

   ```json
   { "module": "order", "dryRun": true }
   ```

   返回 `planId` 与计划摘要，不写平台、不写本地文件。

2. **向用户展示影响范围并取得明确同意**。

3. **执行写入**

   ```json
   { "module": "order", "dryRun": false, "planId": "<64 位十六进制>" }
   ```

规则：

- `dryRun=false` 缺少 `planId` → `ConfirmationRequired`（`PLAN_ID_REQUIRED`），不会启动 CLI；
- `dryRun=true` 却携带 `planId` → `InvalidParams`（`PLAN_ID_UNEXPECTED`）；
- `planId` 必须是 64 位十六进制 SHA-256；
- `planId` 与 CLI 当前计划不一致 → CLI 返回 `PLAN_STALE`，需重新 dry-run；
- 仓库有未提交改动 → `GIT_DIRTY`，写操作被拒绝；
- 不存在 `--force`，MCP 不会绕过 git 检查；
- 不得复用其他模块或其他时间的 `planId`。

上传可附加灰度参数：

```json
{ "module": "order", "dryRun": true, "limit": 50, "rateMs": 200 }
```

下载可限定语言：

```json
{ "module": "order", "languages": ["zh_CN", "en"], "dryRun": true }
```

### 6.4 输出结构

所有工具返回完整 CLI envelope 的 JSON 文本：

```json
{
  "ok": true,
  "command": "scan",
  "data": {},
  "warnings": [],
  "error": null
}
```

注意：

- `scan / diff / validate` 按模块分组，单模块结果位于 `data.modules[0]`，
  汇总位于 `data.totals`；
- 失败时 `isError: true`，`error` 中包含 `code`（MCP 层）、`cliCode`（CLI 层）、
  `message`、`hint`、`retryable`；
- 写工具执行后会把完整结果记录到 `i18n://reports/latest`，仅存内存，重启清空。

### 6.5 错误处理

| CLI 退出码 | MCP 错误 `code` |
| --- | --- |
| 2（参数/配置） | `InvalidParams` |
| 3（网络/平台） | `UpstreamError`（保留 `retryable`） |
| 4（写被拒绝） | `ConfirmationRequired` |
| 1（内部错误） | `InternalError` |
| 超时 / 输出非 JSON | `UpstreamError`（`cliCode` 为 `TIMEOUT` / `CLI_BAD_OUTPUT`） |

处理原则：

- `retryable=true` 才考虑重试，写操作重试前必须重新 dry-run 获取新 `planId`；
- `ConfirmationRequired` 不自动重试，先向用户说明原因；
- `InternalError` 且 `cliCode` 为 `CLI_*` 时，优先检查 jar、网络与仓库。

### 6.6 日志与脱敏

- 所有日志写 stderr，stdout 只用于 MCP 协议；
- 脱敏范围：`Bearer <token>`、JWT、`token/access_token/appSecret/password/authorization`
  键值对，以及 `FLP_I18N_TOKEN` 的实际值；
- CLI 的 stderr 只在 `debug` 级别转发，且经过同一脱敏器；
- `FLP_I18N_LOG_LEVEL=debug` 可用于排查，但不要长期开启。

---

## 附录 A：环境变量

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `FLP_I18N_CONFIG` | 是* | `modules.yaml` 绝对路径 |
| `FLP_I18N_ALLOWED_ROOTS` | 是* | 允许 CLI 访问的根目录，多个用系统路径分隔符连接（macOS/Linux 为 `:`） |
| `FLP_I18N_TOKEN` | 否** | 平台 Token，仅从环境读取；必须是原始 JWT，不带 `Bearer` |
| `FLP_I18N_CLI_JAR` | 否 | `flp-i18n-cli.jar` 绝对路径；配置后跳过 Maven 解析 |
| `FLP_I18N_CLI_MAVEN_REPO` | 否 | Maven 仓库根地址，默认 `https://registry.flashexpress.pub/repository/maven-public/` |
| `FLP_I18N_CLI_MAVEN_COORD` | 否 | CLI 坐标，默认 `com.flashfin:flp-i18n-tool:0.1.0` |
| `FLP_I18N_CACHE_DIR` | 否 | CLI jar 缓存目录，默认 `$XDG_CACHE_HOME/flp-i18n/cli` 或 `~/.cache/flp-i18n/cli` |
| `FLP_I18N_JAVA` | 否 | Java 可执行文件，默认 `java` |
| `FLP_I18N_LOG_LEVEL` | 否 | `error` / `info` / `debug`，默认 `info` |
| `FLP_I18N_USER_CONFIG` | 否 | 用户配置文件路径；插件场景通常由 `QODER_PLUGIN_DATA` 推导 |

\* 也可由用户配置文件提供 `configFile` / `allowedRoots`。
\** 缺失时 Server 仍启动：`i18n_status` 返回 `TOKEN_MISSING` 诊断，
`diff / upload / download` 由 CLI 返回 `TOKEN_MISSING`。

---

## 附录 B：错误码与故障排查

### B.1 常见 `cliCode`

| `cliCode` | 含义 | 处理 |
| --- | --- | --- |
| `CONFIG_INVALID` | 配置缺失或非法 | `i18n_config_get` 查看 `issues`，用 `i18n_config_set` 修复 |
| `CONFIG_READONLY` | 以静态测试配置启动，无法写用户配置 | 使用正常启动方式或手动创建配置文件 |
| `CONFIG_EMPTY_PATCH` | `i18n_config_set` 未提供任何字段 | 至少提供一个配置项 |
| `CLI_UNAVAILABLE` | 本地 jar 不存在 | 检查 `cliJar` / `FLP_I18N_CLI_JAR`，或留空改用 Maven |
| `CLI_MAVEN_CONFIG_MISSING` | 缺少 Maven 仓库或坐标 | 设置 `FLP_I18N_CLI_MAVEN_REPO` / `FLP_I18N_CLI_MAVEN_COORD` |
| `CLI_MAVEN_COORDINATE_INVALID` | 坐标格式非法或为 SNAPSHOT | 使用 `group:artifact:version[:classifier]`，不要 SNAPSHOT |
| `CLI_DOWNLOAD_FAILED` | 无法访问或下载 jar | 检查网络、代理、仓库地址与坐标 |
| `CLI_CHECKSUM_MISSING` | 仓库未提供 `.sha256` / `.sha1` | 重新部署带校验文件的 jar，或更换仓库 |
| `CLI_CHECKSUM_MISMATCH` | 校验值不一致 | 放弃使用该 jar，检查仓库完整性 |
| `CLI_CHECKSUM_INVALID` | 校验文件格式非法 | 检查仓库中的校验文件 |
| `CLI_DOWNLOAD_TOO_LARGE` | jar 体积异常 | 检查坐标是否指向了错误文件 |
| `CLI_CACHE_WRITE_FAILED` | 无法写入缓存目录 | 检查 `FLP_I18N_CACHE_DIR` 权限与磁盘空间 |
| `CLI_START_FAILED` | Java 进程启动失败 | 检查 `FLP_I18N_JAVA`、Java 版本与 jar 路径 |
| `CLI_BAD_OUTPUT` | CLI 输出不是合法 JSON | 开启 debug 日志查看 CLI stderr |
| `TIMEOUT` | CLI 执行超时 | 读 120s / 写 600s；检查平台连通性 |
| `TOKEN_MISSING` | 未提供 Token | 设置 `FLP_I18N_TOKEN`（原始 JWT） |
| `TOKEN_EXPIRED` | Token 过期 | 重新获取 Token |
| `PLAN_ID_REQUIRED` | 写操作缺少 `planId` | 先 dry-run 获取 `planId` |
| `PLAN_ID_UNEXPECTED` | dry-run 携带了 `planId` | dry-run 时不要传 `planId` |
| `PLAN_STALE` | 计划已失效 | 重新 dry-run，使用新的 `planId` |
| `GIT_DIRTY` | 仓库有未提交改动 | 提交或暂存后重新 dry-run |

### B.2 排查清单

| 现象 | 可能原因 | 处理 |
| --- | --- | --- |
| `npm view` 返回 404 | 用错仓库、scope 错误、版本未发布 | 读取用 `npm-all`；确认包名 `@flash-finance/flp-i18n-mcp` |
| `npx` 提示找不到包 | scope registry 未配置或认证失败 | 显式加 `--registry=.../npm-all/` |
| 客户端工具列表为空 | 包启动失败或配置异常 | 用 5.2 的握手命令检查 stderr |
| 首次调用很慢 | 正在下载 CLI jar | 等待完成，检查缓存目录 |
| `CLI_DOWNLOAD_FAILED` | 内网无法访问 Maven/代理 | 检查仓库地址与网络 |
| `CONFIG_INVALID` | 缺少 `modules.yaml` 或根目录 | 用 `i18n_config_set` 补齐 |
| `TOKEN_MISSING` | 环境变量未传给 MCP 进程 | 重启宿主或改用插件 `env_vars` 注入 |
| 写操作总被拒绝 | 未 dry-run、plan 过期或 git 不干净 | 重新 dry-run 并处理未提交改动 |
| 终端直接运行「无响应」 | stdio 服务等待客户端输入 | 正常现象，用 MCP 客户端或握手命令 |
| 修改配置后不生效 | 改的不是用户配置文件 | `i18n_config_get` 查看 `userConfigPath` |

---

## 附录 C：安全约束

- Token 只能通过 `FLP_I18N_TOKEN` 提供，禁止写入源码、`modules.yaml`、`.mcp.json`、
  插件包、日志、测试、提交记录或对话；
- 不在聊天中粘贴真实 Token，不把 `Bearer` 前缀带进环境变量；
- 所有写入默认 dry-run，必须显式 `dryRun=false` + 有效 `planId`；
- 不存在 `--force`，MCP 与 CLI 都不会跳过 git 检查；
- 路径访问由 `FLP_I18N_ALLOWED_ROOTS` 约束，拒绝 `..` 与符号链接逃逸；
- 下载 jar 必须通过 `.sha256` / `.sha1` 校验，校验失败不落盘；
- npm 发布凭据只保存在 `~/.npmrc` 或 CI Secret，文件权限 `0600`；
- 删除私服组件属于共享仓库的破坏性操作，执行前必须取得明确确认；
- 开发与测试只使用 fake CLI 或临时目录，不触碰真实业务仓库与生产平台。

---

## 分发形态

| 形态 | 包/坐标 | 说明 |
| --- | --- | --- |
| npm 包 | `@flash-finance/flp-i18n-mcp` | 发布到 `npm-internal`，客户端从 `npm-all` 读取 |
| Qoder 插件 | `flp-i18n-plugin` | 内置 Server 单文件产物，不重复下载 MCP 依赖 |
| Java CLI | `com.flashfin:flp-i18n-tool:0.1.0` | 发布到 Maven `maven-releases`，经 `maven-public` 读取 |

发布新版本的完整命令：

```bash
cd /path/to/flp-i18n-mcp
npm ci
npm test
npm version patch --no-git-tag-version
npm publish
npm view @flash-finance/flp-i18n-mcp version \
  --registry=https://registry.flashexpress.pub/repository/npm-all/
```

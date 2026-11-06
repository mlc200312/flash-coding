# FLP 多语言同步 Qoder 插件

把 FLP 多语言同步能力接入 Qoder：Agent 可以发现 `flp-i18n` 技能、使用 `/i18n-sync` 命令，并通过
MCP 工具完成模块检查、上传缺失 key、下载译文回填。

插件本身不包含 Token，也不包含业务仓库代码；Token 只从 Qoder 进程环境变量读取。CLI jar 默认由
MCP Server 按 Maven 坐标自动下载并校验，不再要求单独准备 jar；`modules.yaml` 与允许根目录保存在
用户配置文件（`~/.qoder/plugins/data/flp-i18n-local/config.json`），安装时由 `install.sh` /
`install.ps1` 写入，之后可以在 Qoder 中通过 `/i18n-config` 或 Agent 的 `i18n_config_set`
直接修改，无需重新打包或重启 Qoder。

## 目录结构

```text
flp-i18n-plugin/
├── .qoder-plugin/plugin.json     # 插件元数据与挂载点
├── .mcp.json                     # MCP Server 注册（QODER_NODE_RUNTIME）
├── skills/flp-i18n/SKILL.md      # 技能：触发词、强制流程、禁止事项、报告格式
├── commands/i18n-sync.md         # /i18n-sync 命令
├── commands/i18n-config.md       # /i18n-config 命令（查看/修改 jar、配置、允许根目录）
├── server/dist/index.js          # 打包后的 MCP Server（单文件，构建产物）
├── docs/INSTALL_AND_USAGE.md     # 完整安装与使用文档
├── install.sh                    # macOS/Linux 一键安装
├── install.ps1                   # Windows PowerShell 一键安装
├── package.sh                    # 构建 MCP bundle 并打 zip
└── README.md
```

## 前置条件

1. Node.js >= 18（Qoder 通过 `${QODER_NODE_RUNTIME}` 提供运行时）。
2. Java 8+，且 `java` 在 Qoder 进程的 `PATH` 中。
3. CLI jar：默认由 MCP Server 从 Maven 自动下载；也可用 `cliJar` / `FLP_I18N_CLI_JAR`
   指向本地 `flp-i18n-cli.jar` 覆盖。
4. 可用的 `modules.yaml`，且其中 `workspaceRoot` 指向业务仓库工作区。
5. 翻译平台 Token。

## 构建与打包

```bash
cd flp-i18n-plugin
./package.sh            # 便携包（默认）
./package.sh --local    # 把本机路径写死到 .mcp.json
# 产物：dist/flp-i18n-plugin-0.1.4.zip
```

`package.sh` 会：

1. 在 `../flp-i18n-mcp` 执行 `npm run build`；
2. 用 esbuild 把 MCP Server 及其依赖打包为 `server/dist/index.js`；
3. 把 `.qoder-plugin/`、`.mcp.json`、`skills/`、`commands/`、`server/`、`docs/`、`README.md`、
   `install.sh`、`install.ps1` 放到 zip 根目录；
4. 便携模式写入占位路径，由安装脚本注入真实路径并写入用户配置文件；`--local` 模式直接使用本机路径。
   Token 不会被写入。

若只想刷新 bundle：

```bash
cd ../flp-i18n-mcp && npm run bundle
```

## 安装

macOS / Linux：

```bash
unzip flp-i18n-plugin-0.1.4.zip -d flp-i18n-plugin
./flp-i18n-plugin/install.sh --config /abs/modules.yaml --roots /abs/workspace
```

需要固定本地 jar 时追加 `--jar /abs/flp-i18n-cli.jar`；自定义 Maven 源可用
`--maven-repo` / `--maven-coord`。

Windows PowerShell：

```powershell
Expand-Archive .\flp-i18n-plugin-0.1.4.zip -DestinationPath .\flp-i18n-plugin
.\flp-i18n-plugin\install.ps1 -Config C:\abs\modules.yaml -Roots C:\abs\workspace
```

可选参数：`-Jar C:\abs\flp-i18n-cli.jar`、`-MavenRepo <url>`、`-MavenCoord <coord>`。

安装脚本会写入 `~/.qoder/plugins/cache/local/flp-i18n/<version>/`、写入用户配置文件
`~/.qoder/plugins/data/flp-i18n-local/config.json`、更新 `installed_plugins_v2.json` 与
`settings.json`（改动前备份），安装后需完全退出并重启 Qoder。

也可以照旧在 Qoder 的插件安装入口选择 zip 安装（便携包安装后执行 `/i18n-config` 完成路径配置）。
安装后应能看到：

- 技能 `flp-i18n`
- 命令 `/i18n-sync`、`/i18n-config`
- MCP Server `flp-i18n` 及其 8 个工具

详细参数、Token 配置、升级与卸载见 `docs/INSTALL_AND_USAGE.md`。

## 配置

配置优先级：**用户配置文件 > 环境变量 > 默认值**。用户配置文件默认位于：

- macOS / Linux：`~/.qoder/plugins/data/flp-i18n-local/config.json`
- Windows：`C:\Users\<user>\.qoder\plugins\data\flp-i18n-local\config.json`

格式：

```json
{
  "version": 1,
  "configFile": "/abs/path/modules.yaml",
  "allowedRoots": ["/abs/path/workspace"],
  "java": "java",
  "logLevel": "info"
}
```

`cliJar` 可省略（默认按 Maven 自动下载）；需要固定版本或使用本地构建时可加：

```json
{
  "cliJar": "/abs/path/flp-i18n-cli.jar",
  "cliMavenRepo": "https://registry.flashexpress.pub/repository/maven-public/",
  "cliMavenCoordinate": "com.flashfin:flp-i18n-tool:0.1.0"
}
```

### 在 Qoder 内修改（推荐）

- 执行 `/i18n-config` 查看当前值、来源与校验结果，并按提示修改。
- 或直接对 Agent 说「把 jar 路径改成 …」「允许根目录加上 …」，Agent 会调用
  `i18n_config_get` / `i18n_config_set`：默认 `dryRun=true` 预览，确认后写入。
- 也可以直接用 Qoder 编辑上面的 JSON 文件，保存后下一次工具调用自动生效，无需重启 Qoder。

Qoder 插件清单本身不提供自定义设置表单，因此这些值不会出现在 Qoder 的插件设置页；
`/i18n-config` + 用户配置文件是在 Qoder 内完成配置的入口。安装脚本仍是首次安装的推荐方式。

### 环境变量（回退）

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `FLP_I18N_CONFIG` | 是 | `modules.yaml` 的绝对路径 |
| `FLP_I18N_ALLOWED_ROOTS` | 是 | 允许访问的仓库根目录，多个用系统路径分隔符连接（macOS/Linux 为 `:`） |
| `FLP_I18N_TOKEN` | 是 | 翻译平台 Token，只放在环境变量中 |
| `FLP_I18N_CLI_JAR` | 否 | `flp-i18n-cli.jar` 绝对路径；配置后跳过 Maven 解析 |
| `FLP_I18N_CLI_MAVEN_REPO` | 否 | Maven 仓库根地址，默认 `https://registry.flashexpress.pub/repository/maven-public/` |
| `FLP_I18N_CLI_MAVEN_COORD` | 否 | CLI 坐标，默认 `com.flashfin:flp-i18n-tool:0.1.0` |
| `FLP_I18N_CACHE_DIR` | 否 | CLI jar 缓存目录，默认 `~/.cache/flp-i18n/cli` |
| `FLP_I18N_USER_CONFIG` | 否 | 覆盖用户配置文件路径 |
| `FLP_I18N_LOG_LEVEL` | 否 | `error` / `info` / `debug`，默认 `info` |
| `FLP_I18N_JAVA` | 否 | Java 可执行文件路径，默认 `java` |

安装脚本会同时写入用户配置文件和 `.mcp.json` 的 `env`（作为回退）；修改配置后以用户配置文件为准。
Token 只能通过 `FLP_I18N_TOKEN` 提供，不会写入配置文件、`modules.yaml`、插件目录、README、日志或提交记录。

macOS 从 Dock 启动 Qoder 时不会继承终端里的 `export`。请在启动 Qoder 的环境中配置 Token
（例如从终端执行 `open -a Qoder`），或使用 Qoder 提供的环境变量设置。

## 使用

完整的安装、Token 注入、check/upload/download 流程、结果字段与排障见
[docs/INSTALL_AND_USAGE.md](docs/INSTALL_AND_USAGE.md)。

### 技能触发

在对话中提到多语言、i18n、国际化、properties、key 缺失、上传/下载翻译时，Agent 会加载
`flp-i18n` 技能，并按 SKILL.md 的强制流程执行。

### 斜杠命令

```text
/i18n-sync flm-flp-base-business
/i18n-sync flm-flp-base-business check
/i18n-sync flm-flp-base-business flp-code upload
/i18n-sync flm-flp-base-business flp-code download
/i18n-config
/i18n-config cliJar /Users/me/flp-i18n-tool/target/flp-i18n-cli.jar
```

参数格式：`<module> [os] [check|upload|download]`，默认 `check`。

### 两阶段确认

- 检查（check）只读：`i18n_status` → `i18n_scan` → `i18n_diff` → `i18n_validate`。
- 上传/下载先以 `dryRun=true` 生成计划，Agent 展示 `planId`、数量与文件列表后暂停。
- 用户明确同意后，Agent 才用同一 `planId` 与 `dryRun=false` 执行。
- 没有 `planId` 的写调用会被 MCP Server 直接拒绝（`ConfirmationRequired`），不会启动 CLI。

## 执行后验证与回滚

- 上传：核对返回的 `applied` / `failed` / `remaining` / `journal`；平台侧只新增 key，不改既有翻译。
- 下载：核对 `changedFiles`、`appliedOperations` 与 `backupDir`，并执行 `git diff` 检查内容。
- 文件回滚：从 `backupDir` 复制回原路径，或使用 `git checkout -- <file>`。
- 平台回滚：按 journal 记录的 key 在平台人工删除本次新增项。

## 安全边界

- 所有写操作默认 dry-run，必须携带 dry-run 生成的 `planId`。
- 路径访问受 `FLP_I18N_ALLOWED_ROOTS` 约束，越界与符号链接逃逸会被 CLI 拒绝。
- 工作区有未提交改动时写操作返回 `GIT_DIRTY` 并停止；插件不提供强制绕过参数。
- 日志只写 stderr，并对 Bearer、JWT、Token、appSecret 等敏感值脱敏。
- 下载写入前备份、写后校验；远端空值保留本地值，非 ASCII 写为 `\uXXXX`。

## 故障排查

| 现象 | 处理 |
| --- | --- |
| MCP 工具不可见 | 检查插件是否启用、`server/dist/index.js` 是否存在、Qoder 的 MCP 日志（stderr） |
| 工具返回 `CONFIG_INVALID` | 执行 `/i18n-config` 或调用 `i18n_config_get`，按提示用 `i18n_config_set` 修正路径 |
| 启动报缺少环境变量 | 重新执行 `install.sh` / `install.ps1`；或直接用 `/i18n-config` 写入用户配置文件 |
| `TOKEN_MISSING` / `TOKEN_EXPIRED` | 更新 `FLP_I18N_TOKEN`；status 仍可诊断配置与网络 |
| `REMOTE_UNAVAILABLE` | 检查 CDN/平台连通性；恢复后重新 dry-run |
| `GIT_DIRTY` | 提交或 stash 目标仓库改动后重新 dry-run |
| `PATH_NOT_ALLOWED` | 确认 `modules.yaml` 的 `workspaceRoot`、`allowedRoots` 与实际仓库路径一致，必要时用 `/i18n-config` 修改 |
| `CLI_UNAVAILABLE` | 本地 `cliJar` 路径失效，或 Maven 不可达；用 `/i18n-config` 修正 `cliJar` / `cliMavenRepo` |
| `CLI_CHECKSUM_*` / `CLI_DOWNLOAD_FAILED` | Maven 仓库缺少校验文件或网络失败；确认仓库地址与网络后重试 |
| `CLI_BAD_OUTPUT` | 确认 `FLP_I18N_CLI_JAR` / Maven 坐标指向支持 `--json` 的最新构建 |

## 非 Qoder 客户端

MCP Server 也发布为 npm 包，可直接被 DeepSeek Harness、MCP Inspector 等 stdio 客户端使用：

```bash
FLP_I18N_TOKEN='<token>' \
FLP_I18N_CONFIG=/abs/modules.yaml \
FLP_I18N_ALLOWED_ROOTS=/abs/workspace \
npx --registry=https://registry.flashexpress.pub/repository/npm-all/ \
  -y @flash-finance/flp-i18n-mcp
```

内网可先执行 `npm config set @flash-finance:registry https://registry.flashexpress.pub/repository/npm-all/`，
之后直接用 `npx -y @flash-finance/flp-i18n-mcp`。

首次调用业务工具时自动下载并校验 Maven jar，无需预装 CLI。

## 版本

- 插件：0.1.4
- 依赖 MCP Server：`flp-i18n-mcp` 0.1.4
- 依赖 CLI：`flp-i18n-tool` 0.1.0（默认按 Maven 坐标解析）

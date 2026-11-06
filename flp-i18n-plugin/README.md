# FLP 多语言同步 Qoder 插件

把 FLP 多语言同步能力接入 Qoder：Agent 可以发现 `flp-i18n` 技能、使用 `/i18n-sync` 命令，并通过
MCP 工具完成模块检查、上传缺失 key、下载译文回填。

插件本身不包含 Token，也不包含业务仓库代码；Token 只从 Qoder 进程环境变量读取。jar、配置和允许根目录
在打包时注入 `.mcp.json` 的 `env`，避免依赖 Qoder 启动环境。

## 目录结构

```text
flp-i18n-plugin/
├── .qoder-plugin/plugin.json     # 插件元数据与挂载点
├── .mcp.json                     # MCP Server 注册（QODER_NODE_RUNTIME）
├── skills/flp-i18n/SKILL.md      # 技能：触发词、强制流程、禁止事项、报告格式
├── commands/i18n-sync.md         # /i18n-sync 命令
├── server/dist/index.js          # 打包后的 MCP Server（单文件，构建产物）
├── docs/INSTALL_AND_USAGE.md     # 完整安装与使用文档
├── package.sh                    # 构建 MCP bundle 并打 zip
└── README.md
```

## 前置条件

1. Node.js >= 18（Qoder 通过 `${QODER_NODE_RUNTIME}` 提供运行时）。
2. 已构建的 Java CLI：`flp-i18n-tool/target/flp-i18n-cli.jar`（Java 8+）。
3. 可用的 `modules.yaml`，且其中 `workspaceRoot` 指向业务仓库工作区。
4. 翻译平台 Token。

## 构建与打包

```bash
cd flp-i18n-plugin
./package.sh
# 产物：dist/flp-i18n-plugin-0.1.1.zip
```

`package.sh` 会：

1. 在 `../flp-i18n-mcp` 执行 `npm run build`；
2. 用 esbuild 把 MCP Server 及其依赖打包为 `server/dist/index.js`；
3. 把 `.qoder-plugin/`、`.mcp.json`、`skills/`、`commands/`、`server/`、`docs/`、`README.md` 放到 zip 根目录；
4. 把 `FLP_I18N_CLI_JAR`、`FLP_I18N_CONFIG`、`FLP_I18N_ALLOWED_ROOTS`（未设置时取本仓库默认路径）
   写入 zip 内 `.mcp.json` 的 `env`。Token 不会被写入。

若只想刷新 bundle：

```bash
cd ../flp-i18n-mcp && npm run bundle
```

## 安装

在 Qoder 的插件安装入口选择 `dist/flp-i18n-plugin-0.1.1.zip`。zip 根目录即包含 `.qoder-plugin/`，
安装后应能看到：

- 技能 `flp-i18n`
- 命令 `/i18n-sync`
- MCP Server `flp-i18n` 及其 6 个工具

## 配置环境变量

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `FLP_I18N_CLI_JAR` | 是 | `flp-i18n-cli.jar` 的绝对路径 |
| `FLP_I18N_CONFIG` | 是 | `modules.yaml` 的绝对路径 |
| `FLP_I18N_ALLOWED_ROOTS` | 是 | 允许访问的仓库根目录，多个用系统路径分隔符连接（macOS/Linux 为 `:`） |
| `FLP_I18N_TOKEN` | 是 | 翻译平台 Token，只放在环境变量中 |
| `FLP_I18N_LOG_LEVEL` | 否 | `error` / `info` / `debug`，默认 `info` |
| `FLP_I18N_JAVA` | 否 | Java 可执行文件路径，默认 `java` |

`.mcp.json` 同时声明了两组来源：

- `env`：打包时注入前三个路径变量，插件安装后即可启动 MCP Server；
- `env_vars`：从 Qoder 进程环境转发同名变量，适合更换仓库或注入 `FLP_I18N_TOKEN`。

因此安装后只需确保 `FLP_I18N_TOKEN` 对 Qoder 进程可见；若 jar、配置或允许根目录发生变化，重新执行
`./package.sh` 并安装新包。`FLP_I18N_LOG_LEVEL` 与 `FLP_I18N_JAVA` 可选；如需在插件中使用，需要把它们
加入 `.mcp.json` 的 `env_vars`。

macOS 从 Dock 启动 Qoder 时不会继承终端里的 `export`。请在启动 Qoder 的环境中配置变量
（例如从终端执行 `open -a Qoder`），或使用 Qoder 提供的环境变量设置。

Token 不要写入 `modules.yaml`、插件目录、README、日志或提交记录。

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
| 启动报缺少环境变量 | 重新执行 `./package.sh` 安装新包；若仍失败，检查 zip 内 `.mcp.json` 的 `env` |
| `TOKEN_MISSING` / `TOKEN_EXPIRED` | 更新 `FLP_I18N_TOKEN`；status 仍可诊断配置与网络 |
| `REMOTE_UNAVAILABLE` | 检查 CDN/平台连通性；恢复后重新 dry-run |
| `GIT_DIRTY` | 提交或 stash 目标仓库改动后重新 dry-run |
| `PATH_NOT_ALLOWED` | 确认 `modules.yaml` 的 `workspaceRoot`、`FLP_I18N_ALLOWED_ROOTS` 与实际仓库路径一致 |
| `CLI_BAD_OUTPUT` | 确认 `FLP_I18N_CLI_JAR` 是支持 `--json` 的最新构建 |

## 版本

- 插件：0.1.1
- 依赖 MCP Server：`flp-i18n-mcp` 0.1.0
- 依赖 CLI：`flp-i18n-tool` 0.1.0（`status` 应能正常返回 `moduleCount`）

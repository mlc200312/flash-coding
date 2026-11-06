# flp-i18n-mcp

FLP 多语言同步工具的 MCP stdio Server，封装 Java CLI（`flp-i18n-tool`）的
`status / scan / diff / validate / upload / download` 能力，供 Qoder / MCP Inspector 等客户端调用。

- 传输：stdio（stdout 仅承载 JSON-RPC，日志全部写 stderr）
- Node：>= 18
- 依赖：已构建的 `flp-i18n-cli.jar`（Java 8+）与 `modules.yaml`

## 构建与测试

```bash
cd flp-i18n-mcp
npm install
npm run build     # 输出 dist/
npm test          # 构建 + 28 个测试（含 stdio 集成测试，使用 fake CLI，不访问平台）
```

## 环境变量

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `FLP_I18N_CLI_JAR` | 是 | `flp-i18n-cli.jar` 的绝对路径 |
| `FLP_I18N_CONFIG` | 是 | `modules.yaml` 绝对路径 |
| `FLP_I18N_ALLOWED_ROOTS` | 是 | 允许 CLI 访问的根目录，多个用系统路径分隔符（macOS/Linux 为 `:`）连接 |
| `FLP_I18N_TOKEN` | 否* | 平台 Token，仅从环境读取；缺失时 `i18n_status` 仍可诊断 |
| `FLP_I18N_LOG_LEVEL` | 否 | `error` / `info` / `debug`，默认 `info` |
| `FLP_I18N_JAVA` | 否 | Java 可执行文件，默认 `java` |

\* Token 缺失时 Server 仍启动：`i18n_status` 返回 `TOKEN_MISSING` 诊断，`diff / upload / download` 由 CLI 返回
`TOKEN_MISSING` 错误。Token 不得写入源码、配置、日志或提交记录。

## 接入 Qoder

```json
{
  "mcpServers": {
    "flp-i18n": {
      "command": "node",
      "args": ["/absolute/path/flp-i18n-mcp/dist/index.js"],
      "env": {
        "FLP_I18N_CLI_JAR": "/absolute/path/flp-i18n-tool/target/flp-i18n-cli.jar",
        "FLP_I18N_CONFIG": "/absolute/path/modules.yaml",
        "FLP_I18N_ALLOWED_ROOTS": "/absolute/path/workspace",
        "FLP_I18N_TOKEN": "<从环境注入，不要写死在文件中>",
        "FLP_I18N_LOG_LEVEL": "info"
      }
    }
  }
}
```

也可用 MCP Inspector 验证：

```bash
npx @modelcontextprotocol/inspector node /absolute/path/flp-i18n-mcp/dist/index.js
```

## 工具

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| `i18n_status` | 无 | 配置、Token、远端连通性；Token 缺失/过期不算工具错误 |
| `i18n_scan` | `module`, `os?` | 本地 key 扫描，含 counts / duplicates / classLoadFailures |
| `i18n_diff` | `module`, `os?`, `languages?` | 本地与平台差异，含 totals / remoteUnavailable |
| `i18n_validate` | `module`, `os?` | 本地 properties 校验 issue 列表 |
| `i18n_upload` | `module`, `os?`, `dryRun=true`, `planId?`, `limit?`, `rateMs?` | 上传平台缺失 key |
| `i18n_download` | `module`, `os?`, `languages?`, `dryRun=true`, `planId?` | 下载平台译文到本地 |

读工具超时 120s，写工具超时 600s。同一 `module + os` 的写操作在进程内串行。

工具返回值是完整 CLI envelope 的 JSON 文本：

```json
{ "ok": true, "command": "scan", "data": {}, "warnings": [], "error": null }
```

注意：`scan / diff / validate` 的 CLI 输出按模块分组，单模块结果位于 `data.modules[0]`，
汇总信息位于 `data.totals`。

## 写入确认流程

1. 以 `dryRun=true`（默认）调用，获得 `planId` 与计划摘要。
2. 向用户展示影响范围并取得明确同意。
3. 以完全相同的 `planId` + `dryRun=false` 调用执行。

规则：

- `dryRun=false` 缺少 `planId` → `ConfirmationRequired`，不会启动 CLI。
- `dryRun=true` 却携带 `planId` → `InvalidParams`。
- `planId` 必须是 64 位十六进制 SHA-256；CLI 校验不一致返回 `PLAN_STALE`。
- `GIT_DIRTY`、`PLAN_STALE` 等写拒绝映射为 `ConfirmationRequired`，不会自动重试。
- 不存在 `--force` 参数，MCP 层不会绕过 git 检查。

## 错误映射

| CLI 退出码 | MCP 错误 `code` |
| --- | --- |
| 2（参数/配置） | `InvalidParams` |
| 3（网络/平台） | `UpstreamError`（保留 `retryable`） |
| 4（写被拒绝） | `ConfirmationRequired` |
| 1（内部错误） | `InternalError` |
| 超时 / 输出非 JSON | `UpstreamError`（`cliCode` 为 `TIMEOUT` / `CLI_BAD_OUTPUT`） |

错误结果同时保留 CLI 的 `data`、`warnings` 与原始错误码（`cliCode`），并设置 `isError: true`。

## Resources 与 Prompt

- `i18n://modules`：当前 `modules.yaml` 内容（按敏感字段正则脱敏）。
- `i18n://reports/latest`：本次 Server 会话最近一次 scan/diff/validate/upload/download 的完整结果；
  尚未产生报告时返回 `{ "empty": true }`。仅保存在内存中，重启即清空。
- Prompt `sync-module`：参数 `module`、`os?`、`languages?`，输出从 status 到 dry-run、确认、apply、
  验证 git diff 的安全操作流程。

## 日志与脱敏

- 所有日志写 stderr，stdout 只用于 MCP 协议。
- 脱敏范围：`Bearer <token>`、JWT、`token/access_token/appSecret/password/authorization` 键值对，
  以及 `FLP_I18N_TOKEN` 的实际值。
- CLI 的 stderr 只在 `debug` 级别转发，且经过同一脱敏器。

## 安全约束

- 所有写入默认 dry-run，必须显式 `dryRun=false` + `planId`。
- 路径访问由 CLI 的 `FLP_I18N_ALLOWED_ROOTS` 约束，MCP 不额外放宽。
- 不在源码、日志、测试或配置中保存真实 Token。
- 开发与测试只使用 fake CLI 或临时目录，不触碰真实业务仓库与生产平台。

## 当前范围

本目录对应方案中的 Step 5（MCP Server）。Step 6 的 Qoder 插件尚未实现。

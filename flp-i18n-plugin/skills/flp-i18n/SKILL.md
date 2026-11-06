---
name: flp-i18n
description: >-
  Scan Java error-code and enum keys for FLP multilingual properties, compare them with the
  translation platform, and sync new keys up or translations back down. Use when the user asks
  about i18n, multilingual keys, missing translations, the translation platform, properties
  files, uploading keys, or downloading translations. Every write must start with a dry run
  and requires explicit user confirmation with the returned planId.
descriptionZh: >-
  扫描 Java 代码中的错误码/枚举多语言 key，检查与翻译平台的缺失和冲突，并把新 key 上传到
  平台或把平台译文回填到 properties。当用户提到多语言、i18n、国际化、翻译平台、properties、
  key 缺失、上传 key、下载翻译时使用。所有写操作必须先 dry-run 并取得用户对 planId 的明确确认。
metadata:
  requires:
    mcpServers: ["flp-i18n"]
---

# FLP 多语言同步

通过 `flp-i18n` MCP Server 调用底层 Java CLI，完成单个模块的多语言检查、上传与下载。

## 何时使用

- 检查某个模块的错误码/枚举 key 是否齐全、是否与平台一致
- 把代码中新增、平台缺失的 key 上传到翻译平台
- 把平台译文下载并回填到本地 `properties` 文件
- 排查里中文冲突、远端独有、本地独有的 key

不适用：机器翻译、翻译质量评估、修改翻译平台服务端、批量跨模块无确认写入。

## 前置条件

1. MCP 工具 `i18n_config_get`、`i18n_config_set`、`i18n_status`、`i18n_scan`、`i18n_diff`、`i18n_validate`、`i18n_upload`、`i18n_download` 可见。
2. 已完成配置：`configFile`、`allowedRoots`（用户配置文件优先，环境变量兜底），以及环境变量 `FLP_I18N_TOKEN`。
   `cliJar` 可选：未配置时由 MCP Server 按 Maven 坐标自动下载并校验；也可用 `cliMavenRepo` / `cliMavenCoordinate` 覆盖。
3. Token 只能由用户通过环境变量配置。绝不能让用户在对话中粘贴 Token，也不要把它写进任何文件。

如果工具不可见，说明插件 MCP Server 未启动：让用户检查 Qoder 的 MCP 配置与上述环境变量后重启 Qoder，不要改用 shell 直接调用 CLI。

## 核心概念

- **module**：`modules.yaml` 中定义的模块名，例如 `flm-flp-base-business`。
- **os**：`flp-code` / `flp-enum` / `common-code` / `common-enum`。通常可省略；同名模块跨 os 时才需要显式传入。
- **key 类型**：错误码 `code_<code>`；枚举 `<EnumSimpleName>_<code>`。
- **planId**：dry-run 返回的 64 位十六进制计划指纹。apply 时必须原样回传，计划变化会被拒绝（`PLAN_STALE`）。
- **dryRun**：两个写工具都默认 `true`，只生成计划，不写平台、不写文件。

## 配置

配置由用户配置文件提供（路径见 `i18n_config_get` 返回的 `userConfigPath`），优先级高于环境变量；
修改后立即生效，不需要重启 MCP Server。

- `i18n_config_get`：查看有效值、来源（file / env / default）、校验问题与配置就绪状态。
- `i18n_config_set`：修改 `cliJar` / `cliMavenRepo` / `cliMavenCoordinate` / `configFile` / `allowedRoots` / `java` / `logLevel`。
  默认 `dryRun=true` 只预览；用户明确同意后传 `dryRun=false` 写入。只写用户配置文件，不碰业务仓库。
- 用户说「配置不对」「jar 路径要改」「换一个 modules.yaml」时，优先走配置工具或 `/i18n-config`，
  不要让用户手动改 `.mcp.json`，也不要直接修改已安装插件目录。
- 未配置 `cliJar` 时 jar 按 Maven 自动下载；把 `cliJar` 设为空字符串会忽略 `FLP_I18N_CLI_JAR` 并强制使用 Maven。
  遇到 `CLI_UNAVAILABLE` 时优先检查网络与 `cliMavenRepo` / `cliMavenCoordinate`，仍失败再让用户提供本地 jar。
- 如果 `i18n_status` 等工具返回 `CONFIG_INVALID`，先调用 `i18n_config_get` 定位缺失项。

## 强制流程

### A. check（只读，不写任何数据）

1. 调用 `i18n_status`（无参数），确认 `data.configOk=true`、`data.token.present=true`、`data.remote` 可达。
   - Token 缺失/过期或远端不可达时，报告后停止；check 阶段仍可继续做纯本地扫描。
2. 调用 `i18n_scan {module, os?}`，记录 key 数量与类加载失败。
3. 调用 `i18n_diff {module, os?, languages?}`，获取缺失、冲突与远端不可用明细。
4. 调用 `i18n_validate {module, os?}`，列出本地 properties 的问题。
5. 按「报告格式」输出，不执行任何写工具。

### B. upload（写，必须二次确认）

1. 先完成 check，并向用户说明计划上传的内容。
2. 调用 `i18n_upload {module, os?, dryRun: true}`（可带 `limit`、`rateMs` 灰度）。
3. 从返回的 `data.planId`、`data.plan.entryCount`、`data.plan.entries` 展示：模块、os、条目数、前若干个示例 key、是否限流。
4. 明确询问用户是否执行，等待**明确同意**。没有回复、回答含糊或只表示「看看」都不算同意。
5. 用户同意后，调用 `i18n_upload {module, os?, dryRun: false, planId: <同一 planId>}`。
6. 汇报 `applied / failed / remaining / alreadyCompleted / journal`。失败时不要自行重试 apply，先解释原因。

### C. download（写，必须二次确认）

1. 先完成 check；提醒用户目标仓库需要是干净工作区。
2. 调用 `i18n_download {module, os?, languages?, dryRun: true}`。
3. 展示 `data.planId`、`data.plan.fileCount`、`data.plan.operationCount`、`data.plan.files`。
4. 取得用户明确同意后，调用 `i18n_download {module, os?, languages?, dryRun: false, planId: <同一 planId>}`。
5. 汇报 `changedFiles`、`appliedOperations`、`backupDir`，并提示用户检查 `git diff`；回滚使用该备份目录。

跨模块操作必须逐模块确认，禁止复用其他模块或其他改动的 `planId`。

## 参数速查

| 工具 | 参数 |
| --- | --- |
| `i18n_config_get` | 无 |
| `i18n_config_set` | `cliJar?`, `cliMavenRepo?`, `cliMavenCoordinate?`, `configFile?`, `allowedRoots?`, `java?`, `logLevel?`, `dryRun`（默认 true） |
| `i18n_status` | 无 |
| `i18n_scan` | `module`, `os?` |
| `i18n_diff` | `module`, `os?`, `languages?`（数组） |
| `i18n_validate` | `module`, `os?` |
| `i18n_upload` | `module`, `os?`, `dryRun`（默认 true）, `planId?`, `limit?`, `rateMs?` |
| `i18n_download` | `module`, `os?`, `languages?`, `dryRun`（默认 true）, `planId?` |

## 如何读结果

工具返回一段 JSON 文本，顶层是 CLI envelope：

```json
{ "ok": true, "command": "diff", "data": {}, "warnings": [], "error": null }
```

- `scan / diff / validate` 的模块结果在 `data.modules[0]`，汇总在 `data.totals`。
  - diff 的 `data.totals`：`missingOnPlatform`、`missingLocally`、`zhConflicts`、`remoteUnavailable`。
  - diff 的 `data.modules[0].languages.<lang>`：`remoteAvailable`、`counts`、`missingOnPlatform[]`、`missingLocally[]`、`zhConflicts[]`，条目含 `key`、`type`、`localValue`、`remoteValue`。
  - validate 的 `data.modules[0].issues[]` 含 `type`、`severity`、`key`、`message`、`languages`。
- `upload` 的 `data`：`planId`、`plan.entryCount`、`plan.entries`；apply 后追加 `applied`、`failed`、`remaining`、`journal`。
- `download` 的 `data`：`planId`、`plan.fileCount`、`plan.operationCount`、`plan.files`；apply 后追加 `changedFiles`、`appliedOperations`、`backupDir`。
- `i18n_status` 在 Token 缺失时可能返回 `ok:false` 与 `error.code=TOKEN_MISSING`，这是诊断信息，不是工具故障。

错误结果会设置 `isError`，错误对象形如：

```json
{ "code": "UpstreamError", "cliCode": "REMOTE_UNAVAILABLE", "message": "...", "hint": "...", "retryable": true }
```

## 报告格式

```markdown
## 多语言检查：<module>（os=<os>）
结论：通过 / 有缺失 / 有冲突 / 远端不可用

| 语言 | 平台缺失 | 本地独有 | 中文冲突 | 远端状态 |
| --- | --- | --- | --- | --- |

### 平台缺失（按类型）
- 错误码：code_xxx（示例）… 共 N 条
- 枚举：Enum_xxx（示例）… 共 N 条

### 中文冲突（不自动覆盖）
- key：本地值 vs 平台值

### 本地校验
- error N 条，warning N 条；列出 error 明细

### 远端不可用
- 语言 / URL / 错误原因
```

写操作报告必须包含：planId、影响条目/文件数、执行结果（成功/失败/剩余）、备份目录或 journal 路径、建议用户执行的验证命令（`git diff`）。

## 禁止事项

- 不展示、打印、回显或写入 Token；不要求用户粘贴 Token。
- 不在用户明确确认前执行 `dryRun=false`；不伪造 planId；不复用旧 planId。
- 不跳过 git 脏检查，不尝试使用 `--force` 或任何绕过方式。
- 远端不可用、路径越界、工作区脏、Token 缺失/过期时立即停止，不自动重试写入。
- 不覆盖中文冲突；冲突只报告，交由人工决策。
- 不直接调用 `java -jar` 绕过 MCP 工具（用户明确要求排障除外）。
- 不把 Token 写入用户配置文件；`i18n_config_set` 只接受路径、Maven 坐标与日志级别，拒绝疑似 Token 的值。
- 不把下载结果描述为「已生效」；需要用户重新构建/部署后才生效。

## 失败处理

| 错误 | 处理 |
| --- | --- |
| `TOKEN_MISSING` / `TOKEN_EXPIRED` | 让用户设置/更新 `FLP_I18N_TOKEN` 并重启 Qoder；不要重试写入 |
| `REMOTE_UNAVAILABLE`（retryable） | 报告不可用语言与 URL；可稍后重试只读检查，写操作需重新 dry-run |
| `GIT_DIRTY` | 停止，让用户提交或 stash 后重新 dry-run；不得使用强制参数 |
| `PLAN_STALE` | 计划已变化，重新 dry-run 并向用户重新确认 |
| `CONFIG_INVALID` | 调用 `i18n_config_get` 定位缺失/失效项，再让用户选择新值并用 `i18n_config_set` 修改 |
| `PATH_NOT_ALLOWED` / `PATH_NOT_FOUND` | 配置或 `allowedRoots` 问题；检查配置后必要时用 `i18n_config_set` 补充根目录 |
| `CLI_UNAVAILABLE` | 本地 jar 失效或 Maven 不可达；检查 `cliMavenRepo` / `cliMavenCoordinate`，必要时改用本地 `cliJar` |
| `CLI_CHECKSUM_*` / `CLI_DOWNLOAD_FAILED` | Maven 下载或校验失败；确认仓库地址与网络，不要跳过校验 |
| `CLI_BAD_OUTPUT` / `TIMEOUT` | 报告命令与耗时；检查 CLI jar 版本与网络，不要盲目重试写入 |
| `InvalidParams` | 修正参数（module/os/languages/dryRun 组合）后重试 |

## 安全边界

- 下载写入会先备份、原子替换并做写后校验；远端空值保留本地值，非 ASCII 统一写为 `\uXXXX`。
- 上传只新增平台缺失的 key，不修改既有翻译；中文差异只报告。
- 详细机制与限制见插件 `README.md`。

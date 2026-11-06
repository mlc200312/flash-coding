# FLP 多语言同步插件：安装与使用

本文档对应插件 `flp-i18n@local` 0.1.1、MCP Server `flp-i18n-mcp` 0.1.0、Java CLI `flp-i18n-tool` 0.1.0。

组件关系：

```text
Qoder（技能 / 命令）
        │  MCP stdio
        ▼
flp-i18n-mcp（参数校验、dry-run/planId 门禁、超时、日志脱敏）
        │  java -jar ... --json
        ▼
flp-i18n-tool（扫描、diff、validate、upload、download）
        │
        ├── 业务仓库的 target/classes 与 properties
        └── 翻译平台 / CDN 字典
```

---

## 一、前置条件

| 条件 | 说明 |
| --- | --- |
| Java 8+ | CLI 运行在 Java 8，`java` 需在 Qoder 进程的 `PATH` 中 |
| CLI jar | `flp-i18n-tool/target/flp-i18n-cli.jar` 已构建 |
| `modules.yaml` | 模块、仓库路径、语言、平台地址配置 |
| 翻译平台 Token | 只通过 `FLP_I18N_TOKEN` 注入，不写入任何文件 |
| Qoder Desktop | 通过 `${QODER_NODE_RUNTIME}` 提供 Node 运行时 |

构建 CLI：

```bash
cd flp-i18n-tool
mvn package
ls -l target/flp-i18n-cli.jar
```

`modules.yaml` 最小结构：

```yaml
version: 1
workspaceRoot: ..
languages: [zh_CN, en, th, en_id]
platform:
  saveUrl: https://ard-i18n-api.flashexpress.pub/v1/translations/save
  dictionaryBase: https://ali-static-lang.flashexpress.com

modules:
  flm-flp-base-business:
    repoRoot: flm-flp-base-business/flm-flp-base-business
    domainPath: flm-flp-base-business-domain
    os: flp-code
    packages: [com.flashfin.base.business.domain.type]
    codeFile: BaseBusinessCodeMessages
    enumFile: BaseBusinessEnumerableMessages
```

路径语义：`workspaceRoot` 相对 `modules.yaml` 所在目录；`repoRoot` 相对 `workspaceRoot`；`domainPath` 相对 `repoRoot`。
所有路径必须是已存在目录，且解析后不得越出 `FLP_I18N_ALLOWED_ROOTS`。

---

## 二、安装插件

### 1. 打包

```bash
cd flp-i18n-plugin
./package.sh
# 产物：dist/flp-i18n-plugin-0.1.1.zip
```

打包脚本会：

1. 构建并 bundle MCP Server 到 `server/dist/index.js`；
2. 将 `.qoder-plugin/`、`.mcp.json`、`skills/`、`commands/`、`server/`、`README.md` 放到 zip 根目录；
3. 把下面三个路径变量写入包内 `.mcp.json` 的 `env`：
   - `FLP_I18N_CLI_JAR`（默认 `../flp-i18n-tool/target/flp-i18n-cli.jar`）
   - `FLP_I18N_CONFIG`（默认 `../flp-i18n-tool/modules.yaml`）
   - `FLP_I18N_ALLOWED_ROOTS`（默认工作区根目录）

如需指向其他位置，打包前设置环境变量：

```bash
FLP_I18N_CLI_JAR=/path/to/flp-i18n-cli.jar \
FLP_I18N_CONFIG=/path/to/modules.yaml \
FLP_I18N_ALLOWED_ROOTS=/path/to/workspace1:/path/to/workspace2 \
./package.sh
```

Token 不会被写入包内。

### 2. 安装

在 Qoder 插件安装入口选择 `dist/flp-i18n-plugin-0.1.1.zip`。安装后插件为用户级并默认启用，安装位置：

```text
~/.qoder/plugins/cache/local/flp-i18n/0.1.1/
```

安装完成后应能看到：

- 技能 `flp-i18n`
- 命令 `/i18n-sync`
- MCP Server `flp-i18n` 及 6 个工具：`i18n_status`、`i18n_scan`、`i18n_diff`、`i18n_validate`、`i18n_upload`、`i18n_download`

技能和工具通常在新会话中加载；当前会话看不到时，新开一个对话即可。

### 3. 配置 Token

Token 必须是**纯 JWT**，不要带 `Bearer ` 前缀，前缀由 CLI 自动添加。

在终端执行（输入不会回显、不会进入命令历史）：

```bash
printf 'FLP_I18N_TOKEN（纯 JWT，不带 Bearer）: '
IFS= read -rs FLP_I18N_TOKEN
echo

case "$FLP_I18N_TOKEN" in
  Bearer\ *)
    echo "错误：包含 Bearer 前缀"
    unset FLP_I18N_TOKEN
    ;;
  *.*.*)
    echo "格式 OK"
    launchctl setenv FLP_I18N_TOKEN "$FLP_I18N_TOKEN"
    unset FLP_I18N_TOKEN
    echo "已注入，请完全退出并重启 Qoder"
    ;;
  *)
    echo "警告：不是三段式 JWT，仍将注入"
    launchctl setenv FLP_I18N_TOKEN "$FLP_I18N_TOKEN"
    unset FLP_I18N_TOKEN
    ;;
esac
```

验证（不打印 Token）：

```bash
launchctl getenv FLP_I18N_TOKEN | awk -F. '{ printf "段数=%d 长度=%d\n", NF, length($0) }'
# 期望：段数=3
```

然后 `Cmd+Q` 完全退出 Qoder 再打开。`launchctl setenv` 只对之后启动的 GUI 应用生效，且在注销/重启前有效。

持久化可选方案：从终端 `export FLP_I18N_TOKEN=...` 后执行 `open -a Qoder`，此时 Qoder 继承该会话环境。

### 4. 验证安装

在新会话中任选一种方式：

```text
/i18n-sync flm-flp-base-business check
```

或直接说：

```text
检查 flm-flp-base-business 的多语言状态
```

`i18n_status` 正常结果应包含：

- `data.configOk = true`
- `data.token.present = true`、`data.token.jwt = true`
- `data.token.expiresAt` 为未来时间
- `data.remote.platform.reachable = true`（HTTP 200）

CDN 返回 403 属于预期鉴权响应，只要 `reachable=true` 即为可达。

---

## 三、使用方式

### 1. 触发方式

| 方式 | 示例 |
| --- | --- |
| 自然语言 | “检查 flm-flp-base-business 的多语言 key 缺失” |
| 自然语言 | “把 base-business 缺失的 key 上传到翻译平台” |
| 自然语言 | “下载 en 译文回填到 flm-flp-base-business” |
| 斜杠命令 | `/i18n-sync flm-flp-base-business check` |

技能触发词包括：多语言、i18n、国际化、properties、key 缺失、上传 key、下载翻译、翻译平台。

### 2. `/i18n-sync` 命令

```text
/i18n-sync <module> [os] [check|upload|download]
```

示例：

```text
/i18n-sync flm-flp-base-business
/i18n-sync flm-flp-base-business check
/i18n-sync flm-flp-base-business flp-code upload
/i18n-sync flm-flp-base-business flp-code download
```

- `module` 必填，取自 `modules.yaml` 的 `modules` 键。
- `os` 可省略；仅在同名模块跨 os 时需要（`flp-code` / `flp-enum` / `common-code` / `common-enum`）。
- `mode` 默认 `check`。

### 3. check：只读检查

执行顺序：

1. `i18n_status`：配置、Token、远端连通性
2. `i18n_scan`：本地代码/枚举 key 数量、重复项、类加载失败
3. `i18n_diff`：与平台字典逐语言对比
4. `i18n_validate`：本地 properties 校验

全程不写平台、不写文件。报告包含：

- 平台缺失（应上传的 key，按错误码/枚举分类）
- 本地独有（参考项，通常包含其他模块的 key，不代表本模块要处理）
- 中文冲突（只报告，不自动覆盖）
- 本地校验 error/warning
- 远端不可用语言与原因

大模块提示：`diff` 的完整结果可能很大（例如 base-business 约 2.6 MB）。只关心部分语言时，在对话中明确说明，例如“只对比 zh_CN”，Agent 会传 `languages: ["zh_CN"]`。

### 4. upload：上传缺失 key

两阶段流程：

1. Agent 先完成 check；
2. 调用 `i18n_upload`，默认 `dryRun=true`，生成计划但不写平台；
3. Agent 展示 `planId`、`plan.entryCount`、示例 key、是否限流，然后暂停；
4. **你明确回复同意**后，Agent 才用同一 `planId` 且 `dryRun=false` 执行；
5. 汇报 `applied`、`failed`、`remaining`、`alreadyCompleted`、`journal`。

规则：

- 只提交“平台缺失或值为空、本地有值”的 key；平台已有值不覆盖。
- 平台中文差异只报告，不覆盖。
- 计划按 `module/os/key/language` 规范化后计算 SHA-256；计划变化会返回 `PLAN_STALE`，必须重新 dry-run 并重新确认。
- 每条成功写入 `.i18n-tool/journal/upload-<planId>.json`；同一 `planId` 重跑会跳过已完成条目。
- 灰度参数：`limit`（本次最多提交条数）、`rateMs`（条目间隔，默认 200ms）。
- 工作区有未提交改动时返回 `GIT_DIRTY` 并停止；插件不提供强制绕过参数。

示例对话：

```text
你：把 flm-flp-base-business 缺失的 key 上传到平台
Agent：（先 check，再 dry-run）
      计划上传 604 条，planId=3501a8d1...，示例 key：code_610045、Status_1...
      是否执行？
你：确认执行
Agent：（dryRun=false + 同一 planId）已上传 604 条，失败 0，剩余 0
```

### 5. download：下载译文回填

两阶段流程：

1. Agent 先完成 check，并确认目标仓库工作区干净；
2. `i18n_download` 默认 `dryRun=true`，生成计划；
3. Agent 展示 `planId`、`plan.fileCount`、`plan.operationCount`、`plan.files`；
4. 明确同意后，用同一 `planId` 且 `dryRun=false` 执行；
5. 汇报 `changedFiles`、`appliedOperations`、`backupDir`，并提示执行 `git diff`。

规则：

- 操作类型：`update`（已有 key 且远端值不同）、`add`（本地缺 key 且远端有值）、`comment`（本地缺 key 且远端为空，写 `#key=` 占位）。
- 远端为空不覆盖本地已有值。
- 保留行序、注释、空行；仅重写实际变更行；非 ASCII 统一转为 `\uXXXX`。
- 写前备份到 `.i18n-tool/backup/<timestamp>/`，写临时文件后原子替换，写后回读校验。
- `download` 不支持 `limit` / `rateMs`。
- 写入后需要重新构建/部署，译文才会在应用中生效。

回滚：

```bash
# 方式一：从 backupDir 覆盖回原路径
cp -R .i18n-tool/backup/<timestamp>/<module>/... <原路径>

# 方式二：git checkout -- <file>
```

### 6. 直接指定 MCP 工具参数

Agent 会自动选择工具；排障或需要精确控制时可参考：

| 工具 | 参数 |
| --- | --- |
| `i18n_status` | 无 |
| `i18n_scan` | `module`, `os?` |
| `i18n_diff` | `module`, `os?`, `languages?`（数组） |
| `i18n_validate` | `module`, `os?` |
| `i18n_upload` | `module`, `os?`, `dryRun`（默认 true）, `planId?`, `limit?`, `rateMs?` |
| `i18n_download` | `module`, `os?`, `languages?`, `dryRun`（默认 true）, `planId?` |

写操作门禁：

- `dryRun=true` 不传 `planId`：只生成计划，允许。
- `dryRun=false` 不传 `planId`：返回 `ConfirmationRequired / PLAN_ID_REQUIRED`，不会启动 CLI。
- `dryRun=true` 同时传 `planId`：返回 `InvalidParams / PLAN_ID_UNEXPECTED`。

---

## 四、如何读返回结果

所有工具返回 CLI envelope：

```json
{ "ok": true, "command": "diff", "data": {}, "warnings": [], "error": null }
```

| 命令 | 关键字段 |
| --- | --- |
| `scan` | `data.modules[0].counts`、`duplicates`、`classLoadFailures` |
| `diff` | `data.totals`、`data.modules[0].languages.<lang>` |
| `validate` | `data.modules[0].valid`、`issues[]` |
| `upload` | `data.planId`、`plan.entryCount`；apply 后 `applied/failed/remaining/journal` |
| `download` | `data.planId`、`plan.fileCount/operationCount`；apply 后 `changedFiles/appliedOperations/backupDir` |

错误结果会设置 `isError=true`：

```json
{
  "code": "UpstreamError",
  "cliCode": "GIT_DIRTY",
  "message": "...",
  "hint": "...",
  "retryable": false
}
```

---

## 五、安全边界

- 所有写操作默认 dry-run；apply 必须携带 dry-run 生成的 64 位 `planId`。
- `planId` 不可伪造、不可跨模块或跨次复用；计划变化返回 `PLAN_STALE`。
- 路径访问受 `FLP_I18N_ALLOWED_ROOTS` 约束，拒绝 `..` 与符号链接逃逸。
- 写操作要求目标仓库为干净 git 工作区；插件不暴露 `--force`。
- 上传只新增缺失 key，不覆盖既有翻译；中文冲突只报告。
- 下载先备份、原子写入、写后校验；远端空值保留本地值。
- Token 只从环境变量读取；日志只写 stderr，并对 Bearer、JWT、Token、appSecret 等脱敏。
- 禁止用生产 Token 做批量上传；先在测试模块小批量（`limit`）验证。

---

## 六、故障排查

| 现象 | 原因与处理 |
| --- | --- |
| 工具/技能不可见 | 插件未启用、MCP 进程启动失败或当前会话未加载；新开会话，或重启 Qoder |
| MCP 状态 `process_exited` | 通常是路径/配置变量未注入；检查包内 `.mcp.json` 的 `env`，重新打包安装 |
| `TOKEN_MISSING` | Token 未进入 Qoder 进程环境；重新注入并重启 Qoder |
| `TOKEN_EXPIRED` | 重新获取 JWT 并注入；`status` 可提前看到过期时间 |
| `status` 显示 `jwt=true` 但 `diff` 401 | Token 误加了 `Bearer ` 前缀，去掉后重试 |
| `REMOTE_UNAVAILABLE` | CDN/平台网络问题，查看 `remoteUnavailable` 的 URL 与状态码 |
| `GIT_DIRTY` | 提交或 stash 目标仓库改动后重新 dry-run |
| `PLAN_STALE` | 计划期间数据变化，重新 dry-run 并重新确认 |
| `PATH_NOT_ALLOWED` / `PATH_NOT_FOUND` | 检查 `workspaceRoot`、`repoRoot`、`domainPath` 与 `FLP_I18N_ALLOWED_ROOTS` |
| `CLASSES_NOT_FOUND` | 业务仓库未编译，先执行 `mvn -pl <domain> -am compile` |
| `diff` 输出被截断 | 指定 `languages` 缩小范围 |
| `missingLocally` 数量很大 | CDN 字典按 os 共享，包含其他模块 key；该项仅作参考 |
| CDN 状态 403 | 预期鉴权响应，`reachable=true` 即正常 |

---

## 七、更新与卸载

更新：

1. 修改代码或 `modules.yaml` 后重新 `./package.sh`；
2. 提高 `.qoder-plugin/plugin.json` 的 `version`；
3. 安装新的 zip，重启 Qoder。

若 jar、配置或允许根目录变化，必须重新打包，因为这三个路径在打包时写入 `.mcp.json` 的 `env`。

卸载：在 Qoder 插件管理中停用/移除 `flp-i18n@local`。卸载不会修改业务仓库、`modules.yaml`、Token 或 `.i18n-tool/` 下的 journal/backup。

---

## 八、已知限制

- `diff` 返回包含逐 key 明细，大模块可能超出对话展示上限；建议按语言过滤。
- Token 过期后所有远端操作失败，本地 `scan` / `validate` 仍可用。
- 下载写入后需要重新构建/部署才生效，插件不会触发构建。
- 下载只处理 `modules.yaml` 中声明的模块与 properties 文件，不处理其他目录。
- 中文冲突与远端独有 key 不自动处理，需人工决策。

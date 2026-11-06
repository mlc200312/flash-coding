# flp-i18n-tool

FLP 多语言 key 扫描、差异比对与校验工具。当前完成 Step 0（基线）、Step 1（CLI 骨架 + status）、Step 2（classpath 构建 + 类扫描 + key 生成 + scan）、Step 3（DictionaryClient + DiffEngine + validate）与 Step 4（upload/download 两阶段写操作）。

## 快速开始

```bash
cd flp-i18n-tool
mvn package
java -jar target/flp-i18n-cli.jar status --json
java -jar target/flp-i18n-cli.jar scan --module flm-flp-base-business --json
java -jar target/flp-i18n-cli.jar validate --module flm-flp-base-business --json
java -jar target/flp-i18n-cli.jar diff --module flm-flp-base-business --languages zh_CN,en --json
```

写操作默认 dry-run，必须二次确认：

```bash
# 1. 生成计划，拿到 planId（不写平台、不写文件）
java -jar target/flp-i18n-cli.jar upload --module flm-flp-base-business --languages en --json
java -jar target/flp-i18n-cli.jar download --module flm-flp-base-business --languages en --json

# 2. 确认计划后用同一 planId 执行
export FLP_I18N_TOKEN='<token>'
java -jar target/flp-i18n-cli.jar upload --module flm-flp-base-business --languages en \
  --apply --plan-id <sha256> --limit 50 --rate-ms 200 --json
java -jar target/flp-i18n-cli.jar download --module flm-flp-base-business --languages en \
  --apply --plan-id <sha256> --json
```

首次构建需要访问内网 Maven 镜像下载 surefire provider 与 shade 插件依赖；依赖落到本地仓库后可用 `mvn -o package` 离线构建。

Token 只从环境变量读取：

```bash
export FLP_I18N_TOKEN='<token>'
java -jar target/flp-i18n-cli.jar status --json
```

## status 命令

```bash
java -jar target/flp-i18n-cli.jar status [--config <modules.yaml>] [--json]
```

配置查找顺序：`--config` > `FLP_I18N_CONFIG` > 当前目录 `modules.yaml`。

status 只做只读检查：

- 校验配置结构：version、languages、platform、modules 及每个模块的必填字段。
- 检查 `FLP_I18N_TOKEN` 是否存在；能解析 JWT 时读取 `exp` 判断是否过期。
- 对 CDN 与翻译平台各发一次 HEAD 请求；HTTP 状态码小于 500 视为可达。
- 单次探测超时取 `platform.timeoutMs` 与 5000ms 的较小值。
- 探测请求不携带 Token。
- stdout 只输出一个 JSON 对象；正常路径不写 stderr。

### 输出契约

```json
{
  "ok": false,
  "command": "status",
  "data": {
    "configOk": true,
    "configPath": "/path/to/modules.yaml",
    "version": 1,
    "moduleCount": 2,
    "modules": ["flm-flp-base-business", "flm-openapi-server"],
    "languages": ["zh_CN", "en", "th", "en_id"],
    "token": {
      "present": false,
      "jwt": false,
      "expiresAt": null,
      "expiresInSeconds": null
    },
    "remote": {
      "timeoutMs": 5000,
      "cdn": {
        "url": "https://ali-static-lang.flashexpress.com",
        "reachable": true,
        "status": 403,
        "latencyMs": 1179,
        "error": null
      },
      "platform": {
        "url": "https://ard-i18n-api.flashexpress.pub/v1/translations/save",
        "reachable": true,
        "status": 200,
        "latencyMs": 745,
        "error": null
      }
    }
  },
  "warnings": [],
  "error": {
    "code": "TOKEN_MISSING",
    "message": "未检测到环境变量 FLP_I18N_TOKEN",
    "hint": "export FLP_I18N_TOKEN=<token> 后重试；Token 不得写入配置文件、日志或提交记录",
    "retryable": false
  }
}
```

错误对象字段：`code`、`message`、`hint`、`retryable`。当前错误码：

| code | 退出码 | 含义 |
| --- | --- | --- |
| `INVALID_ARGUMENT` | 2 | 命令行参数错误 |
| `COMMAND_NOT_IMPLEMENTED` | 2 | 命令尚未实现 |
| `CONFIG_NOT_FOUND` | 2 | 配置文件不存在 |
| `CONFIG_PARSE_ERROR` | 2 | YAML 解析失败（含重复键） |
| `CONFIG_INVALID` | 2 | 配置字段不合法 |
| `TOKEN_MISSING` | 2 | 未设置 `FLP_I18N_TOKEN` |
| `TOKEN_EXPIRED` | 2 | Token 已过期 |
| `CDN_UNREACHABLE` | 3 | CDN 不可达 |
| `PLATFORM_UNREACHABLE` | 3 | 翻译平台不可达 |
| `REMOTE_UNREACHABLE` | 3 | 两者均不可达 |
| `PATH_NOT_ALLOWED` | 2 | 路径越界、包含 `..` 或符号链接逃逸 |
| `PATH_NOT_FOUND` | 2 | 配置指向的目录不存在 |
| `MODULE_NOT_FOUND` | 2 | `--module` 未知或 `--os` 过滤后无模块 |
| `CLASSES_NOT_FOUND` | 2 | 模块未编译，缺少 `target/classes` |
| `CLASSPATH_BUILD_FAILED` | 2 | Maven 生成依赖 classpath 失败 |
| `SCAN_FAILED` | 2 | 遍历目录/jar 失败 |
| `FRAMEWORK_CLASS_MISSING` | 2 | 扫描 classpath 缺少 `Enumerable` / `ResultCode` |
| `KEY_GENERATION_FAILED` | 2 | 反射读取枚举 code 失败 |
| `LOCAL_MESSAGES_READ_FAILED` | 2 | 本地 `.properties` 无法读取或编码非法 |
| `DICTIONARY_NOT_FOUND` | 3 | CDN 字典返回 404/410 |
| `DICTIONARY_FETCH_FAILED` | 3 | CDN 请求失败（网络错误或 5xx/408/429） |
| `DICTIONARY_RESPONSE_INVALID` | 3 | 字典响应不是 JSON 对象、值不是标量或 JSON 解析失败 |
| `DICTIONARY_RESPONSE_TOO_LARGE` | 3 | 字典响应超过 32MiB |
| `INVALID_LANGUAGE` | 3 | 语言或 os 代码不合法（防御性校验） |
| `REMOTE_UNAVAILABLE` | 3 | `diff` 中某模块所有语言字典均不可用 |
| `INTERNAL_ERROR` | 1 | 未预期内部错误 |

非致命情况写入 `warnings`：

- `TOKEN_UNVERIFIABLE`：Token 存在但不是标准 JWT，或缺少 `exp`。
- `TOKEN_EXPIRING_SOON`：距离过期不足 24 小时。
- `CDN_UNREACHABLE` / `PLATFORM_UNREACHABLE`：已有更高优先级的错误时作为 warning 保留。
- `CLASS_LOAD_FAILED`：扫描时部分类无法加载，已跳过。
- `REMOTE_PARTIAL`：`diff` 中部分语言字典不可用，其余语言正常输出。
- `LOCAL_MESSAGES_MISSING`：本地语言对应的 properties 文件缺失。

退出码：0 成功；2 参数/配置错误；3 网络/平台错误；4 写操作被拒绝（后续步骤使用）。`diff` 仅在某模块所有语言字典都不可用时返回 3；部分语言失败以 warning 呈现。`validate` 即使发现 ERROR 级问题也返回 0，由 `data.valid` 表达结果；配置文件、模块扫描或本地文件读取失败返回 2。

## scan 命令

```bash
java -jar target/flp-i18n-cli.jar scan [--module <name>] [--os <os>] [--config <modules.yaml>] [--json]
```

- 不传 `--module` 时扫描配置内所有模块；`--os` 可按 `flp-code` / `flp-enum` / `common-code` / `common-enum` 过滤。
- 每个模块先执行 `mvn -o -q dependency:build-classpath -DincludeScope=runtime`，失败后自动联网重试；生成的依赖 classpath 前追加 `<domainPath>/target/classes`。
- 按 `packages` 遍历目录与 jar 中的 `.class`，用独立 `URLClassLoader`（父加载器为系统加载器的父级）以 `initialize=false` 加载，避免触发静态初始化。
- key 规则与旧实现一致：`ResultCode` → `code_<getCode()>`；`Enumerable` → `<SimpleClass>_<getCode()>`；类上有 `StringEnumerable` 时改用 `getCodeString()`。同时实现两个接口的枚举会生成两类 key。
- 同一模块内出现重复 key 时保留一个并写入 `duplicates`；跨模块不合并。
- classpath 构建使用临时文件，结束后删除；不写入业务仓库。

输出中的每个模块包含：

```json
{
  "module": "flm-flp-base-business",
  "os": "flp-code",
  "ok": true,
  "repoRoot": "/.../flm-flp-base-business",
  "domainPath": "/.../flm-flp-base-business-domain",
  "classesDir": "/.../flm-flp-base-business-domain/target/classes",
  "classpathEntries": 492,
  "classpathBuiltOnline": false,
  "classesScanned": 73,
  "counts": { "code": 234, "enum": 351, "duplicate": 0 },
  "codeKeys": { "code_10001": "com.example.SomeErrorEnum" },
  "enumKeys": { "Status_1": "com.example.StatusEnum" },
  "duplicates": []
}
```

`classesScanned` 是扫描到的全部 class 数（含内部类与非枚举类）；`counts` 只统计生成的唯一 key。部分类无法加载时写入 `classLoadFailures` 并给出 warning，与旧实现跳过 `ClassNotFoundException` 的行为一致。

## diff 命令

```bash
java -jar target/flp-i18n-cli.jar diff [--module <name>] [--os <os>] [--languages zh_CN,en] [--config <modules.yaml>] [--json]
```

只读对比代码中的 key 与 CDN 字典，不写任何文件、不访问翻译平台。

- 先执行与 `scan` 相同的 classpath 构建、类扫描与 key 生成。
- `--languages` 缺省使用配置中的全部语言；未知语言返回 `INVALID_ARGUMENT`。
- code key 拉取 `{dictionaryBase}/{os}/lang/{cdnLang}.json`；枚举 key 需要额外拉取对应枚举 os：`flp-code → flp-enum`、`common-code → common-enum`，两者按语言合并后参与对比。字典按 `(os, language)` 在单次执行内缓存，多模块共享同一 os 时只请求一次。
- 请求不携带 Token/Authorization，超时取 `platform.timeoutMs`，按 `platform.maxRetries` 对网络错误、408、429 与 5xx 重试（指数退避，单次上限 2s）。404/410 不重试。
- 远端字典不可用时不会当作空字典：整模块全部失败返回 `REMOTE_UNAVAILABLE`（退出码 3），部分失败在 `remoteUnavailable` 中逐条列出并给出 `REMOTE_PARTIAL` warning。

差异语义：

- `missingOnPlatform`：本地 key 在远端不存在或值空白。上传步骤应以此为准。
- `missingLocally`：远端存在且非空、但本地 key 集合中没有该 key。CDN 字典按 os 共享，会包含其他模块/服务的 key，因此该列表可能较多，仅作参考，不代表本模块需要处理。
- `zhConflicts`：中文语言（`zh*`）本地值与远端值都非空且不相等。
- `remoteUnavailable`：按模块、语言列出拉取失败的 URL、错误码与消息。

输出结构（多模块时顶层为聚合结果，`modules` 内保留逐模块明细）：

```json
{
  "ok": true,
  "command": "diff",
  "data": {
    "configPath": "/path/to/modules.yaml",
    "workspaceRoot": "/path/to/workspace",
    "languages": ["zh_CN", "en"],
    "moduleCount": 1,
    "totals": {
      "missingOnPlatform": 151,
      "missingLocally": 2785,
      "zhConflicts": 0,
      "remoteUnavailable": 0
    },
    "missingOnPlatform": { "zh_CN": ["WalletType_2", "code_610045"], "en": ["code_610045"] },
    "missingLocally": { "zh_CN": ["code_0", "ActionType_0"] },
    "zhConflicts": [],
    "remoteUnavailable": [],
    "modules": [
      {
        "module": "flm-flp-base-business",
        "os": "flp-code",
        "ok": true,
        "classesScanned": 73,
        "counts": { "code": 234, "enum": 351, "duplicate": 0 },
        "totals": { "missingOnPlatform": 151, "missingLocally": 2785, "zhConflicts": 0, "remoteUnavailable": 0 },
        "languages": {
          "zh_CN": {
            "remoteAvailable": true,
            "counts": { "missingOnPlatform": 151, "missingLocally": 2785, "zhConflicts": 0, "remoteUnavailable": 0 },
            "missingOnPlatform": [
              { "key": "WalletType_2", "type": "enum", "localValue": "钱包类型", "remoteValue": null }
            ],
            "missingLocally": [],
            "zhConflicts": []
          }
        },
        "remoteUnavailable": []
      }
    ]
  },
  "warnings": [],
  "error": null
}
```

## validate 命令

```bash
java -jar target/flp-i18n-cli.jar validate [--module <name>] [--languages zh_CN,en] [--config <modules.yaml>] [--json]
```

只读校验 key 与本地多语言文件，不访问网络。

- 本地文件路径为 `{domainPath}/src/main/resources/i18n/{codeFile}_{suffix}.properties` 与 `{enumFile}_{suffix}.properties`，UTF-8 读取；`zh_CN` 还会读取无后缀文件，仅用于补齐后缀文件中不存在的 key。
- 后缀映射：`zh_CN → _zh_CN`、`en → _en`、`th → _th`、`en_id → _en_ID`、`id/en_ID` 统一归一为 `en_id`。
- 校验项：
  - `INVALID_KEY_FORMAT`（ERROR）：code key 必须匹配 `code_-?\d+`；枚举 key 必须是 `<类名>_<不含空白/=/:的编码>`。
  - `DUPLICATE_KEY`（ERROR）：同一模块内重复生成的 key。
  - `EMPTY_VALUE`（WARNING）：某个 key 在部分语言中缺失或空白，`languages` 列出缺失语言。
  - `PLACEHOLDER_MISMATCH`（ERROR）：占位符（如 `{0}`、`{name}`）集合与其他语言不一致。以 `zh_CN` 为基准，若 `zh_CN` 缺失则取第一个非空语言；比较前对占位符排序，忽略顺序差异。
- `data.valid` 为 true 表示没有 ERROR 级问题；WARNING 不影响 `valid`。

输出结构：

```json
{
  "ok": true,
  "command": "validate",
  "data": {
    "configPath": "/path/to/modules.yaml",
    "workspaceRoot": "/path/to/workspace",
    "languages": ["zh_CN", "en", "th", "en_id"],
    "moduleCount": 1,
    "valid": true,
    "totals": { "keysChecked": 585, "errors": 0, "warnings": 135 },
    "issues": [
      {
        "module": "flm-flp-base-business",
        "type": "EMPTY_VALUE",
        "severity": "warning",
        "key": "code_610045",
        "message": "以下语言缺少译文: zh_CN en th en_id",
        "languages": ["zh_CN", "en", "th", "en_id"],
        "expected": null,
        "actual": {}
      }
    ],
    "modules": [
      {
        "module": "flm-flp-base-business",
        "os": "flp-code",
        "ok": true,
        "classesScanned": 73,
        "counts": { "code": 234, "enum": 351, "duplicate": 0 },
        "keysChecked": 585,
        "valid": true,
        "errorCount": 0,
        "warningCount": 135,
        "localFiles": {
          "zh_CN": { "loaded": ["/.../BaseBusinessCodeMessages_zh_CN.properties"], "missing": [] }
        },
        "issues": []
      }
    ]
  },
  "warnings": [],
  "error": null
}
```

## upload 命令

```bash
java -jar target/flp-i18n-cli.jar upload [--module <name>] [--languages zh_CN,en] \
  [--config <modules.yaml>] [--limit <n>] [--rate-ms <ms>] [--apply --plan-id <sha256>] [--force] [--json]
```

只把“线上缺失、本地有值”的 key 推送到翻译平台：

- 对每个语言拉取 code 与 enum 字典；code key 使用模块 `os`，enum key 使用 `flp-code → flp-enum`、`common-code → common-enum` 派生的命名空间。
- 远端值非空即视为已存在，不覆盖；远端为空且本地值为空时跳过，并在 `skippedBlankValues` 计数。
- `--limit` 限制本次最多提交的条目数；`--rate-ms` 为条目间隔，默认 200ms，`0` 表示不等待。
- 计划按 `module/os/key/language` 排序后规范化 JSON（Jackson `ORDER_MAP_ENTRIES_BY_KEYS`）并计算 SHA-256，得到 `planId`。计划内容包含 `saveUrl` 与语言列表，不包含 `--limit`/`--rate-ms`。
- 不带 `--apply` 只输出计划；`--apply` 必须携带 dry-run 得到的 `--plan-id`，否则拒绝执行。
- 执行时重新扫描并重算计划；与服务端现状不一致时返回 `PLAN_STALE`，要求重新生成计划。
- 每成功一条写入 `.i18n-tool/journal/upload-<planId>.json`；再次以同一 `planId` 执行时跳过已完成条目，支持断点续跑。
- 仅当存在待上传条目时才要求 `FLP_I18N_TOKEN`；空计划执行直接成功。
- git 守卫：目标仓库必须是 git 工作区且无未提交变更（忽略 `.i18n-tool/`）；`--force` 跳过守卫，同时向 stderr 和 `warnings` 写入 `GIT_FORCE_OVERRIDE`。
- 平台请求沿用旧接口结构：`{os, t_key, loading:true, langs:{<field>: value}}`，`Authorization: Bearer <token>`。语言字段映射：`zh_CN → zh`、`en_id/id → id`（平台 LangsDTO 没有 `en_id` 字段）、`en/th/my/vi` 原样。401/403 不重试，408/429/5xx 退避重试。

输出结构：

```json
{
  "ok": true,
  "command": "upload",
  "data": {
    "planId": "<64 位 sha256>",
    "plan": {
      "planId": "<64 位 sha256>",
      "entryCount": 1,
      "moduleCount": 1,
      "entries": [
        { "module": "demo", "os": "flp-code", "key": "code_100", "type": "code", "language": "en", "value": "Old error" }
      ]
    },
    "apply": true,
    "limit": 50,
    "rateMs": 200,
    "alreadyCompleted": 0,
    "pending": 1,
    "applied": 1,
    "failed": 0,
    "remaining": 0,
    "journal": "/path/to/.i18n-tool/journal/upload-<sha256>.json",
    "results": [ { "module": "demo", "os": "flp-code", "key": "code_100", "language": "en", "ok": true } ]
  },
  "warnings": [],
  "error": null
}
```

错误码：`TOKEN_MISSING`、`TOKEN_EXPIRED`、`PLAN_STALE`、`GIT_DIRTY`、`JOURNAL_READ_FAILED`、`JOURNAL_WRITE_FAILED`、`UPLOAD_UNAUTHORIZED`、`UPLOAD_FAILED`、`INVALID_LANGUAGE`。

## download 命令

```bash
java -jar target/flp-i18n-cli.jar download [--module <name>] [--languages zh_CN,en] \
  [--config <modules.yaml>] [--apply --plan-id <sha256>] [--force] [--json]
```

把翻译平台的值回填到 `{domainPath}/src/main/resources/i18n/{codeFile|enumFile}_{suffix}.properties`：

- 先补齐缺失 key，再回填翻译。`--limit`、`--rate-ms` 不适用于 download，传入会返回 `INVALID_ARGUMENT`。
- 计划操作：
  - `update`：文件已有该 key，远端非空且值不同（或该行仍是未转义的原始非 ASCII）。
  - `add`：生成的 key 在文件中不存在，且远端有值。
  - `comment`：生成的 key 不存在且远端为空，写入 `#key=` 占位注释。
- 远端为空时不覆盖本地已有值。
- 无法表示为 properties key 的生成 key（含空白、`=`、`:` 或以 `#`/`!` 开头）会跳过，并写入 `INVALID_KEY_SKIPPED` warning。
- 行序、注释、空行与未变更行保持原样；只有真正变更的行会重写。非 ASCII 字符统一转成大写 `\uXXXX`。
- `--apply` 校验 `planId`、执行 git 守卫（规则与 upload 相同），然后：
  1. 在 `.i18n-tool/backup/<yyyyMMdd-HHmmss-SSS>/<module>/<相对路径>` 备份原文件；
  2. 写同目录临时文件并 `ATOMIC_MOVE` 替换（不支持时退化为普通替换）；
  3. 用 `Properties.load` 回读校验，失败时从备份恢复并返回错误。
- 计划为空时 `--apply` 直接成功，不创建备份、不要求 Token。
- 重复执行幂等：第一次写入后再跑 dry-run，`fileCount` 与 `operationCount` 均为 0。

输出结构：

```json
{
  "ok": true,
  "command": "download",
  "data": {
    "planId": "<64 位 sha256>",
    "plan": { "planId": "<64 位 sha256>", "fileCount": 2, "operationCount": 7, "files": [] },
    "apply": true,
    "changedFiles": [
      { "module": "demo", "file": "domain/src/main/resources/i18n/DemoCodeMessages_en.properties", "operations": 3, "backup": "/path/to/.i18n-tool/backup/.../DemoCodeMessages_en.properties" }
    ],
    "appliedOperations": 7,
    "backupDir": "/path/to/.i18n-tool/backup/<timestamp>"
  },
  "warnings": [],
  "error": null
}
```

错误码：`PLAN_STALE`、`GIT_DIRTY`、`BACKUP_FAILED`、`PROPERTIES_READ_FAILED`、`PROPERTIES_WRITE_FAILED`、`PROPERTIES_VERIFY_FAILED`、`REMOTE_UNAVAILABLE`。

## modules.yaml

```yaml
version: 1
workspaceRoot: ..
languages: [zh_CN, en, th, en_id]
platform:
  saveUrl: https://ard-i18n-api.flashexpress.pub/v1/translations/save
  dictionaryBase: https://ali-static-lang.flashexpress.com
  timeoutMs: 30000
  maxRetries: 3

modules:
  flm-flp-base-business:
    repoRoot: flm-flp-base-business/flm-flp-base-business
    domainPath: flm-flp-base-business-domain
    os: flp-code
    packages: [com.flashfin.base.business.domain.type]
    codeFile: BaseBusinessCodeMessages
    enumFile: BaseBusinessEnumerableMessages
```

路径语义：

- `workspaceRoot` 相对配置文件所在目录解析，缺省为 `.`（即配置目录）。
- `repoRoot` 相对 `workspaceRoot`。
- `domainPath` 相对 `repoRoot`。
- 所有路径必须存在且为目录，不得包含 `..`，解析真实路径后不得越出允许根目录。
- 允许根目录来自 `FLP_I18N_ALLOWED_ROOTS`（多个值用系统 path separator 分隔）；未设置时只允许 `workspaceRoot`。

约束：

- `version` 必须为 1。
- `languages` 缺省为 `[zh_CN, en, th, en_id]`；不可为空或重复。
- `platform.saveUrl`、`platform.dictionaryBase` 必须是 http/https URL，且不得包含用户名密码。
- `platform.timeoutMs` 范围 100–600000，默认 30000。
- `platform.maxRetries` 范围 0–10，默认 3。
- `os` 只能是 `flp-code`、`flp-enum`、`common-code`、`common-enum`。配置中的 `os` 表示 code key 使用的字典命名空间；当模块存在枚举 key 时，`diff` 会按 `flp-code → flp-enum`、`common-code → common-enum` 自动补拉枚举字典。若 `os` 本身是枚举命名空间，则 code 与 enum 共用该字典。
- 每个模块必须有非空的 `repoRoot`、`domainPath`、`packages`、`codeFile`、`enumFile`。

## Step 0 基线

### 产物

- `baseline/baseline.json`：flm-flp-base-business 的完整 key 清单。
- `baseline/BaselineScan.java`：只读扫描程序，复现方式：

```bash
# 1. 生成模块依赖 classpath
mvn -o -q -f ../flm-flp-base-business/flm-flp-base-business/flm-flp-base-business-domain/pom.xml \
  dependency:build-classpath -Dmdep.outputFile=/tmp/bb-cp.txt -DincludeScope=runtime

# 2. 编译并运行（需把 domain jar 追加到 classpath）
CP="$(cat /tmp/bb-cp.txt):$HOME/.m2/repository/com/flashfin/base/flm-flp-base-business-domain/0.0.1-SNAPSHOT/flm-flp-base-business-domain-0.0.1-SNAPSHOT.jar"
javac -cp "$CP" -d /tmp/flp-baseline baseline/BaselineScan.java
java -cp "/tmp/flp-baseline:$CP" BaselineScan baseline/baseline.json
```

### 结果

- 生成时间：2026-11-05T18:20:39+08:00
- 来源：本地 Maven 包 `flm-flp-base-business-domain-0.0.1-SNAPSHOT.jar`（生成基线时工作区无 `target/classes`；jar 内该包有 73 个 class，与源码目录下 73 个 `.java` 一致。Step 2 已编译 `target/classes` 并复扫，结果与基线一致）
- class 数：73
- 错误码 key：234
- 枚举 key：351
- 旧实现语言集合：`zh_CN, en, th, id, en_ID`

`baseline.json` 中的 `counts` 为 `{ "code": 234, "enum": 351 }`，`codeKeys` 与 `enumKeys` 按旧实现的五种语言分别保存完整 key 列表。

### 旧流程映射

上传：

```
I18nMainTest#uploadFlpI18n (32)
  → FlmDictionariesApplicationTests#exportFlpCodeToPlatform (80)
  → FlmDictionariesApplicationTests#exportFlpEnumToPlatform (194)
  → FlmDictionariesApplicationTests#sendDataToServer (229)
  → HttpUtils.postJson → platform.saveUrl
```

- 只推送平台缺失的 key；zh_CN 差异只打印“代码中/翻译平台”，不覆盖。
- Token 来自 `getTokenHeader()` (446) 中的硬编码 JWT。新工具不读取、不复用该 JWT，只使用 `FLP_I18N_TOKEN`。

下载：

```
I18nMainTest#downloadFlpI18n (46)
  → I18nMainTest#writeFlpKeysToProperties
  → I18nUtils#testEnum / #testResultCode
  → I18nGenerator#generatorEnumKeyToProperties (645)
  → I18nGenerator#generatorCodeKeyToProperties (712)
  → EnumTest#resetFlpKeysToProperties (14)
  → I18nGenerator#getDic (869) 拉取 CDN JSON
  → I18nGenerator#resetValueToProperties (541) 回填 .properties
```

key 生成规则：

- 错误码：`code_<code>`
- 枚举：`<EnumSimpleName>_<code>`
- 类上有 `StringEnumerable` 注解时优先 `getCodeString()`
- 相关入口：`generateJsonByClasses` (391)、`generateCodeJsonByClassesPackage` (432)

### 遗留

- Step 0 第 3 项“记录平台 API 的实际响应结构”尚未完成：需要测试环境 Token。当前不打印或推测生产响应；`sendDataToServer` 只打印 `HttpResponse` 对象，无法从代码确定响应体。
- 旧语言集合中的 `id`、`en_ID` 与新配置默认的 `en_id` 命名不一致；Step 3 已在 `LanguageCodes` 中统一映射，并由 `LanguageCodesTest`、`LocalMessagesLoaderTest` 与 `HttpDictionaryClientTest` 覆盖。

## Step 2 验证结果

本机于 2026-11-05 对三个模块分别执行新 `scan` 与旧实现（`baseline/OldKeyDump.java`，内部调用旧 `ClassUtils` 与 `I18nGenerator`）比对，key 集合差异均为 0：

| 模块 | 扫描 class | code key | enum key | 重复 key | 与旧实现差异 |
| --- | --- | --- | --- | --- | --- |
| `flm-flp-base-business` | 73 | 234 | 351 | 0 | 0（同时与 `baseline.json` 比对） |
| `flm-flp-ms-server` | 62 | 128 | 390 | 0 | 0 |
| `flm-flp-mp-server` | 25 | 71 | 105 | 0 | 0 |

旧实现比对方式（以 ms 为例）：

```bash
# 1. 生成模块依赖 classpath
mvn -o -q -f ../flm-flp-ms-server/flm-flp-ms-server/flm-flp-ms-domain/pom.xml \
  dependency:build-classpath -Dmdep.outputFile=/tmp/cp-ms.txt -DincludeScope=runtime

# 2. 编译旧实现比对程序（CP 需包含 kaffatech-mocha-generator/common 及其依赖）
CP="$(cat /tmp/cp-ms.txt)"
javac -cp "$CP" -d /tmp/flp-oldcheck baseline/OldKeyDump.java

# 3. 导出旧实现 key 集合（domain target/classes 放在最前）
java -cp "../flm-flp-ms-server/flm-flp-ms-server/flm-flp-ms-domain/target/classes:$CP:/tmp/flp-oldcheck" \
  OldKeyDump "com.flashfin.flp.ms.domain.type,com.flashfin.flp.ms.domain.accounting.type,com.flashfin.flp.ms.domain.trade.type,com.flashfin.flp.ms.domain.fx.type,com.flashfin.flp.ms.domain.settle.type,com.flashfin.flp.ms.domain.paycore.type" \
  /tmp/old-ms.json

# 4. 新工具扫描后按 key 集合比对
java -jar target/flp-i18n-cli.jar scan --module flm-flp-ms-server > /tmp/scan-ms.json
```

比对结果表明新扫描器在目录扫描、jar 扫描、`URLClassLoader` 加载、`Enumerable` / `ResultCode` / `StringEnumerable` 判定与 key 拼接上均与旧实现一致。

## Step 3 验证结果

单元测试共 93 个，`mvn -o test` 全部通过。新增覆盖：

- `DiffEngineTest`：10 组差异场景（完全一致、平台缺失、远端空值、本地缺失、中文冲突、中文相同、远端不可用、多语言独立、code/enum 类型归类、多语言同时空值）。
- `ValidatorTest`：key 格式、重复 key、空值 warning、占位符缺失与顺序差异。
- `HttpDictionaryClientTest`：语言路径映射、`en_ID → en_id`、5xx 重试、404 不重试、非法 JSON、非对象 JSON、非标量值、连接失败、非法 os。
- `ModuleDictionariesTest`：code/enum 字典 os 派生、合并与单侧不可用。
- `LocalMessagesLoaderTest`：后缀文件优先级、`en_ID` 后缀、缺失文件与缺失目录、UTF-8 读取。
- `LanguageCodesTest`：语言别名、CDN 路径、properties 后缀与非法语言。

2026-11-05 本机只读实测：

| 命令 | 范围 | 结果 |
| --- | --- | --- |
| `validate` | 全部 4 个模块 | `valid=true`，1285 个 key，0 ERROR，190 WARNING（均为 `EMPTY_VALUE`） |
| `diff --languages en` | 全部 4 个模块 | `missingOnPlatform=208`（base 151、ms 40、mp 15、openapi 2），`remoteUnavailable=0`，耗时约 13s |
| `diff --languages zh_CN,en` | `flm-flp-base-business` | 每种语言 `missingOnPlatform=151`，`zhConflicts=0`，`remoteUnavailable=0` |

验证过程中确认了 code/enum 字典必须分开拉取：仅用 `flp-code` 对比时，base 模块每种语言会出现 352 个缺失（351 个枚举 key 全部误报）；合并 `flp-enum` 后降为 151 个，其中 150 个枚举 key、1 个 code key 在字典中确实不存在。`missingLocally` 因字典按 os 共享而包含其他服务 key（base 模块每种语言约 2785 个），属于信息性输出。

## Step 4 验证结果

单元测试共 122 个，`mvn -o test` 全部通过。Step 4 新增覆盖：

- `PropertiesDocumentTest`：原样返回、非 ASCII 转义、注释占位、追加 key、分隔符与转义、重复 key、CRLF。
- `PropertiesFileStoreTest`：原子写 + 备份、无变化不重写、缺失文件与父目录创建。
- `PlanHasherTest`：map 顺序无关、内容敏感、64 位十六进制。
- `GitGuardTest`：非 git 目录、干净仓库、脏文件、`.i18n-tool` 忽略。
- `UploadPlanTest`：输入顺序无关、值与端点敏感。
- `UploadJournalTest`：完成项持久化与重载。
- `HttpPlatformClientTest`：旧接口 body、Bearer 头、`en_id → id`、401 不重试、5xx 重试。
- `SyncEndToEndTest`：临时 git 仓库完成 upload 与 download 全流程。

2026-11-05 本机端到端验收（`SyncEndToEndTest`，仅临时目录，未访问真实平台、未修改业务仓库）：

| 步骤 | 结果 |
| --- | --- |
| upload dry-run | 1 个缺失 key（`code_100`），生成 64 位 `planId` |
| upload apply | 1 条成功，Token 仅通过内存传入；journal 记录完成项 |
| upload 二次 dry-run | 0 条；空计划 apply 无需 Token 即成功 |
| download dry-run | 2 个文件、7 个操作（update/add/comment） |
| download apply | 7 个操作全部应用；备份内容与原始文件一致 |
| 文件结果 | 注释与行序保持；中文写为 `\uXXXX`；远端空值对应 key 生成注释占位 |
| `git status` | 仅 2 个 properties 文件被修改；`git diff --name-only` 与预期一致 |
| download 二次 dry-run | 0 个文件、0 个操作；空计划 apply 成功 |
| 脏仓库 apply | 返回 `GIT_DIRTY`；`--force` 后成功并产生 `GIT_FORCE_OVERRIDE` warning |

## 安全约束

- Token 只从 `FLP_I18N_TOKEN` 读取，不写入配置、源码、测试、日志或提交记录。
- 输出中只暴露 Token 是否存在、是否为 JWT、过期时间，不输出 Token 本身。
- status 探测不发送 Authorization 头；diff 拉取 CDN 字典同样不发送 Token。
- 测试使用自造 JWT，不使用真实 Token。
- 配置与 `--module` 指定的路径必须落在 `workspaceRoot` / `FLP_I18N_ALLOWED_ROOTS` 内；拒绝 `..` 与符号链接逃逸。
- scan 的 classpath 临时文件写入系统临时目录并在结束时删除，不修改业务仓库源码、properties 或构建配置。
- diff 与 validate 只读：不写业务仓库、不写 `.i18n-tool`、不访问翻译平台写接口。
- upload/download 默认 dry-run；`--apply` 必须携带 dry-run 生成的 `planId`，计划变化时拒绝执行。
- upload 仅在存在待上传条目时读取 `FLP_I18N_TOKEN`；Token 不写入 journal、备份、日志或输出。
- 写操作前执行 git 守卫；`--force` 仅跳过守卫，不跳过 `planId` 校验与备份。
- download 写入前备份到配置文件同级的 `.i18n-tool/backup`，使用临时文件 + 原子替换，写后回读校验。
- 端到端测试只在临时 git 仓库执行，未对真实业务仓库或生产平台执行 `--apply`。
- 旧仓库中硬编码的 JWT 未做修改，后续迁移时应单独清理并轮换。

## 当前范围

已实现：配置解析与校验、JSON envelope、退出码、status、模块路径守卫、Maven classpath 构建、目录/jar 类扫描、key 生成、scan、CDN 字典拉取（重试 + 大小限制）、diff、validate、upload（缺口推送 + 限流 + journal 断点续跑）、download（回填 + 备份 + 原子写 + 写后校验）、planId 两阶段确认、git 守卫。

未实现（Step 6）：Qoder 插件。MCP Server 见同级目录 `flp-i18n-mcp`。

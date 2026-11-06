---
description: View or change FLP i18n jar, modules.yaml, and allowed roots
description_zh: 查看或修改 FLP 多语言同步的 jar、modules.yaml 与允许根目录
argument-hint: [查看 | <字段> <新值>]
---

# /i18n-config

查看或修改 FLP 多语言同步的运行配置。如果 `flp-i18n` Skill 尚未加载，先读取
`${QODER_PLUGIN_ROOT}/skills/flp-i18n/SKILL.md` 并遵守其中的安全约束。

用户输入：$ARGUMENTS

## 配置项

| 字段 | 含义 | 对应环境变量（兼容回退） |
| --- | --- | --- |
| `cliJar` | `flp-i18n-cli.jar` 绝对路径（可选，省略时 Maven 自动下载） | `FLP_I18N_CLI_JAR` |
| `cliMavenRepo` | CLI jar 的 Maven 仓库根地址 | `FLP_I18N_CLI_MAVEN_REPO` |
| `cliMavenCoordinate` | CLI 坐标 `groupId:artifactId:version` | `FLP_I18N_CLI_MAVEN_COORD` |
| `configFile` | `modules.yaml` 绝对路径 | `FLP_I18N_CONFIG` |
| `allowedRoots` | 允许访问的仓库根目录（数组） | `FLP_I18N_ALLOWED_ROOTS` |
| `java` | Java 可执行文件或命令名 | `FLP_I18N_JAVA` |
| `logLevel` | `error` / `info` / `debug` | `FLP_I18N_LOG_LEVEL` |

配置保存在用户配置文件（`i18n_config_get` 返回的 `userConfigPath`），优先级高于环境变量。
未配置 `cliJar` 时，MCP Server 首次调用业务工具会按 Maven 坐标下载并校验 jar 到缓存目录；
把 `cliJar` 设为空字符串会忽略 `FLP_I18N_CLI_JAR` 并强制使用 Maven。
Token 不在配置文件中管理，只能通过 `FLP_I18N_TOKEN` 环境变量提供。

## 执行规则

1. 先调用 `i18n_config_get`，把当前有效值、来源（file / env / default）和校验问题列成表格。
2. 用户没有指定要修改的字段时，只展示配置与 `userConfigPath`，并说明可以用
   「/i18n-config cliJar /path/to/flp-i18n-cli.jar」或
   「/i18n-config cliMavenCoordinate com.flashfin:flp-i18n-tool:0.1.0」这种方式修改。
3. 用户给出新值时，先调用 `i18n_config_set` 且 `dryRun=true`（默认），展示校验结果与
   修改前后的差异。
4. 只有用户明确同意后，才用相同的值、`dryRun=false` 写入。写入后调用 `i18n_config_get`
   确认 `effective.ready=true`，再视情况调用 `i18n_status` 验证。
5. 路径必须是绝对路径；`allowedRoots` 必须是已存在的目录。校验失败时不要绕过，直接说明原因。

## 约束

- 不要求、不接收、不展示 Token；不要把 Token 写进任何参数或文件。
- 不在用户确认前写入配置；不直接编辑业务仓库或平台数据。
- 不修改 `modules.yaml` 内容，只修改它的路径配置。
- 结束后输出：修改项、校验结果、`userConfigPath`、备份文件（如有）、下一步建议。

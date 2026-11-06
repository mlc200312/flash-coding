import { z } from "zod";

const NAME_PATTERN = /^[A-Za-z0-9._-]+$/;

export const moduleNameSchema = z
  .string()
  .min(1)
  .max(128)
  .regex(NAME_PATTERN, "模块名只能包含字母、数字、点、下划线和短横线")
  .describe("modules.yaml 中定义的模块名");

export const osSchema = z
  .string()
  .min(1)
  .max(64)
  .regex(NAME_PATTERN, "os 只能包含字母、数字、点、下划线和短横线")
  .describe("模块 os（同一模块名跨 os 时用于消歧，通常可省略）");

export const languagesSchema = z
  .array(
    z
      .string()
      .min(1)
      .max(32)
      .regex(/^[A-Za-z0-9_-]+$/, "语言代码只能包含字母、数字、下划线和短横线"),
  )
  .min(1)
  .max(50)
  .describe("语言代码列表，例如 [\"zh_CN\", \"en\"]；省略则使用配置中的全部语言");

export const planIdSchema = z
  .string()
  .regex(/^[0-9a-fA-F]{64}$/, "planId 必须是 64 位十六进制 SHA-256")
  .describe("dryRun 返回的 planId；apply 时必填且必须与本次 dryRun 完全一致");

export const dryRunSchema = z
  .boolean()
  .default(true)
  .describe("true（默认）= 仅生成计划，不写平台/本地文件；false = 执行写入，必须同时提供 planId");

export const statusInput = {};

export const scanInput = {
  module: moduleNameSchema,
  os: osSchema.optional(),
};

export const diffInput = {
  module: moduleNameSchema,
  os: osSchema.optional(),
  languages: languagesSchema.optional(),
};

export const validateInput = {
  module: moduleNameSchema,
  os: osSchema.optional(),
};

export const uploadInput = {
  module: moduleNameSchema,
  os: osSchema.optional(),
  dryRun: dryRunSchema,
  planId: planIdSchema.optional(),
  limit: z
    .number()
    .int()
    .min(1)
    .max(100000)
    .optional()
    .describe("最多处理多少条（用于灰度）"),
  rateMs: z
    .number()
    .int()
    .min(0)
    .max(60000)
    .optional()
    .describe("每次保存之间的间隔毫秒数"),
};

export const configGetInput = {};

const pathSchema = z
  .string()
  .min(1)
  .max(4096)
  .describe("绝对路径；不允许包含换行或空字符");

export const configSetInput = {
  cliJar: z
    .string()
    .max(4096)
    .optional()
    .describe("flp-i18n-cli.jar 的绝对路径；传空字符串则忽略本地 jar 与 FLP_I18N_CLI_JAR，改用 Maven 自动下载"),
  cliMavenRepo: z
    .string()
    .min(8)
    .max(4096)
    .regex(/^https?:\/\/[^\s]+$/i, "Maven 仓库必须是 http/https 地址")
    .optional()
    .describe("Maven 仓库根地址，例如 https://registry.example.com/repository/maven-public/"),
  cliMavenCoordinate: z
    .string()
    .min(5)
    .max(512)
    .regex(
      /^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?$/,
      "坐标格式 groupId:artifactId:version[:classifier]",
    )
    .optional()
    .describe("Maven 坐标，例如 com.flashfin:flp-i18n-tool:0.1.0"),
  configFile: pathSchema.optional().describe("modules.yaml 的绝对路径"),
  allowedRoots: z
    .array(pathSchema)
    .min(1)
    .max(20)
    .optional()
    .describe("允许访问的仓库根目录列表（绝对路径，必须是已存在的目录）"),
  java: z.string().min(1).max(1024).optional().describe("Java 可执行文件路径或 PATH 中的命令名"),
  logLevel: z.enum(["error", "info", "debug"]).optional().describe("日志级别"),
  dryRun: z
    .boolean()
    .default(true)
    .describe("true（默认）= 只校验并预览，不写配置文件；false = 写入用户配置文件"),
};

export const downloadInput = {
  module: moduleNameSchema,
  os: osSchema.optional(),
  languages: languagesSchema.optional(),
  dryRun: dryRunSchema,
  planId: planIdSchema.optional(),
};

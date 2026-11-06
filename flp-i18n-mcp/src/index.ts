#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { ConfigError, loadConfig } from "./config";
import { createRedactor, Logger } from "./logger";
import { createServer, SERVER_NAME, SERVER_VERSION } from "./server";

async function main(): Promise<void> {
  let config;
  try {
    config = loadConfig();
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    const hint = error instanceof ConfigError ? error.hint : "";
    process.stderr.write(`[flp-i18n-mcp] ERROR 启动配置无效: ${message}${hint ? `（${hint}）` : ""}\n`);
    process.exit(1);
  }

  const redact = createRedactor([config.token]);
  const logger = new Logger(config.logLevel, redact);
  const { server } = createServer(config, { logger, redact });

  const transport = new StdioServerTransport();
  await server.connect(transport);

  logger.info(
    `${SERVER_NAME} ${SERVER_VERSION} 已启动（config=${config.configPath}，Token ${config.token ? "已配置" : "缺失"}）`,
  );
  if (!config.token) {
    logger.error("未检测到 FLP_I18N_TOKEN；status 仍可用于诊断，diff/upload/download 将失败");
  }
}

main().catch((error) => {
  const message = error instanceof Error ? error.stack ?? error.message : String(error);
  process.stderr.write(`[flp-i18n-mcp] ERROR 启动失败: ${message}\n`);
  process.exit(1);
});

#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";

import { ConfigStore } from "./config";
import { createRedactor, Logger } from "./logger";
import { createServer, SERVER_NAME, SERVER_VERSION } from "./server";

async function main(): Promise<void> {
  const store = new ConfigStore();
  const state = store.getState();

  const token = state.config?.token ?? null;
  const redact = createRedactor([token, process.env.FLP_I18N_TOKEN ?? null]);
  const logger = new Logger(state.config?.logLevel ?? "info", redact);
  const { server } = createServer(store, { logger, redact });

  const transport = new StdioServerTransport();
  await server.connect(transport);

  if (state.config) {
    logger.info(
      `${SERVER_NAME} ${SERVER_VERSION} 已启动（config=${state.config.configPath}，Token ${state.config.token ? "已配置" : "缺失"}，用户配置=${store.userConfigPath}）`,
    );
  } else {
    logger.error(
      `${SERVER_NAME} ${SERVER_VERSION} 已启动，但配置未就绪: ${state.error?.message ?? "缺少必需配置"}；可调用 i18n_config_get / i18n_config_set 修复`,
    );
  }
  if (!token) {
    logger.error("未检测到 FLP_I18N_TOKEN；status 仍可用于诊断，diff/upload/download 将失败");
  }
}

main().catch((error) => {
  const message = error instanceof Error ? error.stack ?? error.message : String(error);
  process.stderr.write(`[flp-i18n-mcp] ERROR 启动失败: ${message}\n`);
  process.exit(1);
});

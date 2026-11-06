import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

import type { ToolDeps } from "./common";
import { registerConfigTools } from "./config";
import { registerDiffTool } from "./diff";
import { registerDownloadTool } from "./download";
import { registerScanTool } from "./scan";
import { registerStatusTool } from "./status";
import { registerUploadTool } from "./upload";
import { registerValidateTool } from "./validate";

export function registerTools(server: McpServer, deps: ToolDeps): void {
  registerConfigTools(server, deps);
  registerStatusTool(server, deps);
  registerScanTool(server, deps);
  registerDiffTool(server, deps);
  registerValidateTool(server, deps);
  registerUploadTool(server, deps);
  registerDownloadTool(server, deps);
}

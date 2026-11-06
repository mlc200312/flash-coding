#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MCP_DIR="$ROOT/../flp-i18n-mcp"
VERSION="$(node -p "require('$ROOT/.qoder-plugin/plugin.json').version")"
OUT_DIR="$ROOT/dist"
ZIP_PATH="$OUT_DIR/flp-i18n-plugin-$VERSION.zip"

npm --prefix "$MCP_DIR" run build
npm --prefix "$MCP_DIR" run bundle

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

cp -R "$ROOT/.qoder-plugin" "$STAGE/"
cp -R "$ROOT/skills" "$STAGE/"
cp -R "$ROOT/commands" "$STAGE/"
cp -R "$ROOT/server" "$STAGE/"
cp -R "$ROOT/docs" "$STAGE/"
cp "$ROOT/.mcp.json" "$ROOT/README.md" "$STAGE/"

CLI_JAR="${FLP_I18N_CLI_JAR:-$ROOT/../flp-i18n-tool/target/flp-i18n-cli.jar}"
CONFIG="${FLP_I18N_CONFIG:-$ROOT/../flp-i18n-tool/modules.yaml}"
ALLOWED_ROOTS="${FLP_I18N_ALLOWED_ROOTS:-$(cd "$ROOT/.." && pwd)}"
normalize() { (cd "$(dirname "$1")" && printf '%s/%s\n' "$(pwd)" "$(basename "$1")"); }
CLI_JAR="$(normalize "$CLI_JAR")"
CONFIG="$(normalize "$CONFIG")"
node - "$STAGE/.mcp.json" "$CLI_JAR" "$CONFIG" "$ALLOWED_ROOTS" <<'NODE'
const fs = require("fs");
const [file, jar, config, roots] = process.argv.slice(2);
const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
for (const server of Object.values(parsed.mcpServers)) {
  server.env = { ...server.env, FLP_I18N_CLI_JAR: jar, FLP_I18N_CONFIG: config, FLP_I18N_ALLOWED_ROOTS: roots };
}
fs.writeFileSync(file, JSON.stringify(parsed, null, 2) + "\n");
NODE

mkdir -p "$OUT_DIR"
rm -f "$ZIP_PATH"
(cd "$STAGE" && zip -qr "$ZIP_PATH" .)

echo "Packaged $ZIP_PATH"

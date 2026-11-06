#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
MCP_DIR="$ROOT/../flp-i18n-mcp"
OUT_DIR="$ROOT/dist"
MODE="portable"

while [ $# -gt 0 ]; do
  case "$1" in
    --local)
      MODE="local"
      shift
      ;;
    --portable)
      MODE="portable"
      shift
      ;;
    -h|--help)
      echo "用法: ./package.sh [--portable|--local]"
      echo "  --portable  生成便携包（默认），.mcp.json 使用占位路径，由 install.sh / install.ps1 注入"
      echo "  --local     把本机实际路径写入 .mcp.json，适合在打包机上直接安装"
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      exit 2
      ;;
  esac
done

VERSION="$(node -p "require('$ROOT/.qoder-plugin/plugin.json').version")"
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
cp "$ROOT/.mcp.json" "$ROOT/README.md" "$ROOT/install.sh" "$ROOT/install.ps1" "$STAGE/"
chmod +x "$STAGE/install.sh"

if [ "$MODE" = "local" ]; then
  CLI_JAR="${FLP_I18N_CLI_JAR:-$ROOT/../flp-i18n-tool/target/flp-i18n-cli.jar}"
  CONFIG="${FLP_I18N_CONFIG:-$ROOT/../flp-i18n-tool/modules.yaml}"
  ALLOWED_ROOTS="${FLP_I18N_ALLOWED_ROOTS:-$(cd "$ROOT/.." && pwd)}"
  normalize() { (cd "$(dirname "$1")" && printf '%s/%s\n' "$(pwd)" "$(basename "$1")"); }
  CLI_JAR="$(normalize "$CLI_JAR")"
  CONFIG="$(normalize "$CONFIG")"
  CLI_JAR="$CLI_JAR" CONFIG="$CONFIG" ALLOWED_ROOTS="$ALLOWED_ROOTS" \
    node - "$STAGE/.mcp.json" <<'NODE'
const fs = require("fs");
const file = process.argv[2];
const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
for (const server of Object.values(parsed.mcpServers)) {
  server.env = {
    ...server.env,
    FLP_I18N_CLI_JAR: process.env.CLI_JAR,
    FLP_I18N_CONFIG: process.env.CONFIG,
    FLP_I18N_ALLOWED_ROOTS: process.env.ALLOWED_ROOTS,
  };
}
fs.writeFileSync(file, JSON.stringify(parsed, null, 2) + "\n");
NODE
else
  node - "$STAGE/.mcp.json" <<'NODE'
const fs = require("fs");
const file = process.argv[2];
const parsed = JSON.parse(fs.readFileSync(file, "utf8"));
for (const server of Object.values(parsed.mcpServers)) {
  if (server.env) delete server.env.FLP_I18N_CLI_JAR;
  server.env = {
    ...server.env,
    FLP_I18N_CONFIG: "/absolute/path/to/modules.yaml",
    FLP_I18N_ALLOWED_ROOTS: "/absolute/path/to/workspace",
  };
}
fs.writeFileSync(file, JSON.stringify(parsed, null, 2) + "\n");
NODE
fi

find "$STAGE" -name '.DS_Store' -delete

mkdir -p "$OUT_DIR"
rm -f "$ZIP_PATH"
(cd "$STAGE" && zip -qr "$ZIP_PATH" .)

echo "Packaged $ZIP_PATH ($MODE)"
echo "安装说明见 docs/INSTALL_AND_USAGE.md，或解压后执行 ./install.sh / .\\install.ps1"

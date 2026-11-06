#!/usr/bin/env bash
set -euo pipefail

PROCESS_PATTERN="${FLP_I18N_QODER_PROCESS:-Qoder.app/Contents/MacOS/Qoder}"

usage() {
  cat <<'EOF'
用法: ./install.sh --jar <flp-i18n-cli.jar> --config <modules.yaml> [选项]

必填:
  --jar PATH           flp-i18n-cli.jar 的绝对路径（也可用 FLP_I18N_CLI_JAR）
  --config PATH        modules.yaml 的绝对路径（也可用 FLP_I18N_CONFIG）
  --roots PATH         允许访问的仓库根目录，多个用 : 分隔（默认取 FLP_I18N_ALLOWED_ROOTS）

可选:
  --zip PATH           从 zip 安装；省略时使用本脚本所在目录（解压后的插件包）
  --qoder-home PATH    Qoder 数据目录，默认 ~/.qoder（也可用 QODER_HOME）
  --no-register        只落盘，不写入 installed_plugins_v2.json / settings.json
  --allow-running      Qoder 正在运行时也继续（默认会中止）
  -h, --help           显示帮助

示例:
  ./install.sh --jar ~/flp-i18n-tool/target/flp-i18n-cli.jar \
               --config ~/flp-i18n-tool/modules.yaml \
               --roots ~/repos
EOF
}

die() {
  printf '错误: %s\n' "$1" >&2
  exit 1
}

JAR="${FLP_I18N_CLI_JAR:-}"
CONFIG="${FLP_I18N_CONFIG:-}"
ROOTS="${FLP_I18N_ALLOWED_ROOTS:-}"
ZIP=""
QODER_HOME="${QODER_HOME:-$HOME/.qoder}"
REGISTER=1
ALLOW_RUNNING=0

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --config) CONFIG="$2"; shift 2 ;;
    --roots) ROOTS="$2"; shift 2 ;;
    --zip) ZIP="$2"; shift 2 ;;
    --qoder-home) QODER_HOME="$2"; shift 2 ;;
    --no-register) REGISTER=0; shift ;;
    --allow-running) ALLOW_RUNNING=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数: $1（使用 --help 查看用法）" ;;
  esac
done

[ -n "$JAR" ] || die "缺少 --jar"
[ -n "$CONFIG" ] || die "缺少 --config"
[ -n "$ROOTS" ] || die "缺少 --roots"
[ -f "$JAR" ] || die "jar 不存在: $JAR"
[ -f "$CONFIG" ] || die "配置不存在: $CONFIG"
[ -d "$ROOTS" ] || die "允许根目录不存在: $ROOTS"
[ -d "$QODER_HOME" ] || die "未找到 Qoder 数据目录: $QODER_HOME（请先安装并启动一次 Qoder，或用 --qoder-home 指定）"

normalize() { (cd "$(dirname "$1")" && printf '%s/%s' "$(pwd)" "$(basename "$1")"); }
JAR="$(normalize "$JAR")"
CONFIG="$(normalize "$CONFIG")"
ROOTS="$(cd "$ROOTS" && pwd)"
QODER_HOME="$(cd "$QODER_HOME" && pwd)"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORK=""
cleanup() { if [ -n "$WORK" ]; then rm -rf "$WORK"; fi; }
trap cleanup EXIT

if [ -n "$ZIP" ]; then
  [ -f "$ZIP" ] || die "zip 不存在: $ZIP"
  WORK="$(mktemp -d)"
  unzip -q "$ZIP" -d "$WORK"
  SRC="$WORK"
elif [ -f "$SCRIPT_DIR/.qoder-plugin/plugin.json" ]; then
  SRC="$SCRIPT_DIR"
else
  AUTO_ZIP="$(find "$SCRIPT_DIR/../dist" -maxdepth 1 -name 'flp-i18n-plugin-*.zip' 2>/dev/null | sort -V | tail -n 1 || true)"
  [ -n "$AUTO_ZIP" ] || die "当前目录不是插件包，且未找到 ../dist/flp-i18n-plugin-*.zip；请用 --zip 指定"
  WORK="$(mktemp -d)"
  unzip -q "$AUTO_ZIP" -d "$WORK"
  SRC="$WORK"
  echo "使用安装包: $AUTO_ZIP"
fi

[ -f "$SRC/.qoder-plugin/plugin.json" ] || die "安装包缺少 .qoder-plugin/plugin.json"
VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$SRC/.qoder-plugin/plugin.json" | head -n 1)"
[ -n "$VERSION" ] || die "无法从 plugin.json 读取版本号"

if [ "$ALLOW_RUNNING" -eq 0 ] && pgrep -f "$PROCESS_PATTERN" >/dev/null 2>&1; then
  die "检测到 Qoder 正在运行，请先完全退出（Cmd+Q）后重试；确认无风险可加 --allow-running"
fi

DEST="$QODER_HOME/plugins/cache/local/flp-i18n/$VERSION"
if [ -d "$DEST" ]; then
  BACKUP="$DEST.bak-$(date +%Y%m%d-%H%M%S)"
  mv "$DEST" "$BACKUP"
  echo "已备份旧版本: $BACKUP"
fi
mkdir -p "$DEST"
cp -R "$SRC"/. "$DEST"/

json_patch() {
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$@" <<'PY'
import datetime
import json
import os
import sys
import tempfile

mcp_path, reg_path, settings_path, jar, config, roots, version, dest, display, register = sys.argv[1:11]


def read(path, default):
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        return default


def write(path, data):
    directory = os.path.dirname(path)
    os.makedirs(directory, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=directory)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    os.replace(tmp, path)


mcp = read(mcp_path, {"mcpServers": {}})
for server in mcp.get("mcpServers", {}).values():
    server.setdefault("env", {})
    server["env"]["FLP_I18N_CLI_JAR"] = jar
    server["env"]["FLP_I18N_CONFIG"] = config
    server["env"]["FLP_I18N_ALLOWED_ROOTS"] = roots
write(mcp_path, mcp)

if register != "1":
    sys.exit(0)

now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
registry = read(reg_path, {"version": 2, "plugins": {}})
registry["version"] = 2
plugins = registry.setdefault("plugins", {})
previous = plugins.get("flp-i18n@local") or [{}]
installed_at = previous[0].get("installedAt") or now
plugins["flp-i18n@local"] = [
    {
        "scope": "user",
        "installPath": dest,
        "version": version,
        "installedAt": installed_at,
        "lastUpdated": now,
        "displayName": display,
        "appRowId": "local:plugin:flp-i18n",
    }
]
write(reg_path, registry)

settings = read(settings_path, {})
settings.setdefault("enabledPlugins", {})["flp-i18n@local"] = True
write(settings_path, settings)
PY
  elif command -v node >/dev/null 2>&1; then
    node - "$@" <<'JS'
const fs = require("fs");
const [mcpPath, regPath, settingsPath, jar, config, roots, version, dest, display, register] = process.argv.slice(2);

function read(path, fallback) {
  try {
    return JSON.parse(fs.readFileSync(path, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") return fallback;
    throw error;
  }
}

function write(path, data) {
  const tmp = `${path}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + "\n");
  fs.renameSync(tmp, path);
}

const mcp = read(mcpPath, { mcpServers: {} });
for (const server of Object.values(mcp.mcpServers || {})) {
  server.env = {
    ...(server.env || {}),
    FLP_I18N_CLI_JAR: jar,
    FLP_I18N_CONFIG: config,
    FLP_I18N_ALLOWED_ROOTS: roots,
  };
}
write(mcpPath, mcp);

if (register !== "1") process.exit(0);

const now = new Date().toISOString();
const registry = read(regPath, { version: 2, plugins: {} });
registry.version = 2;
const plugins = (registry.plugins = registry.plugins || {});
const previous = (plugins["flp-i18n@local"] || [{}])[0];
plugins["flp-i18n@local"] = [
  {
    scope: "user",
    installPath: dest,
    version,
    installedAt: previous.installedAt || now,
    lastUpdated: now,
    displayName: display,
    appRowId: "local:plugin:flp-i18n",
  },
];
write(regPath, registry);

const settings = read(settingsPath, {});
settings.enabledPlugins = settings.enabledPlugins || {};
settings.enabledPlugins["flp-i18n@local"] = true;
write(settingsPath, settings);
JS
  else
    die "需要 python3 或 node 来写入 JSON 配置"
  fi
}

STAMP="$(date +%Y%m%d-%H%M%S)"
REG_PATH="$QODER_HOME/plugins/installed_plugins_v2.json"
SETTINGS_PATH="$QODER_HOME/settings.json"
if [ -f "$REG_PATH" ]; then cp "$REG_PATH" "$REG_PATH.bak-$STAMP"; fi
if [ -f "$SETTINGS_PATH" ]; then cp "$SETTINGS_PATH" "$SETTINGS_PATH.bak-$STAMP"; fi

json_patch "$DEST/.mcp.json" "$REG_PATH" "$SETTINGS_PATH" "$JAR" "$CONFIG" "$ROOTS" "$VERSION" "$DEST" "FLP 多语言同步" "$REGISTER"

TOKEN_NOTE="未检测到 FLP_I18N_TOKEN，安装后需自行配置"
if [ -n "${FLP_I18N_TOKEN:-}" ]; then
  if command -v launchctl >/dev/null 2>&1; then
    launchctl setenv FLP_I18N_TOKEN "$FLP_I18N_TOKEN"
    TOKEN_NOTE="已将 FLP_I18N_TOKEN 注入当前登录会话（未回显）"
  else
    TOKEN_NOTE="已检测到 FLP_I18N_TOKEN，请确保启动 Qoder 的进程可见该变量"
  fi
fi

command -v java >/dev/null 2>&1 || echo "警告: PATH 中未找到 java，MCP Server 调用 CLI 会失败"

cat <<EOF

安装完成
  版本      : $VERSION
  安装路径  : $DEST
  CLI jar   : $JAR
  配置      : $CONFIG
  允许根    : $ROOTS
  注册插件  : $([ "$REGISTER" -eq 1 ] && echo "已写入 Qoder 配置" || echo "已跳过（--no-register）")
  Token     : $TOKEN_NOTE

下一步：完全退出并重新打开 Qoder，然后验证插件列表出现「FLP 多语言同步」，
且可用 6 个 i18n_* MCP 工具与 /i18n-sync 命令。

回滚：将 $DEST 删除，并用 $REG_PATH.bak-$STAMP 与 $SETTINGS_PATH.bak-$STAMP 覆盖原文件。
EOF

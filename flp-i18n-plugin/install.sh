#!/usr/bin/env bash
set -euo pipefail

PROCESS_PATTERN="${FLP_I18N_QODER_PROCESS:-Qoder.app/Contents/MacOS/Qoder}"

usage() {
  cat <<'EOF'
用法: ./install.sh --config <modules.yaml> [选项]

必填:
  --config PATH        modules.yaml 的绝对路径（也可用 FLP_I18N_CONFIG）
  --roots PATH         允许访问的仓库根目录，多个用 : 分隔（默认取 FLP_I18N_ALLOWED_ROOTS）
                       安装后会写入用户配置文件，之后可在 Qoder 内用 /i18n-config 修改

可选:
  --jar PATH           flp-i18n-cli.jar 的绝对路径（也可用 FLP_I18N_CLI_JAR）
                       省略时由 MCP Server 按 Maven 坐标自动下载并校验
  --maven-repo URL     CLI jar 的 Maven 仓库根地址（省略时使用内置默认值）
  --maven-coord COORD  CLI 坐标 groupId:artifactId:version（省略时使用内置默认值）
  --zip PATH           从 zip 安装；省略时使用本脚本所在目录（解压后的插件包）
  --qoder-home PATH    Qoder 数据目录，默认 ~/.qoder（也可用 QODER_HOME）
  --no-register        只落盘，不写入 installed_plugins_v2.json / settings.json
  --allow-running      Qoder 正在运行时也继续（默认会中止）
  -h, --help           显示帮助

示例:
  ./install.sh --config ~/flp-i18n-tool/modules.yaml --roots ~/repos
  ./install.sh --jar ~/flp-i18n-tool/target/flp-i18n-cli.jar \
               --config ~/flp-i18n-tool/modules.yaml --roots ~/repos
EOF
}

die() {
  printf '错误: %s\n' "$1" >&2
  exit 1
}

JAR="${FLP_I18N_CLI_JAR:-}"
CONFIG="${FLP_I18N_CONFIG:-}"
ROOTS="${FLP_I18N_ALLOWED_ROOTS:-}"
MAVEN_REPO="${FLP_I18N_CLI_MAVEN_REPO:-}"
MAVEN_COORD="${FLP_I18N_CLI_MAVEN_COORD:-}"
ZIP=""
QODER_HOME="${QODER_HOME:-$HOME/.qoder}"
REGISTER=1
ALLOW_RUNNING=0

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="$2"; shift 2 ;;
    --config) CONFIG="$2"; shift 2 ;;
    --roots) ROOTS="$2"; shift 2 ;;
    --maven-repo) MAVEN_REPO="$2"; shift 2 ;;
    --maven-coord) MAVEN_COORD="$2"; shift 2 ;;
    --zip) ZIP="$2"; shift 2 ;;
    --qoder-home) QODER_HOME="$2"; shift 2 ;;
    --no-register) REGISTER=0; shift ;;
    --allow-running) ALLOW_RUNNING=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "未知参数: $1（使用 --help 查看用法）" ;;
  esac
done

[ -n "$CONFIG" ] || die "缺少 --config"
[ -n "$ROOTS" ] || die "缺少 --roots"
if [ -n "$JAR" ]; then
  [ -f "$JAR" ] || die "jar 不存在: $JAR"
fi
[ -f "$CONFIG" ] || die "配置不存在: $CONFIG"
[ -d "$QODER_HOME" ] || die "未找到 Qoder 数据目录: $QODER_HOME（请先安装并启动一次 Qoder，或用 --qoder-home 指定）"

normalize() { (cd "$(dirname "$1")" && printf '%s/%s' "$(pwd)" "$(basename "$1")"); }
if [ -n "$JAR" ]; then
  JAR="$(normalize "$JAR")"
fi
CONFIG="$(normalize "$CONFIG")"

ROOTS_LIST=""
IFS=':' read -r -a _roots <<< "$ROOTS"
for _root in "${_roots[@]}"; do
  [ -n "$_root" ] || continue
  [ -d "$_root" ] || die "允许根目录不存在: $_root"
  _abs="$(cd "$_root" && pwd)"
  ROOTS_LIST="${ROOTS_LIST:+$ROOTS_LIST:}$_abs"
done
[ -n "$ROOTS_LIST" ] || die "缺少有效的允许根目录"
ROOTS="$ROOTS_LIST"
QODER_HOME="$(cd "$QODER_HOME" && pwd)"
USER_CONFIG="$QODER_HOME/plugins/data/flp-i18n-local/config.json"

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

mcp_path, reg_path, settings_path, user_config_path, jar, config, roots, maven_repo, maven_coord, version, dest, display, register = sys.argv[1:14]


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
    if jar:
        server["env"]["FLP_I18N_CLI_JAR"] = jar
    else:
        server["env"].pop("FLP_I18N_CLI_JAR", None)
    server["env"]["FLP_I18N_CONFIG"] = config
    server["env"]["FLP_I18N_ALLOWED_ROOTS"] = roots
write(mcp_path, mcp)

roots_list = [entry for entry in roots.split(os.pathsep) if entry]
try:
    user_config = read(user_config_path, {})
except (OSError, ValueError):
    os.replace(user_config_path, user_config_path + ".bak-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    user_config = {}
if not isinstance(user_config, dict):
    user_config = {}
user_config["version"] = 1
if jar:
    user_config["cliJar"] = jar
else:
    user_config.pop("cliJar", None)
if maven_repo:
    user_config["cliMavenRepo"] = maven_repo
if maven_coord:
    user_config["cliMavenCoordinate"] = maven_coord
user_config["configFile"] = config
user_config["allowedRoots"] = roots_list
write(user_config_path, user_config)

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
const path = require("path");
const [mcpPath, regPath, settingsPath, userConfigPath, jar, config, roots, mavenRepo, mavenCoord, version, dest, display, register] = process.argv.slice(2);

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
  server.env = { ...(server.env || {}) };
  if (jar) server.env.FLP_I18N_CLI_JAR = jar;
  else delete server.env.FLP_I18N_CLI_JAR;
  server.env.FLP_I18N_CONFIG = config;
  server.env.FLP_I18N_ALLOWED_ROOTS = roots;
}
write(mcpPath, mcp);

let userConfig = {};
try {
  userConfig = read(userConfigPath, {});
} catch {
  if (fs.existsSync(userConfigPath)) {
    fs.renameSync(userConfigPath, `${userConfigPath}.bak-${new Date().toISOString().replace(/[-:]/g, "").replace(/\..+/, "")}`);
  }
}
const rootsList = roots.split(path.delimiter).filter(Boolean);
const nextConfig = {
  ...(userConfig && typeof userConfig === "object" ? userConfig : {}),
  version: 1,
  configFile: config,
  allowedRoots: rootsList,
};
if (jar) nextConfig.cliJar = jar;
else delete nextConfig.cliJar;
if (mavenRepo) nextConfig.cliMavenRepo = mavenRepo;
if (mavenCoord) nextConfig.cliMavenCoordinate = mavenCoord;
write(userConfigPath, nextConfig);

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

json_patch "$DEST/.mcp.json" "$REG_PATH" "$SETTINGS_PATH" "$USER_CONFIG" "$JAR" "$CONFIG" "$ROOTS" "$MAVEN_REPO" "$MAVEN_COORD" "$VERSION" "$DEST" "FLP 多语言同步" "$REGISTER"

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
  CLI jar   : ${JAR:-Maven 自动下载（首次调用时）}
  配置      : $CONFIG
  允许根    : $ROOTS
  用户配置  : $USER_CONFIG
  注册插件  : $([ "$REGISTER" -eq 1 ] && echo "已写入 Qoder 配置" || echo "已跳过（--no-register）")
  Token     : $TOKEN_NOTE

下一步：完全退出并重新打开 Qoder，然后验证插件列表出现「FLP 多语言同步」，
且可用 8 个 i18n_* MCP 工具与 /i18n-sync、/i18n-config 命令。

后续修改配置：在 Qoder 中执行 /i18n-config，或让 Agent 调用 i18n_config_set；
也可以直接编辑上面的「用户配置」文件（不会写入 Token）。

回滚：将 $DEST 删除，并用 $REG_PATH.bak-$STAMP 与 $SETTINGS_PATH.bak-$STAMP 覆盖原文件。
EOF

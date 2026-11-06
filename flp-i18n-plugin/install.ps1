#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Jar = $env:FLP_I18N_CLI_JAR,
    [string]$MavenRepo = $env:FLP_I18N_CLI_MAVEN_REPO,
    [string]$MavenCoord = $env:FLP_I18N_CLI_MAVEN_COORD,
    [string]$Config = $env:FLP_I18N_CONFIG,
    [string]$Roots = $env:FLP_I18N_ALLOWED_ROOTS,
    [string]$Zip,
    [string]$QoderHome = (Join-Path $env:USERPROFILE ".qoder"),
    [switch]$NoRegister,
    [switch]$AllowRunning,
    [switch]$PersistToken
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    Write-Host "错误: $Message" -ForegroundColor Red
    exit 1
}

function Write-Json([string]$Path, $Data) {
    $directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    $json = $Data | ConvertTo-Json -Depth 20
    [System.IO.File]::WriteAllText($Path, $json + "`n", (New-Object System.Text.UTF8Encoding($false)))
}

function Read-Json([string]$Path, $Default) {
    if (-not (Test-Path -LiteralPath $Path)) { return $Default }
    return (Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json)
}

if (-not $Config) { Fail "缺少 -Config（modules.yaml 路径）" }
if (-not $Roots) { Fail "缺少 -Roots（允许访问的仓库根目录）" }

if ($Jar -and -not (Test-Path -LiteralPath $Jar -PathType Leaf)) { Fail "jar 不存在: $Jar" }
if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) { Fail "配置不存在: $Config" }
if (-not (Test-Path -LiteralPath $QoderHome -PathType Container)) {
    Fail "未找到 Qoder 数据目录: $QoderHome（请先安装并启动一次 Qoder，或用 -QoderHome 指定）"
}

$RootsList = @()
foreach ($entry in ($Roots -split ';')) {
    if (-not $entry) { continue }
    if (-not (Test-Path -LiteralPath $entry -PathType Container)) { Fail "允许根目录不存在: $entry" }
    $RootsList += (Resolve-Path -LiteralPath $entry).Path
}
if ($RootsList.Count -eq 0) { Fail "缺少有效的允许根目录" }

if ($Jar) { $Jar = (Resolve-Path -LiteralPath $Jar).Path }
$Config = (Resolve-Path -LiteralPath $Config).Path
$Roots = $RootsList -join ';'
$QoderHome = (Resolve-Path -LiteralPath $QoderHome).Path
$UserConfigPath = Join-Path $QoderHome "plugins\data\flp-i18n-local\config.json"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$TempDir = $null

try {
    if ($Zip) {
        if (-not (Test-Path -LiteralPath $Zip -PathType Leaf)) { Fail "zip 不存在: $Zip" }
        $TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("flp-i18n-" + [guid]::NewGuid().ToString("N"))
        Expand-Archive -LiteralPath $Zip -DestinationPath $TempDir -Force
        $Source = $TempDir
    }
    elseif (Test-Path -LiteralPath (Join-Path $ScriptDir ".qoder-plugin\plugin.json")) {
        $Source = $ScriptDir
    }
    else {
        $distDir = Join-Path $ScriptDir "..\dist"
        $candidate = Get-ChildItem -LiteralPath $distDir -Filter "flp-i18n-plugin-*.zip" -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if (-not $candidate) {
            Fail "当前目录不是插件包，且未找到 ..\dist\flp-i18n-plugin-*.zip；请用 -Zip 指定"
        }
        $TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("flp-i18n-" + [guid]::NewGuid().ToString("N"))
        Expand-Archive -LiteralPath $candidate.FullName -DestinationPath $TempDir -Force
        $Source = $TempDir
        Write-Host "使用安装包: $($candidate.FullName)"
    }

    $pluginJsonPath = Join-Path $Source ".qoder-plugin\plugin.json"
    if (-not (Test-Path -LiteralPath $pluginJsonPath)) { Fail "安装包缺少 .qoder-plugin\plugin.json" }
    $Version = (Get-Content -LiteralPath $pluginJsonPath -Raw | ConvertFrom-Json).version
    if (-not $Version) { Fail "无法从 plugin.json 读取版本号" }

    if (-not $AllowRunning) {
        $running = Get-Process -Name "Qoder" -ErrorAction SilentlyContinue
        if ($running) {
            Fail "检测到 Qoder 正在运行，请先完全退出后重试；确认无风险可加 -AllowRunning"
        }
    }

    $Dest = Join-Path $QoderHome "plugins\cache\local\flp-i18n\$Version"
    if (Test-Path -LiteralPath $Dest) {
        $backup = "$Dest.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        Move-Item -LiteralPath $Dest -Destination $backup
        Write-Host "已备份旧版本: $backup"
    }
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | Copy-Item -Destination $Dest -Recurse -Force

    $McpPath = Join-Path $Dest ".mcp.json"
    $mcp = Get-Content -LiteralPath $McpPath -Raw | ConvertFrom-Json
    foreach ($name in $mcp.mcpServers.PSObject.Properties.Name) {
        $server = $mcp.mcpServers.$name
        if (-not $server.env) {
            $server | Add-Member -NotePropertyName env -NotePropertyValue ([pscustomobject]@{}) -Force
        }
        if ($Jar) {
            $server.env | Add-Member -NotePropertyName FLP_I18N_CLI_JAR -NotePropertyValue $Jar -Force
        }
        else {
            $server.env.PSObject.Properties.Remove("FLP_I18N_CLI_JAR")
        }
        $server.env | Add-Member -NotePropertyName FLP_I18N_CONFIG -NotePropertyValue $Config -Force
        $server.env | Add-Member -NotePropertyName FLP_I18N_ALLOWED_ROOTS -NotePropertyValue $Roots -Force
    }
    Write-Json $McpPath $mcp

    $userConfig = [ordered]@{ version = 1 }
    if (Test-Path -LiteralPath $UserConfigPath) {
        try {
            $existing = Get-Content -LiteralPath $UserConfigPath -Raw | ConvertFrom-Json
            foreach ($property in $existing.PSObject.Properties) {
                $userConfig[$property.Name] = $property.Value
            }
        }
        catch {
            Move-Item -LiteralPath $UserConfigPath -Destination "$UserConfigPath.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        }
    }
    if ($Jar) {
        $userConfig["cliJar"] = $Jar
    }
    elseif ($userConfig.Contains("cliJar")) {
        $userConfig.Remove("cliJar")
    }
    if ($MavenRepo) { $userConfig["cliMavenRepo"] = $MavenRepo }
    if ($MavenCoord) { $userConfig["cliMavenCoordinate"] = $MavenCoord }
    $userConfig["configFile"] = $Config
    $userConfig["allowedRoots"] = @($RootsList)
    Write-Json $UserConfigPath ([pscustomobject]$userConfig)

    $registered = $false
    if (-not $NoRegister) {
        $Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $RegPath = Join-Path $QoderHome "plugins\installed_plugins_v2.json"
        $SettingsPath = Join-Path $QoderHome "settings.json"
        if (Test-Path -LiteralPath $RegPath) { Copy-Item -LiteralPath $RegPath -Destination "$RegPath.bak-$Stamp" -Force }
        if (Test-Path -LiteralPath $SettingsPath) { Copy-Item -LiteralPath $SettingsPath -Destination "$SettingsPath.bak-$Stamp" -Force }

        $now = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
        $registry = Read-Json $RegPath ([pscustomobject]@{ version = 2; plugins = [pscustomobject]@{} })
        $registry | Add-Member -NotePropertyName version -NotePropertyValue 2 -Force
        if (-not $registry.plugins) {
            $registry | Add-Member -NotePropertyName plugins -NotePropertyValue ([pscustomobject]@{}) -Force
        }
        $previous = $null
        if ($registry.plugins.PSObject.Properties.Name -contains "flp-i18n@local") {
            $previous = @($registry.plugins."flp-i18n@local")[0]
        }
        $installedAt = if ($previous -and $previous.installedAt) { $previous.installedAt } else { $now }
        $entry = [pscustomobject]@{
            scope       = "user"
            installPath = $Dest
            version     = $Version
            installedAt = $installedAt
            lastUpdated = $now
            displayName = "FLP 多语言同步"
            appRowId    = "local:plugin:flp-i18n"
        }
        $registry.plugins | Add-Member -NotePropertyName "flp-i18n@local" -NotePropertyValue @($entry) -Force
        Write-Json $RegPath $registry

        $settings = Read-Json $SettingsPath ([pscustomobject]@{})
        if (-not $settings.enabledPlugins) {
            $settings | Add-Member -NotePropertyName enabledPlugins -NotePropertyValue ([pscustomobject]@{}) -Force
        }
        $settings.enabledPlugins | Add-Member -NotePropertyName "flp-i18n@local" -NotePropertyValue $true -Force
        Write-Json $SettingsPath $settings
        $registered = $true
    }

    $tokenNote = "未检测到 FLP_I18N_TOKEN，安装后需自行配置"
    if ($PersistToken) {
        if ($env:FLP_I18N_TOKEN) {
            [Environment]::SetEnvironmentVariable("FLP_I18N_TOKEN", $env:FLP_I18N_TOKEN, "User")
            $tokenNote = "已写入用户环境变量 FLP_I18N_TOKEN（未回显）"
        }
        else {
            $tokenNote = "指定了 -PersistToken，但当前进程没有 FLP_I18N_TOKEN"
        }
    }
    elseif ($env:FLP_I18N_TOKEN) {
        $tokenNote = "当前进程可见 FLP_I18N_TOKEN；从开始菜单启动 Qoder 时需确保用户环境变量中也已设置"
    }

    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Write-Host "警告: PATH 中未找到 java，MCP Server 调用 CLI 会失败" -ForegroundColor Yellow
    }

    Write-Host ""
    Write-Host "安装完成"
    Write-Host "  版本      : $Version"
    Write-Host "  安装路径  : $Dest"
    Write-Host "  CLI jar   : $(if ($Jar) { $Jar } else { 'Maven 自动下载（首次调用时）' })"
    Write-Host "  配置      : $Config"
    Write-Host "  允许根    : $Roots"
    Write-Host "  用户配置  : $UserConfigPath"
    Write-Host "  注册插件  : $(if ($registered) { '已写入 Qoder 配置' } else { '已跳过（-NoRegister）' })"
    Write-Host "  Token     : $tokenNote"
    Write-Host ""
    Write-Host "下一步：完全退出并重新打开 Qoder，然后验证插件列表出现「FLP 多语言同步」，"
    Write-Host "且可用 8 个 i18n_* MCP 工具与 /i18n-sync、/i18n-config 命令。"
    Write-Host ""
    Write-Host "后续修改配置：在 Qoder 中执行 /i18n-config，或让 Agent 调用 i18n_config_set；"
    Write-Host "也可以直接编辑上面的「用户配置」文件（不会写入 Token）。"
    Write-Host ""
    if ($registered) {
        Write-Host "回滚：删除 $Dest，并用 $RegPath.bak-$Stamp 与 $SettingsPath.bak-$Stamp 覆盖原文件。"
    }
}
finally {
    if ($TempDir -and (Test-Path -LiteralPath $TempDir)) {
        Remove-Item -LiteralPath $TempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

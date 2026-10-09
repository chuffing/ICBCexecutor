[CmdletBinding()]
param(
    [string]$VmrunPath = "D:\vmrun.exe",
    [string]$VmxPath = "D:\ICBCVMware\Ubuntu\Ubuntu 64 位.vmx",
    [string]$JavaHome = "D:\jdk1.8",
    [string]$MavenPath = "D:\IDEAULTIMATE\IntelliJ IDEA 2025.1.1.1\plugins\maven\lib\maven3\bin\mvn.cmd",
    [string]$GuestUser = "root123456",
    [Security.SecureString]$GuestPassword,
    [string]$AgentName,
    [string]$PlatformUrl,
    [string]$RegisterCode,
    [Security.SecureString]$RedisPassword,
    [Security.SecureString]$SentinelPassword,
    [string]$RedisConfigDir
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectDir = $PSScriptRoot
$jarPath = Join-Path $projectDir "target\redis-monitor-agent.jar"
$scriptsDir = Join-Path $projectDir "scripts"
$deployDir = Join-Path $projectDir "deploy"
$installerPath = Join-Path $deployDir "install-agent.sh"
$serviceUnitPath = Join-Path $deployDir "redis-monitor-agent.service"

$scriptNames = @(
    "start-redis.sh",
    "stop-redis.sh",
    "start-sentinel.sh",
    "stop-sentinel.sh",
    "restore-config.sh"
)

$guestJar = "/tmp/redis-monitor-agent.jar.new"
$guestInstaller = "/tmp/install-redis-monitor-agent.sh"
$guestServiceUnit = "/tmp/redis-monitor-agent.service.new"
$guestOverrides = "/tmp/redis-monitor-agent.env.overrides"
$guestStatus = "/tmp/redis-monitor-agent-deploy-status.txt"
$localOverrides = Join-Path ([IO.Path]::GetTempPath()) "redis-monitor-agent.env.overrides"
$localStatus = Join-Path ([IO.Path]::GetTempPath()) "redis-monitor-agent-deploy-status.txt"

<#
.SYNOPSIS
检查上一条外部命令是否成功。
#>
function Assert-LastExitCode {
    param([string]$Step)

    if ($LASTEXITCODE -ne 0) {
        throw "$Step 失败，退出码：$LASTEXITCODE"
    }
}

<#
.SYNOPSIS
把 SecureString 临时转换为明文；调用方必须在 finally 中清除引用。
#>
function ConvertFrom-SecureStringPlainText {
    param([Security.SecureString]$SecureValue)

    if ($null -eq $SecureValue) {
        return $null
    }

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

<#
.SYNOPSIS
把配置值转换为可同时被 systemd EnvironmentFile 和 Bash source 读取的行。
#>
function ConvertTo-EnvironmentLine {
    param(
        [string]$Name,
        [AllowEmptyString()][string]$Value
    )

    if ($Value -match "[`r`n]") {
        throw "配置 $Name 不能包含换行符"
    }

    $escaped = $Value.Replace("\", "\\")
    $escaped = $escaped.Replace('"', '\"')
    $escaped = $escaped.Replace('$', '\$')
    $escaped = $escaped.Replace('`', '\`')
    return "$Name=`"$escaped`""
}

<#
.SYNOPSIS
向覆盖文件追加一个仅由本次命令明确指定的服务器配置。
#>
function Add-EnvironmentOverride {
    param(
        [Collections.Generic.List[string]]$Lines,
        [string]$ParameterName,
        [string]$EnvironmentName,
        [AllowNull()][AllowEmptyString()][string]$Value
    )

    if ($PSBoundParameters.ContainsKey("Value") -and $null -ne $Value) {
        $Lines.Add((ConvertTo-EnvironmentLine -Name $EnvironmentName -Value $Value))
    }
}

foreach ($requiredPath in @($VmrunPath, $VmxPath, $JavaHome, $MavenPath,
        $installerPath, $serviceUnitPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "找不到所需路径：$requiredPath"
    }
}

foreach ($scriptName in $scriptNames) {
    $scriptPath = Join-Path $scriptsDir $scriptName
    if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
        throw "找不到正式运维脚本：$scriptPath"
    }
}

if (-not $GuestPassword) {
    $GuestPassword = Read-Host "请输入虚拟机用户 $GuestUser 的密码" -AsSecureString
}

$plainGuestPassword = $null
$plainRedisPassword = $null
$plainSentinelPassword = $null
$passwordBase64 = $null

try {
    $plainGuestPassword = ConvertFrom-SecureStringPlainText $GuestPassword
    $passwordBytes = [Text.Encoding]::UTF8.GetBytes($plainGuestPassword + "`n")
    $passwordBase64 = [Convert]::ToBase64String($passwordBytes)

    # 覆盖文件只写本次明确传入的值；未传参数时保留服务器现有配置。
    $overrideLines = [Collections.Generic.List[string]]::new()
    if ($PSBoundParameters.ContainsKey("AgentName")) {
        Add-EnvironmentOverride $overrideLines "AgentName" "AGENT_NAME" $AgentName
    }
    if ($PSBoundParameters.ContainsKey("PlatformUrl")) {
        Add-EnvironmentOverride $overrideLines "PlatformUrl" "AGENT_PLATFORM_URL" $PlatformUrl
    }
    if ($PSBoundParameters.ContainsKey("RegisterCode")) {
        Add-EnvironmentOverride $overrideLines "RegisterCode" "AGENT_REGISTER_CODE" $RegisterCode
    }
    if ($PSBoundParameters.ContainsKey("RedisConfigDir")) {
        Add-EnvironmentOverride $overrideLines "RedisConfigDir" "AGENT_REDIS_CONFIG_DIR" $RedisConfigDir
    }
    if ($PSBoundParameters.ContainsKey("RedisPassword")) {
        $plainRedisPassword = ConvertFrom-SecureStringPlainText $RedisPassword
        Add-EnvironmentOverride $overrideLines "RedisPassword" "AGENT_REDIS_PASSWORD" $plainRedisPassword
    }
    if ($PSBoundParameters.ContainsKey("SentinelPassword")) {
        $plainSentinelPassword = ConvertFrom-SecureStringPlainText $SentinelPassword
        Add-EnvironmentOverride $overrideLines "SentinelPassword" "AGENT_SENTINEL_PASSWORD" $plainSentinelPassword
    }
    # 显式使用 LF，避免 Windows CRLF 被 Linux source 当成配置值的一部分。
    $overrideContent = if ($overrideLines.Count -eq 0) {
        ""
    }
    else {
        ($overrideLines -join "`n") + "`n"
    }
    [IO.File]::WriteAllText(
        $localOverrides,
        $overrideContent,
        [Text.UTF8Encoding]::new($false)
    )

    Write-Host "`n[1/6] Maven 构建" -ForegroundColor Cyan
    $oldJavaHome = $env:JAVA_HOME
    $oldPath = $env:Path
    try {
        $env:JAVA_HOME = $JavaHome
        $env:Path = (Join-Path $JavaHome "bin") + ";" + $env:Path
        & $MavenPath -f (Join-Path $projectDir "pom.xml") "-DforkCount=0" clean package
        Assert-LastExitCode "Maven 构建"
    }
    finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:Path = $oldPath
    }

    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
        throw "构建完成后未找到 JAR：$jarPath"
    }

    Write-Host "`n[2/6] 检查并启动虚拟机" -ForegroundColor Cyan
    $runningVms = & $VmrunPath -T ws list 2>&1
    Assert-LastExitCode "读取虚拟机列表"
    if ($runningVms -notcontains $VmxPath) {
        & $VmrunPath -T ws start $VmxPath nogui
        Assert-LastExitCode "启动虚拟机"
    }
    $guestIp = & $VmrunPath -T ws getGuestIPAddress $VmxPath -wait
    Assert-LastExitCode "等待虚拟机网络"
    Write-Host "虚拟机 IP：$guestIp"

    Write-Host "`n[3/6] 上传 JAR、安装器和 systemd 单元" -ForegroundColor Cyan
    & $VmrunPath -T ws -gu $GuestUser -gp $plainGuestPassword CopyFileFromHostToGuest $VmxPath $jarPath $guestJar
    Assert-LastExitCode "上传 JAR"
    & $VmrunPath -T ws -gu $GuestUser -gp $plainGuestPassword CopyFileFromHostToGuest $VmxPath $installerPath $guestInstaller
    Assert-LastExitCode "上传 Linux 安装器"
    & $VmrunPath -T ws -gu $GuestUser -gp $plainGuestPassword CopyFileFromHostToGuest $VmxPath $serviceUnitPath $guestServiceUnit
    Assert-LastExitCode "上传 systemd 单元"
    & $VmrunPath -T ws -gu $GuestUser -gp $plainGuestPassword CopyFileFromHostToGuest $VmxPath $localOverrides $guestOverrides
    Assert-LastExitCode "上传环境变量覆盖文件"

    Write-Host "`n[4/6] 上传正式运维脚本" -ForegroundColor Cyan
    foreach ($scriptName in $scriptNames) {
        $localScript = Join-Path $scriptsDir $scriptName
        $guestScript = "/tmp/$scriptName.new"
        & $VmrunPath -T ws -gu $GuestUser -gp $plainGuestPassword CopyFileFromHostToGuest $VmxPath $localScript $guestScript
        Assert-LastExitCode "上传 $scriptName"
    }

    Write-Host "`n[5/6] 安装并启动执行器" -ForegroundColor Cyan
    $sudoCommand = "echo '$passwordBase64' | base64 -d | sudo -S -p '' /bin/bash '$guestInstaller'"
    & $VmrunPath -T ws -gu $GuestUser -gp $plainGuestPassword runProgramInGuest $VmxPath "/bin/bash" "-lc" $sudoCommand
    $deployExitCode = $LASTEXITCODE

    Write-Host "`n[6/6] 读取部署验收结果" -ForegroundColor Cyan
    & $VmrunPath -T ws -gu $GuestUser -gp $plainGuestPassword CopyFileFromGuestToHost $VmxPath $guestStatus $localStatus
    Assert-LastExitCode "读取部署结果"
    Get-Content -LiteralPath $localStatus -Encoding UTF8

    if ($deployExitCode -ne 0) {
        throw "虚拟机内部署失败，安装器已尝试恢复部署前状态"
    }

    Write-Host "`n执行器部署完成。Redis 与 Sentinel 的运行状态未被部署脚本修改。" -ForegroundColor Green
}
finally {
    $plainGuestPassword = $null
    $plainRedisPassword = $null
    $plainSentinelPassword = $null
    $passwordBase64 = $null
    Remove-Item -LiteralPath $localOverrides -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $localStatus -Force -ErrorAction SilentlyContinue
}


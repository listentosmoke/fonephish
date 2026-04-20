$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$proxyScript = Join-Path $scriptDir "domain-traffic-proxy.mjs"
$configPath = Join-Path $scriptDir "domain-traffic-proxy.config.json"
$logPath = Join-Path $scriptDir "domain-traffic-proxy.log"
$errPath = Join-Path $scriptDir "domain-traffic-proxy.err.log"

$config = @{}
if (Test-Path $configPath) {
    $config = Get-Content $configPath -Raw | ConvertFrom-Json -AsHashtable
}

$WebhookUrl = if ($env:DISCORD_WEBHOOK_URL) {
    $env:DISCORD_WEBHOOK_URL
} elseif ($config.ContainsKey("webhookUrl")) {
    [string]$config.webhookUrl
} else {
    ""
}

$TargetUrl = if ($config.ContainsKey("targetUrl")) {
    [string]$config.targetUrl
} else {
    "http://192.168.1.147:8080"
}

$ListenPort = if ($config.ContainsKey("listenPort")) {
    [int]$config.listenPort
} else {
    8090
}

Get-CimInstance Win32_Process |
    Where-Object { $_.Name -eq "node.exe" -and $_.CommandLine -like "*domain-traffic-proxy.mjs*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

$envVars = @(
    "set LISTEN_PORT=$ListenPort",
    "set TARGET_URL=$TargetUrl"
)

if ($WebhookUrl) {
    $envVars += "set DISCORD_WEBHOOK_URL=$WebhookUrl"
}

$argList = @(
    "/c",
    ($envVars -join " && ") + " && node `"$proxyScript`""
)

$process = Start-Process -FilePath "cmd.exe" `
    -ArgumentList $argList `
    -WorkingDirectory $scriptDir `
    -RedirectStandardOutput $logPath `
    -RedirectStandardError $errPath `
    -WindowStyle Hidden `
    -PassThru

Write-Output "Started domain traffic proxy on port $ListenPort with PID $($process.Id)."
Write-Output "Logs: $logPath"

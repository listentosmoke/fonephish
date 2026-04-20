param(
    [string]$Domain = "6lrw9ma0oyc8z1waidjygvevgz09lczmogid3fs3pvqltbqaxijyb2uwsdl4r58.online",
    [int]$IntervalSeconds = 30,
    [string[]]$Resolvers = @("1.1.1.1", "8.8.8.8")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Windows.Forms

function Get-Nameservers {
    param(
        [string]$LookupDomain,
        [string]$Resolver
    )

    try {
        $records = Resolve-DnsName -Name $LookupDomain -Type NS -Server $Resolver
        return @(
            $records |
                Where-Object { $_.Type -eq "NS" -and $_.NameHost } |
                ForEach-Object { $_.NameHost.TrimEnd(".").ToLowerInvariant() } |
                Sort-Object -Unique
        )
    } catch {
        Write-Host "[$(Get-Date -Format 'HH:mm:ss')] resolver $Resolver lookup failed: $($_.Exception.Message)" -ForegroundColor Yellow
        return @()
    }
}

function Is-CloudflareDelegated {
    param([string[]]$Nameservers)

    if ($Nameservers.Count -lt 2) {
        return $false
    }

    return ($Nameservers | Where-Object { $_ -like "*.ns.cloudflare.com" }).Count -eq $Nameservers.Count
}

Write-Host ""
Write-Host "Watching NS delegation for $Domain" -ForegroundColor Cyan
Write-Host "Polling every $IntervalSeconds seconds via: $($Resolvers -join ', ')" -ForegroundColor DarkCyan
Write-Host "This window will stay open until Cloudflare nameservers are visible." -ForegroundColor DarkGray
Write-Host ""

while ($true) {
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $success = $false

    foreach ($resolver in $Resolvers) {
        $nameservers = Get-Nameservers -LookupDomain $Domain -Resolver $resolver

        if ($nameservers.Count -gt 0) {
            Write-Host "[$timestamp] $resolver -> $($nameservers -join ', ')" -ForegroundColor Gray
        }

        if (Is-CloudflareDelegated -Nameservers $nameservers) {
            $message = @"
Cloudflare nameservers are now visible for:
$Domain

Resolver: $resolver
Nameservers:
$($nameservers -join "`r`n")
"@

            [console]::Beep(1200, 400)
            [console]::Beep(1500, 400)
            [System.Windows.Forms.MessageBox]::Show(
                $message,
                "Cloudflare NS Ready",
                [System.Windows.Forms.MessageBoxButtons]::OK,
                [System.Windows.Forms.MessageBoxIcon]::Information
            ) | Out-Null

            Write-Host ""
            Write-Host "[$timestamp] Cloudflare nameservers detected. Exiting watcher." -ForegroundColor Green
            $success = $true
            break
        }
    }

    if ($success) {
        break
    }

    Start-Sleep -Seconds $IntervalSeconds
}

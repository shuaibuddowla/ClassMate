param(
    [Parameter(Mandatory=$true)][string]$VersionName,
    [Parameter(Mandatory=$true)][long]$VersionCode
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$secretFile = Join-Path $root 'supabase\.classmate-secrets.local.env'
if (-not (Test-Path -LiteralPath $secretFile)) { throw 'Local production dispatch credentials are missing.' }
$entry = Get-Content -LiteralPath $secretFile | Where-Object { $_ -match '^CLASSMATE_DISPATCH_SECRET=' } | Select-Object -First 1
if (-not $entry) { throw 'CLASSMATE_DISPATCH_SECRET is not configured locally.' }
$dispatchSecret = $entry.Substring($entry.IndexOf('=')+1).Trim().Trim('"').Trim("'")
$payload = @{version_name=$VersionName;version_code=$VersionCode} | ConvertTo-Json -Compress
for ($attempt=0;$attempt -lt 3;$attempt++) {
    try {
        $response = Invoke-RestMethod -Method Post -Uri 'https://cbskpifgbdbgdmnnxfrh.supabase.co/functions/v1/publish-classmate-release' -Headers @{'x-dispatch-secret'=$dispatchSecret} -ContentType 'application/json' -Body $payload -TimeoutSec 30
        if (-not $response.queued) { throw 'Release notification was not queued.' }
        Write-Host "Release notification queued for ClassMate $VersionName. Repeating this command is safe."
        return
    } catch {
        # Do not echo request headers or private local credentials.
        if ($attempt -eq 2) { throw 'Release is published, but its notification could not be queued. Retry scripts/notify-classmate-release.ps1 with the same version.' }
        Start-Sleep -Seconds 2
    }
}

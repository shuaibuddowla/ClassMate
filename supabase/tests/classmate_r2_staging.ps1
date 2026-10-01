param(
    [string]$Workspace = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
)

$ErrorActionPreference = 'Stop'
$projectRef = 'qpryxvunhebwljlwmggk'
$localEnv = Join-Path $Workspace 'supabase/.classmate-staging.local.env'
$settings = @{}
Get-Content -LiteralPath $localEnv | ForEach-Object {
    $parts = $_ -split '=', 2
    if ($parts.Length -eq 2) { $settings[$parts[0]] = $parts[1] }
}
if ($settings['SUPABASE_PROJECT_REF'] -ne $projectRef) { throw 'Wrong staging project' }
if ([string]::IsNullOrWhiteSpace($settings['R2_TEST_USER_EMAIL']) -or
    [string]::IsNullOrWhiteSpace($settings['R2_TEST_USER_PASSWORD'])) {
    throw 'Set R2_TEST_USER_EMAIL and R2_TEST_USER_PASSWORD in the ignored staging env file for a disposable active CR or owner account, then remove them after the test.'
}

$cli = Join-Path $Workspace 'node_modules/supabase/dist/supabase.js'
$keys = node $cli projects api-keys --project-ref $projectRef --output json | ConvertFrom-Json
$anon = ($keys | Where-Object name -eq 'anon' | Select-Object -First 1).api_key
$base = "https://$projectRef.supabase.co"
$loginBody = @{
    email = $settings['R2_TEST_USER_EMAIL']
    password = $settings['R2_TEST_USER_PASSWORD']
} | ConvertTo-Json -Compress
$login = Invoke-WebRequest -Method Post -Uri "$base/auth/v1/token?grant_type=password" `
    -Headers @{ apikey = $anon } -ContentType 'application/json' -Body $loginBody -SkipHttpErrorCheck
if ($login.StatusCode -ne 200) { throw "Staging sign-in failed: $($login.StatusCode)" }
$jwt = ($login.Content | ConvertFrom-Json).access_token

$batchResponse = Invoke-WebRequest -Uri "$base/rest/v1/batches?select=id,batch_number&batch_number=eq.22" `
    -Headers @{ apikey = $anon; Authorization = "Bearer $jwt"; 'Accept-Profile' = 'classmate' } `
    -SkipHttpErrorCheck
if ($batchResponse.StatusCode -ne 200) { throw "Batch lookup failed: $($batchResponse.StatusCode)" }
$batch = @($batchResponse.Content | ConvertFrom-Json)
if ($batch.Count -ne 1) { throw 'Expected one staging batch 22' }

$payload = [System.Text.Encoding]::ASCII.GetBytes("%PDF-1.4`n%staging fixture`n")
$startBody = @{
    action = 'start'
    batch_id = $batch[0].id
    semester_course_id = $null
    title = 'Staging R2 roundtrip'
    file_type = 'pdf'
    category = 'notes'
    mime_type = 'application/pdf'
    size_bytes = $payload.Length
} | ConvertTo-Json -Compress
$headers = @{ apikey = $anon; Authorization = "Bearer $jwt" }
$start = Invoke-WebRequest -Method Post -Uri "$base/functions/v1/upload-classmate-resource" `
    -Headers $headers -ContentType 'application/json' -Body $startBody -SkipHttpErrorCheck
if ($start.StatusCode -ne 200) { throw "Upload start failed: $($start.StatusCode) $($start.Content)" }
$started = $start.Content | ConvertFrom-Json
if ($started.resource_id -notmatch '^[0-9a-f-]{36}$') { throw 'Invalid resource ID' }
Add-Content -LiteralPath $localEnv "R2_TEST_RESOURCE_ID=$($started.resource_id)"

$put = Invoke-WebRequest -Method Put -Uri $started.upload_url -Body $payload `
    -ContentType 'application/pdf' -SkipHttpErrorCheck
if ($put.StatusCode -notin @(200, 201)) { throw "R2 PUT failed: $($put.StatusCode)" }

$finishBody = @{ action = 'finish'; resource_id = $started.resource_id } | ConvertTo-Json -Compress
$finish = Invoke-WebRequest -Method Post -Uri "$base/functions/v1/upload-classmate-resource" `
    -Headers $headers -ContentType 'application/json' -Body $finishBody -SkipHttpErrorCheck
if ($finish.StatusCode -ne 200) { throw "Upload finish failed: $($finish.StatusCode) $($finish.Content)" }

$readBody = @{ resource_id = $started.resource_id } | ConvertTo-Json -Compress
$read = Invoke-WebRequest -Method Post -Uri "$base/functions/v1/signed-classmate-resource" `
    -Headers $headers -ContentType 'application/json' -Body $readBody -SkipHttpErrorCheck
if ($read.StatusCode -ne 200) { throw "Signed read failed: $($read.StatusCode) $($read.Content)" }
$signed = $read.Content | ConvertFrom-Json
$downloadPath = Join-Path $Workspace 'supabase/.staging-workdir/r2-roundtrip.pdf'
try {
    $download = Invoke-WebRequest -Uri $signed.url -OutFile $downloadPath -PassThru -SkipHttpErrorCheck
    if ($download.StatusCode -ne 200) { throw "R2 GET failed: $($download.StatusCode)" }
    $expected = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($payload))
    $actual = (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash
    if ($expected -ne $actual) { throw 'Downloaded bytes differ from uploaded bytes' }
} finally {
    if (Test-Path -LiteralPath $downloadPath) { Remove-Item -LiteralPath $downloadPath }
}
"r2_roundtrip=passed resource_id=$($started.resource_id)"

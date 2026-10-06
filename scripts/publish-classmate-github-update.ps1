param(
    [string]$ApkPath,
    [switch]$Build,
    [switch]$ValidateOnly,
    [long]$MinSupportedVersionCode = 0,
    [switch]$Mandatory,
    [string[]]$ReleaseNotes = @('ClassMate improvements and bug fixes')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$repo = 'shuaibuddowla/ClassMate'
$releaseBase = "https://github.com/$repo/releases"

if ($Build) {
    if ($ApkPath) { throw 'Choose either -Build or -ApkPath.' }
    if (-not $env:CLASSMATE_STORE_PASSWORD -or -not $env:CLASSMATE_KEY_PASSWORD) {
        throw 'Set CLASSMATE_STORE_PASSWORD and CLASSMATE_KEY_PASSWORD locally before -Build.'
    }
    & (Join-Path $repoRoot 'gradlew.bat') ':app:assembleRelease' '-PclassmateTarget=production' '--no-build-cache'
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    $ApkPath = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
}
if (-not $ApkPath) { throw 'Provide -ApkPath for a signed release APK, or use -Build.' }
$apk = (Resolve-Path -LiteralPath $ApkPath).Path
$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$tools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending | Select-Object -First 1
if (-not $tools) { throw 'Android SDK build-tools are required.' }
$aapt = Join-Path $tools.FullName 'aapt.exe'
$apksigner = Join-Path $tools.FullName 'apksigner.bat'
if (-not (Test-Path $aapt) -or -not (Test-Path $apksigner)) { throw 'aapt or apksigner is missing.' }

$badging = (& $aapt dump badging $apk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'") {
    throw 'Could not read APK package and version.'
}
$packageName = $Matches[1]
$versionCode = [long]$Matches[2]
$versionName = $Matches[3]
if ($packageName -ne 'com.shuaib.classmate') { throw "Wrong APK package: $packageName" }
if ($versionCode -le 0 -or $MinSupportedVersionCode -gt $versionCode) { throw 'Invalid release version policy.' }
if ($versionName -notmatch '^\d+\.\d+\.\d+$') { throw 'Use a numeric major.minor.patch release version.' }

$signing = (& $apksigner verify --verbose --print-certs $apk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $signing" }
if ($signing -match 'Android Debug') { throw 'Debug signed APKs cannot be published.' }
if ($signing -notmatch 'Number of signers: 1') { throw 'Expected one production APK signer.' }
if ($signing -notmatch 'certificate SHA-256 digest: ([0-9a-fA-F]+)') {
    throw 'Could not read APK signing certificate fingerprint.'
}
$actualCert = $Matches[1].ToLowerInvariant()
$identityFile = Join-Path $repoRoot 'keystore.properties'
$expectedCert = $env:CLASSMATE_RELEASE_CERT_SHA256
if (-not $expectedCert -and (Test-Path $identityFile)) {
    $certLine = Get-Content -LiteralPath $identityFile | Where-Object { $_ -match '^releaseCertSha256=' } | Select-Object -First 1
    if ($certLine) { $expectedCert = $certLine.Substring('releaseCertSha256='.Length).Trim() }
}
if (-not $expectedCert -or $actualCert -ne ($expectedCert -replace ':', '').ToLowerInvariant()) {
    throw 'Signing certificate does not match the configured ClassMate release identity.'
}
$fileHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$fileSize = (Get-Item -LiteralPath $apk).Length
$expectedUpdateSource = "$releaseBase/latest/download"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($apk)
$hasUpdateSource = $false
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -notmatch '^classes[0-9]*\.dex$') { continue }
        $stream = $entry.Open()
        try {
            $memory = [IO.MemoryStream]::new()
            $stream.CopyTo($memory)
            if ([Text.Encoding]::UTF8.GetString($memory.ToArray()).Contains($expectedUpdateSource)) {
                $hasUpdateSource = $true
                break
            }
        } finally { $stream.Dispose() }
    }
} finally { $archive.Dispose() }
if (-not $hasUpdateSource) {
    throw 'This APK predates the GitHub updater configuration. Rebuild and sign the current source before publishing.'
}
Write-Host "Verified ClassMate $versionName ($versionCode), signing certificate $actualCert"
if ($ValidateOnly) { return }

Push-Location $repoRoot
try {
    if ((& git status --porcelain -- .gitignore app cloudflare-worker docs firestore.rules gradle package.json package-lock.json scripts/publish-classmate-github-update.ps1 scripts/notify-classmate-release.ps1 supabase)) {
        throw 'Commit the release source before publishing.'
    }
    $branch = (& git branch --show-current).Trim()
    $localHead = (& git rev-parse HEAD).Trim()
    $remoteLine = (& git ls-remote origin "refs/heads/$branch" | Select-Object -First 1)
    if (-not $remoteLine -or $remoteLine.Split("`t")[0] -ne $localHead) {
        throw 'Push the release commit to GitHub before publishing.'
    }
} finally { Pop-Location }

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI (gh) is required.' }
$tag = "v$versionName"
$existing = & gh release view $tag --repo $repo --json tagName 2>$null
if ($LASTEXITCODE -eq 0) { throw "GitHub release $tag already exists; refusing to overwrite it." }
$existingTag = & gh api "repos/$repo/git/refs/tags/$tag" --jq '.ref' 2>$null
if ($LASTEXITCODE -eq 0 -and $existingTag) {
    throw "Git tag $tag already exists; increment the app version before publishing."
}

$latestMetadataUrl = "$releaseBase/latest/download/update.json"
try {
    $latest = Invoke-RestMethod -Uri $latestMetadataUrl -Headers @{ 'Cache-Control' = 'no-cache' } -TimeoutSec 30
    if ([long]$latest.versionCode -ge $versionCode) { throw 'Published versionCode is already equal or newer.' }
} catch {
    if ($_.Exception.Message -like '*Published versionCode*') { throw }
    $status = [int]$_.Exception.Response.StatusCode
    if ($status -ne 404) { throw "Could not check the latest release metadata: $_" }
}

$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("classmate-release-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempDir | Out-Null
try {
    $assetName = "classmate-$versionName.apk"
    $versionedApk = Join-Path $tempDir $assetName
    Copy-Item -LiteralPath $apk -Destination $versionedApk
    $metadata = [ordered]@{
        versionCode = $versionCode
        versionName = $versionName
        minSupportedVersionCode = $MinSupportedVersionCode
        apkUrl = "$releaseBase/download/$tag/$assetName"
        apkSize = $fileSize
        sha256 = $fileHash
        mandatory = [bool]$Mandatory
        releaseDate = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        releaseNotes = $ReleaseNotes
    }
    $jsonFile = Join-Path $tempDir 'update.json'
    [IO.File]::WriteAllText($jsonFile, ($metadata | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    $notesFile = Join-Path $tempDir 'release-notes.md'
    [IO.File]::WriteAllLines($notesFile, $ReleaseNotes, [Text.UTF8Encoding]::new($false))

    # A draft is invisible to latest-release clients. Upload the APK first, metadata last.
    & gh release create $tag $versionedApk --repo $repo --target $localHead --draft --title "ClassMate $versionName" --notes-file $notesFile
    if ($LASTEXITCODE -ne 0) { throw 'Could not create draft GitHub release.' }
    & gh release upload $tag $jsonFile --repo $repo
    if ($LASTEXITCODE -ne 0) { throw 'Could not upload update.json. The release remains a draft.' }

    $release = (& gh release view $tag --repo $repo --json assets,isDraft,tagName,targetCommitish | ConvertFrom-Json)
    if ($LASTEXITCODE -ne 0 -or -not $release.isDraft -or
        $release.tagName -ne $tag -or $release.targetCommitish -ne $localHead) {
        throw 'Draft release verification failed.'
    }
    $apkAsset = $release.assets | Where-Object { $_.name -eq $assetName } | Select-Object -First 1
    $jsonAsset = $release.assets | Where-Object { $_.name -eq 'update.json' } | Select-Object -First 1
    if (-not $apkAsset -or -not $jsonAsset -or [long]$apkAsset.size -ne $fileSize -or
        [long]$jsonAsset.size -ne (Get-Item -LiteralPath $jsonFile).Length) {
        throw 'Release assets are missing or have the wrong size. The release remains a draft.'
    }
    if ($apkAsset.digest -and $apkAsset.digest -ne "sha256:$fileHash") {
        throw 'GitHub APK digest differs from the signed APK. The release remains a draft.'
    }
    & gh release edit $tag --repo $repo --draft=false --latest
    if ($LASTEXITCODE -ne 0) { throw 'Could not publish the release; check the draft in GitHub.' }
    Write-Host "Published $tag to $latestMetadataUrl"
    & (Join-Path $PSScriptRoot 'notify-classmate-release.ps1') -VersionName $versionName -VersionCode $versionCode
} finally {
    $cleanupPath = [IO.Path]::GetFullPath($tempDir)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $cleanupPath.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $cleanupPath) -notmatch '^classmate-release-[0-9a-f]{32}$') {
        throw 'Refusing to clean up a path outside the release temporary directory.'
    }
    Remove-Item -LiteralPath $cleanupPath -Recurse -Force -ErrorAction SilentlyContinue
}

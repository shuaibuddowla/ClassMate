param(
    [string]$SourceProjectId = "classmate-a016a",
    [string]$ArchiveProjectId = "mbstu-cse-archive-260908",
    [string]$BatchId = "cse22",
    [string]$SemesterId = "2nd",
    [switch]$Apply
)

$ErrorActionPreference = "Stop"
$accessToken = (& gcloud.cmd auth print-access-token).Trim()
if ([string]::IsNullOrWhiteSpace($accessToken)) { throw "Unable to obtain a Google Cloud access token." }
$headers = @{ Authorization = "Bearer $accessToken" }
$sourceBase = "https://firestore.googleapis.com/v1/projects/$SourceProjectId/databases/(default)/documents"
$targetBase = "https://firestore.googleapis.com/v1/projects/$ArchiveProjectId/databases/(default)/documents"

function Get-Documents([string]$base, [string]$path) {
    $items = [System.Collections.Generic.List[object]]::new()
    $pageToken = $null
    do {
        $uri = "$base/${path}?pageSize=300&showMissing=true"
        if ($pageToken) { $uri += "&pageToken=$([uri]::EscapeDataString($pageToken))" }
        $response = Invoke-RestMethod -Method Get -Uri $uri -Headers $headers
        foreach ($document in @($response.documents)) {
            if ($document -and $document.name -and $document.fields) { $items.Add($document) }
        }
        $pageToken = $response.nextPageToken
    } while ($pageToken)
    return $items.ToArray()
}

function Field-String($document, [string]$name, [string]$fallback = "") {
    $value = $document.fields.$name
    if ($null -eq $value) { return $fallback }
    if ($null -ne $value.stringValue) { return [string]$value.stringValue }
    if ($null -ne $value.timestampValue) { return [string]$value.timestampValue }
    return $fallback
}

function Field-Int($document, [string]$name, [long]$fallback = 0) {
    $value = $document.fields.$name.integerValue
    if ($null -eq $value) { return $fallback }
    return [long]$value
}

function Field-Bool($document, [string]$name, [bool]$fallback = $false) {
    $value = $document.fields.$name.booleanValue
    if ($null -eq $value) { return $fallback }
    return [bool]$value
}

function Course-Id([string]$batch, [int]$semester, [string]$name) {
    $normalized = (($name.ToLowerInvariant() -replace '[^a-z0-9]+', ' ').Trim())
    $bytes = [Text.Encoding]::UTF8.GetBytes("${batch}:${semester}:${normalized}")
    return ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))).ToLowerInvariant().Substring(0, 32)
}

function Search-Tokens([string[]]$values) {
    $tokens = [System.Collections.Generic.HashSet[string]]::new()
    $text = (($values -join ' ').ToLowerInvariant() -replace '[^a-z0-9]+', ' ').Trim()
    foreach ($word in @($text -split '\s+' | Where-Object { $_ })) {
        for ($i = 1; $i -le [Math]::Min($word.Length, 24); $i++) { [void]$tokens.Add($word.Substring(0, $i)) }
    }
    return @($tokens | Select-Object -First 1500)
}

function Firestore-String([string]$value) { return @{ stringValue = $value } }
function Firestore-Int([long]$value) { return @{ integerValue = [string]$value } }
function Firestore-Array([string[]]$values) {
    return @{ arrayValue = @{ values = @($values | ForEach-Object { @{ stringValue = $_ } }) } }
}

function Put-Document([string]$path, [hashtable]$fields) {
    if (-not $Apply) { return }
    $body = @{ fields = $fields } | ConvertTo-Json -Depth 30 -Compress
    Invoke-RestMethod -Method Patch -Uri "$targetBase/$path" -Headers $headers -ContentType "application/json" -Body $body | Out-Null
}

$semesterNumber = [int]([regex]::Match($SemesterId, '[1-8]').Value)
if ($semesterNumber -lt 1) { throw "SemesterId must contain a semester number from 1 to 8." }
$archiveBatch = if ($BatchId -match '^([A-Za-z]+)[-_ ]?(\d+)$') { "$($Matches[1].ToUpperInvariant())-$($Matches[2])" } else { $BatchId }
$sourcePath = "batches/$BatchId/semesters/$SemesterId/library"
$source = @(Get-Documents $sourceBase $sourcePath)
$active = @($source | Where-Object { -not (Field-Bool $_ 'isDeleted') })
$courses = @{}
$resources = 0

foreach ($document in $active) {
    $subject = Field-String $document 'subject' 'Other Document'
    $courseCode = Field-String $document 'courseCode'
    $courseType = (Field-String $document 'courseType' 'regular').ToLowerInvariant()
    $category = if ($courseType -eq 'lab') { 'lab' } elseif ($courseType -in @('other', 'syllabus')) { 'syllabus' } else { 'regular' }
    $courseId = Course-Id $archiveBatch $semesterNumber $subject
    $courses[$courseId] = @{ name = $subject; code = $courseCode; category = $category }

    $documentId = ($document.name -split '/')[-1]
    $title = Field-String $document 'title' 'Library resource'
    $provider = Field-String $document 'provider' 'external_link'
    $externalUrl = Field-String $document 'downloadUrl'
    if ([string]::IsNullOrWhiteSpace($externalUrl)) { $externalUrl = Field-String $document 'driveUrl' }
    if ([string]::IsNullOrWhiteSpace($externalUrl)) { $externalUrl = Field-String $document 'telegramUrl' }
    if ([string]::IsNullOrWhiteSpace($externalUrl) -or -not $externalUrl.StartsWith('https://')) { continue }
    $mime = Field-String $document 'mimeType' 'application/octet-stream'
    $fileType = Field-String $document 'fileType' 'pdf'
    $fileName = Field-String $document 'githubAssetName'
    if ([string]::IsNullOrWhiteSpace($fileName)) {
        $extension = if ($fileType -match '^[a-z0-9]{2,5}$') { $fileType } else { 'pdf' }
        $fileName = (($title -replace '[^A-Za-z0-9._-]+', '-').Trim('-')) + "." + $extension
    }
    $createdAt = Field-String $document 'createdAt' (Field-String $document 'timestamp' ([DateTime]::UtcNow.ToString('o')))
    $materialType = if ($category -eq 'lab') { 'Lab' } elseif ($category -eq 'syllabus') { 'Syllabus' } elseif ($title -match '(?i)question|ct-|mid|final') { 'Question Bank' } elseif ($fileType -match '(?i)ppt') { 'Slides' } else { 'Notes' }
    $uploaderName = Field-String $document 'uploadedByName' (Field-String $document 'uploadedBy' 'ClassMate contributor')
    $uploadedBy = Field-String $document 'uploadedByUid' 'classmate-migration'
    $tokens = Search-Tokens @($title, $subject, $courseCode, $archiveBatch, $semesterNumber, $materialType)
    $fields = [ordered]@{
        title = Firestore-String $title
        batchId = Firestore-String $archiveBatch
        semesterNumber = Firestore-Int $semesterNumber
        courseId = Firestore-String $courseId
        courseName = Firestore-String $subject
        courseCode = Firestore-String $courseCode
        materialType = Firestore-String $materialType
        uploaderName = Firestore-String $uploaderName
        uploadedBy = Firestore-String $uploadedBy
        status = Firestore-String 'approved'
        fileName = Firestore-String $fileName
        fileSize = Firestore-Int (Field-Int $document 'sizeBytes')
        mimeType = Firestore-String $mime
        createdAt = Firestore-String $createdAt
        publishedAt = Firestore-String $createdAt
        externalUrl = Firestore-String $externalUrl
        legacyProvider = Firestore-String $provider
        source = Firestore-String 'classmate_library_migration'
        searchTokens = Firestore-Array $tokens
        tags = Firestore-Array @('ClassMate migration')
    }
    Put-Document "resources/$documentId" $fields
    $resources++
}

foreach ($entry in $courses.GetEnumerator()) {
    $value = $entry.Value
    Put-Document "courses/$($entry.Key)" ([ordered]@{
        batchId = Firestore-String $archiveBatch
        semesterNumber = Firestore-Int $semesterNumber
        courseName = Firestore-String $value.name
        courseCode = Firestore-String $value.code
        category = Firestore-String $value.category
        status = Firestore-String 'approved'
        createdBy = Firestore-String 'classmate_library_migration'
        createdAt = Firestore-String ([DateTime]::UtcNow.ToString('o'))
    })
}

Put-Document "semesters/$archiveBatch-$semesterNumber" ([ordered]@{
    batchId = Firestore-String $archiveBatch
    semesterNumber = Firestore-Int $semesterNumber
})

[pscustomobject]@{
    Mode = if ($Apply) { 'APPLIED' } else { 'DRY RUN' }
    SourcePath = $sourcePath
    SourceDocuments = $source.Count
    ActiveLibraryResources = $active.Count
    CoursesPrepared = $courses.Count
    ResourcesPrepared = $resources
    NonLibraryCollectionsTouched = 0
}

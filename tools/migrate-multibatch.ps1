param(
    [string]$ProjectId = "classmate-a016a",
    [string]$BatchId = "cse22",
    [string]$LegacySemesterId = "2nd",
    [switch]$Apply
)

$ErrorActionPreference = "Stop"
$accessToken = (& gcloud.cmd auth print-access-token).Trim()
if ([string]::IsNullOrWhiteSpace($accessToken)) { throw "Unable to obtain a Google Cloud access token." }

$headers = @{ Authorization = "Bearer $accessToken" }
$base = "https://firestore.googleapis.com/v1/projects/$ProjectId/databases/(default)/documents"
$stats = [ordered]@{ copied = 0; skipped = 0; usersUpdated = 0; adminsUpdated = 0 }

function Get-Documents([string]$path) {
    $all = @()
    $token = $null
    do {
        $uri = "${base}/${path}?pageSize=300"
        if ($token) { $uri += "&pageToken=$([uri]::EscapeDataString($token))" }
        $response = Invoke-RestMethod -Method Get -Uri $uri -Headers $headers
        $all += @($response.documents) | Where-Object { $_ -and $_.name }
        $token = $response.nextPageToken
    } while ($token)
    return $all
}

function Get-DocumentId($document) {
    return ($document.name -split "/")[-1]
}

function Test-Document([string]$path) {
    try {
        Invoke-RestMethod -Method Get -Uri "${base}/${path}" -Headers $headers | Out-Null
        return $true
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 404) { return $false }
        throw
    }
}

function Add-StringField($fields, [string]$name, [string]$value) {
    $fields | Add-Member -NotePropertyName $name -NotePropertyValue ([pscustomobject]@{ stringValue = $value }) -Force
}

function Copy-Document($document, [string]$targetPath, [hashtable]$extraFields = @{}) {
    if (-not $Apply) {
        $stats.copied++
        return
    }
    if (Test-Document $targetPath) {
        $stats.skipped++
        return
    }
    $fields = $document.fields
    foreach ($entry in $extraFields.GetEnumerator()) { Add-StringField $fields $entry.Key ([string]$entry.Value) }
    $body = @{ fields = $fields } | ConvertTo-Json -Depth 100 -Compress
    try {
        Invoke-RestMethod -Method Patch -Uri "${base}/${targetPath}" -Headers $headers -ContentType "application/json" -Body $body | Out-Null
    } catch {
        $detail = $_.ErrorDetails.Message
        throw "Failed to copy $targetPath. $detail"
    }
    $stats.copied++
}

function Copy-Collection([string]$source, [string]$target, [hashtable]$extraFields = @{}) {
    foreach ($document in @(Get-Documents $source)) {
        Copy-Document $document "$target/$(Get-DocumentId $document)" $extraFields
    }
}

# Batch-scoped notice history and interactions.
Copy-Collection "notices" "batches/$BatchId/notices" @{ batchId = $BatchId }
foreach ($collection in @("notice_comments", "notice_likes", "notice_shares")) {
    foreach ($document in @(Get-Documents $collection)) {
        $noticeId = $document.fields.noticeId.stringValue
        if ([string]::IsNullOrWhiteSpace($noticeId)) { continue }
        $childName = switch ($collection) {
            "notice_comments" { "comments" }
            "notice_likes" { "likes" }
            default { "shares" }
        }
        Copy-Document $document "batches/$BatchId/notices/$noticeId/$childName/$(Get-DocumentId $document)" @{ batchId = $BatchId }
    }
}

# Semester-scoped academic resources. Legacy data is copied into the known
# legacy semester and remains untouched at its original path for rollback.
$semesterRoot = "batches/$BatchId/semesters/$LegacySemesterId"
Copy-Collection "library_files" "$semesterRoot/library" @{ batchId = $BatchId; semesterId = $LegacySemesterId; semester = $LegacySemesterId }
foreach ($collection in @("assignments", "academic_calendar_exceptions", "bus_schedules", "attendance_records", "attendance_sessions")) {
    Copy-Collection $collection "$semesterRoot/$collection" @{ batchId = $BatchId; semesterId = $LegacySemesterId }
}

foreach ($day in @("saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday")) {
    foreach ($period in @(Get-Documents "timetable/$day/periods")) {
        Copy-Document $period "$semesterRoot/timetable/$day/periods/$(Get-DocumentId $period)" @{ batchId = $BatchId; semesterId = $LegacySemesterId }
    }
}

# Polls are batch scoped in the current application. Preserve votes as nested data.
foreach ($poll in @(Get-Documents "polls")) {
    $pollId = Get-DocumentId $poll
    Copy-Document $poll "batches/$BatchId/polls/$pollId" @{ batchId = $BatchId }
    foreach ($vote in @(Get-Documents "polls/$pollId/votes")) {
        Copy-Document $vote "batches/$BatchId/polls/$pollId/votes/$(Get-DocumentId $vote)" @{ batchId = $BatchId }
    }
}

# All existing profiles came from the legacy CSE-22-only application. Assign
# only missing batch IDs; never overwrite an existing batch assignment.
foreach ($user in @(Get-Documents "users")) {
    $uid = Get-DocumentId $user
    $role = $user.fields.role.stringValue
    $missingBatch = [string]::IsNullOrWhiteSpace($user.fields.batchId.stringValue)
    $isAdmin = $role -in @("admin", "superadmin")
    $adminBatchValues = @($user.fields.adminBatchIds.arrayValue.values | Where-Object { $_ -and $_.stringValue })
    $missingAdminBatches = $isAdmin -and ($adminBatchValues.Count -eq 0)
    if (-not $missingBatch -and -not $missingAdminBatches) { continue }

    $fields = [ordered]@{}
    $masks = @()
    if ($missingBatch) {
        $fields.batchId = @{ stringValue = $BatchId }
        $masks += "updateMask.fieldPaths=batchId"
    }
    if ($missingAdminBatches) {
        $fields.adminBatchIds = @{ arrayValue = @{ values = @(@{ stringValue = $BatchId }) } }
        $masks += "updateMask.fieldPaths=adminBatchIds"
    }
    if ($Apply) {
        $body = @{ fields = $fields } | ConvertTo-Json -Depth 20 -Compress
        $query = $masks -join "&"
        Invoke-RestMethod -Method Patch -Uri "${base}/users/${uid}?$query" -Headers $headers -ContentType "application/json" -Body $body | Out-Null
    }
    if ($missingBatch) { $stats.usersUpdated++ }
    if ($missingAdminBatches) { $stats.adminsUpdated++ }
}

[pscustomobject]@{
    Mode = if ($Apply) { "APPLIED" } else { "DRY RUN" }
    Batch = $BatchId
    LegacySemester = $LegacySemesterId
    DocumentsCopied = $stats.copied
    ExistingTargetsSkipped = $stats.skipped
    UsersAssigned = $stats.usersUpdated
    AdminPermissionsAssigned = $stats.adminsUpdated
}

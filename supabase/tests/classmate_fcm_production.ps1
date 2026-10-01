# Disposable production FCM check. It uses the owner's registered test device,
# temporarily assigns its token to a synthetic student in the imported batch,
# sends one notice, and restores the token in finally.
$ErrorActionPreference = 'Stop'
$projectRef = 'cbskpifgbdbgdmnnxfrh'
$cli = Join-Path $PSScriptRoot '..\..\node_modules\supabase\dist\supabase.js'
$secretFile = Join-Path $PSScriptRoot '..\.classmate-secrets.local.env'
$secretLine = Get-Content -LiteralPath $secretFile |
    Where-Object { $_.StartsWith('CLASSMATE_DISPATCH_SECRET=') } |
    Select-Object -First 1
if (-not $secretLine) { throw 'Production dispatcher secret is unavailable' }
$dispatchSecret = $secretLine.Substring('CLASSMATE_DISPATCH_SECRET='.Length)

$fixture = 'f1000000-0000-4000-8000-000000000001'
$notice = 'f1000000-0000-4000-8000-000000000002'
$cleanup = @"
begin;
update classmate.device_tokens set profile_id =
  (select id from classmate.profiles where role = 'admin')
where profile_id = '$fixture';
delete from classmate.notification_outbox where record_id = '$notice';
delete from classmate.notices where id = '$notice';
delete from classmate.profiles where id = '$fixture';
delete from auth.users where id = '$fixture';
commit;
"@
$setup = @"
begin;
do `$$ begin
  if (select count(*) from classmate.profiles where role = 'admin') <> 1
     or (select count(*) from classmate.device_tokens dt
       join classmate.profiles p on p.id = dt.profile_id where p.role = 'admin') <> 1
     or (select count(*) from classmate.batches b
       join classmate.departments d on d.id = b.department_id
       where d.code = 'cse' and b.batch_number = 22) <> 1 then
    raise exception 'One owner, owner device token, and CSE batch 22 required';
  end if;
end `$$;
insert into auth.users (id, email, email_confirmed_at, aud, role)
values ('$fixture', 'fcm-production-fixture@mbstu.ac.bd',
  now(), 'authenticated', 'authenticated');
insert into classmate.profiles
  (id, email, role, department_id, batch_id, verification_status)
select '$fixture', 'fcm-production-fixture@mbstu.ac.bd', 'student',
  b.department_id, b.id, 'active'
from classmate.batches b join classmate.departments d on d.id = b.department_id
where d.code = 'cse' and b.batch_number = 22;
update classmate.device_tokens set profile_id = '$fixture'
where profile_id = (select id from classmate.profiles where role = 'admin');
insert into classmate.notices
  (id, department_id, batch_id, author_id, title, body)
select '$notice', b.department_id, b.id,
  (select id from classmate.profiles where role = 'admin'),
  'ClassMate production delivery check', 'Temporary test event'
from classmate.batches b join classmate.departments d on d.id = b.department_id
where d.code = 'cse' and b.batch_number = 22;
commit;
"@

function Invoke-ProductionSql([string] $sql) {
    & node $cli db query --linked --project-ref $projectRef $sql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Production SQL command failed ($LASTEXITCODE)" }
}

try {
    Invoke-ProductionSql $cleanup
    Invoke-ProductionSql $setup
    $dispatchUri = "https://$projectRef.supabase.co/functions/v1/dispatch-classmate-notifications"
    $result = Invoke-RestMethod -Method Post -Uri $dispatchUri `
      -Headers @{ 'x-dispatch-secret' = $dispatchSecret } -ContentType 'application/json' -Body '{}'
    Write-Output "Inspected: $($result.inspected); delivered: $($result.delivered)"
} finally {
    Invoke-ProductionSql $cleanup
}

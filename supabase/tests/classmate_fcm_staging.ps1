# Staging-only live FCM check. Moves one owner test-device token to a
# disposable student profile, dispatches one batch notice, then restores it.
$ErrorActionPreference = 'Stop'
$projectRef = 'qpryxvunhebwljlwmggk'
$cli = Join-Path $PSScriptRoot '..\..\node_modules\supabase\dist\supabase.js'
$secretFile = Join-Path $PSScriptRoot '..\.classmate-staging.local.env'
$secretLine = Get-Content -LiteralPath $secretFile |
    Where-Object { $_.StartsWith('CLASSMATE_DISPATCH_SECRET=') } |
    Select-Object -First 1
if (-not $secretLine) { throw 'Staging dispatcher secret is unavailable' }
$dispatchSecret = $secretLine.Substring('CLASSMATE_DISPATCH_SECRET='.Length)

$cleanup = @'
begin;
update classmate.device_tokens set profile_id =
  (select id from classmate.profiles where role = 'admin')
where profile_id = 'f0000000-0000-4000-8000-000000000001';
delete from classmate.notification_outbox
where record_id = 'f0000000-0000-4000-8000-000000000003';
delete from classmate.notices where id = 'f0000000-0000-4000-8000-000000000003';
delete from classmate.profiles where id = 'f0000000-0000-4000-8000-000000000001';
delete from auth.users where id = 'f0000000-0000-4000-8000-000000000001';
delete from classmate.semesters where batch_id = 'f0000000-0000-4000-8000-000000000002';
delete from classmate.batches where id = 'f0000000-0000-4000-8000-000000000002';
commit;
'@
$setup = @'
begin;
do $$ begin
  if (select count(*) from classmate.profiles where role = 'admin') <> 1
     or (select count(*) from classmate.device_tokens dt
       join classmate.profiles p on p.id = dt.profile_id where p.role = 'admin') <> 1 then
    raise exception 'Exactly one owner and one owner device token required';
  end if;
end $$;
insert into auth.users (id, email, email_confirmed_at, aud, role)
values ('f0000000-0000-4000-8000-000000000001',
  'fcm-staging-fixture@mbstu.ac.bd', now(), 'authenticated', 'authenticated');
insert into classmate.batches (id, department_id, batch_number, academic_session)
values ('f0000000-0000-4000-8000-000000000002',
  (select id from classmate.departments where code = 'cse'), 22, 25);
insert into classmate.profiles
  (id, email, role, department_id, batch_id, verification_status)
values ('f0000000-0000-4000-8000-000000000001',
  'fcm-staging-fixture@mbstu.ac.bd', 'student',
  (select id from classmate.departments where code = 'cse'),
  'f0000000-0000-4000-8000-000000000002', 'active');
update classmate.device_tokens set profile_id =
  'f0000000-0000-4000-8000-000000000001'
where profile_id = (select id from classmate.profiles where role = 'admin');
insert into classmate.notices
  (id, department_id, batch_id, author_id, title, body)
values ('f0000000-0000-4000-8000-000000000003',
  (select id from classmate.departments where code = 'cse'),
  'f0000000-0000-4000-8000-000000000002',
  (select id from classmate.profiles where role = 'admin'),
  'Staging FCM delivery check', 'Temporary test event');
commit;
'@

function Invoke-StagingSql([string] $sql) {
    & node $cli db query --linked --project-ref $projectRef $sql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Staging SQL command failed ($LASTEXITCODE)" }
}

try {
    Invoke-StagingSql $cleanup
    Invoke-StagingSql $setup
    $dispatchUri = "https://$projectRef.supabase.co/functions/v1/dispatch-classmate-notifications"
    $result = Invoke-RestMethod -Method Post -Uri $dispatchUri `
      -Headers @{ 'x-dispatch-secret' = $dispatchSecret } -ContentType 'application/json' -Body '{}'
    Write-Output "Inspected: $($result.inspected); delivered: $($result.delivered)"
} finally {
    Invoke-StagingSql $cleanup
}

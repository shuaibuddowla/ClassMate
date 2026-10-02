# ClassMate 1.1.11 (version code 12)

## Changes

- Automatic notice dispatch after an outbox insert, with a scheduled retry every minute.
- Server-side recipient selection includes active students in the notice's batch and active, allowlisted teachers assigned to that batch's active semester. Viewing a batch as a global administrator does not subscribe the administrator to that batch's push alerts.
- Worker leases prevent simultaneous claims. Per-device delivery receipts avoid resending accepted messages during retries. Android notifications use stable record identifiers.
- Android uses the saved Supabase session, environment, recipient identity, and student batch to validate incoming notifications. App notification preferences and Android permission are respected. Token rotation can register after a background process restart.
- Horizontal swipes switch between the user's visible tabs. Vertical scrolling, editable text, horizontal content controls, and TalkBack retain their native gestures.
- The period editor reuses the timetable's day pills and period cards. Add opens a course/room/time dialog; tapping an authorized period opens its edit dialog. Existing save/delete RPC authorization remains unchanged.
- Google sign-in follows the device theme and uses a single university-email instruction. After entering a valid batch, three dialogs show actual account details, available features, and notification opt-in. Students retain their server-assigned batch; staff select an authorized running batch.

## Server verification completed on 2026-10-02

Migration `202610020004` and the dispatcher were deployed to production and staging. Dispatcher credentials were stored in Vault using the existing private local configuration; no credentials are included here.

`supabase/tests/classmate_batch_notifications.sql` passed in both environments. It verifies own-batch targeting, exclusion of pending/global administrator accounts, teacher revocation, inactive batches, claim leases/retry, and denial of device enumeration/queue claims to students. All fixtures roll back.

A separate empty fixture batch in each environment verified automatic dispatch without manually calling the worker: one attempt, no error, HTTP 200, active retry job. These fixtures were removed. No real device was used for this pipeline test.

## Release verification

Debug and unsigned production release builds are generated with:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio2\jbr'
.\gradlew.bat :app:assembleDebug :app:assembleRelease -PclassmateTarget=production --no-configuration-cache --console=plain
```

Sign version 1.1.11 in Android Studio with the existing production `key0`, then save to `release/app-release.apk`. The keystore passwords are not available to automated Gradle signing. Do not install a debug-signed APK over the production app.

## Phone checks after signing/installing

Phone screenshots and UI control were not used. The user should check:

1. Light/dark device-theme sign-in; successful university-email authentication and the three welcome steps.
2. Notification permission opt-in, denied permission, and app notification toggle behavior.
3. Tab swipes, normal vertical scrolling, horizontal filters, and navigation taps during transitions.
4. Add/edit/delete a period and confirm the main timetable updates.
5. Post a notice to a batch with a consenting active member device; confirm only that batch's members receive its alert, including while the app is in the background. Actual device delivery is not established by the empty-batch pipeline test.

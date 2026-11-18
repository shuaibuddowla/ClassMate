import os

repo_c = r"C:\Users\USER\AndroidStudioProjects\ClassMate"
repo_d = r"D:\Code & Scripts\ClassMate-main"

key_files = [
    "app/src/main/java/com/shuaib/classmate/ClassMateApp.kt",
    "app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt",
    "app/src/main/java/com/shuaib/classmate/activities/ClassMatePublishActivity.kt",
    "app/src/main/java/com/shuaib/classmate/activities/ClassMateConfigurationActivity.kt",
    "app/src/main/java/com/shuaib/classmate/activities/AdminPanelActivity.kt",
    "app/src/main/java/com/shuaib/classmate/activities/ClassMateAuthActivity.kt",
    "app/src/main/java/com/shuaib/classmate/activities/ClassMateBatchFundActivity.kt",
    "app/src/main/java/com/shuaib/classmate/activities/ClassMateFriendsScreen.kt",
    "app/src/main/java/com/shuaib/classmate/ui/ClassMateHamburgerSheet.kt",
    "app/src/main/java/com/shuaib/classmate/chat/ChatRepository.kt",
    "app/src/main/java/com/shuaib/classmate/chat/ClassMateChatActivity.kt",
    "app/src/main/java/com/shuaib/classmate/chat/ChatAdapter.kt",
    "app/src/main/java/com/shuaib/classmate/chat/ChatNotificationHelper.kt",
    "app/src/main/java/com/shuaib/classmate/chat/ChatReplyReceiver.kt",
    "app/src/main/java/com/shuaib/classmate/chat/ClassMateUsernamePromptBottomSheet.kt",
    "scripts/deploy_chat_schema.py",
    "scripts/deploy_chat_rpcs1.py",
    "scripts/deploy_chat_rpcs2.py",
    "scripts/deploy_chat_notif.py",
    "firmware/esp32_rfid_attendance.ino",
    "supabase/migrations/202611020009_fix_targeted_ban_notifications.sql",
    "supabase/migrations/202611050003_exclude_banned_students_from_notifications.sql",
    "supabase/migrations/202611120002_autonomous_offline_attendance_sync.sql",
    "supabase/migrations/202611130001_fix_can_manage_batch_fund_permissions.sql",
    "supabase/migrations/202611130004_disable_telegram_broadcast_and_link_resource_notices.sql",
    "supabase/migrations/202611130005_notice_feed_details_include_resources.sql",
    "supabase/migrations/202611130006_clean_resource_notice_bodies.sql",
    "supabase/migrations/202611140001_notice_system_fixes.sql",
    "supabase/migrations/202611140002_courses_system_fixes.sql",
    "supabase/migrations/202611140003_revoke_public_mutation_privileges.sql",
    "supabase/functions/signed-classmate-resource/index.ts",
    "web/src/app/api/scan/route.ts",
    "web/src/app/n/[id]/page.tsx",
    "web/src/app/r/[id]/page.tsx",
    "web/src/components/manage.tsx",
    "web/src/components/notices.tsx",
    "web/src/components/transit.tsx",
    "classmate-memory.md"
]

header = f"{'Target File / Subsystem Path':<68} | {'Workspace C:':<14} | {'Workspace D:':<15}"
print("+" + "-"*70 + "+" + "-"*16 + "+" + "-"*17 + "+")
print("| " + header + " |")
print("+" + "-"*70 + "+" + "-"*16 + "+" + "-"*17 + "+")

all_matched = True
for rel in key_files:
    fc = os.path.join(repo_c, rel.replace('/', os.sep))
    fd = os.path.join(repo_d, rel.replace('/', os.sep))
    sz_c = f"{os.path.getsize(fc):,} bytes" if os.path.exists(fc) else "MISSING"
    sz_d = f"{os.path.getsize(fd):,} bytes" if os.path.exists(fd) else "MISSING"
    match = sz_c == sz_d and sz_c != "MISSING"
    if not match:
        all_matched = False
    print(f"| {rel:<68} | {sz_c:>14} | {sz_d:>15} |")

print("+" + "-"*70 + "+" + "-"*16 + "+" + "-"*17 + "+")
print(f"All matched: {all_matched}")

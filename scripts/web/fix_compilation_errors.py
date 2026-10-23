from pathlib import Path

# 1. Update ProfileFragment.kt
p_prof = Path("app/src/main/java/com/shuaib/classmate/fragments/ProfileFragment.kt")
c_prof = p_prof.read_text(encoding="utf-8")
c_prof = c_prof.replace(
    'val canManageStorage = isAdmin || user.isCr || user.role in listOf("admin", "superadmin", "owner", "teacher")',
    'val canManageStorage = isAdmin || user.role in listOf("cr", "admin", "superadmin", "owner", "teacher")'
)
p_prof.write_text(c_prof, encoding="utf-8")
print("Fixed ProfileFragment.kt")

# 2. Update ClassMateAcademicScreensSupabase.kt
p_screens = Path("app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt")
c_screens = p_screens.read_text(encoding="utf-8")
c_screens = c_screens.replace(
    'val canViewR2 = isAdmin || canManage',
    'val canViewR2 = initialCanManage || isAdmin || isCr || isTeacher'
)
p_screens.write_text(c_screens, encoding="utf-8")
print("Fixed ClassMateAcademicScreensSupabase.kt")

# 3. Update ClassMatePublishActivity.kt imports
p_pub = Path("app/src/main/java/com/shuaib/classmate/activities/ClassMatePublishActivity.kt")
c_pub = p_pub.read_text(encoding="utf-8")
if "import kotlinx.coroutines.Dispatchers" not in c_pub:
    c_pub = "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\n" + c_pub
p_pub.write_text(c_pub, encoding="utf-8")
print("Fixed ClassMatePublishActivity.kt")

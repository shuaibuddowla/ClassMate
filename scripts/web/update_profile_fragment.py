from pathlib import Path

p = Path("app/src/main/java/com/shuaib/classmate/fragments/ProfileFragment.kt")
content = p.read_text(encoding="utf-8")

# 1. Setup click listener in setupViews
target_click = """        binding.layoutDeleteOfflineCache.setOnClickListener {"""
replacement_click = """        binding.layoutR2Storage.setOnClickListener {
            com.shuaib.classmate.ui.R2StorageDialog.show(requireContext())
        }

        binding.layoutDeleteOfflineCache.setOnClickListener {"""

if target_click in content:
    content = content.replace(target_click, replacement_click)
elif target_click.replace('\n', '\r\n') in content:
    content = content.replace(target_click.replace('\n', '\r\n'), replacement_click.replace('\n', '\r\n'))

# 2. Setup visibility in bindUserData
target_vis = """        val isAdmin = user.isAdmin()
        binding.adminSection.isVisible = isAdmin"""

replacement_vis = """        val isAdmin = user.isAdmin()
        binding.adminSection.isVisible = isAdmin
        val canManageStorage = isAdmin || user.isCr || user.role in listOf("admin", "superadmin", "owner", "teacher")
        binding.layoutR2Storage.isVisible = canManageStorage
        binding.dividerR2Storage.isVisible = canManageStorage"""

if target_vis in content:
    content = content.replace(target_vis, replacement_vis)
elif target_vis.replace('\n', '\r\n') in content:
    content = content.replace(target_vis.replace('\n', '\r\n'), replacement_vis.replace('\n', '\r\n'))

p.write_text(content, encoding="utf-8")
print("Updated ProfileFragment.kt successfully")

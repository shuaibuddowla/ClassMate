from pathlib import Path

p = Path("app/src/main/java/com/shuaib/classmate/activities/ClassMatePublishActivity.kt")
c = p.read_text(encoding="utf-8")

c = c.replace("import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\npackage com.shuaib.classmate.activities", "package com.shuaib.classmate.activities\n\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext")
c = c.replace("import kotlinx.coroutines.Dispatchers\r\nimport kotlinx.coroutines.withContext\r\npackage com.shuaib.classmate.activities", "package com.shuaib.classmate.activities\r\n\r\nimport kotlinx.coroutines.Dispatchers\r\nimport kotlinx.coroutines.withContext")

p.write_text(c, encoding="utf-8")
print("Fixed package and import order in ClassMatePublishActivity.kt")

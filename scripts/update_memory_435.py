import os
import shutil

repo_c = r"C:\Users\USER\AndroidStudioProjects\ClassMate"
repo_d = r"D:\Code & Scripts\ClassMate-main"

memory_file_c = os.path.join(repo_c, "classmate-memory.md")
memory_file_d = os.path.join(repo_d, "classmate-memory.md")
section_file = os.path.join(repo_c, "scripts", "section_435.md")

with open(section_file, "r", encoding="utf-8") as f:
    section_content = f.read()

with open(memory_file_c, "r", encoding="utf-8") as f:
    existing_c = f.read()

if "## 435. Master Production State v421" in existing_c:
    print("Section 435 already present in Workspace C. Skipping append.")
else:
    updated_c = existing_c.rstrip() + "\n\n" + section_content.strip() + "\n"
    with open(memory_file_c, "w", encoding="utf-8", newline="\n") as f:
        f.write(updated_c)
    print(f"Appended Section 435 to Workspace C: {len(updated_c.splitlines())} lines.")

if os.path.exists(repo_d):
    shutil.copy2(memory_file_c, memory_file_d)
    print(f"Synchronized classmate-memory.md to Workspace D ({memory_file_d}).")

# Copy scripts/section_435.md and update_memory_435.py to repo_d/scripts/
scripts_d = os.path.join(repo_d, "scripts")
if os.path.exists(scripts_d):
    shutil.copy2(section_file, os.path.join(scripts_d, "section_435.md"))
    shutil.copy2(os.path.join(repo_c, "scripts", "update_memory_435.py"), os.path.join(scripts_d, "update_memory_435.py"))
    print(f"Synchronized section_435.md and update_memory_435.py to Workspace D scripts.")

# Verify lines and size
with open(memory_file_c, "r", encoding="utf-8") as f:
    lines_c = len(f.readlines())
with open(memory_file_d, "r", encoding="utf-8") as f:
    lines_d = len(f.readlines())

size_c = os.path.getsize(memory_file_c)
size_d = os.path.getsize(memory_file_d)

print(f"Verification: C has {lines_c} lines ({size_c} bytes), D has {lines_d} lines ({size_d} bytes).")

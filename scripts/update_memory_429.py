# scripts/update_memory_429.py
import os
import shutil

script_dir = os.path.dirname(os.path.abspath(__file__))
section_file = os.path.join(script_dir, "section_429.md")

with open(section_file, "r", encoding="utf-8") as f:
    section_429 = f.read()

c_mem = r"C:\Users\USER\AndroidStudioProjects\ClassMate\classmate-memory.md"
with open(c_mem, "r", encoding="utf-8") as f:
    content = f.read()

if "### 429.1 Executive Architecture" not in content:
    with open(c_mem, "a", encoding="utf-8") as f:
        f.write(section_429)
    print("Successfully appended Section 429 to C: workspace")
else:
    print("Section 429 already exists in C: workspace")

d_mem = r"D:\Code & Scripts\ClassMate-main\classmate-memory.md"
if os.path.exists(os.path.dirname(d_mem)):
    shutil.copyfile(c_mem, d_mem)
    print("Successfully synchronized to D: workspace")
else:
    print("Warning: D: mirror directory not found")

d_script = r"D:\Code & Scripts\ClassMate-main\scripts\update_memory_429.py"
if os.path.exists(os.path.dirname(d_script)):
    shutil.copyfile(os.path.abspath(__file__), d_script)
    shutil.copyfile(section_file, os.path.join(os.path.dirname(d_script), "section_429.md"))
    print("Successfully copied update scripts to D: mirror")

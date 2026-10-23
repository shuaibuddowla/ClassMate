from pathlib import Path

# 1. Fix library.tsx imports
p_lib = Path("web/src/components/library.tsx")
content_lib = p_lib.read_text(encoding="utf-8")
content_lib = content_lib.replace(
    "  Clock,\n  X,\n} from \"lucide-react\";",
    "  Clock,\n  X,\n  AlertCircle,\n  AlertTriangle,\n} from \"lucide-react\";"
)
content_lib = content_lib.replace(
    "  Clock,\r\n  X,\r\n} from \"lucide-react\";",
    "  Clock,\r\n  X,\r\n  AlertCircle,\r\n  AlertTriangle,\r\n} from \"lucide-react\";"
)
p_lib.write_text(content_lib, encoding="utf-8")
print("Fixed library.tsx imports")

# 2. Fix profile.tsx state
p_prof = Path("web/src/components/profile.tsx")
content_prof = p_prof.read_text(encoding="utf-8")
content_prof = content_prof.replace(
    "[developerOpen, setDeveloperOpen] = useState(false),",
    "[developerOpen, setDeveloperOpen] = useState(false),\n    [r2StorageOpen, setR2StorageOpen] = useState(false),"
)
content_prof = content_prof.replace(
    "[developerOpen, setDeveloperOpen] = useState(false),\r\n",
    "[developerOpen, setDeveloperOpen] = useState(false),\r\n    [r2StorageOpen, setR2StorageOpen] = useState(false),\r\n"
)
p_prof.write_text(content_prof, encoding="utf-8")
print("Fixed profile.tsx state")

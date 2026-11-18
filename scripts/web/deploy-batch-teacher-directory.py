"""Apply the batch-scoped teacher directory RPC to production."""
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / "scripts" / "capacity"))
from platform_access import PRODUCTION, query

version = "202611020006"
name = "batch_teacher_directory"
if query(PRODUCTION, f"select version from supabase_migrations.schema_migrations where version='{version}'"):
    print("Migration already applied: " + version)
else:
    sql = (root / "supabase" / "migrations" / f"{version}_{name}.sql").read_text(encoding="utf-8")
    sql = sql.replace(
        "commit;",
        f"insert into supabase_migrations.schema_migrations(version,name,statements) values('{version}','{name}',array[]::text[]);\ncommit;",
    )
    query(PRODUCTION, sql)
    print("Applied migration: " + version)
query(PRODUCTION, "notify pgrst, 'reload schema';")
print("Production API schema reload requested")

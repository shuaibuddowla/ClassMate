"""Import owner-supplied calendar data into Supabase, never into Android resources."""
import argparse
import json
import sys
from datetime import date
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent / 'capacity'))
from platform_access import query, STAGING, PRODUCTION

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--target', choices=['staging', 'production'], required=True)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    rows = json.loads((ROOT / 'supabase/seeds/mbstu_calendar_2026.json').read_text(encoding='utf-8'))
    publication = json.loads((ROOT / 'supabase/seeds/mbstu_calendar_2026_notes.json').read_text(encoding='utf-8'))
    assert len(rows) == 29 and len({r['source_key'] for r in rows}) == 29
    for row in rows:
        assert date.fromisoformat(row['start_date']) <= date.fromisoformat(row['end_date'])
        assert row['scope'] in ['university', 'classes', 'observance', 'working_day']
    if not args.apply:
        print('Validated 29 records: 25 listed holidays, two split class/office ranges, two observances. No changes made.')
        return
    def literal(value):
        return "'" + json.dumps(value, ensure_ascii=False).replace("'", "''") + "'::jsonb"
    sql = """begin;
insert into classmate.academic_calendar_events(title,start_date,end_date,scope,provisional,source_key,source_note)
select title,start_date,end_date,scope,provisional,source_key,source_note
from jsonb_to_recordset(""" + literal(rows) + """) as r(title text,start_date date,end_date date,scope text,provisional boolean,source_key text,source_note text)
on conflict(source_key) do update set title=excluded.title,start_date=excluded.start_date,end_date=excluded.end_date,
scope=excluded.scope,provisional=excluded.provisional,source_note=excluded.source_note,updated_at=now();
insert into classmate.academic_calendar_publications(year,title,notes,source_note)
select year,title,notes,source_note from jsonb_to_record(""" + literal(publication) + """) as r(year integer,title text,notes text,source_note text)
on conflict(year) do update set title=excluded.title,notes=excluded.notes,source_note=excluded.source_note,updated_at=now();
commit;"""
    ref = STAGING if args.target == 'staging' else PRODUCTION
    query(ref, sql)
    result = query(ref, "select scope,count(*) from classmate.academic_calendar_events where source_key like 'mbstu-2026-%' group by scope order by scope")
    assert sum(r['count'] for r in result) == len(rows)
    print(args.target, result)

if __name__ == '__main__':
    main()

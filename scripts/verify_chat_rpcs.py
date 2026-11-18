import sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts/capacity')))
from platform_access import query, PRODUCTION

def main():
    names = ['get_room_messages', 'toggle_message_reaction', 'mark_room_as_read', 'ensure_batch_discussion_room', 'get_or_create_dm_room']
    res = query(PRODUCTION, f"SELECT routine_name FROM information_schema.routines WHERE routine_schema = 'classmate' AND routine_name IN ({','.join([repr(x) for x in names])});")
    print("Found routines:", [r['routine_name'] for r in res])

if __name__ == '__main__':
    main()

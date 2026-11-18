import sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts/capacity')))
from platform_access import query, PRODUCTION

def main():
    print("Testing chat RPCs...")
    
    users = query(PRODUCTION, "SELECT id, full_name, username, batch_id FROM classmate.profiles WHERE verification_status = 'active' LIMIT 5;")
    print(f"Found {len(users)} active users:")
    for u in users:
        print(f"  {u['id']}: {u['full_name']} (@{u['username']}) - batch: {u['batch_id']}")
    
    if not users:
        print("No users found.")
        return

    test_user_1 = users[0]

    # Test search_students_for_chat
    print(f"\nTesting search_students_for_chat...")
    search_res = query(PRODUCTION, "SELECT classmate.search_students_for_chat('') AS res;")
    items = search_res[0]['res']
    print(f"search_students_for_chat returned {len(items)} students!")
    if items:
        print("Sample student returned:", items[0])

    # Test ensure_batch_discussion_room
    if test_user_1['batch_id']:
        print(f"\nTesting ensure_batch_discussion_room for batch {test_user_1['batch_id']}...")
        room_res = query(PRODUCTION, f"""
            SELECT * FROM classmate.ensure_batch_discussion_room('{test_user_1['batch_id']}');
        """)
        print("ensure_batch_discussion_room result:", room_res)

    print("\nAll database queries executed successfully with zero errors!")

if __name__ == '__main__':
    main()

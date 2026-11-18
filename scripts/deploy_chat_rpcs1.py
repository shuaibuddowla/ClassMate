import sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts/capacity')))
from platform_access import query, PRODUCTION

sql_rpcs = """
-- 1. Helper function to ensure batch discussion room exists
CREATE OR REPLACE FUNCTION classmate.ensure_batch_discussion_room(p_batch_id UUID)
RETURNS TABLE (
    room_id TEXT,
    batch_name TEXT
) LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_room_id TEXT;
    v_batch_name TEXT;
    v_institution_id UUID;
    v_batch_num INT;
BEGIN
    SELECT 'batch_' || p_batch_id::TEXT INTO v_room_id;
    
    SELECT b.institution_id, b.batch_number 
    INTO v_institution_id, v_batch_num
    FROM classmate.batches b
    WHERE b.id = p_batch_id;

    v_batch_name := 'Discussion Group (Batch ' || COALESCE(v_batch_num::TEXT, '') || ')';

    INSERT INTO classmate.chat_rooms (
        id, type, batch_id, institution_id, name, created_at, updated_at
    )
    VALUES (
        v_room_id, 'group', p_batch_id, v_institution_id, 'Discussion Group', now(), now()
    )
    ON CONFLICT (id) DO NOTHING;

    RETURN QUERY SELECT v_room_id, v_batch_name;
END;
$$;

-- 2. get_user_chat_rooms: returns all chat rooms with unread counts and peer details
CREATE OR REPLACE FUNCTION classmate.get_user_chat_rooms()
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_batch_id UUID;
    v_batch_room_id TEXT;
    v_result JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Get user batch
    SELECT COALESCE(effective_batch_id, batch_id) INTO v_batch_id
    FROM classmate.profiles
    WHERE id = v_user_id;

    -- Ensure batch discussion room exists and user is participant
    IF v_batch_id IS NOT NULL THEN
        PERFORM classmate.ensure_batch_discussion_room(v_batch_id);
        v_batch_room_id := 'batch_' || v_batch_id::TEXT;

        INSERT INTO classmate.chat_participants (room_id, user_id, joined_at)
        VALUES (v_batch_room_id, v_user_id, now())
        ON CONFLICT (room_id, user_id) DO NOTHING;
    END IF;

    WITH my_rooms AS (
        SELECT 
            r.id,
            r.type,
            r.name,
            r.batch_id,
            r.last_message_text,
            r.last_message_time,
            r.last_message_sender_id,
            r.last_message_sender_name,
            cp.last_read_at,
            cp.is_pinned,
            cp.is_muted,
            CASE WHEN r.type = 'group' THEN true ELSE false END AS is_cohort_group
        FROM classmate.chat_rooms r
        JOIN classmate.chat_participants cp ON cp.room_id = r.id AND cp.user_id = v_user_id
        WHERE (r.type = 'group' AND r.batch_id = v_batch_id) OR (r.type = 'dm')
    ),
    unread_counts AS (
        SELECT 
            m.room_id,
            COUNT(*)::INT AS unread_count
        FROM classmate.chat_messages m
        JOIN my_rooms mr ON mr.id = m.room_id
        WHERE m.sender_id != v_user_id 
          AND (mr.last_read_at IS NULL OR m.created_at > mr.last_read_at)
          AND m.is_deleted = false
          AND m.is_unsent = false
        GROUP BY m.room_id
    ),
    dm_peers AS (
        SELECT 
            mr.id AS room_id,
            p.id AS peer_id,
            p.full_name AS peer_name,
            p.username AS peer_username,
            p.student_id AS peer_student_id,
            p.avatar_url AS peer_avatar
        FROM my_rooms mr
        JOIN classmate.chat_participants cp ON cp.room_id = mr.id AND cp.user_id != v_user_id
        JOIN classmate.profiles p ON p.id = cp.user_id
        WHERE mr.type = 'dm'
    )
    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'id', mr.id,
            'type', mr.type,
            'name', CASE 
                WHEN mr.type = 'dm' THEN COALESCE(dp.peer_name, 'Direct Message')
                ELSE mr.name
            END,
            'batch_id', mr.batch_id,
            'is_cohort_group', mr.is_cohort_group,
            'is_pinned', mr.is_pinned OR mr.is_cohort_group,
            'is_muted', mr.is_muted,
            'last_message_text', mr.last_message_text,
            'last_message_time', mr.last_message_time,
            'last_message_sender_name', mr.last_message_sender_name,
            'unread_count', COALESCE(uc.unread_count, 0),
            'peer_id', dp.peer_id,
            'peer_name', dp.peer_name,
            'peer_username', dp.peer_username,
            'peer_student_id', dp.peer_student_id,
            'peer_avatar', dp.peer_avatar
        ) ORDER BY (mr.is_cohort_group) DESC, COALESCE(mr.last_message_time, mr.last_read_at, '1970-01-01'::timestamptz) DESC
    ), '[]'::jsonb) INTO v_result
    FROM my_rooms mr
    LEFT JOIN unread_counts uc ON uc.room_id = mr.id
    LEFT JOIN dm_peers dp ON dp.room_id = mr.id;

    RETURN v_result;
END;
$$;

-- 3. get_or_create_dm_room: safely creates 1-on-1 DM between two students
CREATE OR REPLACE FUNCTION classmate.get_or_create_dm_room(p_target_user_id UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_room_id TEXT;
    v_target_profile RECORD;
    v_result JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;
    IF v_user_id = p_target_user_id THEN
        RAISE EXCEPTION 'Cannot start chat with yourself';
    END IF;

    SELECT id, full_name, username, student_id, avatar_url, institution_id
    INTO v_target_profile
    FROM classmate.profiles
    WHERE id = p_target_user_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Target user not found';
    END IF;

    -- Canonical deterministic room ID
    v_room_id := 'dm_' || LEAST(v_user_id::TEXT, p_target_user_id::TEXT) || '_' || GREATEST(v_user_id::TEXT, p_target_user_id::TEXT);

    -- Insert room if not exists
    INSERT INTO classmate.chat_rooms (
        id, type, institution_id, name, created_at, updated_at
    )
    VALUES (
        v_room_id, 'dm', v_target_profile.institution_id, 'Direct Message', now(), now()
    )
    ON CONFLICT (id) DO NOTHING;

    -- Insert both participants
    INSERT INTO classmate.chat_participants (room_id, user_id, joined_at)
    VALUES (v_room_id, v_user_id, now())
    ON CONFLICT (room_id, user_id) DO NOTHING;

    INSERT INTO classmate.chat_participants (room_id, user_id, joined_at)
    VALUES (v_room_id, p_target_user_id, now())
    ON CONFLICT (room_id, user_id) DO NOTHING;

    SELECT jsonb_build_object(
        'id', v_room_id,
        'type', 'dm',
        'peer_id', v_target_profile.id,
        'peer_name', v_target_profile.full_name,
        'peer_username', v_target_profile.username,
        'peer_student_id', v_target_profile.student_id,
        'peer_avatar', v_target_profile.avatar_url
    ) INTO v_result;

    RETURN v_result;
END;
$$;
"""

def main():
    print("Deploying RPCs part 1...")
    res = query(PRODUCTION, sql_rpcs)
    print("Result:", res)

if __name__ == '__main__':
    main()

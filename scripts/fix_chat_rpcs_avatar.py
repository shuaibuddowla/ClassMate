import sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts/capacity')))
from platform_access import query, PRODUCTION

sql = """
-- 1. Fix search_students_for_chat (remove p.avatar_url, use empty string)
CREATE OR REPLACE FUNCTION classmate.search_students_for_chat(p_query TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_clean TEXT := TRIM(REPLACE(p_query, '@', ''));
    v_result JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'id', p.id,
            'full_name', p.full_name,
            'username', p.username,
            'student_id', p.student_id,
            'batch_number', b.batch_number,
            'avatar_url', '',
            'is_cr', p.is_cr
        )
    ), '[]'::jsonb) INTO v_result
    FROM classmate.profiles p
    LEFT JOIN classmate.batches b ON b.id = COALESCE(p.effective_batch_id, p.batch_id)
    WHERE p.id != v_user_id
      AND p.verification_status = 'approved'
      AND (
          v_clean = '' 
          OR p.username ILIKE '%' || v_clean || '%' 
          OR p.full_name ILIKE '%' || v_clean || '%'
          OR p.student_id ILIKE '%' || v_clean || '%'
      )
    LIMIT 30;

    RETURN v_result;
END;
$$;

-- 2. Fix get_user_chat_rooms (remove p.avatar_url)
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
            '' AS peer_avatar
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

-- 3. Fix get_or_create_dm_room (remove p.avatar_url)
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

    SELECT id, full_name, username, student_id, institution_id
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
        'peer_avatar', ''
    ) INTO v_result;

    RETURN v_result;
END;
$$;

-- 4. Fix send_chat_message (remove p.avatar_url)
CREATE OR REPLACE FUNCTION classmate.send_chat_message(
    p_room_id TEXT,
    p_content TEXT,
    p_image_url TEXT DEFAULT NULL,
    p_reply_to_id UUID DEFAULT NULL,
    p_forwarded_from TEXT DEFAULT NULL
)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_sender_name TEXT;
    v_sender_username TEXT;
    v_sender_batch_id UUID;
    v_room RECORD;
    v_reply_text TEXT;
    v_reply_sender TEXT;
    v_msg_id UUID;
    v_result JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Fetch sender info
    SELECT full_name, username, COALESCE(effective_batch_id, batch_id)
    INTO v_sender_name, v_sender_username, v_sender_batch_id
    FROM classmate.profiles
    WHERE id = v_user_id;

    -- Fetch room info
    SELECT id, type, batch_id, name INTO v_room
    FROM classmate.chat_rooms
    WHERE id = p_room_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Room does not exist: %', p_room_id;
    END IF;

    -- Batch isolation check: For group chat, user must belong to this batch!
    IF v_room.type = 'group' AND v_room.batch_id IS NOT NULL THEN
        IF v_sender_batch_id IS DISTINCT FROM v_room.batch_id THEN
            RAISE EXCEPTION 'Batch isolation violation: You cannot post in other cohort discussion groups';
        END IF;
    END IF;

    -- For DM chat, ensure caller is a participant
    IF v_room.type = 'dm' THEN
        IF NOT EXISTS (
            SELECT 1 FROM classmate.chat_participants 
            WHERE room_id = p_room_id AND user_id = v_user_id
        ) THEN
            RAISE EXCEPTION 'Access denied to this direct message room';
        END IF;
    END IF;

    -- Fetch reply snippet if applicable
    IF p_reply_to_id IS NOT NULL THEN
        SELECT content, sender_name INTO v_reply_text, v_reply_sender
        FROM classmate.chat_messages
        WHERE id = p_reply_to_id;
    END IF;

    -- Insert message
    INSERT INTO classmate.chat_messages (
        room_id, sender_id, sender_name, sender_username, sender_avatar_url,
        content, image_url, reply_to_id, reply_to_text, reply_to_sender,
        forwarded_from, created_at
    )
    VALUES (
        p_room_id, v_user_id, COALESCE(v_sender_name, 'Classmate'), v_sender_username, '',
        p_content, p_image_url, p_reply_to_id, v_reply_text, v_reply_sender,
        p_forwarded_from, now()
    )
    RETURNING id INTO v_msg_id;

    -- Update room last message preview
    UPDATE classmate.chat_rooms
    SET last_message_text = CASE 
            WHEN p_image_url IS NOT NULL AND (p_content IS NULL OR p_content = '') THEN 'ðŸ“· Photo'
            ELSE p_content
        END,
        last_message_time = now(),
        last_message_sender_id = v_user_id,
        last_message_sender_name = v_sender_name,
        updated_at = now()
    WHERE id = p_room_id;

    -- Update sender's last_read_at in participants
    UPDATE classmate.chat_participants
    SET last_read_at = now()
    WHERE room_id = p_room_id AND user_id = v_user_id;

    -- Record self-read
    INSERT INTO classmate.chat_message_reads (message_id, user_id, read_at)
    VALUES (v_msg_id, v_user_id, now())
    ON CONFLICT (message_id, user_id) DO NOTHING;

    -- Queue push notifications for all other participants
    INSERT INTO classmate.notification_outbox (
        kind, record_id, batch_id, recipient_profile_id, routing_label, created_at
    )
    SELECT 
        'chat_message',
        v_msg_id,
        v_room.batch_id,
        cp.user_id,
        CASE 
            WHEN v_room.type = 'group' THEN 'Discussion Group'
            ELSE v_sender_name
        END,
        now()
    FROM classmate.chat_participants cp
    WHERE cp.room_id = p_room_id AND cp.user_id != v_user_id AND cp.is_muted = false;

    -- Return full message object
    SELECT jsonb_build_object(
        'id', v_msg_id,
        'room_id', p_room_id,
        'sender_id', v_user_id,
        'sender_name', v_sender_name,
        'sender_username', v_sender_username,
        'sender_avatar_url', '',
        'content', p_content,
        'image_url', p_image_url,
        'reply_to_id', p_reply_to_id,
        'reply_to_text', v_reply_text,
        'reply_to_sender', v_reply_sender,
        'forwarded_from', p_forwarded_from,
        'is_pinned', false,
        'is_unsent', false,
        'is_deleted', false,
        'created_at', now()
    ) INTO v_result;

    RETURN v_result;
END;
$$;
"""

def main():
    print("Fixing avatar_url in chat RPCs...")
    res = query(PRODUCTION, sql)
    print("Result:", res)

if __name__ == '__main__':
    main()

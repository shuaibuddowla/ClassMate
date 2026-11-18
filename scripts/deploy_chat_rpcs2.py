import sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts/capacity')))
from platform_access import query, PRODUCTION

sql_rpcs2 = """
-- 4. send_chat_message: atomic send with room summary update & notification outbox enqueue
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
    v_sender_avatar TEXT;
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
    SELECT full_name, username, avatar_url, COALESCE(effective_batch_id, batch_id)
    INTO v_sender_name, v_sender_username, v_sender_avatar, v_sender_batch_id
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
        p_room_id, v_user_id, COALESCE(v_sender_name, 'Classmate'), v_sender_username, v_sender_avatar,
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
        'sender_avatar_url', v_sender_avatar,
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

-- 5. get_room_messages: paginated messages with reaction list & read status
CREATE OR REPLACE FUNCTION classmate.get_room_messages(
    p_room_id TEXT,
    p_limit INT DEFAULT 50,
    p_before TIMESTAMPTZ DEFAULT NULL
)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_result JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    WITH msg_page AS (
        SELECT 
            m.id,
            m.room_id,
            m.sender_id,
            m.sender_name,
            m.sender_username,
            m.sender_avatar_url,
            CASE WHEN m.is_unsent THEN '' ELSE m.content END AS content,
            CASE WHEN m.is_unsent THEN NULL ELSE m.image_url END AS image_url,
            m.reply_to_id,
            m.reply_to_text,
            m.reply_to_sender,
            m.forwarded_from,
            m.is_pinned,
            m.is_unsent,
            m.is_deleted,
            m.edited_at,
            m.created_at
        FROM classmate.chat_messages m
        WHERE m.room_id = p_room_id
          AND (p_before IS NULL OR m.created_at < p_before)
          AND m.is_deleted = false
        ORDER BY m.created_at DESC
        LIMIT p_limit
    ),
    reactions_agg AS (
        SELECT 
            r.message_id,
            jsonb_agg(jsonb_build_object(
                'user_id', r.user_id,
                'user_name', r.user_name,
                'reaction', r.reaction,
                'is_mine', (r.user_id = v_user_id)
            )) AS reactions
        FROM classmate.chat_message_reactions r
        JOIN msg_page mp ON mp.id = r.message_id
        GROUP BY r.message_id
    ),
    reads_agg AS (
        SELECT 
            rd.message_id,
            jsonb_agg(rd.user_id) AS read_by
        FROM classmate.chat_message_reads rd
        JOIN msg_page mp ON mp.id = rd.message_id
        GROUP BY rd.message_id
    )
    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'id', mp.id,
            'room_id', mp.room_id,
            'sender_id', mp.sender_id,
            'sender_name', mp.sender_name,
            'sender_username', mp.sender_username,
            'sender_avatar_url', mp.sender_avatar_url,
            'content', mp.content,
            'image_url', mp.image_url,
            'reply_to_id', mp.reply_to_id,
            'reply_to_text', mp.reply_to_text,
            'reply_to_sender', mp.reply_to_sender,
            'forwarded_from', mp.forwarded_from,
            'is_pinned', mp.is_pinned,
            'is_unsent', mp.is_unsent,
            'is_deleted', mp.is_deleted,
            'edited_at', mp.edited_at,
            'created_at', mp.created_at,
            'timestamp', (EXTRACT(EPOCH FROM mp.created_at) * 1000)::BIGINT,
            'reactions', COALESCE(ra.reactions, '[]'::jsonb),
            'read_by', COALESCE(rda.read_by, '[]'::jsonb),
            'is_read', (rda.read_by IS NOT NULL AND jsonb_array_length(rda.read_by) > 1)
        ) ORDER BY mp.created_at ASC
    ), '[]'::jsonb) INTO v_result
    FROM msg_page mp
    LEFT JOIN reactions_agg ra ON ra.message_id = mp.id
    LEFT JOIN reads_agg rda ON rda.message_id = mp.id;

    RETURN v_result;
END;
$$;

-- 6. toggle_message_reaction: like/react to message
CREATE OR REPLACE FUNCTION classmate.toggle_message_reaction(
    p_message_id UUID,
    p_reaction TEXT
)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_user_name TEXT;
    v_existing TEXT;
    v_result JSONB;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    SELECT full_name INTO v_user_name
    FROM classmate.profiles
    WHERE id = v_user_id;

    SELECT reaction INTO v_existing
    FROM classmate.chat_message_reactions
    WHERE message_id = p_message_id AND user_id = v_user_id;

    IF v_existing IS NOT NULL AND v_existing = p_reaction THEN
        -- Toggle off
        DELETE FROM classmate.chat_message_reactions
        WHERE message_id = p_message_id AND user_id = v_user_id;
    ELSIF v_existing IS NOT NULL THEN
        -- Change reaction
        UPDATE classmate.chat_message_reactions
        SET reaction = p_reaction, created_at = now()
        WHERE message_id = p_message_id AND user_id = v_user_id;
    ELSE
        -- Insert new
        INSERT INTO classmate.chat_message_reactions (message_id, user_id, user_name, reaction)
        VALUES (p_message_id, v_user_id, COALESCE(v_user_name, 'Classmate'), p_reaction);
    END IF;

    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'user_id', r.user_id,
            'user_name', r.user_name,
            'reaction', r.reaction,
            'is_mine', (r.user_id = v_user_id)
        )
    ), '[]'::jsonb) INTO v_result
    FROM classmate.chat_message_reactions r
    WHERE r.message_id = p_message_id;

    RETURN v_result;
END;
$$;

-- 7. unsend_chat_message: unsend for everyone
CREATE OR REPLACE FUNCTION classmate.unsend_chat_message(p_message_id UUID)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    UPDATE classmate.chat_messages
    SET content = '',
        image_url = NULL,
        is_unsent = true,
        edited_at = now()
    WHERE id = p_message_id AND sender_id = v_user_id;

    RETURN FOUND;
END;
$$;

-- 8. delete_chat_message: delete message
CREATE OR REPLACE FUNCTION classmate.delete_chat_message(p_message_id UUID)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    UPDATE classmate.chat_messages
    SET is_deleted = true,
        edited_at = now()
    WHERE id = p_message_id AND (sender_id = v_user_id OR EXISTS (
        SELECT 1 FROM classmate.profiles WHERE id = v_user_id AND (is_cr = true OR role IN ('admin', 'teacher'))
    ));

    RETURN FOUND;
END;
$$;

-- 9. mark_room_as_read: updates read receipts
CREATE OR REPLACE FUNCTION classmate.mark_room_as_read(p_room_id TEXT)
RETURNS INT LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_count INT := 0;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Update last_read_at in participants
    UPDATE classmate.chat_participants
    SET last_read_at = now()
    WHERE room_id = p_room_id AND user_id = v_user_id;

    -- Insert reads for all unread messages
    INSERT INTO classmate.chat_message_reads (message_id, user_id, read_at)
    SELECT m.id, v_user_id, now()
    FROM classmate.chat_messages m
    WHERE m.room_id = p_room_id 
      AND m.sender_id != v_user_id
      AND NOT EXISTS (
          SELECT 1 FROM classmate.chat_message_reads r 
          WHERE r.message_id = m.id AND r.user_id = v_user_id
      )
    ON CONFLICT (message_id, user_id) DO NOTHING;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END;
$$;

-- 10. search_students_for_chat: find peers by username or full name
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
            'avatar_url', p.avatar_url,
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

-- Add chat tables to Supabase Realtime publication
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' AND schemaname = 'classmate' AND tablename = 'chat_messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE classmate.chat_messages;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' AND schemaname = 'classmate' AND tablename = 'chat_message_reactions'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE classmate.chat_message_reactions;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' AND schemaname = 'classmate' AND tablename = 'chat_rooms'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE classmate.chat_rooms;
    END IF;
END $$;
"""

def main():
    print("Deploying RPCs part 2 & Realtime publication...")
    res = query(PRODUCTION, sql_rpcs2)
    print("Result:", res)

if __name__ == '__main__':
    main()

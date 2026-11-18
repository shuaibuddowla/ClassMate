import sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts/capacity')))
from platform_access import query, PRODUCTION

sql = """
-- 1. Create chat_rooms table in classmate schema
CREATE TABLE IF NOT EXISTS classmate.chat_rooms (
    id TEXT PRIMARY KEY,
    type TEXT NOT NULL CHECK (type IN ('group', 'dm')),
    batch_id UUID REFERENCES classmate.batches(id) ON DELETE CASCADE,
    institution_id UUID REFERENCES classmate.institutions(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    avatar_url TEXT,
    last_message_text TEXT,
    last_message_time TIMESTAMPTZ,
    last_message_sender_id UUID REFERENCES classmate.profiles(id) ON DELETE SET NULL,
    last_message_sender_name TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);

-- Index for fast batch lookup
CREATE INDEX IF NOT EXISTS idx_chat_rooms_batch_id ON classmate.chat_rooms(batch_id);
CREATE INDEX IF NOT EXISTS idx_chat_rooms_type ON classmate.chat_rooms(type);

-- 2. Create chat_participants table
CREATE TABLE IF NOT EXISTS classmate.chat_participants (
    room_id TEXT REFERENCES classmate.chat_rooms(id) ON DELETE CASCADE,
    user_id UUID REFERENCES classmate.profiles(id) ON DELETE CASCADE,
    last_read_at TIMESTAMPTZ DEFAULT now(),
    joined_at TIMESTAMPTZ DEFAULT now(),
    is_pinned BOOLEAN DEFAULT false,
    is_muted BOOLEAN DEFAULT false,
    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_participants_user_id ON classmate.chat_participants(user_id);
CREATE INDEX IF NOT EXISTS idx_chat_participants_room_id ON classmate.chat_participants(room_id);

-- 3. Create chat_messages table
CREATE TABLE IF NOT EXISTS classmate.chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id TEXT NOT NULL REFERENCES classmate.chat_rooms(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES classmate.profiles(id) ON DELETE CASCADE,
    sender_name TEXT NOT NULL,
    sender_username TEXT,
    sender_avatar_url TEXT,
    content TEXT,
    image_url TEXT,
    reply_to_id UUID REFERENCES classmate.chat_messages(id) ON DELETE SET NULL,
    reply_to_text TEXT,
    reply_to_sender TEXT,
    forwarded_from TEXT,
    is_pinned BOOLEAN DEFAULT false,
    is_unsent BOOLEAN DEFAULT false,
    is_deleted BOOLEAN DEFAULT false,
    edited_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_room_created ON classmate.chat_messages(room_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_chat_messages_sender ON classmate.chat_messages(sender_id);

-- 4. Create chat_message_reactions table
CREATE TABLE IF NOT EXISTS classmate.chat_message_reactions (
    message_id UUID NOT NULL REFERENCES classmate.chat_messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES classmate.profiles(id) ON DELETE CASCADE,
    user_name TEXT NOT NULL,
    reaction TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_reactions_msg ON classmate.chat_message_reactions(message_id);

-- 5. Create chat_message_reads table (Read Receipts)
CREATE TABLE IF NOT EXISTS classmate.chat_message_reads (
    message_id UUID NOT NULL REFERENCES classmate.chat_messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES classmate.profiles(id) ON DELETE CASCADE,
    read_at TIMESTAMPTZ DEFAULT now(),
    PRIMARY KEY (message_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_chat_reads_msg ON classmate.chat_message_reads(message_id);
"""

def main():
    print("Executing chat schema DDL...")
    res = query(PRODUCTION, sql)
    print("Result:", res)

if __name__ == '__main__':
    main()

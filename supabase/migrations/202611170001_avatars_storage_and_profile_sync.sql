-- 1. Add avatar_url column to classmate.profiles if it doesn't exist
ALTER TABLE classmate.profiles ADD COLUMN IF NOT EXISTS avatar_url text;

-- 2. Create or ensure avatars bucket
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
  'avatars', 'avatars', true, 5242880,
  array['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE SET
  public = true,
  file_size_limit = 5242880,
  allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp'];

-- 3. Storage RLS Policies
DROP POLICY IF EXISTS "Public avatar access" ON storage.objects;
CREATE POLICY "Public avatar access"
ON storage.objects FOR SELECT
TO anon, authenticated
USING (bucket_id = 'avatars');

DROP POLICY IF EXISTS "Authenticated users upload avatar" ON storage.objects;
CREATE POLICY "Authenticated users upload avatar"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (bucket_id = 'avatars');

DROP POLICY IF EXISTS "Authenticated users update avatar" ON storage.objects;
CREATE POLICY "Authenticated users update avatar"
ON storage.objects FOR UPDATE
TO authenticated
USING (bucket_id = 'avatars');

-- 4. Create update_profile_avatar RPC
CREATE OR REPLACE FUNCTION classmate.update_profile_avatar(target_avatar_url text)
RETURNS classmate.profiles
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO ''
AS $$
DECLARE
  v_uid uuid := auth.uid();
  result classmate.profiles%rowtype;
  cleaned_url text := nullif(trim(target_avatar_url), '');
BEGIN
  IF v_uid IS NULL THEN
    RAISE EXCEPTION 'Authentication required' USING errcode = '28000';
  END IF;

  -- 1. Update classmate.profiles
  UPDATE classmate.profiles
  SET avatar_url = cleaned_url, updated_at = now()
  WHERE id = v_uid
  RETURNING * INTO result;

  -- 2. Update auth.users raw_user_meta_data
  UPDATE auth.users
  SET raw_user_meta_data = coalesce(raw_user_meta_data, '{}'::jsonb) || 
    jsonb_build_object('avatar_url', cleaned_url, 'picture', cleaned_url)
  WHERE id = v_uid;

  -- 3. Update public.profiles if table exists
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'profiles') THEN
    UPDATE public.profiles
    SET avatar_url = cleaned_url
    WHERE id = v_uid;
  END IF;

  RETURN result;
END;
$$;

REVOKE ALL ON FUNCTION classmate.update_profile_avatar(text) FROM public, anon;
GRANT EXECUTE ON FUNCTION classmate.update_profile_avatar(text) TO authenticated;

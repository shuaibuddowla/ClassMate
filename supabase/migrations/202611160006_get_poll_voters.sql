-- Get poll voters grouped by option
CREATE OR REPLACE FUNCTION classmate.get_poll_voters(target_poll uuid)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = classmate, public, auth
AS $$
DECLARE
    poll_row classmate.polls%ROWTYPE;
    result jsonb;
BEGIN
    SELECT * INTO poll_row FROM classmate.polls WHERE id = target_poll;
    IF NOT FOUND THEN
        RETURN '[]'::jsonb;
    END IF;

    SELECT coalesce(jsonb_agg(
        jsonb_build_object(
            'option_id', po.id,
            'option_text', po.option_text,
            'position', po.position,
            'votes_count', po.votes_count,
            'voters', coalesce((
                SELECT jsonb_agg(
                    jsonb_build_object(
                        'voter_id', p.id,
                        'name', coalesce(nullif(trim(p.full_name), ''), 'Student'),
                        'avatar_url', case 
                            when left(coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture', ''), 8) = 'https://' 
                            then coalesce(u.raw_user_meta_data->>'avatar_url', u.raw_user_meta_data->>'picture') 
                            else null 
                        end,
                        'student_id', coalesce(p.student_id, ''),
                        'voted_at', pv.created_at
                    ) ORDER BY pv.created_at DESC
                )
                FROM classmate.poll_votes pv
                JOIN classmate.profiles p ON p.id = pv.voter_id
                LEFT JOIN auth.users u ON u.id = p.id
                WHERE pv.option_id = po.id
            ), '[]'::jsonb)
        ) ORDER BY po.position ASC
    ), '[]'::jsonb) INTO result
    FROM classmate.poll_options po
    WHERE po.poll_id = target_poll;

    RETURN result;
END;
$$;

GRANT EXECUTE ON FUNCTION classmate.get_poll_voters(uuid) TO authenticated, service_role, anon;

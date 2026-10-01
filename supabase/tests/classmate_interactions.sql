-- Run against staging. All fixture writes are rolled back.
begin;

insert into classmate.departments(id, name, code, is_active)
values ('dededede-dede-4ded-8ded-dededededede', 'Interaction fixture', 'interaction_fixture', true);
insert into classmate.batches(id, department_id, batch_number, academic_session)
values ('bebebebe-bebe-4beb-8beb-bebebebebebe',
        'dededede-dede-4ded-8ded-dededededede', 77, 77);
insert into classmate.notices(id, department_id, batch_id, author_id, title, body)
select 'fafafafa-fafa-4faf-8faf-fafafafafafa',
       'dededede-dede-4ded-8ded-dededededede',
       'bebebebe-bebe-4beb-8beb-bebebebebebe', id,
       'Interaction fixture', 'Will roll back'
from classmate.profiles where role = 'admin' limit 1;
insert into classmate.file_metadata
  (id, department_id, batch_id, uploaded_by, title, file_type,
   r2_object_key, mime_type, size_bytes, status, category)
select 'fefefefe-fefe-4fef-8fef-fefefefefefe',
       'dededede-dede-4ded-8ded-dededededede',
       'bebebebe-bebe-4beb-8beb-bebebebebebe', id,
       'Interaction fixture', 'pdf', 'fixture/interactions/rollback',
       'application/pdf', 100, 'active', 'notes'
from classmate.profiles where role = 'admin' limit 1;

select set_config('request.jwt.claim.sub',
  (select id::text from classmate.profiles where role='admin' limit 1), true);
set local role authenticated;

insert into classmate.notice_reactions(profile_id, notice_id, liked, pinned)
values (auth.uid(), 'fafafafa-fafa-4faf-8faf-fafafafafafa', true, true);
insert into classmate.notice_comments(notice_id, author_id, body)
values ('fafafafa-fafa-4faf-8faf-fafafafafafa', auth.uid(), 'Visible fixture comment');
insert into classmate.file_favorites(profile_id, file_id)
values (auth.uid(), 'fefefefe-fefe-4fef-8fef-fefefefefefe');

do $$
declare engagement record;
begin
  select * into engagement from classmate.notice_engagement(
    array['fafafafa-fafa-4faf-8faf-fafafafafafa'::uuid]);
  if engagement.like_count != 1 or engagement.comment_count != 1
    or engagement.is_liked is not true or engagement.is_pinned is not true then
    raise exception 'Notice engagement mismatch';
  end if;
  if (select count(*) from classmate.file_favorites) != 1 then
    raise exception 'Favorite not visible to owner';
  end if;
end;
$$;

rollback;

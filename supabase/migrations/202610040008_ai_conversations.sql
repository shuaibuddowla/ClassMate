begin;
create table classmate.ai_conversations (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references classmate.profiles(id) on delete cascade,
 batch_id uuid not null references classmate.batches(id) on delete cascade, title text not null default 'New chat',
 owner_only boolean not null default false, created_at timestamptz not null default now(),updated_at timestamptz not null default now()
);
create index ai_conversation_user_batch on classmate.ai_conversations(user_id,batch_id,updated_at desc,id);
create table classmate.ai_messages (
 id bigint generated always as identity primary key, conversation_id uuid not null references classmate.ai_conversations(id) on delete cascade,
 request_id uuid not null references classmate.ai_requests(id) on delete cascade, role text not null check(role in ('user','assistant')),
 content text not null check(length(content)<=16000),created_at timestamptz not null default now(),unique(request_id,role)
);
create index ai_message_conversation_page on classmate.ai_messages(conversation_id,id desc);
alter table classmate.ai_conversations enable row level security;
alter table classmate.ai_messages enable row level security;
create policy own_ai_conversation on classmate.ai_conversations for select to authenticated using(
 user_id=auth.uid() and classmate.is_active() and classmate.can_read_batch(batch_id) and (not owner_only or classmate.is_owner()));
create policy own_ai_message on classmate.ai_messages for select to authenticated using(exists(
 select 1 from classmate.ai_conversations c where c.id=conversation_id));
revoke all on classmate.ai_conversations,classmate.ai_messages from public,anon,authenticated;
grant select on classmate.ai_conversations,classmate.ai_messages to authenticated;

create function classmate.manage_ai_conversation(target_id uuid,target_batch uuid,operation text,target_title text default 'New chat') returns jsonb
language plpgsql security definer set search_path='' as $$
declare c classmate.ai_conversations;
begin
 if not classmate.is_active() or not classmate.can_read_batch(target_batch) then raise exception 'Chat access denied' using errcode='42501'; end if;
 if operation='create' then
  insert into classmate.ai_conversations(id,user_id,batch_id,title) values(target_id,auth.uid(),target_batch,left(trim(target_title),100)) on conflict do nothing;
 end if;
 select * into c from classmate.ai_conversations where id=target_id and user_id=auth.uid() and batch_id=target_batch for update;
 if not found or (c.owner_only and not classmate.is_owner()) then raise exception 'Chat access denied' using errcode='42501'; end if;
 if operation='rename' then
  if nullif(trim(target_title),'') is null then raise exception 'Enter a chat title'; end if;
  update classmate.ai_conversations set title=left(trim(target_title),100),updated_at=now() where id=c.id returning * into c;
 elsif operation='delete' then
  delete from classmate.ai_conversations where id=c.id;
 elsif operation<>'create' then raise exception 'Unknown chat operation'; end if;
 return to_jsonb(c);
end $$;

create function classmate.ai_message_page(target_conversation uuid,before_id bigint default null) returns jsonb
language sql stable security invoker set search_path='' as $$
 select coalesce(jsonb_agg(to_jsonb(page) order by page.id),'[]') from (
 select m.*,r.status,r.plan from classmate.ai_messages m left join classmate.ai_requests r on r.id=m.request_id
 where m.conversation_id=target_conversation and (before_id is null or m.id<before_id) order by m.id desc limit 50) page;
$$;

create function classmate.ai_conversation_page(target_batch uuid,before_at timestamptz default null,before_uuid uuid default null) returns setof classmate.ai_conversations
language sql stable security invoker set search_path='' as $$
 select * from classmate.ai_conversations where batch_id=target_batch and (before_at is null or (updated_at,id)<(before_at,before_uuid))
 order by updated_at desc,id desc limit 30;
$$;

-- Only the authenticated caller can append their own user turn. Assistant replies
-- come exclusively from the service-only request completion path below.
create function classmate.attach_ai_chat_turn(target_request uuid,target_conversation uuid,target_text text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare c classmate.ai_conversations; r classmate.ai_requests; existing classmate.ai_messages;
begin
 select * into c from classmate.ai_conversations where id=target_conversation and user_id=auth.uid() for update;
 if not found or not classmate.is_active() or not classmate.can_read_batch(c.batch_id) or (c.owner_only and not classmate.is_owner()) then
  raise exception 'Chat access denied' using errcode='42501'; end if;
 select * into r from classmate.ai_requests where id=target_request and user_id=auth.uid() and batch_id=c.batch_id and mode='agent';
 if not found or nullif(trim(target_text),'') is null or length(target_text)>8000 then raise exception 'Chat request denied' using errcode='42501'; end if;
 select * into existing from classmate.ai_messages where request_id=r.id and role='user';
 if found and (existing.conversation_id<>c.id or existing.content<>target_text) then raise exception 'Chat request conflict' using errcode='42501'; end if;
 if exists(select 1 from classmate.ai_messages m join classmate.ai_requests q on q.id=m.request_id
  where m.conversation_id=c.id and m.role='user' and q.id<>r.id and q.status='generating' and q.updated_at>now()-interval '90 seconds') then
  raise exception 'Wait for the current reply before sending another message'; end if;
 insert into classmate.ai_messages(conversation_id,request_id,role,content) values(c.id,r.id,'user',target_text) on conflict(request_id,role) do nothing;
 update classmate.ai_conversations set title=case when title='New chat' then left(target_text,80) else title end,
  owner_only=owner_only or classmate.is_owner(),updated_at=now() where id=c.id;
 return (select coalesce(jsonb_agg(to_jsonb(h) order by h.id),'[]') from (
  select m.id,m.role,m.content as text from classmate.ai_messages m where m.conversation_id=c.id and m.request_id<>r.id order by m.id desc limit 8) h);
end $$;

create function classmate.persist_ai_chat_reply() returns trigger language plpgsql security definer set search_path='' as $$
declare conv uuid;
begin
 select conversation_id into conv from classmate.ai_messages where request_id=new.id and role='user';
 if conv is not null and new.mode='agent' and new.status in ('ready','complete') and new.plan->>'message' is not null then
  insert into classmate.ai_messages(conversation_id,request_id,role,content) values(conv,new.id,'assistant',left(new.plan->>'message',16000))
   on conflict(request_id,role) do nothing;
  update classmate.ai_conversations set updated_at=now(),owner_only=owner_only or coalesce((new.plan->>'owner_only')::boolean,false) where id=conv;
 end if;
 return new;
end $$;
create trigger persist_ai_chat_reply after update of status,plan on classmate.ai_requests for each row execute function classmate.persist_ai_chat_reply();
revoke all on function classmate.manage_ai_conversation(uuid,uuid,text,text),classmate.ai_message_page(uuid,bigint),classmate.ai_conversation_page(uuid,timestamptz,uuid),classmate.attach_ai_chat_turn(uuid,uuid,text),classmate.persist_ai_chat_reply() from public,anon;
grant execute on function classmate.manage_ai_conversation(uuid,uuid,text,text),classmate.ai_message_page(uuid,bigint),classmate.ai_conversation_page(uuid,timestamptz,uuid),classmate.attach_ai_chat_turn(uuid,uuid,text) to authenticated;
notify pgrst,'reload schema';
commit;

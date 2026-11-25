create or replace function public.jamline_guard_updates()
returns trigger language plpgsql security definer set search_path = public
as $$
begin
  if (new.current_track_id is distinct from old.current_track_id or new.position_ms is distinct from old.position_ms or new.is_playing is distinct from old.is_playing)
     and not public.jamline_can_control(old.id) then
    raise exception 'Only the host or elected co-host can control Jamline playback';
  end if;
  if new.host_id is distinct from old.host_id
     and auth.uid() is not null
     and current_setting('jamusic.allow_host_transfer', true) is distinct from 'on' then
    raise exception 'Host changes must use the Jamline handoff flow';
  end if;
  new.updated_at := now();
  return new;
end $$;

create or replace function public.leave_jamline(p_room_id uuid)
returns void language plpgsql security definer set search_path = public
as $$
declare
  actor uuid := auth.uid();
  actor_role text;
  next_host uuid;
begin
  if actor is null then raise exception 'Sign in to leave a Jamline'; end if;
  select m.role into actor_role from public.jamline_members m where m.jamline_id = p_room_id and m.user_id = actor for update;
  if actor_role is null then raise exception 'You are not a member of this Jamline'; end if;
  if actor_role = 'host' then
    select m.user_id into next_host from public.jamline_members m
      where m.jamline_id = p_room_id and m.role = 'cohost' and m.user_id <> actor for update;
    if next_host is null then raise exception 'Elect a co-host before leaving so ownership can be handed over'; end if;
    update public.jamline_members set role = 'member' where jamline_id = p_room_id and user_id = actor;
    update public.jamline_members set role = 'host' where jamline_id = p_room_id and user_id = next_host;
    perform set_config('jamusic.allow_host_transfer', 'on', true);
    update public.jamlines set host_id = next_host where id = p_room_id;
  end if;
  delete from public.jamline_members where jamline_id = p_room_id and user_id = actor;
end $$;
grant execute on function public.leave_jamline(uuid) to authenticated;

create or replace function public.kick_jamline_member(p_room_id uuid, p_user_id uuid)
returns void language plpgsql security definer set search_path = public
as $$
begin
  if auth.uid() is null or not exists (select 1 from public.jamlines j where j.id = p_room_id and j.host_id = auth.uid()) then
    raise exception 'Only this Jamline host can remove listeners';
  end if;
  if p_user_id = auth.uid() then raise exception 'Use Leave Jamline to transfer or leave ownership'; end if;
  delete from public.jamline_members where jamline_id = p_room_id and user_id = p_user_id and role <> 'host';
  if not found then raise exception 'Listener not found in this Jamline'; end if;
end $$;
grant execute on function public.kick_jamline_member(uuid,uuid) to authenticated;

create table if not exists public.jamline_messages (
  id uuid primary key default gen_random_uuid(),
  jamline_id uuid not null references public.jamlines(id) on delete cascade,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  body text not null check (char_length(btrim(body)) between 1 and 1000),
  created_at timestamptz not null default now()
);
create index if not exists jamline_messages_room_created_idx on public.jamline_messages(jamline_id, created_at desc);
grant select, insert on public.jamline_messages to authenticated;
alter table public.jamline_messages enable row level security;
drop policy if exists "Jamline members read messages" on public.jamline_messages;
create policy "Jamline members read messages" on public.jamline_messages for select to authenticated using (public.jamline_is_member(jamline_id));
drop policy if exists "Jamline members send messages as themselves" on public.jamline_messages;
create policy "Jamline members send messages as themselves" on public.jamline_messages for insert to authenticated with check (auth.uid() = sender_id and public.jamline_is_member(jamline_id));

notify pgrst, 'reload schema';

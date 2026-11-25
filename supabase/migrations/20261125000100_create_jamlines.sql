create table if not exists public.jamlines (
  id uuid primary key default gen_random_uuid(),
  name text not null default 'Jamline' check (char_length(name) between 1 and 60),
  host_id uuid not null references public.profiles(id) on delete cascade,
  current_track_id uuid references public.tracks(id) on delete set null,
  position_ms bigint not null default 0 check (position_ms >= 0),
  is_playing boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.jamline_members (
  jamline_id uuid not null references public.jamlines(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null default 'member' check (role in ('host', 'cohost', 'member')),
  joined_at timestamptz not null default now(),
  primary key (jamline_id, user_id)
);

create table if not exists public.jamline_invites (
  id uuid primary key default gen_random_uuid(),
  jamline_id uuid not null references public.jamlines(id) on delete cascade,
  inviter_id uuid not null references public.profiles(id) on delete cascade,
  invitee_id uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'declined')),
  created_at timestamptz not null default now(),
  unique (jamline_id, invitee_id),
  check (inviter_id <> invitee_id)
);

create index if not exists jamline_members_user_idx on public.jamline_members(user_id);
create unique index if not exists jamline_one_cohost_per_room_idx on public.jamline_members(jamline_id) where role = 'cohost';
create index if not exists jamline_invites_invitee_idx on public.jamline_invites(invitee_id, status);

grant select, insert, update on public.jamlines to authenticated;
grant select, insert on public.jamline_members to authenticated;
grant update (role) on public.jamline_members to authenticated;
grant select, insert, update on public.jamline_invites to authenticated;

alter table public.jamlines enable row level security;
alter table public.jamline_members enable row level security;
alter table public.jamline_invites enable row level security;

create or replace function public.jamline_is_member(line_id uuid)
returns boolean language sql stable security definer set search_path = public
as $$ select exists (select 1 from jamline_members m where m.jamline_id = line_id and m.user_id = auth.uid()) $$;
create or replace function public.jamline_is_host(line_id uuid)
returns boolean language sql stable security definer set search_path = public
as $$ select exists (select 1 from jamlines j where j.id = line_id and j.host_id = auth.uid()) $$;
create or replace function public.jamline_can_control(line_id uuid)
returns boolean language sql stable security definer set search_path = public
as $$ select exists (select 1 from jamline_members m where m.jamline_id = line_id and m.user_id = auth.uid() and m.role in ('host', 'cohost')) $$;
grant execute on function public.jamline_is_member(uuid), public.jamline_is_host(uuid), public.jamline_can_control(uuid) to authenticated;

create or replace function public.jamline_add_host()
returns trigger language plpgsql security definer set search_path = public
as $$ begin insert into public.jamline_members(jamline_id,user_id,role) values(new.id,new.host_id,'host'); return new; end $$;
drop trigger if exists jamline_add_host_after_insert on public.jamlines;
create trigger jamline_add_host_after_insert after insert on public.jamlines for each row execute function public.jamline_add_host();

create or replace function public.jamline_guard_updates()
returns trigger language plpgsql security definer set search_path = public
as $$
begin
  if (new.current_track_id is distinct from old.current_track_id or new.position_ms is distinct from old.position_ms or new.is_playing is distinct from old.is_playing)
     and not public.jamline_can_control(old.id) then
    raise exception 'Only the host or elected co-host can control Jamline playback';
  end if;
  if new.host_id is distinct from old.host_id and auth.uid() is not null then raise exception 'The host cannot be changed'; end if;
  new.updated_at := now();
  return new;
end $$;
drop trigger if exists jamline_guard_update on public.jamlines;
create trigger jamline_guard_update before update on public.jamlines for each row execute function public.jamline_guard_updates();

drop policy if exists "Jamline members read their rooms" on public.jamlines;
create policy "Jamline members read their rooms" on public.jamlines for select to authenticated using (public.jamline_is_member(id) or auth.uid() = host_id);
drop policy if exists "Jamline invitees read invited rooms" on public.jamlines;
create policy "Jamline invitees read invited rooms" on public.jamlines for select to authenticated using (exists (select 1 from public.jamline_invites i where i.jamline_id = public.jamlines.id and i.invitee_id = auth.uid() and i.status = 'pending'));
drop policy if exists "Users create their own jamlines" on public.jamlines;
create policy "Users create their own jamlines" on public.jamlines for insert to authenticated with check (auth.uid() = host_id);
drop policy if exists "Jamline controllers update room state" on public.jamlines;
create policy "Jamline controllers update room state" on public.jamlines for update to authenticated using (public.jamline_can_control(id)) with check (public.jamline_can_control(id));

drop policy if exists "Members read their jamline roster" on public.jamline_members;
create policy "Members read their jamline roster" on public.jamline_members for select to authenticated using (public.jamline_is_member(jamline_id));
drop policy if exists "Hosts elect cohosts" on public.jamline_members;
create policy "Hosts elect cohosts" on public.jamline_members for update to authenticated using (public.jamline_is_host(jamline_id) and role <> 'host') with check (public.jamline_is_host(jamline_id) and role in ('member','cohost'));
drop policy if exists "Invitees join after accepting" on public.jamline_members;
create policy "Invitees join after accepting" on public.jamline_members for insert to authenticated with check (
  auth.uid() = user_id and role = 'member' and exists (select 1 from public.jamline_invites i where i.jamline_id = jamline_members.jamline_id and i.invitee_id = auth.uid() and i.status = 'accepted')
);

drop policy if exists "Room participants and invitees read invites" on public.jamline_invites;
create policy "Room participants and invitees read invites" on public.jamline_invites for select to authenticated using (
  auth.uid() = inviter_id or auth.uid() = invitee_id or public.jamline_is_member(jamline_id)
);
drop policy if exists "Controllers invite artists" on public.jamline_invites;
create policy "Controllers invite artists" on public.jamline_invites for insert to authenticated with check (
  auth.uid() = inviter_id and public.jamline_can_control(jamline_id) and invitee_id <> auth.uid()
);
drop policy if exists "Invitees accept or decline" on public.jamline_invites;
create policy "Invitees accept or decline" on public.jamline_invites for update to authenticated using (auth.uid() = invitee_id and status = 'pending') with check (auth.uid() = invitee_id and status in ('accepted','declined'));
revoke update on public.jamline_invites from authenticated;
grant update (status) on public.jamline_invites to authenticated;

create or replace function public.create_jamline(p_name text default 'Jamline')
returns uuid language plpgsql security definer set search_path = public
as $$
declare new_id uuid;
begin
  if auth.uid() is null then raise exception 'Sign in to create a Jamline'; end if;
  insert into public.jamlines(name, host_id)
  values (coalesce(nullif(trim(p_name), ''), 'Jamline'), auth.uid())
  returning id into new_id;
  return new_id;
end $$;
grant execute on function public.create_jamline(text) to authenticated;

notify pgrst, 'reload schema';

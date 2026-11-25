-- JaMusic Supabase schema and storage setup.
-- Run once in Supabase Dashboard → SQL Editor → New query.

create extension if not exists pgcrypto;

grant usage on schema public to anon, authenticated;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text not null unique check (username ~ '^[a-z0-9_]{3,20}$'),
  display_name text not null default 'Artist' check (char_length(display_name) <= 40),
  pronouns text check (char_length(pronouns) <= 24),
  bio text check (char_length(bio) <= 280),
  avatar_url text,
  banner_url text,
  created_at timestamptz not null default now()
);

create table if not exists public.tracks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  title text not null check (char_length(title) between 1 and 120),
  album text,
  genre text,
  lyrics text,
  audio_url text not null,
  audio_path text not null,
  cover_url text not null,
  cover_path text not null,
  duration integer not null default 0 check (duration >= 0),
  created_at timestamptz not null default now()
);

create table if not exists public.likes (
  user_id uuid not null references public.profiles(id) on delete cascade,
  track_id uuid not null references public.tracks(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, track_id)
);

create table if not exists public.follows (
  follower_id uuid not null references public.profiles(id) on delete cascade,
  following_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (follower_id, following_id),
  constraint follows_no_self_follow check (follower_id <> following_id)
);

grant select on public.profiles to anon, authenticated;
grant insert, update on public.profiles to authenticated;
grant select on public.tracks to anon, authenticated;
grant insert, delete on public.tracks to authenticated;
revoke update on public.tracks from public, anon, authenticated;
grant update (lyrics) on public.tracks to authenticated;
grant select, insert, delete on public.likes to authenticated;
grant select on public.follows to anon, authenticated;
grant insert, delete on public.follows to authenticated;

create index if not exists tracks_created_at_idx on public.tracks (created_at desc);
create index if not exists tracks_user_id_idx on public.tracks (user_id);
create index if not exists likes_track_id_idx on public.likes (track_id);
create index if not exists follows_following_id_idx on public.follows (following_id);

alter table public.profiles enable row level security;
alter table public.tracks enable row level security;
alter table public.likes enable row level security;
alter table public.follows enable row level security;

drop policy if exists "Profiles are public" on public.profiles;
create policy "Profiles are public" on public.profiles for select using (true);
drop policy if exists "Users create their own profile" on public.profiles;
create policy "Users create their own profile" on public.profiles for insert to authenticated with check (auth.uid() = id);
drop policy if exists "Users update their own profile" on public.profiles;
create policy "Users update their own profile" on public.profiles for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);

drop policy if exists "Tracks are public" on public.tracks;
create policy "Tracks are public" on public.tracks for select using (true);
drop policy if exists "Signed-in artists publish tracks" on public.tracks;
create policy "Signed-in artists publish tracks" on public.tracks for insert to authenticated with check (auth.uid() = user_id);
drop policy if exists "Artists update their own tracks" on public.tracks;
create policy "Artists update their own tracks" on public.tracks for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
drop policy if exists "Artists delete their own tracks" on public.tracks;
create policy "Artists delete their own tracks" on public.tracks for delete to authenticated using (auth.uid() = user_id);

drop policy if exists "Users read their own likes" on public.likes;
create policy "Users read their own likes" on public.likes for select to authenticated using (auth.uid() = user_id);
drop policy if exists "Users like tracks as themselves" on public.likes;
create policy "Users like tracks as themselves" on public.likes for insert to authenticated with check (auth.uid() = user_id);
drop policy if exists "Users remove their own likes" on public.likes;
create policy "Users remove their own likes" on public.likes for delete to authenticated using (auth.uid() = user_id);

drop policy if exists "Follows are publicly readable" on public.follows;
create policy "Follows are publicly readable" on public.follows for select using (true);
drop policy if exists "Users follow as themselves" on public.follows;
create policy "Users follow as themselves" on public.follows for insert to authenticated with check (auth.uid() = follower_id);
drop policy if exists "Users unfollow as themselves" on public.follows;
create policy "Users unfollow as themselves" on public.follows for delete to authenticated using (auth.uid() = follower_id);

insert into storage.buckets (id, name, public, file_size_limit)
values
  ('audio', 'audio', true, 52428800),
  ('artwork', 'artwork', true, 5242880),
  ('profiles', 'profiles', true, 5242880)
on conflict (id) do update set public = excluded.public, file_size_limit = excluded.file_size_limit;

drop policy if exists "JaMusic assets are publicly readable" on storage.objects;
create policy "JaMusic assets are publicly readable" on storage.objects for select using (bucket_id in ('audio', 'artwork', 'profiles'));
drop policy if exists "Users upload JaMusic assets into own folder" on storage.objects;
create policy "Users upload JaMusic assets into own folder" on storage.objects for insert to authenticated with check (bucket_id in ('audio', 'artwork', 'profiles') and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists "Users update JaMusic assets in own folder" on storage.objects;
create policy "Users update JaMusic assets in own folder" on storage.objects for update to authenticated using (bucket_id in ('audio', 'artwork', 'profiles') and (storage.foldername(name))[1] = auth.uid()::text) with check (bucket_id in ('audio', 'artwork', 'profiles') and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists "Users delete JaMusic assets in own folder" on storage.objects;
create policy "Users delete JaMusic assets in own folder" on storage.objects for delete to authenticated using (bucket_id in ('audio', 'artwork', 'profiles') and (storage.foldername(name))[1] = auth.uid()::text);

-- Force PostgREST to pick up freshly-created/updated tables and relationships.
notify pgrst, 'reload schema';
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

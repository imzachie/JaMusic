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

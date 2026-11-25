create or replace function public.leave_jamline(p_room_id uuid)
returns void language plpgsql security definer set search_path = public
as $$
declare
  actor uuid := auth.uid();
  actor_role text;
  next_host uuid;
begin
  if actor is null then raise exception 'Sign in to leave a Jamline'; end if;
  select m.role into actor_role
    from public.jamline_members m
    where m.jamline_id = p_room_id and m.user_id = actor
    for update;
  if actor_role is null then raise exception 'You are not a member of this Jamline'; end if;

  if actor_role = 'host' then
    -- Prefer the elected co-host; otherwise hand the room to the longest-standing listener.
    select m.user_id into next_host
      from public.jamline_members m
      where m.jamline_id = p_room_id and m.user_id <> actor and m.role = 'cohost'
      for update;
    if next_host is null then
      select m.user_id into next_host
        from public.jamline_members m
        where m.jamline_id = p_room_id and m.user_id <> actor
        order by m.joined_at asc
        limit 1
        for update;
    end if;

    if next_host is null then
      -- A solo host can leave; the now-empty room is closed and related rows cascade away.
      delete from public.jamlines where id = p_room_id;
      return;
    end if;

    update public.jamline_members set role = 'member'
      where jamline_id = p_room_id and user_id = actor;
    update public.jamline_members set role = 'host'
      where jamline_id = p_room_id and user_id = next_host;
    perform set_config('jamusic.allow_host_transfer', 'on', true);
    update public.jamlines set host_id = next_host where id = p_room_id;
  end if;

  delete from public.jamline_members where jamline_id = p_room_id and user_id = actor;
end $$;
grant execute on function public.leave_jamline(uuid) to authenticated;

notify pgrst, 'reload schema';

create or replace function public.invite_to_jamline(p_room_id uuid, p_username text)
returns uuid language plpgsql security definer set search_path = public
as $$
declare
  actor uuid := auth.uid();
  target uuid;
  invitation_id uuid;
begin
  if actor is null then raise exception 'Sign in to invite artists'; end if;
  if not public.jamline_can_control(p_room_id) then raise exception 'Only the host or co-host can invite artists'; end if;
  select p.id into target from public.profiles p
    where lower(p.username) = lower(regexp_replace(trim(p_username), '^@', ''))
    limit 1;
  if target is null then raise exception 'Artist not found. Check their JaMusic username.'; end if;
  if target = actor then raise exception 'You cannot invite yourself'; end if;

  insert into public.jamline_invites(jamline_id, inviter_id, invitee_id, status, created_at)
    values (p_room_id, actor, target, 'pending', now())
    on conflict (jamline_id, invitee_id) do update
      set inviter_id = excluded.inviter_id, status = 'pending', created_at = now()
    returning id into invitation_id;
  return invitation_id;
end $$;
grant execute on function public.invite_to_jamline(uuid, text) to authenticated;
notify pgrst, 'reload schema';

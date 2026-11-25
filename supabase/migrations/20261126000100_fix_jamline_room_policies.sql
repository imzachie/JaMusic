drop policy if exists "Jamline members read their rooms" on public.jamlines;
create policy "Jamline members read their rooms" on public.jamlines
for select to authenticated
using (public.jamline_is_member(public.jamlines.id) or auth.uid() = public.jamlines.host_id);

drop policy if exists "Jamline invitees read invited rooms" on public.jamlines;
create policy "Jamline invitees read invited rooms" on public.jamlines
for select to authenticated
using (exists (
  select 1 from public.jamline_invites i
  where i.jamline_id = public.jamlines.id
    and i.invitee_id = auth.uid()
    and i.status = 'pending'
));

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

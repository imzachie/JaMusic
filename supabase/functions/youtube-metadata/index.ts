import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, apikey, x-client-info, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status,
  headers: { ...corsHeaders, 'Content-Type': 'application/json' },
});

function parseVideoId(value: string): string | null {
  try {
    const url = new URL(value);
    if (!['youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtu.be', 'www.youtu.be'].includes(url.hostname)) return null;
    const id = url.hostname.endsWith('youtu.be')
      ? url.pathname.split('/').filter(Boolean)[0]
      : url.searchParams.get('v') || url.pathname.match(/^\/(?:shorts|embed|live)\/([\w-]+)/)?.[1];
    return id && /^[\w-]{11}$/.test(id) ? id : null;
  } catch { return null; }
}
function durationSeconds(iso: string): number {
  const m = iso.match(/^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/);
  return m ? Number(m[1] || 0) * 3600 + Number(m[2] || 0) * 60 + Number(m[3] || 0) : 0;
}

Deno.serve(async request => {
  if (request.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });
  if (request.method !== 'POST') return json({ error: 'Use POST.' }, 405);
  const url = Deno.env.get('SUPABASE_URL');
  const anon = Deno.env.get('SUPABASE_ANON_KEY');
  const apiKey = Deno.env.get('YOUTUBE_API_KEY');
  const authorization = request.headers.get('Authorization');
  if (!url || !anon) return json({ error: 'Supabase function configuration is incomplete.' }, 500);
  if (!apiKey) return json({ error: 'YouTube metadata is not configured. Add YOUTUBE_API_KEY to Supabase Edge Function secrets.' }, 503);
  if (!authorization?.startsWith('Bearer ')) return json({ error: 'Sign in to use YouTube import.' }, 401);
  const auth = createClient(url, anon, { global: { headers: { Authorization: authorization } } });
  const { data: { user }, error: authError } = await auth.auth.getUser();
  if (authError || !user) return json({ error: 'Your session expired. Sign in again.' }, 401);
  const { data: profile, error: profileError } = await auth.from('profiles').select('username').eq('id', user.id).maybeSingle();
  if (profileError) return json({ error: 'Could not verify the JaMusic owner profile.' }, 503);
  const isOwner = user.email?.trim().toLowerCase() === 'aidilzafri2013@gmail.com' &&
    profile?.username?.trim().replace(/^@/, '').toLowerCase() === 'imzachie';
  if (!isOwner) return json({ error: 'YouTube import is restricted to the JaMusic owner account.' }, 403);
  let body: { video_url?: string; store_thumbnail?: boolean };
  try { body = await request.json(); } catch { return json({ error: 'Send a YouTube URL.' }, 400); }
  const id = typeof body.video_url === 'string' ? parseVideoId(body.video_url) : null;
  if (!id) return json({ error: 'Enter a valid YouTube video URL.' }, 400);

  const endpoint = new URL('https://www.googleapis.com/youtube/v3/videos');
  endpoint.searchParams.set('part', 'snippet,contentDetails');
  endpoint.searchParams.set('id', id);
  endpoint.searchParams.set('key', apiKey);
  let response: Response;
  try { response = await fetch(endpoint); }
  catch (error) { return json({ error: `Could not reach YouTube Data API: ${error instanceof Error ? error.message : 'network error'}` }, 502); }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) return json({ error: result?.error?.message || `YouTube API returned HTTP ${response.status}.` }, 502);
  const video = result?.items?.[0];
  if (!video) return json({ error: 'That video is unavailable or cannot be imported.' }, 404);
  const snippet = video.snippet || {};
  const thumbs = snippet.thumbnails || {};
  const thumbnail = thumbs.maxres?.url || thumbs.standard?.url || thumbs.high?.url || thumbs.medium?.url || thumbs.default?.url;
  if (!thumbnail) return json({ error: 'This video has no available thumbnail.' }, 422);
  const metadata = {
    video_id: id,
    video_url: `https://www.youtube.com/watch?v=${id}`,
    title: String(snippet.title || 'Untitled').slice(0, 120),
    channel_title: String(snippet.channelTitle || '').slice(0, 100),
    thumbnail_url: thumbnail,
    duration_seconds: durationSeconds(String(video.contentDetails?.duration || '')),
  };
  if (!body.store_thumbnail) return json(metadata);

  const serviceRole = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  if (!serviceRole) return json({ error: 'Server-side artwork storage is not configured.' }, 500);
  let image: Response;
  try { image = await fetch(thumbnail); }
  catch (error) { return json({ error: `Could not fetch the official YouTube thumbnail: ${error instanceof Error ? error.message : 'network error'}` }, 502); }
  if (!image.ok) return json({ error: 'Could not fetch the official YouTube thumbnail.' }, 502);
  const contentType = image.headers.get('content-type')?.split(';')[0] || 'image/jpeg';
  if (!contentType.startsWith('image/')) return json({ error: 'YouTube returned an unsupported thumbnail.' }, 415);
  const extension = contentType === 'image/png' ? 'png' : contentType === 'image/webp' ? 'webp' : 'jpg';
  const coverPath = `${user.id}/yt-${id}-${crypto.randomUUID()}.${extension}`;
  const admin = createClient(url, serviceRole, { auth: { persistSession: false, autoRefreshToken: false } });
  const { error: uploadError } = await admin.storage.from('artwork').upload(coverPath, new Uint8Array(await image.arrayBuffer()), {
    contentType, cacheControl: '3600', upsert: false,
  });
  if (uploadError) return json({ error: `Could not save YouTube artwork: ${uploadError.message}` }, 502);
  const { data: publicUrl } = admin.storage.from('artwork').getPublicUrl(coverPath);
  return json({ ...metadata, cover_path: coverPath, cover_url: publicUrl.publicUrl });
});

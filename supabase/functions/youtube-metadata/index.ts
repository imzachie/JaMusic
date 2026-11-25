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
    const parts = url.pathname.split('/').filter(Boolean);
    let id = url.hostname.endsWith('youtu.be') ? parts[0] : url.searchParams.get('v');
    if (!id && ['shorts', 'embed', 'live'].includes(parts[0])) id = parts[1];
    return id && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
  } catch { return null; }
}
function durationSeconds(iso: string): number {
  const m = iso.match(/^PT(?:([0-9]+)H)?(?:([0-9]+)M)?(?:([0-9]+)S)?$/);
  return m ? Number(m[1] || 0) * 3600 + Number(m[2] || 0) * 60 + Number(m[3] || 0) : 0;
}

Deno.serve(async request => {
  if (request.method === 'OPTIONS') {
    const headers = new Headers(corsHeaders);
    const requested = request.headers.get('Access-Control-Request-Headers');
    if (requested) headers.set('Access-Control-Allow-Headers', requested);
    return new Response(null, { status: 204, headers });
  }
  if (request.method !== 'POST') return json({ error: 'Use POST.' }, 405);

  try {
    const supabaseUrl = Deno.env.get('SUPABASE_URL');
    const anonKey = Deno.env.get('SUPABASE_ANON_KEY');
    const youtubeKey = Deno.env.get('YOUTUBE_API_KEY');
    const authorization = request.headers.get('Authorization');
    if (!supabaseUrl || !anonKey) return json({ error: 'Supabase function configuration is incomplete.' }, 500);
    if (!youtubeKey) return json({ error: 'YouTube metadata is not configured.' }, 503);
    if (!authorization?.startsWith('Bearer ')) return json({ error: 'Sign in to use YouTube import.' }, 401);

    const userResponse = await fetch(`${supabaseUrl}/auth/v1/user`, {
      headers: { apikey: anonKey, Authorization: authorization },
    });
    if (!userResponse.ok) return json({ error: 'Your session expired. Sign in again.' }, 401);
    const user = await userResponse.json();
    if (!user?.id) return json({ error: 'Your session expired. Sign in again.' }, 401);

    const profileUrl = new URL(`${supabaseUrl}/rest/v1/profiles`);
    profileUrl.searchParams.set('select', 'username');
    profileUrl.searchParams.set('id', `eq.${user.id}`);
    profileUrl.searchParams.set('limit', '1');
    const profileResponse = await fetch(profileUrl, {
      headers: { apikey: anonKey, Authorization: authorization },
    });
    if (!profileResponse.ok) return json({ error: 'Could not verify the JaMusic owner profile.' }, 503);
    const profiles = await profileResponse.json();
    const username = profiles?.[0]?.username;
    const isOwner = user.email?.trim().toLowerCase() === 'aidilzafri2013@gmail.com' &&
      username?.trim().replace(/^@/, '').toLowerCase() === 'imzachie';
    if (!isOwner) return json({ error: 'YouTube import is restricted to the JaMusic owner account.' }, 403);

    let body: { video_url?: string; store_thumbnail?: boolean };
    try { body = await request.json(); } catch { return json({ error: 'Send a YouTube URL.' }, 400); }
    const id = typeof body.video_url === 'string' ? parseVideoId(body.video_url) : null;
    if (!id) return json({ error: 'Enter a valid YouTube video URL.' }, 400);

    const endpoint = new URL('https://www.googleapis.com/youtube/v3/videos');
    endpoint.searchParams.set('part', 'snippet,contentDetails');
    endpoint.searchParams.set('id', id);
    endpoint.searchParams.set('key', youtubeKey);
    const response = await fetch(endpoint);
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
    const image = await fetch(thumbnail);
    if (!image.ok) return json({ error: 'Could not fetch the official YouTube thumbnail.' }, 502);
    const contentType = image.headers.get('content-type')?.split(';')[0] || 'image/jpeg';
    if (!contentType.startsWith('image/')) return json({ error: 'YouTube returned an unsupported thumbnail.' }, 415);
    const extension = contentType === 'image/png' ? 'png' : contentType === 'image/webp' ? 'webp' : 'jpg';
    const coverPath = `${user.id}/yt-${id}-${crypto.randomUUID()}.${extension}`;
    const encodedPath = coverPath.split('/').map(encodeURIComponent).join('/');
    const uploadResponse = await fetch(`${supabaseUrl}/storage/v1/object/artwork/${encodedPath}`, {
      method: 'POST',
      headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}`, 'Content-Type': contentType, 'x-upsert': 'false' },
      body: await image.arrayBuffer(),
    });
    if (!uploadResponse.ok) {
      const detail = await uploadResponse.text().catch(() => '');
      return json({ error: `Could not save YouTube artwork: ${detail || `HTTP ${uploadResponse.status}`}` }, 502);
    }
    const coverUrl = `${supabaseUrl}/storage/v1/object/public/artwork/${encodedPath}`;
    return json({ ...metadata, cover_path: coverPath, cover_url: coverUrl });
  } catch (error) {
    console.error('youtube-metadata unexpected failure', error);
    return json({ error: `YouTube metadata request failed: ${error instanceof Error ? error.message : 'unexpected function error'}` }, 500);
  }
});

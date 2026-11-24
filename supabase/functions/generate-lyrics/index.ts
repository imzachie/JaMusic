import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, apikey, x-client-info, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { ...corsHeaders, 'Content-Type': 'application/json' },
});

Deno.serve(async (request: Request) => {
  if (request.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });
  if (request.method !== 'POST') return json({ error: 'Use POST.' }, 405);

  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  const anonKey = Deno.env.get('SUPABASE_ANON_KEY');
  const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
  const openAiKey = Deno.env.get('OPENAI_API_KEY');
  if (!supabaseUrl || !anonKey || !serviceRoleKey || !openAiKey) {
    return json({ error: 'The lyrics service is not configured. Set OPENAI_API_KEY in Supabase function secrets.' }, 500);
  }

  const authorization = request.headers.get('Authorization');
  if (!authorization?.startsWith('Bearer ')) return json({ error: 'Sign in before generating lyrics.' }, 401);
  const authClient = createClient(supabaseUrl, anonKey, { global: { headers: { Authorization: authorization } } });
  const { data: { user }, error: authError } = await authClient.auth.getUser();
  if (authError || !user) return json({ error: 'Your session expired. Sign in again.' }, 401);

  let audioPath: string;
  try {
    const body = await request.json();
    audioPath = typeof body.audio_path === 'string' ? body.audio_path : '';
  } catch { return json({ error: 'Missing uploaded audio path.' }, 400); }
  if (!audioPath || audioPath.startsWith('/') || audioPath.includes('..') || audioPath.split('/')[0] !== user.id) {
    return json({ error: 'You can only transcribe audio you uploaded.' }, 403);
  }

  const admin = createClient(supabaseUrl, serviceRoleKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: audio, error: downloadError } = await admin.storage.from('audio').download(audioPath);
  if (downloadError || !audio) return json({ error: `Could not read the uploaded audio: ${downloadError?.message || 'file not found'}` }, 404);
  if (audio.size > 25 * 1024 * 1024) return json({ error: 'Whisper supports audio files up to 25 MB. Choose manual lyrics or instrumental for larger files.' }, 413);

  const form = new FormData();
  const filename = audioPath.split('/').pop() || 'track.audio';
  form.set('file', new File([audio], filename, { type: audio.type || 'application/octet-stream' }));
  form.set('model', 'whisper-1');
  form.set('response_format', 'verbose_json');
  form.append('timestamp_granularities[]', 'segment');

  let response: Response;
  try {
    response = await fetch('https://api.openai.com/v1/audio/transcriptions', {
      method: 'POST',
      headers: { Authorization: `Bearer ${openAiKey}` },
      body: form,
    });
  } catch (error) {
    return json({ error: `Could not reach the transcription service: ${error instanceof Error ? error.message : 'network error'}` }, 502);
  }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    return json({ error: result?.error?.message || `Whisper returned HTTP ${response.status}.` }, 502);
  }

  const segments = Array.isArray(result.segments) ? result.segments : [];
  const lyrics = segments
    .filter((segment: { text?: unknown; start?: unknown }) => typeof segment.text === 'string' && segment.text.trim() && Number.isFinite(Number(segment.start)))
    .map((segment: { text: string; start: number }) => {
      const seconds = Math.max(0, Math.floor(segment.start));
      return `[${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}] ${segment.text.trim()}`;
    })
    .join('\n');
  if (!lyrics) return json({ error: 'Whisper returned no speech. Try Manual lyrics typing or choose Instrumental.' }, 422);
  return json({ lyrics });
});

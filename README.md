# JaMusic

JaMusic is the original music-sharing website served directly at `/` by Vite. The complete interface—playback, track discovery, artist profiles, uploads, likes, and account flows—lives on the root page; there is no iframe or secondary app URL.

## Supabase setup

Run [`setup.sql`](./setup.sql) in Supabase Dashboard → **SQL Editor** to create the `profiles`, `tracks`, and `likes` tables, access policies, and the `audio`, `artwork`, and `profiles` storage buckets.

## Local development

```sh
npm install
cp .env.example .env
```

Set the Supabase Project URL and publishable key in `.env`:

```dotenv
VITE_SUPABASE_URL=https://your-project-ref.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

Then run `npm run dev`. The app opens at the Vite root URL. `.env` is ignored by Git. Only use a publishable key in browser code; never use the service-role secret.

## Cloudflare Pages

- Build command: `npm run build`
- Build output directory: `dist`
- Build environment variables: `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`

Vite builds the app directly to `dist/index.html`. Google sign-in returns to the site root (`/`).

## Whisper auto-generated lyrics

Auto transcription runs server-side so the OpenAI credential is never exposed in the browser. Deploy the included Supabase Edge Function and set the key as a Supabase secret:

```sh
supabase functions deploy generate-lyrics
supabase secrets set OPENAI_API_KEY=your_openai_api_key
```

The function downloads the just-uploaded audio from the user's own `audio` storage folder, asks Whisper for timestamped segments, and returns `[minutes:seconds] lyric` lines. Audio files for auto transcription must be 25 MB or smaller. Manual lyrics and the instrumental option don't call the transcription service. Never put the OpenAI key in the site's Vite environment variables.

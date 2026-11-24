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

## Groq Whisper auto-generated lyrics

Automatic transcription uses Groq's `whisper-large-v3` endpoint through the Supabase Edge Function at `supabase/functions/generate-lyrics`. The Groq key is kept server-side as a Supabase Function Secret; it is never included in the website bundle. Create a Groq API key in your Groq Console, then deploy the function and save the key:

```sh
npx supabase login
npx supabase link --project-ref YOUR_SUPABASE_PROJECT_REF
npx supabase functions deploy generate-lyrics
npx supabase secrets set GROQ_API_KEY=YOUR_GROQ_API_KEY
```

Replace both placeholders with your actual values. Groq offers API access subject to its current account limits and usage policies; API key access is required. Audio files must be 25 MB or smaller for auto transcription. Manual and instrumental lyric choices do not call Groq.

Auto-generated and manual lyrics are stored as `[MM:SS.hh]` lines (for example `[01:38.12] The lyric`). In the player, each timestamped line is synchronized to playback and can be clicked to seek to that point. Track owners can edit only the lyrics from the track row or fullscreen player. The updated `setup.sql` revokes table-wide track updates and grants owners permission to update only the `lyrics` column; rerun it in Supabase to apply that database-level restriction. Transcription can still mishear singing, so review the generated text.

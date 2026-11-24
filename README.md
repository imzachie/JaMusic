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

## Free Whisper auto-generated lyrics

Automatic transcription uses the open `onnx-community/whisper-small.en` model via Transformers.js and runs locally in the user's browser. It does not call a paid inference API, need an API key, or send the audio to a transcription service. Small is larger and generally more accurate than Tiny, but requires a substantial first-time model download and more CPU/memory; the browser caches it afterward. It is English-focused. Speech recognition can still mishear singing, so review auto-generated lyrics; use Manual lyrics typing when exact words matter. Automatic mode is limited to audio files of 25 MB; manual and instrumental modes work without the model download.

Auto-generated and manual lyrics are stored with `[minutes:seconds]` timestamps. In the player, each timestamped line is synchronized to playback and can be clicked to seek to that point.

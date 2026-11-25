# JaMusic

## Native Android app

A native Kotlin + Jetpack Compose Android app is under `android/app` (no WebView). It uses Material 3 components with an expressive dark visual system, animated lyric timing, and Media3 playback. It loads real recent uploads from `public.tracks` and displays profile names. It does not insert sample/demo songs when configuration is missing; it shows a clear connection error instead.

Configure `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` in `local.properties` or as Gradle properties/environment variables. Build the **release** APK (not debug) with:

```sh
gradle :android:app:assembleRelease
```

The output is `android/app/build/outputs/apk/release/app-release.apk`. The release variant is signed with the standard debug certificate for sandbox installation/testing; it is not a Play Store signing key. GitHub Actions also builds and uploads this release APK as the `JaMusic-app-release` artifact.

The Android app provides public-track browsing, native playback controls, and line/word-synced lyrics. Email/password sign-in and account creation use an in-app Material prompt; Google sign-in begins from the same prompt and returns through `jamusic://auth-callback`. Google sign-in is enabled for this Supabase project, and the native callback URI is on its redirect allowlist. Track upload and lyrics editing are still available in the web app and have not yet been ported to Android.

The Android app and `.env.example` include the project's public Supabase URL and publishable key so release builds can query the real database without CI-only secrets. GitHub Actions variables/secrets named `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` (or `VITE_`-prefixed variants) can override these defaults. Publishable keys are designed for client apps; never put a service-role key in the APK.

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
npx supabase functions deploy youtube-metadata
npx supabase secrets set GROQ_API_KEY=YOUR_GROQ_API_KEY YOUTUBE_API_KEY=YOUR_YOUTUBE_DATA_API_KEY
```

Replace the placeholders with your actual values. Keep both API keys in Supabase Edge Function secrets; never put them in browser code or the APK. A YouTube Data API key is required for the owner-only YouTube metadata import. That import uses YouTube's official API for title, thumbnail, and duration, then requires a separate audio file that the uploader owns or is licensed to distribute—JaMusic does not extract audio from YouTube. Groq offers API access subject to its current account limits and usage policies; API key access is required. Audio files must be 25 MB or smaller for auto transcription. Manual and instrumental lyric choices do not call Groq.

Auto-generated and manually entered lyrics are stored as `[MM:SS.hh]` lines (for example `[01:38.12] The lyric`). Uploaders can also import `.lrc` or `.yaml`/`.yml` files from the Manual lyrics typing option. Standard LRC and typed lines sync by line; enhanced LRC word cues and YAML word timestamps sync word by word with a subtle animation. YAML accepts a top-level `lines` list; each line may have `start_ms` (milliseconds) or `time` in seconds / `MM:SS.hh`, and either `text` or a `words` list. Word entries accept `start_ms` (milliseconds) or `time` in seconds plus `text`. Existing metadata fields such as `version`, `metadata`, `duration_ms`, and `plain` are ignored. In the player, lines can be clicked to seek to their start. Track owners can edit only the lyrics from the track row or fullscreen player. The updated `setup.sql` revokes table-wide track updates and grants owners permission to update only the `lyrics` column; rerun it in Supabase to apply that database-level restriction. Transcription can still mishear singing, so review the generated text.

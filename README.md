# JaMusic

JaMusic is an independent-music discovery and playback project. The repository contains the original static website (`index.html`) and a React Native / Expo mobile app (`App.js`) that ports the core discovery, search, track list, and audio-player experience to iOS and Android. The native app reads the same Supabase track fields and profiles as the website.

## Native app setup

Requirements: Node.js and the Expo Go app, or an iOS/Android development environment.

1. Install dependencies:

   ```sh
   npm install
   ```
2. Copy `.env.example` to `.env` and fill in your Supabase **Project URL** and **publishable key** from Project Settings → API:

   ```dotenv
   EXPO_PUBLIC_SUPABASE_URL=https://your-project-ref.supabase.co
   EXPO_PUBLIC_SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
   ```

   Expo automatically exposes `EXPO_PUBLIC_*` values to the app. Restart Expo after changing `.env`:

   ```sh
   npx expo start
   ```

   The `.env` file is ignored by Git. The publishable key is intended for client use; never put a Supabase secret/service-role key in a mobile app.
3. Make sure your Supabase project has the database schema, Row Level Security policies, and public `audio` and `artwork` buckets used by the website. This checkout does not include the referenced `setup.sql`, so create/configure those resources separately.

The app loads tracks from `tracks`, with associated `profiles(display_name)`, and uses `audio_url` / `cover_url` (falling back to the `audio` / `artwork` storage paths). It displays a helpful setup message when environment values are missing. Supabase-backed functions require the correct project schema and access policies.

## Original website

`index.html` is the original vanilla HTML/CSS/JavaScript website and is still available separately. It does not consume Expo environment variables; its Supabase constants are configured in the page's script. To serve it locally, run `python3 -m http.server 8000` and open `http://localhost:8000`.

## Security

Mobile and browser apps are public clients. Use only Supabase publishable credentials in them and rely on strict Row Level Security and storage policies. Never ship a secret/service-role key in client-side code.

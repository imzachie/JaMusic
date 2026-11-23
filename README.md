# JaMusic

JaMusic is the original independent music sharing and listening website, built and served through a React + Vite entry point. The full original interface and its existing playback, account, artist profile, upload, likes, and search behavior are preserved in `legacy.html` and displayed by the React app. Vite builds both pages and injects the Supabase public configuration into the original app at build time.

## Set up Supabase (required for data and accounts)

The error `Could not find the table 'public.tracks' in the schema cache` means your Supabase database has not been initialized for JaMusic yet. In the Supabase dashboard, open **SQL Editor → New query**, paste the contents of [`setup.sql`](./setup.sql), and run it. This creates the `profiles`, `tracks`, and `likes` tables, access policies, and the public `audio`, `artwork`, and `profiles` storage buckets. Refresh the website after the SQL finishes.

## Local development

```sh
npm install
cp .env.example .env
```

Put your Supabase **Project URL** and **publishable key** from **Project Settings → API** in `.env`:

```dotenv
VITE_SUPABASE_URL=https://your-project-ref.supabase.co
VITE_SUPABASE_PUBLISHABLE_KEY=sb_publishable_...
```

Then run:

```sh
npm run dev
```

Only a publishable key belongs in a browser app. Never use a Supabase secret/service-role key in frontend code. `.env` is ignored by Git.

## Cloudflare Pages

- **Build command:** `npm run build`
- **Build output directory:** `dist`
- **Build environment variables:** `VITE_SUPABASE_URL`, `VITE_SUPABASE_PUBLISHABLE_KEY`

Vite bakes these public values into the production build. After changing Cloudflare environment variables, trigger a fresh deployment. This frontend is a public client; the SQL file enables row-level security and owner-scoped writes, but review policies for your own deployment requirements.

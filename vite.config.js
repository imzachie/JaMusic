import { resolve } from 'node:path';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  return {
    plugins: [
      react(),
      {
        name: 'jamusic-public-env',
        enforce: 'pre',
        transformIndexHtml(html) {
          return html
            .replaceAll('%VITE_SUPABASE_URL%', env.VITE_SUPABASE_URL || '')
            .replaceAll('%VITE_SUPABASE_PUBLISHABLE_KEY%', env.VITE_SUPABASE_PUBLISHABLE_KEY || '');
        },
      },
    ],
    build: {
      rollupOptions: {
        input: {
          main: resolve(import.meta.dirname, 'index.html'),
          legacy: resolve(import.meta.dirname, 'legacy.html'),
        },
      },
    },
  };
});

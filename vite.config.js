import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  return {
    resolve: { conditions: ['onnxruntime-web-use-extern-wasm'] },
    build: { chunkSizeWarningLimit: 600 },
    plugins: [{
      name: 'jamusic-public-env',
      enforce: 'pre',
      transformIndexHtml(html) {
        return html
          .replaceAll('%VITE_SUPABASE_URL%', env.VITE_SUPABASE_URL || '')
          .replaceAll('%VITE_SUPABASE_PUBLISHABLE_KEY%', env.VITE_SUPABASE_PUBLISHABLE_KEY || '');
      },
    }],
  };
});

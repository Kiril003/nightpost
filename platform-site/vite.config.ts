import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';

/*
 * Попереднє завантаження Manrope (заголовок героя — LCP головної).
 *
 * Шрифт оголошено в CSS, тож браузер дізнається про файл лише після того, як
 * розбере стилі, — і перший кадр малюється системним шрифтом, а потім текст
 * «перестрибує» (Lighthouse рахував це як зсув макета 0,23 на головній).
 * Посилання preload у <head> просить кириличний і латинський файли одразу,
 * паралельно зі стилями. Імена з хешем беремо з готового бандла.
 */
function preloadManrope(): Plugin {
  return {
    name: 'preload-manrope',
    apply: 'build',
    transformIndexHtml: {
      order: 'post',
      handler(html, ctx) {
        const files = Object.keys(ctx.bundle ?? {}).filter((f) =>
          /manrope-(cyrillic|latin)-wght-normal-[^/]+\.woff2$/.test(f),
        );
        const tags = files.map((f) => ({
          tag: 'link',
          attrs: { rel: 'preload', href: `/${f}`, as: 'font', type: 'font/woff2', crossorigin: '' },
          injectTo: 'head' as const,
        }));
        return { html, tags };
      },
    },
  };
}

const BUILD = Date.now().toString(36);

function versionFile(): Plugin {
  return {
    name: 'version-file',
    apply: 'build',
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'version.json', source: JSON.stringify({ build: BUILD }) });
    },
  };
}

const mediaProxy = {
  '^/venues/[^/]+/media': { target: process.env.VITE_LICENSE_SERVER ?? 'https://phantom-license.fly.dev', changeOrigin: true },
};

export default defineConfig(({ command }) => ({
  plugins: [react(), preloadManrope(), versionFile()],
  define: { __BUILD__: JSON.stringify(command === 'build' ? BUILD : 'dev') },
  server: { port: 5180, host: true, proxy: mediaProxy },
  preview: { proxy: mediaProxy },
  build: {
    target: 'es2020',
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          react: ['react', 'react-dom', 'react-router-dom'],
        },
      },
    },
  },
}));

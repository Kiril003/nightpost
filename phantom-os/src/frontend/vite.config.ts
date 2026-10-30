import { resolve } from 'node:path';
/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';
import { devLoginPlugin } from './devLogin';



/**
 * Збірка під телефон: `vite build --mode phone` (або VITE_TARGET=phone).
 *
 * Тут стояли підміни модулів (`PHONE_SWAPS`): `ModalHost` із 57 показовими
 * модалками, AI-студія і її стор у phone-режимі замінювались на `*.phone.*`
 * порожнечі, бо динамічний `import()` не вимикається умовою в коді. 24.09
 * самих модулів не стало — їх видалено, а не сховано (перепис —
 * docs/pc-msgr-census.md), тож і підміняти більше нічого. Різниця між
 * збірками лишилась у `base`, `outDir` і прапорці `__PHONE_BUILD__`.
 */
export default defineConfig(({ mode }) => {
  const phone = mode === 'phone' || process.env.VITE_TARGET === 'phone';

  return {
  /**
   * Корінь адрес. Перехоплювач телефона пускає лише
   * `https://phantom.local/webmsgr/**`, а все інше дістає `blocked()` з
   * `text/plain` — і WebView відмовляє модулю: «Failed to load module script».
   * Без цього на склі A52 сторінка була порожньою кремовою.
   */
  base: phone ? '/webmsgr/' : '/',
  plugins: [devLoginPlugin(resolve(__dirname, '../../.phantom-data/identity/bootstrap_pin')), react()],
  // Прапорець доступний коду: ним вимикаються двері в те, чого в бандлі нема.
  define: { __PHONE_BUILD__: JSON.stringify(phone) },
  resolve: {
    alias: [
      { find: '@', replacement: path.resolve(__dirname, './src') },
      { find: '@shared', replacement: path.resolve(__dirname, '../shared') },
      // The default maplibre-gl build starts its workers from a blob: URL.
      // The packaged Tauri CSP declares no worker-src, so workers fall back to
      // `default-src 'self'` and a blob: worker is refused — the map dies in the
      // shipped app while `npm run dev` (a plain browser, no CSP) looks fine.
      // maplibre ships this build for exactly that case; the worker is then a
      // same-origin asset. See src/lib/maplibreWorker.ts for the URL wiring.
      {
        find: /^maplibre-gl$/,
        replacement: 'maplibre-gl/dist/maplibre-gl-csp.js',
      },
    ],
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    // xfwd: вузол бачить справжню адресу LAN-клієнта (X-Forwarded-For) і не
    // приймає його за екран машини лише тому, що проксі стоїть на петлі.
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
        xfwd: true,
      },
      '/ws': {
        target: 'ws://127.0.0.1:8000',
        ws: true,
        changeOrigin: true,
        xfwd: true,
      },
      '/health': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
        xfwd: true,
      },
      '/docs': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
        xfwd: true,
      },
      '/redoc': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
        xfwd: true,
      },
      '/openapi.json': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
        xfwd: true,
      },
    },
  },
  // onnxruntime-web тягне сусідні .wasm динамічним імпортом — оптимізатор їх губить.
  optimizeDeps: {
    exclude: ['onnxruntime-web'],
  },
  build: {
    // Окремий каталог, щоб ПК-збірка в dist/ не ламалась і не змішувалась.
    outDir: phone ? 'dist-phone' : 'dist',
    // «.js.map геть» ухвалено 29.08, але вони їхали в APK і далі: 75 файлів,
    // 9 МБ із 19. Тоді виняток лишили ПК — і 04.09 виміряли, чим той виняток
    // є насправді: Tauri вкладає всю теку `dist/` усередину бінарника
    // оболонки, тож у пакунку ПК (`beta/pc`, артефакт cc10f419) лежало 143
    // ключі `…js.map` — увесь вихідний код веба, читаний із розпакованого
    // AppImage. Мапи не їдуть у жоден зібраний бандл; дев-сервер має їх
    // завжди й від цього рядка не залежить.
    sourcemap: false,
    // Vite inlines assets under 4 kB as data: URIs. For the tiny @fontsource
    // unicode-range subsets that turns 21 self-hosted fonts into `data:` fonts,
    // which the packaged CSP refuses (no font-src, so `default-src 'self'`).
    // Emitting them as files keeps every font same-origin and keeps `data:` out
    // of the policy. Other asset types keep the default behaviour.
    assetsInlineLimit: (filePath) =>
      /\.(woff2?|ttf|otf|eot)$/i.test(filePath) ? false : undefined,
    // Weak-device-first: three.js (~600 kB) and recharts/d3 (~170 kB) sit
    // behind lazy boundaries (PhantomFamiliar summon, chart/diagram render),
    // but Vite hoists the static deps of App.tsx's entry-level React.lazy
    // imports into `<link rel="modulepreload">` in index.html — so the browser
    // downloads them during first paint even when the operator never summons
    // the familiar or renders a chart. Drop just those two vendor chunks from
    // the preload manifest; the runtime `__vitePreload` still fetches them the
    // instant their lazy boundary actually mounts, so nothing loads slower in
    // practice — it just no longer taxes the initial waterfall. framer-motion
    // (used at App root) and the rest stay preloaded.
    modulePreload: {
      resolveDependencies: (_filename, deps) =>
        deps.filter((dep) => !/vendor-(three|charts|sandpack)-/.test(dep)),
    },
    rollupOptions: {
      output: {
        // Split heavy libraries into their own cacheable chunks. Combined
        // with lazy-loading their sole consumers (three.js via
        // PhantomFamiliar, maplibre via MapLayout, recharts/d3 via the
        // Dialogue chat renderers), this keeps the entry chunk lean and
        // lets a chat with no charts never download recharts/d3.
        manualChunks(id) {
          // ВАДА 01.10 (той самий клас, що й react-dom нижче): помічник
          // динамічного імпорту vite (`\0vite/preload-helper`) не лежить у
          // node_modules, тож правила не мав — і rollup доклеював його до
          // першого ручного чанка, що ним користується. Ним виявився
          // `vendor-sandpack`: index.html телефона статично тягнув 955 КБ
          // пісочниці коду заради функції на пів кілобайта, бо кожен
          // `React.lazy` входу кличе саме її. Помічник потрібен усім, тож
          // живе поруч із React, який теж потрібен усім.
          if (id.includes('vite/preload-helper') || id.includes('commonjsHelpers')) return 'vendor-react';
          if (!id.includes('node_modules')) return undefined;
          // ВАДА: React і react-dom не мали власного правила, тож rollup клав
          // їх туди, де вони «найбільше спільні» — а це виявився
          // `vendor-charts`. Виміряно 15.09: 130 КБ react-dom лежало всередині
          // чанка на 506 КБ разом з recharts і d3. Наслідок: щоб дістати сам
          // React, телефон мусив завантажити ВСІ діаграми, тож `vendor-charts`
          // лишався статичним ребром входу — і `modulePreload.resolveDependencies`
          // нижче, який прибирає його з підказок, не міг на це вплинути ніяк.
          // React має власний чанк, бо його потребують усі; діаграми — ні.
          if (id.match(/[\\/]node_modules[\\/](react|react-dom|scheduler)[\\/]/)) return 'vendor-react';
          if (id.match(/[\\/]node_modules[\\/](three|@react-three)[\\/]/)) return 'vendor-three';
          if (id.match(/[\\/]node_modules[\\/](recharts|d3-|d3[\\/]|victory-)/)) return 'vendor-charts';
          if (id.includes('@codesandbox/sandpack')) return 'vendor-sandpack';
          if (id.includes('maplibre-gl')) return 'vendor-maplibre';
          if (id.includes('framer-motion')) return 'vendor-motion';
          if (id.match(/[\\/]node_modules[\\/](onnxruntime-web|@ricky0123)/)) return 'vendor-voice';
          return undefined;
        },
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    testTimeout: 15000,
    exclude: ['node_modules', 'dist', 'dist-phone', 'e2e/**', 'playwright-report/**', 'test-results/**'],
  },
  };
});

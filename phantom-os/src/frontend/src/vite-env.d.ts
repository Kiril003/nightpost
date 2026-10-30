/// <reference types="vite/client" />

// Phase 11b — AudioWorklet is loaded via Vite's `?url` suffix which
// returns the bundled asset URL as a string. Declare the suffix so
// TypeScript's module resolver doesn't 2307 on the import.
declare module '*.js?url' {
  const src: string;
  export default src;
}

/**
 * Ціль збірки. true лише у `vite build --mode phone` (див. vite.config.ts):
 * ним вимикаються двері в те, чого в бандлі телефона фізично немає.
 */
declare const __PHONE_BUILD__: boolean;

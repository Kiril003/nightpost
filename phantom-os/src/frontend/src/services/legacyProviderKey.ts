// Старі збірки клали ключ постачальника моделі у сховище браузера; стираємо.
const LEGACY_KEYS = ['phantom_sovereign_ai_config'];

export function wipeLegacyProviderKey(): void {
  for (const store of ['localStorage', 'sessionStorage'] as const) {
    try {
      const s = globalThis[store];
      for (const k of LEGACY_KEYS) s?.removeItem(k);
    } catch {
      /* сховище недоступне */
    }
  }
}

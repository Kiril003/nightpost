const MEDIA_KEYS = ['key_hex', 'nonce_hex', 'blob_id'];

const keyed = (s: string) => MEDIA_KEYS.some((k) => s.includes(k));

/**
 * Що з тіла листа бачить модель. Текст — як є. Опис вкладення несе ключ і сіль
 * наскрізного шифру: з нього йдуть лише підпис і розшифровка голосового, а без
 * них — нічого (null). Тіло, де трапилась назва поля ключа (навіть екранована
 * чи в підписі), але яке не розбирається як опис, теж не йде.
 */
export function modelText(body: string | null | undefined): string | null {
  const text = (body ?? '').trim();
  if (!keyed(text)) return text;
  let parsed: unknown;
  try {
    parsed = text.startsWith('{') ? JSON.parse(text) : null;
  } catch {
    parsed = null;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
  const o = parsed as Record<string, unknown>;
  const said = [o.transcript, o.caption]
    .map((v) => (typeof v === 'string' ? v.trim() : ''))
    .filter((v) => v && !keyed(v));
  return said.length ? said.join('\n') : null;
}

const MEDIA_TYPES = new Set(['image', 'file', 'voice', 'video-note']);

/** Слова листа для помічника; null — у листі лише вкладення, без підпису й розшифровки. */
export function assistText(msg: {
  type?: string;
  text?: string;
  media?: unknown;
  voiceData?: { transcript?: string };
  videoNoteData?: unknown;
}): string | null {
  const said = modelText(msg.text || msg.voiceData?.transcript);
  if (said === null) return null;
  if (!said && (msg.media || msg.voiceData || msg.videoNoteData || MEDIA_TYPES.has(msg.type ?? ''))) return null;
  return said;
}

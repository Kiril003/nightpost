/** Куди повернути після входу: лише шлях цього ж сайту, не чужа адреса. */
export function safeNext(raw: string | null): string | null {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\')) return null;
  return raw;
}

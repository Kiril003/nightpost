/** Ідентифікатор з `#хеша` адреси — без CSS-селектора, що падав на `#a:b`. */
export function anchorId(hash: string): string {
  const raw = hash.startsWith('#') ? hash.slice(1) : hash;
  if (!raw) return '';
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

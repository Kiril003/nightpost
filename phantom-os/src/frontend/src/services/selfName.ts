/**
 * Імʼя, якого людина не давала: порожнє, імʼя застосунку («Nightpost», а на
 * апаратах до збірки 16 — «PHANTOM»), назва пристрою на кшталт
 * «Nightpost-A52» / «PHANTOM-A52» чи модель «SM-A525F». Таке не показуємо як
 * імʼя — питаємо. Старе імʼя лишається тут свідомо: воно вже лежить у
 * профілях людей, що не перейменовувались.
 */
export const isMachineName = (name?: string | null): boolean => {
  const n = (name ?? '').trim();
  return !n || /^(?:phantom|nightpost)(?:[-_ ·][a-z0-9-]+)?$/i.test(n) || /^(?:sm|gt)-[a-z0-9]+$/i.test(n) || /^android$/i.test(n);
};

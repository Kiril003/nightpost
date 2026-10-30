// Куди веде відповідь сервера в дверях кабінету. Статус -1 — не ApiError
// (впала сама сторінка), 0 — мережі нема.

export const statusOf = (err: unknown): number => {
  const s = (err as { status?: unknown } | null)?.status;
  return typeof s === 'number' ? s : -1;
};

/** Опитування QR зупиняється на всьому, що вже не стане «пусти». */
export const qrPollStops = (status: number): boolean =>
  status === 401 || status === 403 || status === 404 || status === 409 || status === 410;

/** Вхід під час 7 днів до видалення дає лише сесію скасування — не кабінет. */
export const signInLeadsTo = (verified?: { scope?: string } | null): 'cabinet' | 'pendingErase' =>
  verified?.scope === 'erase_cancel' ? 'pendingErase' : 'cabinet';

/** Лист із кодом пішов, лише коли сервер сказав `sent`. */
export const codeWasSent = (res: { sent?: boolean } | null | undefined): boolean => res?.sent !== false;

/** Відповідь на прохання про код відновлення ключа. */
export const recoverRequestStep = (status: number): 'sent' | 'offline' =>
  status >= 200 && status < 300 ? 'sent' : 'offline';

/** Відповідь на код відновлення: невдалий код — лише 400. */
export const recoverConfirmStep = (status: number): 'done' | 'wrongCode' | 'offline' =>
  status >= 200 && status < 300 ? 'done' : status === 400 ? 'wrongCode' : 'offline';

/** «Вийти всюди»: локально виходимо, лише коли сервер справді погасив сесії. */
export const revokeAllSignsOut = (status: number | null): boolean => status === null || status === 401;

/** Скасування видалення. */
export const cancelEraseStep = (status: number): 'stale' | 'nothing' | 'failed' =>
  status === 401 ? 'stale' : status === 404 ? 'nothing' : 'failed';

/** Ключ, якого сервер не знає або не може прочитати, не варто пам'ятати. */
export const keyIsDead = (status: number): boolean => status === 400 || status === 404;

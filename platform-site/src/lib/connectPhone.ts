/** Під'єднати телефон до сторінок облікового запису: QR з метою «link», без входу на телефоні. */
export type LinkStatus = 'loading' | 'pending' | 'linked' | 'expired' | 'unreachable';
export type LinkState = { status: LinkStatus; qr: string | null; left: number };
type Start = { challenge_id: string; poll_token: string; qr_payload: string; expires_at: number };

const STOPS = new Set([400, 401, 404, 409, 410]);

export function linkPhone(o: {
  start: () => Promise<Start>;
  poll: (challengeId: string, pollToken: string) => Promise<{ status: string }>;
  onChange: (s: LinkState) => void;
  everyMs?: number;
}): { stop: () => void } {
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | null = null;
  const emit = (s: LinkState) => {
    if (!stopped) o.onChange(s);
  };
  const stop = () => {
    stopped = true;
    if (timer !== null) clearTimeout(timer);
  };
  emit({ status: 'loading', qr: null, left: 0 });
  void (async () => {
    let s: Start;
    try {
      s = await o.start();
    } catch {
      emit({ status: 'unreachable', qr: null, left: 0 });
      return;
    }
    const left = () => Math.max(0, s.expires_at - Math.floor(Date.now() / 1000));
    if (left() <= 0) return emit({ status: 'expired', qr: null, left: 0 });
    emit({ status: 'pending', qr: s.qr_payload, left: left() });
    const tick = async () => {
      if (stopped) return;
      if (left() <= 0) return emit({ status: 'expired', qr: null, left: 0 });
      try {
        const r = await o.poll(s.challenge_id, s.poll_token);
        if (r.status === 'linked') return emit({ status: 'linked', qr: null, left: 0 });
      } catch (e) {
        if (STOPS.has((e as { status?: number }).status ?? 0)) return emit({ status: 'expired', qr: null, left: 0 });
      }
      emit({ status: 'pending', qr: s.qr_payload, left: left() });
      if (!stopped) timer = setTimeout(tick, o.everyMs ?? 3000);
    };
    timer = setTimeout(tick, o.everyMs ?? 3000);
  })();
  return { stop };
}

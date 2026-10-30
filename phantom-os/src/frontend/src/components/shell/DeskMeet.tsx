/**
 * Знайомство — перший запуск PHANTOM на цьому комп'ютері.
 *
 * Вузол заводить власника сам і мінтить йому випадковий PIN у файл
 * (`identity/bootstrap_pin`), а оболонка впускає без PIN дверима. Тож
 * людина ніколи не чула, як її звати для PHANTOM, і не знала свого PIN —
 * а він потрібен з іншого пристрою й після «Вийти». Ручки першого запуску
 * (`GET /auth/bootstrap`, `POST /auth/bootstrap/claim`) були в ядрі, але
 * інтерфейс їх не кликав ніде.
 *
 * Тут PHANTOM сам питає ім'я і просить придумати PIN (двічі). Лише з
 * самої машини і лише поки вузол нічий — так каже вузол; ми тільки
 * питаємо, чи двері ще не замкнено.
 */
import { useEffect, useState } from 'react';
import PinPad from '../auth/PinPad';
import { PhantomMark } from '../messenger/PhantomMark';
import { authApi, ApiError } from '../../services/api';
import { useAuthStore } from '../../stores/authStore';
import { useSystemStore } from '../../stores/systemStore';
import { useDeskPhase } from './useDeskPhase';
import './shell.css';
import { tNow } from '../chat/tNow';
export type Bootstrap = 'unknown' | 'unclaimed' | 'claimed';

/** Чи вузол ще нічий. Не з петлі чи стара збірка вузла — вважаємо, що має власника. */
export function useBootstrap(nodeUp: boolean): [Bootstrap, () => void] {
  const [state, setState] = useState<Bootstrap>('unknown');
  useEffect(() => {
    if (!nodeUp || state !== 'unknown') return;
    let alive = true;
    authApi
      .bootstrap()
      .then((r) => alive && setState(r.unclaimed ? 'unclaimed' : 'claimed'))
      .catch(() => alive && setState('claimed'));
    return () => {
      alive = false;
    };
  }, [nodeUp, state]);
  return [state, () => setState('claimed')];
}

type Step = { kind: 'name' } | { kind: 'pin' } | { kind: 'again'; pin: string };

export function DeskMeet({ onDone }: { onDone: () => void }) {
  useDeskPhase();
  const [step, setStep] = useState<Step>({ kind: 'name' });
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // PinPad тримає набране в собі — новий ключ на кожному кроці його очищує.
  const [padKey, setPadKey] = useState(0);
  const setUser = useAuthStore((s) => s.setUser);
  const setAuthenticated = useSystemStore((s) => s.setAuthenticated);

  const claim = async (pin: string) => {
    setBusy(true);
    setError('');
    try {
      const res = await authApi.claim(name.trim(), pin);
      setUser(res.user, res.token, res.expires_at);
      setAuthenticated(true);
      onDone();
    } catch (e) {
      const code = e instanceof ApiError ? e.status : 0;
      if (code === 409) return onDone(); // уже має власника — нема про що питати
      setError(code === 400 ? tNow('deskMeet.badPin') : tNow('deskMeet.noReply'));
      setStep({ kind: 'pin' });
      setPadKey((k) => k + 1);
    } finally {
      setBusy(false);
    }
  };

  const line =
    step.kind === 'name'
      ? tNow('deskMeet.hello')
      : step.kind === 'pin'
        ? tNow('deskMeet.pickPin', { name: name.trim() })
        : tNow('deskMeet.again');

  return (
    <div data-mood="calm" className="shell-root relative flex flex-col w-screen h-screen overflow-hidden bg-canvas text-f-ink" data-testid="desk-meet">
      <main className="relative flex-1 flex flex-col items-center justify-center gap-6 px-6">
        <PhantomMark size="w-14 h-14" />
        <h1 className="font-sans text-f-display text-f-ink text-center">{line}</h1>

        {step.kind === 'name' && (
          <form
            className="flex flex-col items-center gap-4 w-full max-w-[360px]"
            onSubmit={(e) => {
              e.preventDefault();
              if (name.trim()) setStep({ kind: 'pin' });
            }}
          >
            <input
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              maxLength={64}
              aria-label={tNow('deskMeet.yourName')}
              placeholder={tNow('deskMeet.name')}
              className="w-full h-12 px-4 rounded-f-m bg-f-surface text-f-read text-f-ink text-center focus:outline focus:outline-2 focus:outline-f-accent"
            />
            <button
              type="submit"
              disabled={!name.trim()}
              className="min-h-11 px-6 rounded-full bg-f-accent text-f-on-accent text-f-body disabled:opacity-40"
            >
              {tNow('deskMeet.next')}
            </button>
          </form>
        )}

        {step.kind !== 'name' && (
          <div className="flex flex-col items-center gap-2">
            <p className="text-f-body text-f-ink-3 text-center max-w-[420px]">
              {tNow('deskMeet.pinHelp')}
            </p>
            <PinPad
              key={padKey}
              disabled={busy}
              error={error}
              onSubmit={(pin) => {
                setError('');
                if (step.kind === 'pin') {
                  setStep({ kind: 'again', pin });
                  setPadKey((k) => k + 1);
                } else if (pin !== step.pin) {
                  setError(tNow('deskMeet.mismatch'));
                  setStep({ kind: 'pin' });
                  setPadKey((k) => k + 1);
                } else {
                  void claim(pin);
                }
              }}
            />
          </div>
        )}
      </main>
    </div>
  );
}

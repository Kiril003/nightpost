import { useCallback, useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, PlugZap, RotateCw, ShieldAlert } from 'lucide-react';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import { useAuthStore } from '../../stores/authStore';
import { useSystemStore } from '../../stores/systemStore';
import { authApi, ApiError } from '../../services/api';
import { PhantomMark } from '../messenger/PhantomMark';
import { Avatar } from '../messenger/Avatar';
import PinPad from './PinPad';

interface Profile {
  id: string;
  username: string;
  avatar_url: string | null;
  /** Ім'я, яким людина назвалась PHANTOM (старий вузол його не віддає). */
  display_name?: string | null;
}

type Phase =
  | { kind: 'loading' }
  | { kind: 'unreachable' }
  | { kind: 'nobody' }
  | { kind: 'pick'; profiles: Profile[] }
  | { kind: 'pin'; who: Profile; many: boolean }
  /** Не з екрана цієї машини: список людей вузол не віддає (404) — ім'я + PIN. */
  | { kind: 'named' };

/** Як і на вузлі: без пробілів, без «@», малими. */
function normalizeLoginName(raw: string): string {
  return raw.trim().replace(/^@+/, '').toLowerCase();
}

/** Логін у базі — «phantom». Вітатися з людиною малою літерою негарно. */
function displayName(p: Pick<Profile, 'username' | 'display_name'>): string {
  if (p.display_name && p.display_name.trim()) return p.display_name.trim();
  return p.username.charAt(0).toUpperCase() + p.username.slice(1);
}

function greeting(hour: number, t: Translation['t']): string {
  if (hour < 5) return t('loginScreen.greetNight');
  if (hour < 11) return t('loginScreen.greetMorning');
  if (hour < 17) return t('loginScreen.greetDay');
  if (hour < 23) return t('loginScreen.greetEvening');
  return t('loginScreen.greetLate');
}

/**
 * Вхід на пристрій — не в хмару. Пристрій знає своїх: якщо власник один —
 * питаємо лише PIN і одразу клавіатурою під палець.
 *
 * Мова — та сама, що в месенджері й у рідному замку телефона: полотно
 * `canvas` з рельєфом «Твоє місце» внизу, знак PHANTOM (диск), заголовок
 * `title-1`, клавіатура замка (PinPad), люди — тонами людей, збої — аркушем
 * (`raised`, кути 20, тінь e3). Бурштин — лише на головній дії аркуша.
 *
 * Тут було скло ПК: бурштинова куля з сяйвом (Orb), розмиті плями світла
 * (AmbientGlows), «glass»-картки, розрядкові мікропідписи й засічковий
 * курсив — поруч із новою ідентичністю вони виглядали чужими.
 */
export default function LoginScreen() {
  const { t } = useTranslation();
  const [phase, setPhase] = useState<Phase>({ kind: 'loading' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [now, setNow] = useState(() => Date.now());
  const [name, setName] = useState('');

  const { setUser, isLocked, lockedUntil, setLockout } = useAuthStore();
  const setAuthenticated = useSystemStore((s) => s.setAuthenticated);

  const load = useCallback(async () => {
    setPhase({ kind: 'loading' });
    try {
      const profiles = await authApi.picker();
      if (profiles && profiles.length > 0) {
        if (profiles.length === 1) return setPhase({ kind: 'pin', who: profiles[0], many: false });
        return setPhase({ kind: 'pick', profiles });
      }
      // Порожній список і мовчання ядра — це різні речі, і раніше вони
      // збігались в один `throw`: обидві вели у demo-профілі. Тому екран
      // `Nobody` нижче став недосяжним, а на місці справжнього збою
      // з'являлись чотири вигадані власники.
      //
      // Ядро відповіло «нуль користувачів» — воно на місці, і воно
      // помиляється: ensure_default_user (security/auth.py) заводить ROOT
      // «phantom» на кожному старті. Це збій, і про нього треба сказати.
      return setPhase({ kind: 'nobody' });
    } catch (e) {
      // 404 — вузол на місці, але список людей віддає лише своєму екрану
      // (браузер з мережі). Тоді — ім'я й PIN, а не «не відповідає».
      if (e instanceof ApiError && e.status === 404) return setPhase({ kind: 'named' });
      // Ядро не відповіло. Раніше тут з'являлись чотири вигадані профілі
      // («kiril», «alex»…) — людина вибирала неіснуючого власника. Чесно:
      // PHANTOM не відповідає, спробуй ще раз.
      setPhase({ kind: 'unreachable' });
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Цокає лише поки блокування триває: інакше екран входу перемальовувався
  // раз на секунду просто так.
  useEffect(() => {
    if (!lockedUntil || Date.now() >= lockedUntil) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [lockedUntil]);

  const locked = isLocked();
  const lockLeft = locked && lockedUntil ? Math.max(0, Math.ceil((lockedUntil - now) / 1000)) : 0;

  const submitPin = async (pin: string) => {
    if (locked) return;
    let username: string;
    if (phase.kind === 'pin') username = phase.who.username;
    else if (phase.kind === 'named') username = normalizeLoginName(name);
    else return;
    if (!username) return;
    setBusy(true);
    setError('');
    try {
      const res = await authApi.loginPin(username, pin);
      setUser(res.user, res.token, res.expires_at);
      setAuthenticated(true);
    } catch (e) {
      // Раніше будь-яка помилка — зокрема 401 на невірний PIN — пускала в
      // інтерфейс із «local_demo_token» і роллю ROOT. Вхід, що не перевіряє
      // PIN, — не вхід. Тепер: чужий PIN — сказати; блокування — чекати;
      // мовчання ядра — так і сказати.
      const status = e instanceof ApiError ? e.status : 0;
      if (status === 401 || status === 403) {
        // З мережі одна відповідь і на чуже ім'я, і на чужий PIN.
        setError(t(phase.kind === 'named' ? 'loginScreen.wrongNameOrPin' : 'loginScreen.wrongPin'));
      } else if (status === 429) {
        const secs = Number(/(\d+)\s*s/.exec(e instanceof ApiError ? e.message : '')?.[1] ?? 60);
        setLockout(Date.now() + secs * 1000);
        setError(t('loginScreen.tooMany'));
      } else {
        setError(t('loginScreen.noAnswer'));
      }
    } finally {
      setBusy(false);
    }
  };

  const hello = greeting(new Date().getHours(), t);

  return (
    <div
      data-login
      className="relative w-full h-full min-h-screen overflow-hidden bg-canvas text-ink font-sans flex items-center justify-center"
    >
      <main className="relative z-10 w-full max-w-[400px] px-4 py-8 flex flex-col items-center ph-rise">
        <PhantomMark size="w-14 h-14" />

        {phase.kind === 'loading' && <Waiting />}
        {phase.kind === 'unreachable' && <Unreachable onRetry={() => void load()} />}
        {phase.kind === 'nobody' && <Nobody onRetry={() => void load()} />}

        {phase.kind === 'pick' && (
          <>
            <Title text={t('loginScreen.whoIsThis', { hello })} />
            <div className="mt-6 w-full rounded-card bg-raised overflow-hidden" role="list">
              {phase.profiles.map((p, i) => (
                <div key={p.id} role="listitem">
                  {i > 0 && <div aria-hidden className="ml-[68px] h-px bg-line" />}
                  <button
                    type="button"
                    onClick={() => {
                      setError('');
                      setPhase({ kind: 'pin', who: p, many: true });
                    }}
                    className="w-full min-h-[64px] py-2 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
                  >
                    <Avatar
                      src={p.avatar_url}
                      name={displayName(p)}
                      tone={p.id}
                      className="w-10 h-10 text-subhead"
                    />
                    <span className="flex-1 min-w-0 truncate text-headline text-ink">{displayName(p)}</span>
                    <ChevronRight className="w-5 h-5 text-ink-3 shrink-0" strokeWidth={1.75} aria-hidden />
                  </button>
                </div>
              ))}
            </div>
          </>
        )}

        {phase.kind === 'named' && (
          <>
            <Title text={`${hello}.`} />
            <input
              type="text"
              value={name}
              onChange={(e) => {
                setName(e.target.value);
                if (error) setError('');
              }}
              aria-label={t('loginScreen.name')}
              placeholder={t('loginScreen.name')}
              onKeyDown={(e) => {
                if (e.key === 'Enter') e.currentTarget.blur();
              }}
              enterKeyHint="next"
              autoComplete="username"
              autoCapitalize="off"
              autoCorrect="off"
              spellCheck={false}
              maxLength={64}
              className="mt-6 w-full h-12 rounded-control bg-raised px-4 text-headline text-ink placeholder:text-ink-3 border border-line focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
            />
            <p className="mt-3 text-callout text-ink-2 text-center">
              {locked ? t('loginScreen.pause', { s: lockLeft }) : t('loginScreen.enterPin')}
            </p>
            <div className="mt-5">
              <PinPad
                onSubmit={(pin) => void submitPin(pin)}
                disabled={busy || locked || !normalizeLoginName(name)}
                error={error}
              />
            </div>
          </>
        )}

        {phase.kind === 'pin' && (
          <>
            <Title text={`${hello}, ${displayName(phase.who)}.`} />
            <p className="mt-1.5 text-callout text-ink-2 text-center">
              {locked ? t('loginScreen.pause', { s: lockLeft }) : t('loginScreen.enterPin')}
            </p>
            <div className="mt-7">
              <PinPad onSubmit={(pin) => void submitPin(pin)} disabled={busy || locked} error={error} />
            </div>
            {phase.many && (
              <BackToPicker
                onBack={() => {
                  setError('');
                  void load();
                }}
              />
            )}
          </>
        )}
      </main>
    </div>
  );
}

/* ─── Стани ────────────────────────────────────────────────────────────── */

function Title({ text }: { text: string }) {
  return <h1 className="mt-5 text-title-1 text-ink text-center">{text}</h1>;
}

function Waiting() {
  const { t } = useTranslation();
  return (
    <div className="mt-6 flex flex-col items-center gap-3" role="status">
      <span className="ph-dots text-ink-3" aria-hidden>
        <i />
        <i />
        <i />
      </span>
      <span className="text-footnote text-ink-3">{t('loginScreen.asking')}</span>
    </div>
  );
}

/**
 * Аркуш збою — мовою FormSheet (`raised`, кути 20, тінь e3): значок `ink-2`,
 * заголовок `headline`, пояснення `callout`/`footnote`, одна головна дія
 * бурштином.
 */
function FaultSheet({
  icon,
  title,
  children,
  action,
  onAction,
}: {
  icon: React.ReactNode;
  title: string;
  children: React.ReactNode;
  action: string;
  onAction: () => void;
}) {
  return (
    <section
      aria-label={title}
      className="mt-6 w-full rounded-sheet bg-raised shadow-e3 px-5 pt-5 pb-4 flex flex-col items-center text-center"
    >
      <span className="w-11 h-11 rounded-full bg-overlay text-ink-2 flex items-center justify-center" aria-hidden>
        {icon}
      </span>
      <h2 className="mt-3 text-headline text-ink">{title}</h2>
      {children}
      <button
        type="button"
        onClick={onAction}
        className="mt-5 w-full h-12 min-h-0 rounded-control bg-accent text-on-accent text-headline flex items-center justify-center gap-2 hover:bg-accent-hover active:bg-accent-press"
      >
        <RotateCw className="w-[18px] h-[18px]" strokeWidth={2} aria-hidden />
        {action}
      </button>
    </section>
  );
}

function Unreachable({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <FaultSheet
      icon={<PlugZap className="w-5 h-5" strokeWidth={1.75} />}
      title={t('loginScreen.noAnswerTitle')}
      action={t('loginScreen.retry')}
      onAction={onRetry}
    >
      <p className="mt-1.5 text-callout text-ink-2">
        {t('loginScreen.unreachableBody')}
      </p>
    </FaultSheet>
  );
}

/**
 * Тут була кнопка «Створити профіль» на /onboarding. Вона вела в нікуди:
 * маршрут лежав під перевіркою автентифікації, а бачить цей екран лише
 * той, хто ще не увійшов, — тобто клік повертав на цей самий екран. Та
 * сторінка й профілю не створювала, вона створювала tenant.
 *
 * Насправді власника заводить саме ядро: ensure_default_user
 * (security/auth.py) на кожному старті створює ROOT «phantom», якщо
 * користувачів нема. Тож порожній список — це збій, а не новий пристрій,
 * і чесна відповідь тут — сказати, звідки взяти PIN.
 */
function Nobody({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <FaultSheet
      icon={<ShieldAlert className="w-5 h-5" strokeWidth={1.75} />}
      title={t('loginScreen.noProfiles')}
      action={t('loginScreen.checkAgain')}
      onAction={onRetry}
    >
      <p className="mt-1.5 text-callout text-ink-2">
        {t('loginScreen.nobodyBody')}
      </p>
      <p className="mt-3 text-footnote text-ink-3">
        {t('loginScreen.nobodyRestart')}
      </p>
    </FaultSheet>
  );
}

function BackToPicker({ onBack }: { onBack: () => void }) {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      onClick={onBack}
      className="mt-4 min-h-11 px-4 rounded-full flex items-center justify-center gap-1 text-subhead text-ink-2 hover:bg-raised active:bg-raised"
    >
      <ChevronLeft className="w-4 h-4" strokeWidth={2} aria-hidden />
      {t('loginScreen.notMe')}
    </button>
  );
}

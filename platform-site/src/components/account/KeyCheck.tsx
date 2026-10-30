import { useCallback, useEffect, useState } from 'react';
import { Check, CircleDashed, Copy, LogOut, Minus } from 'lucide-react';
import { DeviceList, SeatGrid, type SeatDevice } from './DeviceList';
import { Btn, Card, Field, Notice, Status, SubHead, Tag } from './Panels';
import { useLanguage } from '../../i18n';
import { accountCopy } from '../../i18n/account';
import { pricingCopy } from '../../i18n/commerce';
import { LICENSE_SERVER } from '../../lib/meta';
import { keyIsDead, recoverConfirmStep, recoverRequestStep } from '../../lib/accountFlow';
import {
  FEATURE_ORDER,
  OFFLINE_GRACE_DAYS,
  PLANS,
  planById,
  planHas,
  surfaceOf,
  type PlanId,
} from '../../lib/plans';

interface PortalState {
  tier: string;
  revoked: boolean;
  updates_until: string | null;
  max_activations: number;
  devices: SeatDevice[];
}

type ErrorKey = 'malformed' | 'notFound' | 'tooMany' | 'offline' | 'unknown';

const STORAGE_KEY = 'phantom-license-key';

const forgetKey = () => {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* нічого не збережено */
  }
};
const ICON = { strokeWidth: 1.75 } as const;

function errorKeyFor(status: number | null): ErrorKey {
  if (status === null) return 'offline';
  if (status === 400) return 'malformed';
  if (status === 404) return 'notFound';
  if (status === 429) return 'tooMany';
  return 'unknown';
}

function daysUntil(iso: string | null): number | null {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return null;
  return Math.ceil((then - Date.now()) / 86_400_000);
}

function maskKey(key: string): string {
  const clean = key.replace(/\s+/g, '');
  if (clean.length <= 8) return clean;
  return `${clean.slice(0, 4)}····${clean.slice(-4)}`;
}

/**
 * Друга з двох дверей кабінету: ключ ліцензії замість пошти.
 *
 * Живе заради людини з екіпажу, яка тримає ключ, але не має доступу до
 * поштової скриньки покупця. Усе, що потребує пошти — завантаження, новий
 * ключ, листування — за цими дверима не відкривається, і так і сказано.
 *
 * Без анімацій: стани змінюються умовним рендером.
 */
export default function KeyCheck({ lostOpen = false }: { lostOpen?: boolean }) {
  const { lang } = useLanguage();
  const c = accountCopy[lang].portal;
  const cab = c.cabinet;
  const pricing = pricingCopy[lang];

  const [key, setKey] = useState('');
  const [state, setState] = useState<PortalState | null>(null);
  const [busy, setBusy] = useState(false);
  const [releasing, setReleasing] = useState<string | null>(null);
  const [error, setError] = useState<ErrorKey | null>(null);
  const [remember, setRemember] = useState(false);
  const [pubKey, setPubKey] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [recoverOpen, setRecoverOpen] = useState(lostOpen);
  const [recEmail, setRecEmail] = useState('');
  const [recCode, setRecCode] = useState('');
  const [recStage, setRecStage] = useState<'idle' | 'sending' | 'sent' | 'confirming' | 'done'>('idle');
  const [recFailed, setRecFailed] = useState<false | 'wrongCode' | 'offline'>(false);

  const load = useCallback(async (licenseKey: string): Promise<number> => {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`${LICENSE_SERVER}/api/portal/devices`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ license_key: licenseKey }),
      });
      if (!res.ok) {
        setError(errorKeyFor(res.status));
        setState(null);
        return res.status;
      }
      setState((await res.json()) as PortalState);
      return res.status;
    } catch {
      setError('offline');
      setState(null);
      return 0;
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    let saved: string | null = null;
    try {
      saved = localStorage.getItem(STORAGE_KEY);
    } catch {
      /* сховище заблоковане — просто входимо вручну */
    }
    if (saved) {
      const remembered = saved;
      setKey(remembered);
      setRemember(true);
      void load(remembered).then((status) => {
        if (keyIsDead(status)) forgetKey();
      });
    }
  }, [load]);

  useEffect(() => {
    if (!state) return;
    let alive = true;
    fetch(`${LICENSE_SERVER}/healthz`)
      .then((r) => (r.ok ? r.json() : null))
      .then((j) => alive && setPubKey(j?.public_key ?? null))
      .catch(() => alive && setPubKey(null));
    return () => {
      alive = false;
    };
  }, [state]);

  const release = useCallback(
    async (fingerprint: string) => {
      setReleasing(fingerprint);
      try {
        const res = await fetch(`${LICENSE_SERVER}/api/deactivate`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ license_key: key.trim(), device_fingerprint: fingerprint }),
        });
        if (res.ok) await load(key.trim());
        else setError(errorKeyFor(res.status));
      } catch {
        setError('offline');
      } finally {
        setReleasing(null);
      }
    },
    [key, load],
  );

  const signIn = async (value: string) => {
    const status = await load(value);
    try {
      if (remember && status >= 200 && status < 300) localStorage.setItem(STORAGE_KEY, value);
      else if (!remember || keyIsDead(status)) localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* нема сховища — ключ живе лише до перезавантаження */
    }
  };

  const signOut = () => {
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* нічого не збережено */
    }
    setState(null);
    setKey('');
    setError(null);
  };

  const ready = key.trim().length >= 10;
  const used = state?.devices.length ?? 0;
  const free = state ? Math.max(0, state.max_activations - used) : 0;
  const left = daysUntil(state?.updates_until ?? null);
  const plan = state && PLANS.some((p) => p.id === state.tier) ? planById(state.tier as PlanId) : null;

  // Два кроки однією кнопкою: спершу просимо код, потім підтверджуємо ним.
  // Нового ключа тут не буває — сервер шле його поштою, і це навмисно.
  async function submitRecovery() {
    setRecFailed(false);
    if (recStage === 'idle') {
      setRecStage('sending');
      try {
        const res = await fetch(`${LICENSE_SERVER}/api/recover`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email: recEmail.trim().toLowerCase() }),
        });
        if (recoverRequestStep(res.status) === 'sent') {
          setRecStage('sent');
          return;
        }
      } catch {
        /* мережі нема */
      }
      setRecStage('idle');
      setRecFailed('offline');
      return;
    }
    if (recStage !== 'sent') return;
    setRecStage('confirming');
    try {
      const res = await fetch(`${LICENSE_SERVER}/api/recover/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: recEmail.trim().toLowerCase(), code: recCode }),
      });
      const step = recoverConfirmStep(res.status);
      if (step !== 'done') {
        setRecStage('sent');
        setRecFailed(step);
        return;
      }
      setRecStage('done');
    } catch {
      setRecStage('sent');
      setRecFailed('offline');
    }
  }

  const codeStage = recStage === 'sent' || recStage === 'confirming';

  return (
    <div className="grid gap-4">
      {!state && (
        <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.8fr)] lg:items-start lg:gap-12">
          <Card>
            <form
              noValidate
              onSubmit={(e) => {
                e.preventDefault();
                if (ready && !busy) void signIn(key.trim());
              }}
            >
              <Field
                label={c.keyLabel}
                value={key}
                onChange={(next) => {
                  setKey(next.toUpperCase());
                  if (error) setError(null);
                }}
                placeholder={c.keyPlaceholder}
                hint={c.keyHint}
                error={error ? c.errors[error] : null}
                mono
                spellCheck={false}
                autoComplete="off"
                autoCapitalize="characters"
              />

              <label className="mt-3 flex min-h-[44px] cursor-pointer items-center gap-3 text-callout text-ink-2">
                <input
                  type="checkbox"
                  checked={remember}
                  onChange={(e) => setRemember(e.target.checked)}
                  className="h-5 w-5 shrink-0 accent-accent-line"
                />
                {cab.remember}
              </label>

              <div className="mt-4">
                <Btn type="submit" busy={busy} disabled={!ready} full>
                  {busy ? c.checking : c.submit}
                </Btn>
              </div>

              <p className="mt-5 border-t border-line pt-4 text-footnote text-ink-2">{c.noAccount}</p>
            </form>
          </Card>

          <div>
            <h2 className="text-headline text-ink">{cab.previewTitle}</h2>
            <ul className="mt-4 space-y-3">
              {cab.preview.map((line) => (
                <li key={line} className="flex gap-3 text-callout text-ink-2">
                  <Check className="mt-[3px] h-4 w-4 shrink-0 text-ink-2" {...ICON} aria-hidden />
                  {line}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {state && (
        <div className="grid gap-4">
          <Card>
            <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-4">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <h2 className="text-h3 text-ink">{pricing.plans[state.tier as PlanId]?.name ?? state.tier}</h2>
                  <Status tone={state.revoked ? 'danger' : 'success'}>
                    {state.revoked ? cab.statusRevoked : cab.statusActive}
                  </Status>
                </div>
                <p className="mt-1 text-footnote text-ink-2">
                  {cab.signedIn} <span className="font-mono text-ink">{maskKey(key)}</span>
                </p>
              </div>
              <div className="grid w-full grid-cols-2 gap-3 sm:flex sm:w-auto">
                <Btn
                  tone="secondary"
                  onClick={() => {
                    void navigator.clipboard?.writeText(key.trim());
                    setCopied(true);
                    window.setTimeout(() => setCopied(false), 1600);
                  }}
                >
                  {copied ? <Check className="h-4 w-4" {...ICON} aria-hidden /> : <Copy className="h-4 w-4" {...ICON} aria-hidden />}
                  {copied ? cab.copied : cab.copyKey}
                </Btn>
                <Btn tone="secondary" onClick={signOut}>
                  <LogOut className="h-4 w-4" {...ICON} aria-hidden />
                  {cab.signOut}
                </Btn>
              </div>
            </div>

            {error && (
              <Notice tone="error" className="mt-4">
                {c.errors[error]}
              </Notice>
            )}

            <dl className="mt-6 grid gap-6 border-t border-line pt-5 md:grid-cols-3 md:gap-0 md:divide-x md:divide-line">
              <div className="md:pr-6">
                <dt className="text-footnote font-semibold text-ink-2">{cab.seatsTitle}</dt>
                <dd className="mt-2">
                  <p className="tnum text-h2 text-ink">
                    {used}
                    <span className="text-ink-3">/{state.max_activations}</span>
                  </p>
                  <SeatGrid used={used} max={state.max_activations} />
                  <p className="mt-3 text-footnote text-ink-2">{cab.seatsFree(free)}</p>
                </dd>
              </div>

              <div className="md:px-6">
                <dt className="text-footnote font-semibold text-ink-2">{cab.updatesTitle}</dt>
                <dd className="mt-2">
                  <p className="tnum text-title-2 text-ink">
                    {left === null ? cab.updatesNone : left > 0 ? cab.updatesLeft(left) : cab.updatesEnded}
                  </p>
                  <UpdateBar
                    left={left}
                    years={plan?.updateYears ?? null}
                    until={state.updates_until}
                    from={cab.windowFrom}
                    to={cab.windowTo}
                  />
                  <p className="mt-3 text-footnote text-ink-2">{cab.perpetual}</p>
                </dd>
              </div>

              <div className="md:pl-6">
                <dt className="text-footnote font-semibold text-ink-2">{cab.offlineTitle}</dt>
                <dd className="mt-2">
                  <p className="tnum text-title-2 text-ink">{cab.offlineValue(OFFLINE_GRACE_DAYS)}</p>
                  <p className="mt-3 text-footnote text-ink-2">{cab.offlineBody()}</p>
                </dd>
              </div>
            </dl>
          </Card>

          <DeviceList
            devices={state.devices}
            max={state.max_activations}
            labels={{
              title: c.devicesTitle,
              empty: c.emptyDevices,
              activated: c.activatedOn,
              release: c.release,
              releasing: c.releasing,
              free: cab.seatsFree,
            }}
            hint={c.releaseHint}
            releasing={releasing}
            onRelease={(fp) => void release(fp)}
          />

          <div className="grid gap-4 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,0.85fr)]">
            <Card>
              <SubHead>{cab.rightsTitle}</SubHead>
              <p className="mt-2 text-footnote text-ink-2">{cab.rightsLead}</p>
              <ul className="mt-4 grid gap-2.5 sm:grid-cols-2">
                {FEATURE_ORDER.map((fk) => {
                  const f = plan ? planHas(plan, fk) : undefined;
                  const on = Boolean(f?.live);
                  const surface = pricing.surfaces[surfaceOf(fk)];
                  return (
                    <li key={fk} className="flex items-start gap-2.5 text-callout">
                      {on ? (
                        <Check className="mt-[3px] h-4 w-4 shrink-0 text-ink-2" {...ICON} aria-hidden />
                      ) : f ? (
                        <CircleDashed className="mt-[3px] h-4 w-4 shrink-0 text-ink-3" {...ICON} aria-hidden />
                      ) : (
                        <Minus className="mt-[3px] h-4 w-4 shrink-0 text-ink-3" {...ICON} aria-hidden />
                      )}
                      <span className={`flex flex-wrap items-center gap-x-2 gap-y-1 ${on ? 'text-ink' : 'text-ink-2'}`}>
                        {pricing.features[fk]}
                        {surface && <Tag>{surface}</Tag>}
                        {f && !on && <span className="text-footnote text-ink-3">{cab.roadmapTag}</span>}
                        {!f && <span className="text-footnote text-ink-3">{cab.lockedTag}</span>}
                      </span>
                    </li>
                  );
                })}
              </ul>
            </Card>

            <Card>
              <SubHead>{cab.certTitle}</SubHead>
              <p className="mt-2 text-callout text-ink-2">{cab.certBody}</p>
              <details className="group mt-4 border-t border-line pt-2">
                <summary className="flex min-h-[44px] cursor-pointer list-none items-center text-callout font-semibold text-accent-text">
                  {cab.certMore}
                </summary>
                <p className="mt-2 text-footnote font-semibold text-ink-2">{cab.certFieldsLabel}</p>
                <dl className="mt-2 divide-y divide-line">
                  {cab.certFields.map(([name, what]) => (
                    <div key={name} className="flex flex-wrap items-baseline gap-x-3 py-2">
                      <dt className="font-mono text-footnote text-ink">{name}</dt>
                      <dd className="text-footnote text-ink-2">{what}</dd>
                    </div>
                  ))}
                </dl>
                <p className="mt-4 text-footnote font-semibold text-ink-2">{cab.certKeyLabel}</p>
                <p
                  className={`mt-2 break-all rounded-control bg-raised p-3 text-footnote ${
                    pubKey ? 'font-mono text-ink' : 'text-ink-2'
                  }`}
                >
                  {pubKey ?? cab.certUnknown}
                </p>
              </details>
            </Card>
          </div>
        </div>
      )}

      <div className="mt-4">
        {!recoverOpen ? (
          <button type="button" onClick={() => setRecoverOpen(true)} className="btn-ghost -ml-3 !min-h-[44px]">
            {c.recover.open}
          </button>
        ) : (
          <Card className="max-w-xl">
            <h2 className="text-h3 text-ink">{c.recover.title}</h2>
            <p className="mt-2 text-callout text-ink-2">{c.recover.lead}</p>

            {recStage !== 'done' && (
              <form
                className="mt-5 space-y-4"
                noValidate
                onSubmit={(e) => {
                  e.preventDefault();
                  void submitRecovery();
                }}
              >
                <Field
                  label={c.recover.emailLabel}
                  type="email"
                  value={recEmail}
                  onChange={setRecEmail}
                  placeholder={c.recover.emailPlaceholder}
                  autoComplete="email"
                  inputMode="email"
                  disabled={codeStage}
                  error={recFailed && recStage === 'idle' ? c.recover.offline : null}
                />

                {codeStage && (
                  <>
                    <Notice tone="success">{c.recover.sent}</Notice>
                    <Field
                      label={c.recover.codeLabel}
                      value={recCode}
                      onChange={(next) => setRecCode(next.replace(/\D/g, '').slice(0, 6))}
                      placeholder={c.recover.codePlaceholder}
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      mono
                      error={recFailed === 'wrongCode' ? c.recover.failed : recFailed ? c.recover.offline : null}
                    />
                    <p className="rounded-control bg-accent-soft px-4 py-3 text-footnote text-ink">{c.recover.warn}</p>
                  </>
                )}

                <div className="flex flex-wrap gap-3">
                  <Btn
                    type="submit"
                    busy={recStage === 'sending' || recStage === 'confirming'}
                    disabled={codeStage ? recCode.length !== 6 : !recEmail.includes('@')}
                  >
                    {recStage === 'idle' && c.recover.send}
                    {recStage === 'sending' && c.recover.sending}
                    {recStage === 'sent' && c.recover.confirm}
                    {recStage === 'confirming' && c.recover.confirming}
                  </Btn>
                  <Btn
                    tone="ghost"
                    onClick={() => {
                      setRecoverOpen(false);
                      setRecStage('idle');
                      setRecCode('');
                      setRecFailed(false);
                    }}
                  >
                    {c.recover.cancel}
                  </Btn>
                </div>
              </form>
            )}

            {recStage === 'done' && (
              <Notice tone="success" className="mt-5">
                {c.recover.done}
              </Notice>
            )}
          </Card>
        )}
      </div>
    </div>
  );
}

function UpdateBar({
  left,
  years,
  until,
  from,
  to,
}: {
  left: number | null;
  years: number | null;
  until: string | null;
  from: string;
  to: string;
}) {
  if (left === null || !years || !until) return null;
  const total = years * 365;
  const gone = Math.max(0, Math.min(100, ((total - left) / total) * 100));
  const end = new Date(until);
  const start = new Date(end.getTime() - total * 86_400_000);
  const iso = (d: Date) => d.toISOString().slice(0, 10);

  return (
    <div className="mt-4">
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-overlay" aria-hidden>
        <span className="block h-full rounded-full bg-accent-line" style={{ width: `${gone}%` }} />
      </div>
      <div className="tnum mt-2 flex justify-between text-caption text-ink-3">
        <span>
          {from} {iso(start)}
        </span>
        <span>
          {to} {iso(end)}
        </span>
      </div>
    </div>
  );
}

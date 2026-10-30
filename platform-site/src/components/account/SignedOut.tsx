import { useEffect, useMemo, useRef, useState } from 'react';
import { ArrowLeft, Loader2, QrCode, RotateCcw } from 'lucide-react';
import { useSearchParams } from 'react-router-dom';
import { Section } from '../ui';
import Accent from '../wow/Accent';
import { Btn, Card, Field } from './Panels';
import KeyCheck from './KeyCheck';
import { useLanguage } from '../../i18n';
import { accountCopy } from '../../i18n/account';
import {
  ApiError,
  pollQrAuth,
  post,
  startQrAuth,
  writeToken,
  type AuthVerifyResponse,
  type QrPollResponse,
  type QrStartResponse,
} from '../../lib/account-api';
import { codeWasSent, qrPollStops, statusOf } from '../../lib/accountFlow';
import { encodeQrMatrix, encodeQrSvgPath } from '../../lib/qr';

const ICON = { strokeWidth: 1.75 } as const;

const QR_START_TIMEOUT_MS = 10_000;

export type QrAuthStatus = 'loading' | 'pending' | 'authenticated' | 'expired' | 'unreachable';

export interface QrAuthSnapshot {
  status: QrAuthStatus;
  qrPayload: string | null;
  secondsLeft: number;
}

export interface QrAuthControllerOptions {
  start: () => Promise<QrStartResponse>;
  poll: (challengeId: string, pollToken: string) => Promise<QrPollResponse>;
  onAuthenticated: (token: string, polled: QrPollResponse) => Promise<void>;
  onChange: (snapshot: QrAuthSnapshot) => void;
}

export function createQrAuthController(opts: QrAuthControllerOptions) {
  let snap: QrAuthSnapshot = { status: 'loading', qrPayload: null, secondsLeft: 0 };
  let stopped = false;
  let runId = 0;
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let tickTimer: ReturnType<typeof setInterval> | null = null;
  let startTimer: ReturnType<typeof setTimeout> | null = null;
  let busyPolling = false;

  const clearTimers = () => {
    if (pollTimer !== null) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
    if (tickTimer !== null) {
      clearInterval(tickTimer);
      tickTimer = null;
    }
    if (startTimer !== null) {
      clearTimeout(startTimer);
      startTimer = null;
    }
  };

  const emit = (next: QrAuthSnapshot) => {
    snap = next;
    if (!stopped) opts.onChange(snap);
  };

  const start = async () => {
    clearTimers();
    const myRun = ++runId;
    stopped = false;
    busyPolling = false;
    emit({ status: 'loading', qrPayload: null, secondsLeft: 0 });

    try {
      const res = await Promise.race([
        opts.start(),
        new Promise<never>((_, reject) => {
          startTimer = setTimeout(
            () => reject(new Error('/api/auth/qr/start timeout; retry available')),
            QR_START_TIMEOUT_MS,
          );
        }),
      ]);
      if (startTimer !== null) {
        clearTimeout(startTimer);
        startTimer = null;
      }
      if (stopped || myRun !== runId) return;
      const nowSec = Math.floor(Date.now() / 1000);
      const remaining = Math.max(0, res.expires_at - nowSec);
      if (remaining <= 0) {
        emit({ status: 'expired', qrPayload: null, secondsLeft: 0 });
        return;
      }
      emit({ status: 'pending', qrPayload: res.qr_payload, secondsLeft: remaining });

      tickTimer = setInterval(() => {
        if (stopped || myRun !== runId || snap.status !== 'pending') return;
        const left = Math.max(0, res.expires_at - Math.floor(Date.now() / 1000));
        if (left <= 0) {
          clearTimers();
          emit({ status: 'expired', qrPayload: null, secondsLeft: 0 });
        } else {
          emit({ ...snap, secondsLeft: left });
        }
      }, 1000);

      pollTimer = setInterval(async () => {
        if (stopped || myRun !== runId || snap.status !== 'pending' || busyPolling) return;
        busyPolling = true;
        let consumed = false;
        try {
          const polled = await opts.poll(res.challenge_id, res.poll_token);
          if (stopped || myRun !== runId) return;
          if (polled.status === 'authenticated' && polled.token) {
            consumed = true;
            clearTimers();
            await opts.onAuthenticated(polled.token, polled);
            if (stopped || myRun !== runId) return;
            emit({ status: 'authenticated', qrPayload: snap.qrPayload, secondsLeft: 0 });
          }
        } catch (err) {
          if (stopped || myRun !== runId) return;
          if (consumed || (err instanceof ApiError && qrPollStops(statusOf(err)))) {
            clearTimers();
            emit({ status: 'expired', qrPayload: null, secondsLeft: 0 });
          }
        } finally {
          busyPolling = false;
        }
      }, 4000);
    } catch {
      if (stopped || myRun !== runId) return;
      clearTimers();
      emit({ status: 'unreachable', qrPayload: null, secondsLeft: 0 });
    }
  };

  const stop = () => {
    stopped = true;
    runId++;
    clearTimers();
  };

  return {
    start,
    stop,
    snapshot: () => snap,
  };
}

function fmtCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

type SignedIn = (token?: string, verified?: AuthVerifyResponse | QrPollResponse) => Promise<void>;

export default function SignedOut({ onDone }: { onDone: SignedIn }) {
  const { lang } = useLanguage();
  const copy = accountCopy[lang];
  const d = copy.doors;
  const [params, setParams] = useSearchParams();
  const onKeyDoor = params.get('door') === 'key';
  const [useEmail, setUseEmail] = useState(false);

  const openQrDoor = () => {
    const next = new URLSearchParams(params);
    next.delete('door');
    next.delete('lost');
    setUseEmail(false);
    setParams(next, { replace: true });
  };

  if (onKeyDoor) {
    const p = copy.portal;
    return (
      <Section>
        <button type="button" onClick={openQrDoor} className="btn-ghost -ml-3 mb-6 !min-h-[44px]">
          <ArrowLeft className="h-4 w-4" {...ICON} aria-hidden />
          {d.qr.useQr}
        </button>
        <p className="eyebrow">{p.eyebrow}</p>
        <h1 className="mt-3 max-w-3xl text-balance text-page-h1 text-ink">{p.title}</h1>
        <p className="mt-4 max-w-prose text-body text-ink-2">{p.lead}</p>
        <div className="mt-8">
          <KeyCheck lostOpen={params.get('lost') === '1'} />
        </div>
      </Section>
    );
  }

  return (
    <div className="t">
      <section className="ph-head">
        <div className="wrap">
          <p className="kick">{d.eyebrow}</p>
          <h1>
            <Accent text={d.title} words={2} />
          </h1>
          <p className="lead">{d.lead}</p>
        </div>
      </section>

      <section className="wrap acct">
        <div className="door-card">
          {useEmail ? (
            <EmailDoor onDone={onDone} onSwitchToQr={() => setUseEmail(false)} useQrLabel={d.qr.useQr} />
          ) : (
            <QrDoor onDone={onDone} onSwitchToEmail={() => setUseEmail(true)} emailLabel={d.emailDoor} labels={d.qr} />
          )}
        </div>

        <div className="faq">
          <h2>{d.answersTitle}</h2>
          <dl>
            {d.answers.map((item) => (
              <div key={item.q}>
                <dt>{item.q}</dt>
                <dd>{item.a}</dd>
              </div>
            ))}
          </dl>
        </div>
      </section>
    </div>
  );
}

export function QrDoor({
  onDone,
  onSwitchToEmail,
  emailLabel,
  labels,
}: {
  onDone: SignedIn;
  onSwitchToEmail: () => void;
  emailLabel: string;
  labels: {
    scan: string;
    newCode: string;
    retry?: string;
    showQr?: string;
    signedIn: string;
    expired: string;
    unreachable: string;
    openApp: string;
  };
}) {
  const [snap, setSnap] = useState<QrAuthSnapshot>({ status: 'loading', qrPayload: null, secondsLeft: 0 });
  const [qrExpanded, setQrExpanded] = useState(false);

  const onDoneRef = useRef(onDone);
  onDoneRef.current = onDone;

  // /api/auth/qr/start with 10 s timeout and Retry text action when unavailable
  const controller = useMemo(
    () =>
      createQrAuthController({
        start: startQrAuth,
        poll: pollQrAuth,
        onAuthenticated: async (token, polled) => {
          writeToken(token);
          await onDoneRef.current(token, polled);
        },
        onChange: setSnap,
      }),
    [],
  );

  useEffect(() => {
    void controller.start();
    return () => {
      controller.stop();
    };
  }, [controller]);

  const qrSvg = useMemo(() => {
    if (!snap.qrPayload) return null;
    try {
      const matrix = encodeQrMatrix(snap.qrPayload);
      return { size: matrix.size, path: encodeQrSvgPath(matrix) };
    } catch {
      return null;
    }
  }, [snap.qrPayload]);

  const lastQr = useRef(qrSvg);
  if (qrSvg) lastQr.current = qrSvg;
  const shownQr = qrSvg ?? lastQr.current;

  const android = typeof navigator !== 'undefined' && /Android/i.test(navigator.userAgent);
  const appHref = (payload: string) => {
    const fallback = `${window.location.origin}/download`;
    return `intent://phantom-os.dev/s?p=${encodeURIComponent(payload)}#Intent;scheme=https;S.browser_fallback_url=${encodeURIComponent(fallback)};end`;
  };

  const title =
    snap.status === 'authenticated'
      ? labels.signedIn
      : snap.status === 'expired'
        ? labels.expired
        : snap.status === 'unreachable'
          ? labels.unreachable
          : labels.scan;
  const retryLabel = labels.retry ?? 'Retry';
  const showQrLabel = labels.showQr ?? 'Show QR code';
  const showFrame = !android || qrExpanded || snap.status !== 'pending';

  return (
    <Card>
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-title-2 text-ink">{title}</h2>
        {snap.status === 'pending' && (!android || qrExpanded) && (
          <span className="tnum text-subhead text-ink-3">{fmtCountdown(snap.secondsLeft)}</span>
        )}
      </div>

      {snap.status === 'pending' && android && snap.qrPayload && (
        <div className="mt-6">
          <a
            href={appHref(snap.qrPayload)}
            className="btn-primary w-full justify-center"
          >
            {labels.openApp}
          </a>
          {!qrExpanded && (
            <div className="mt-3">
              <button
                type="button"
                onClick={() => setQrExpanded(true)}
                className="btn-ghost -ml-3 inline-flex min-h-[44px] items-center"
              >
                {showQrLabel}
              </button>
            </div>
          )}
        </div>
      )}

      {showFrame && (
        <div className="np-frame mt-6 min-h-[250px] rounded-control border border-line bg-raised p-5">
          <div className="np-layer" data-on={snap.status === 'loading' ? '' : undefined}>
            <Loader2 className="h-6 w-6 animate-spin text-ink-3" {...ICON} aria-hidden />
          </div>

          <div className="np-layer" data-on={snap.status === 'pending' ? '' : undefined}>
            {shownQr && (
              <div className="rounded-control bg-white p-3">
                <svg
                  viewBox={`-2 -2 ${shownQr.size + 4} ${shownQr.size + 4}`}
                  className="h-52 w-52"
                  role="img"
                  aria-label={labels.scan}
                  shapeRendering="crispEdges"
                >
                  <rect x="-2" y="-2" width={shownQr.size + 4} height={shownQr.size + 4} className="fill-white" />
                  <path d={shownQr.path} className="fill-black" />
                </svg>
              </div>
            )}
          </div>

          <div className="np-layer" data-on={snap.status === 'authenticated' ? '' : undefined}>
            <svg viewBox="0 0 24 24" className="np-tick h-7 w-7" aria-hidden>
              <path d="M4 12l5 5L20 6" />
            </svg>
          </div>

          <div className="np-layer" data-on={snap.status === 'expired' ? '' : undefined}>
            <div className="flex flex-col items-center gap-3 text-center">
              <p className="text-subhead text-ink-2">{labels.expired}</p>
              <button type="button" onClick={() => void controller.start()} className="btn-ghost inline-flex min-h-[44px] items-center">
                <RotateCcw className="h-4 w-4" {...ICON} aria-hidden />
                {retryLabel}
              </button>
            </div>
          </div>
          <div className="np-layer" data-on={snap.status === 'unreachable' ? '' : undefined}>
            <div className="flex flex-col items-center gap-3 text-center">
              <p className="text-subhead text-ink-2">{labels.unreachable}</p>
              <button type="button" onClick={() => void controller.start()} className="btn-ghost inline-flex min-h-[44px] items-center">
                <RotateCcw className="h-4 w-4" {...ICON} aria-hidden />
                {retryLabel}
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="mt-6 border-t border-line pt-4">
        <Btn tone="ghost" onClick={onSwitchToEmail}>
          {emailLabel}
        </Btn>
      </div>
    </Card>
  );
}

export function EmailDoor({
  onDone,
  onSwitchToQr,
  useQrLabel,
}: {
  onDone: (token?: string, verified?: AuthVerifyResponse) => Promise<void> | void;
  onSwitchToQr: () => void;
  useQrLabel: string;
}) {
  const { lang } = useLanguage();
  const c = accountCopy[lang].login;
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [stage, setStage] = useState<'email' | 'code'>('email');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<'wrong' | 'offline' | 'tooMany' | 'badEmail' | null>(null);

  const fail = (err: unknown) => {
    const status = err instanceof ApiError ? err.status : -1;
    if (status === 0) setError('offline');
    else if (status === 429) setError('tooMany');
    else if (stage === 'email') setError(status === 400 || status === 422 ? 'badEmail' : 'offline');
    else setError('wrong');
  };

  const request = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await post<{ ok?: boolean; sent?: boolean }>('/api/auth/request', {
        email: email.trim().toLowerCase(),
      });
      if (codeWasSent(res)) setStage('code');
      else setError('offline');
    } catch (err) {
      fail(err);
    } finally {
      setBusy(false);
    }
  };

  const verify = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await post<AuthVerifyResponse>('/api/auth/verify', {
        email: email.trim().toLowerCase(),
        code: code.trim(),
      });
      writeToken(res.token);
      await onDone(res.token, res);
    } catch (err) {
      fail(err);
    } finally {
      setBusy(false);
    }
  };

  const tried = useRef('');
  useEffect(() => {
    if (stage === 'code' && code.length === 6 && !busy && tried.current !== code) {
      tried.current = code;
      void verify();
    }
  }, [stage, code, busy]); // eslint-disable-line react-hooks/exhaustive-deps

  const emailError = stage === 'email' && error ? c[error] : null;
  const codeError = stage === 'code' && error ? c[error] : null;

  return (
    <Card>
      <form
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          if (busy) return;
          if (stage === 'email') {
            if (email.includes('@')) void request();
            else setError('badEmail');
          } else if (code.length === 6) {
            void verify();
          }
        }}
      >
        <h2 className="text-title-2 text-ink">{stage === 'email' ? c.title : c.codeTitle}</h2>
        {stage === 'code' && (
          <p className="mt-2 text-subhead text-ink-2">{c.codeLead(email.trim().toLowerCase())}</p>
        )}

        <div className="mt-6 space-y-4">
          {stage === 'email' ? (
            <>
              <Field
                label={c.email}
                value={email}
                onChange={(next) => {
                  setEmail(next);
                  if (error) setError(null);
                }}
                type="email"
                inputMode="email"
                autoComplete="email"
                placeholder={c.emailPlaceholder}
                error={emailError}
              />
              <Btn type="submit" busy={busy} disabled={!email.trim()} full>
                {busy ? c.sending : c.send}
              </Btn>
            </>
          ) : (
            <>
              <Field
                label={c.code}
                value={code}
                onChange={(next) => {
                  setCode(next.replace(/\D/g, '').slice(0, 6));
                  if (error) setError(null);
                }}
                mono
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="000000"
                error={codeError}
              />
              <div className="flex flex-col gap-3 sm:flex-row">
                <Btn type="submit" busy={busy} disabled={code.length !== 6} full>
                  {busy ? c.entering : c.enter}
                </Btn>
                <Btn
                  tone="secondary"
                  full
                  onClick={() => {
                    setStage('email');
                    setCode('');
                    setError(null);
                    tried.current = '';
                  }}
                >
                  {c.again}
                </Btn>
              </div>
            </>
          )}
        </div>

        <div className="mt-6 border-t border-line pt-4">
          <Btn tone="ghost" onClick={onSwitchToQr}>
            <QrCode className="h-4 w-4" {...ICON} aria-hidden />
            {useQrLabel}
          </Btn>
        </div>
      </form>
    </Card>
  );
}

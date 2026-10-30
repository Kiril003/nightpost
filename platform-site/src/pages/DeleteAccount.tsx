import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Check, ChevronRight, Loader2, RotateCcw } from 'lucide-react';
import { Section } from '../components/ui';
import { Btn, Card, Notice, controlSkin, fmtDate } from '../components/account/Panels';
import {
  EmailDoor,
  createQrAuthController,
  type QrAuthSnapshot,
} from '../components/account/SignedOut';
import { useLanguage } from '../i18n';
import { accountCopy } from '../i18n/account';
import { deleteAccountCopy, type DeleteAccountCopy } from '../i18n/deleteAccount';
import type { Lang } from '../i18n/types';
import {
  ApiError,
  cancelErase,
  eraseMe,
  pollQrAuth,
  post,
  startEraseQr,
  writeToken,
} from '../lib/account-api';
import { usePageMeta } from '../lib/meta';
import { cancelEraseStep, signInLeadsTo, statusOf } from '../lib/accountFlow';
import { encodeQrMatrix, encodeQrSvgPath } from '../lib/qr';

const ICON = { strokeWidth: 1.75 } as const;

type Step = 'phone' | 'email' | 'noPhone' | 'confirm' | 'pending' | 'done';

function fmtCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

interface ConfirmStepProps {
  copy: DeleteAccountCopy;
  onDeleted: (dueAt: number) => void;
  onStale: () => void;
}

function ConfirmStep(props: ConfirmStepProps) {
  const { copy, onDeleted, onStale } = props;
  const [busy, setBusy] = useState(false);
  const [forbidden, setForbidden] = useState(false);
  const [failed, setFailed] = useState(false);

  const handleDelete = async () => {
    setBusy(true);
    setForbidden(false);
    setFailed(false);
    try {
      const res = await eraseMe();
      writeToken(null);
      onDeleted(res.due_at);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        onStale();
        return;
      }
      if (err instanceof ApiError && err.status === 403) {
        setForbidden(true);
        return;
      }
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      <ul className="space-y-2 text-callout text-ink-2">
        <li>{copy.removedAfter7Days}</li>
        <li>{copy.onlyOnPhone}</li>
      </ul>
      {forbidden && (
        <Notice tone="error" className="mt-4">
          {copy.notAvailable}
        </Notice>
      )}
      {failed && (
        <Notice tone="error" className="mt-4">
          {copy.retry}
        </Notice>
      )}
      <div className="mt-6">
        <button
          type="button"
          onClick={() => void handleDelete()}
          disabled={forbidden || busy}
          aria-busy={busy || undefined}
          className="inline-flex min-h-[44px] w-full items-center justify-center gap-2 rounded-control bg-danger px-4 text-subhead font-medium text-surface disabled:cursor-not-allowed disabled:bg-ink/[.12] disabled:text-ink/[.38]"
        >
          {busy && <Loader2 className="h-4 w-4 animate-spin" {...ICON} aria-hidden />}
          {copy.title}
        </button>
      </div>
    </Card>
  );
}

interface PendingStepProps {
  copy: DeleteAccountCopy;
  lang: Lang;
  dueAt: number | null;
  onStale: () => void;
}

function PendingStep(props: PendingStepProps) {
  const { copy, lang, dueAt, onStale } = props;
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<'restored' | 'nothing' | null>(null);
  const [failed, setFailed] = useState(false);

  const handleCancel = async () => {
    setBusy(true);
    setFailed(false);
    try {
      await cancelErase();
      writeToken(null);
      setOutcome('restored');
    } catch (err) {
      const next = cancelEraseStep(statusOf(err));
      if (next === 'stale') {
        onStale();
        return;
      }
      if (next === 'nothing') setOutcome('nothing');
      else setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  const dueIso = dueAt ? new Date(dueAt * 1000).toISOString() : null;

  return (
    <Card>
      {outcome === 'restored' ? (
        <>
          <Notice tone="success">{copy.accountRestored}</Notice>
          <div className="mt-6">
            <Link to="/account" className="text-callout text-ink underline underline-offset-4">
              {copy.toAccount}
            </Link>
          </div>
        </>
      ) : outcome === 'nothing' ? (
        <Notice tone="error">{copy.nothingToCancel}</Notice>
      ) : (
        <>
          <p className="text-headline text-ink">{copy.deletionScheduled}</p>
          {dueIso && <p className="tnum mt-2 text-callout text-ink-2">{fmtDate(dueIso, lang)}</p>}
          {failed && (
            <div className="mt-4">
              <Notice tone="error">{copy.retry}</Notice>
            </div>
          )}
          <div className="mt-6">
            <Btn tone="secondary" busy={busy} onClick={() => void handleCancel()} full>
              {copy.cancelDeletion}
            </Btn>
          </div>
        </>
      )}
    </Card>
  );
}

interface NoPhoneProps {
  copy: DeleteAccountCopy;
  useQrLabel: string;
  onBackToQr: () => void;
  onPendingCancel: (dueAt: number | null) => void;
}

function NoPhone(props: NoPhoneProps) {
  const { copy, useQrLabel, onBackToQr, onPendingCancel } = props;
  const [ready, setReady] = useState(false);
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [failed, setFailed] = useState(false);

  if (!ready) {
    return (
      <EmailDoor
        useQrLabel={useQrLabel}
        onSwitchToQr={onBackToQr}
        onDone={(token, verified) => {
          if (token) writeToken(token);
          if (verified?.scope === 'erase_cancel') {
            onPendingCancel(verified.due_at ?? null);
            return;
          }
          setReady(true);
        }}
      />
    );
  }

  const submitThread = async () => {
    setBusy(true);
    setFailed(false);
    try {
      await post('/api/me/threads', { subject: 'Delete account', body: body.trim() });
      setBody('');
      setSent(true);
    } catch (err) {
      if (statusOf(err) === 401) {
        setReady(false);
        return;
      }
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card>
      {sent ? (
        <div className="flex items-center gap-2 text-callout text-success" role="status">
          <Check className="h-4 w-4 shrink-0" {...ICON} aria-hidden />
          <span>{copy.sent}</span>
        </div>
      ) : (
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (!busy && body.trim()) void submitThread();
          }}
        >
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            rows={4}
            aria-label={copy.message}
            placeholder={copy.message}
            className={`${controlSkin} resize-y px-4 py-3`}
          />
          {failed && <Notice tone="error">{copy.retry}</Notice>}
          <Btn type="submit" busy={busy} disabled={!body.trim()} full>
            {copy.send}
          </Btn>
        </form>
      )}
    </Card>
  );
}

interface PhoneQrStepProps {
  copy: DeleteAccountCopy;
  newCodeLabel: string;
  onApproved: () => void;
  onPending: (dueAt: number | null) => void;
  onEmailAccount: () => void;
  onNoPhone: () => void;
}

function PhoneQrStep(props: PhoneQrStepProps) {
  const { copy, newCodeLabel, onApproved, onPending, onEmailAccount, onNoPhone } = props;
  const [snap, setSnap] = useState<QrAuthSnapshot>({
    status: 'loading',
    qrPayload: null,
    secondsLeft: 0,
  });

  const onApprovedRef = useRef(onApproved);
  onApprovedRef.current = onApproved;
  const onPendingRef = useRef(onPending);
  onPendingRef.current = onPending;

  const controller = useMemo(
    () =>
      createQrAuthController({
        start: startEraseQr,
        poll: pollQrAuth,
        onAuthenticated: async (token, polled) => {
          writeToken(token);
          if (signInLeadsTo(polled) === 'pendingErase') onPendingRef.current(polled.due_at ?? null);
          else onApprovedRef.current();
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

  return (
    <Card>
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-title-2 text-ink">{copy.confirmOnPhone}</h2>
        {snap.status === 'pending' && (
          <span className="tnum text-callout text-ink-3">{fmtCountdown(snap.secondsLeft)}</span>
        )}
      </div>

      <div className="mt-6 flex min-h-[240px] flex-col items-center justify-center rounded-lg border border-line bg-white p-5">
        {snap.status === 'loading' && (
          <Loader2 className="h-6 w-6 animate-spin text-ink-3" {...ICON} aria-hidden />
        )}

        {snap.status === 'pending' && qrSvg && (
          <svg
            viewBox={`-2 -2 ${qrSvg.size + 4} ${qrSvg.size + 4}`}
            className="h-52 w-52"
            role="img"
            aria-label={copy.confirmOnPhone}
            shapeRendering="crispEdges"
          >
            <path d={qrSvg.path} className="fill-black" />
          </svg>
        )}

        {snap.status === 'authenticated' && (
          <Check className="h-7 w-7 text-success" {...ICON} aria-hidden />
        )}

        {snap.status === 'expired' && (
          <Btn onClick={() => void controller.start()}>
            <RotateCcw className="h-4 w-4" {...ICON} aria-hidden />
            {newCodeLabel}
          </Btn>
        )}
      </div>

      <div className="mt-6 flex flex-wrap gap-3 border-t border-line pt-4">
        <Btn tone="ghost" onClick={onEmailAccount}>
          {copy.emailAccount}
        </Btn>
        <Btn tone="ghost" onClick={onNoPhone}>
          {copy.noPhone}
        </Btn>
      </div>
    </Card>
  );
}

function DataFacts({ copy }: { copy: DeleteAccountCopy }) {
  return (
    <div className="mt-8 space-y-6">
      <div>
        <h2 className="text-headline text-ink">{copy.stepsTitle}</h2>
        <ol className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-callout text-ink-2">
          {copy.steps.map((s, i) => (
            <li key={s} className="flex items-center gap-2">
              {i > 0 && <ChevronRight className="h-4 w-4 text-ink-3" {...ICON} aria-hidden />}
              <span>{s}</span>
            </li>
          ))}
        </ol>
      </div>
      <div className="grid gap-6 sm:grid-cols-2">
        <div>
          <h2 className="text-headline text-ink">{copy.deletedTitle}</h2>
          <ul className="mt-2 space-y-1.5 text-callout text-ink-2">
            {copy.deleted.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ul>
        </div>
        <div>
          <h2 className="text-headline text-ink">{copy.keptTitle}</h2>
          <ul className="mt-2 space-y-1.5 text-callout text-ink-2">
            {copy.kept.map((s) => (
              <li key={s}>{s}</li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}

export default function DeleteAccount() {
  const { lang } = useLanguage();
  const copy = deleteAccountCopy[lang];
  const qrLabels = accountCopy[lang].doors.qr;
  usePageMeta({ title: copy.title, description: copy.confirmOnPhone });

  const handedOver = (useLocation().state as { pendingDueAt?: number | null } | null)?.pendingDueAt;
  const [step, setStep] = useState<Step>(handedOver !== undefined ? 'pending' : 'phone');
  const [dueAt, setDueAt] = useState<number | null>(handedOver ?? null);

  const dueIso = dueAt ? new Date(dueAt * 1000).toISOString() : null;

  useEffect(() => {
    if (handedOver !== undefined) window.history.replaceState(null, '');
  }, [handedOver]);

  return (
    <Section>
      <div className="max-w-lg">
        <h1 className="mb-6 text-h2 text-ink">{copy.title}</h1>

        {step === 'phone' && (
          <PhoneQrStep
            copy={copy}
            newCodeLabel={qrLabels.newCode}
            onApproved={() => setStep('confirm')}
            onPending={(pendingDueAt) => {
              setDueAt(pendingDueAt);
              setStep('pending');
            }}
            onEmailAccount={() => setStep('email')}
            onNoPhone={() => setStep('noPhone')}
          />
        )}

        {step === 'email' && (
          <EmailDoor
            useQrLabel={qrLabels.useQr}
            onSwitchToQr={() => setStep('phone')}
            onDone={(token, verified) => {
              if (token) writeToken(token);
              if (verified?.scope === 'erase_cancel') {
                setDueAt(verified.due_at ?? null);
                setStep('pending');
                return;
              }
              setStep('confirm');
            }}
          />
        )}

        {step === 'noPhone' && (
          <NoPhone
            copy={copy}
            useQrLabel={qrLabels.useQr}
            onBackToQr={() => setStep('phone')}
            onPendingCancel={(pendingDueAt) => {
              setDueAt(pendingDueAt);
              setStep('pending');
            }}
          />
        )}

        {step === 'confirm' && (
          <ConfirmStep
            copy={copy}
            onDeleted={(nextDueAt) => {
              setDueAt(nextDueAt);
              setStep('done');
            }}
            onStale={() => setStep('phone')}
          />
        )}

        {step === 'pending' && (
          <PendingStep
            copy={copy}
            lang={lang}
            dueAt={dueAt}
            onStale={() => setStep('email')}
          />
        )}

        {step === 'done' && (
          <Card>
            <Notice tone="success">{copy.accountDisabled}</Notice>
            <p className="mt-3 text-callout text-ink-2">{copy.removedAfter7Days}</p>
            {dueIso && <p className="tnum mt-2 text-footnote text-ink-3">{fmtDate(dueIso, lang)}</p>}
          </Card>
        )}

        <DataFacts copy={copy} />
      </div>
    </Section>
  );
}

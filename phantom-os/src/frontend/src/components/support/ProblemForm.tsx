import React, { useEffect, useRef, useState } from 'react';
import { AlertCircle, Check, Clock, ImagePlus, KeyRound, X } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { FormSheet, PrimaryButton } from '../messenger/FormSheet';
import { motionFor } from '../../feel/motionTokens';
import { prefersReduced } from '../../feel/physics';
import { HELP_TOPICS, type HelpTopic } from '../../data/helpArticles';
import { prepareTicketImage, type TicketImage } from '../../lib/ticketImage';
import { queuedLabelKey, useSupportStore, type SupportTicket } from '../../stores/supportStore';

export interface ProblemFormProps {
  open: boolean;
  onClose: () => void;
  initialType?: HelpTopic;
  inline?: boolean;
}

type Step = 'type' | 'details' | 'preview' | 'sent' | 'queued';

const TOPIC_KEY: Record<HelpTopic | 'security', MessageKey> = {
  calls: 'help.topic.calls',
  messages: 'help.topic.messages',
  money: 'help.topic.money',
  account: 'help.topic.account',
  map: 'help.topic.map',
  other: 'help.topic.other',
  security: 'help.topic.security',
};

const MAX_TEXT = 2000;

export function collectDiagnostics(): Record<string, unknown> {
  const nav = typeof navigator !== 'undefined' ? (navigator as Navigator & { connection?: { effectiveType?: string } }) : undefined;
  return {
    appVersion: 'webmsgr',
    os: nav?.userAgent ? nav.userAgent.slice(0, 120) : 'unknown',
    networkType: nav?.connection?.effectiveType ?? (nav?.onLine === false ? 'offline' : 'online'),
    lastErrorCodes: [],
  };
}

export const ProblemForm: React.FC<ProblemFormProps> = ({ open, onClose, initialType, inline = false }) => {
  const { t } = useTranslation();
  const heldCopy = useSupportStore((s) => queuedLabelKey(s, 'support.queued.copy'));
  const [step, setStep] = useState<Step>(initialType ? 'details' : 'type');
  const [type, setType] = useState<HelpTopic | 'security' | null>(initialType ?? null);
  const [subject, setSubject] = useState('');
  const [text, setText] = useState('');
  const [diag, setDiag] = useState(false);
  const [image, setImage] = useState<TicketImage | null>(null);
  const [imageBad, setImageBad] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [shakeSeq, setShakeSeq] = useState(0);
  const idRef = useRef<string | null>(null);
  const reduced = prefersReduced();
  const stampMotion = motionFor('stamp', { reduced });

  useEffect(() => {
    if (!open) return;
    setStep(initialType ? 'details' : 'type');
    setType(initialType ?? null);
    setSubject('');
    setText('');
    setDiag(false);
    setImage(null);
    setImageBad(false);
    setBusy(false);
    setFailed(false);
    idRef.current = null;
  }, [open, initialType]);

  if (!open) return null;

  const canNext = subject.trim().length > 0 && text.trim().length > 0;

  const pickImage = async (file: File | undefined) => {
    if (!file) return;
    setImageBad(false);
    try {
      setImage(await prepareTicketImage(file));
    } catch {
      setImage(null);
      setImageBad(true);
    }
  };

  const send = async () => {
    if (!type || busy) return;
    setBusy(true);
    setFailed(false);
    if (!idRef.current) idRef.current = globalThis.crypto?.randomUUID?.() ?? `tk-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
    try {
      const ticket: SupportTicket = await useSupportStore.getState().submitTicket({
        id: idRef.current,
        type,
        subject,
        text,
        ...(diag ? { diagnostics: collectDiagnostics() } : {}),
        ...(image ? { attachment: { mime: image.mime, size: image.size, dataB64: image.dataB64 } } : {}),
      });
      setStep(ticket.status === 'queued' ? 'queued' : 'sent');
    } catch {
      setFailed(true);
      setShakeSeq((n) => n + 1);
    } finally {
      setBusy(false);
    }
  };

  const onBack =
    step === 'details' && !initialType ? () => setStep('type') : step === 'preview' ? () => setStep('details') : undefined;

  const footer =
    step === 'details' ? (
      <PrimaryButton onClick={() => setStep('preview')} disabled={!canNext}>
        <span data-testid="problem-next-btn">{t('problem.next')}</span>
      </PrimaryButton>
    ) : step === 'preview' ? (
      <PrimaryButton onClick={() => void send()} disabled={busy}>
        <span data-testid="problem-submit-btn">{failed ? t('support.failed.retry') : t('problem.send')}</span>
      </PrimaryButton>
    ) : step === 'sent' || step === 'queued' ? (
      <PrimaryButton onClick={onClose}>{t('support.sent.done')}</PrimaryButton>
    ) : undefined;

  return (
    <FormSheet
      open={open}
      onClose={onClose}
      onBack={onBack}
      inline={inline}
      title={t('help.reportProblem')}
      testId="problem-form-sheet"
      footer={footer}
    >
      {step === 'type' && (
        <div className="px-4 pb-4">
          <div role="radiogroup" aria-label={t('help.reportProblem')} className="rounded-card bg-surface overflow-hidden">
            {[...HELP_TOPICS, 'security' as const].map((k, idx) => (
              <button
                key={k}
                type="button"
                role="radio"
                aria-checked={type === k}
                data-testid={`problem-type-${k}`}
                onClick={() => {
                  setType(k);
                  setStep('details');
                }}
                className={`w-full min-h-12 px-4 flex items-center text-left text-callout text-ink active:bg-overlay hover:bg-overlay/60 transition-colors duration-quick ${idx > 0 ? 'border-t border-line' : ''}`}
              >
                {t(TOPIC_KEY[k])}
              </button>
            ))}
          </div>
        </div>
      )}

      {step === 'details' && (
        <div className="px-4 pb-4 space-y-4">
          <div>
            <label htmlFor="problem-subject" className="block px-1 pb-1.5 text-footnote font-semibold text-ink-2">
              {t('problem.subject')}
            </label>
            <input
              id="problem-subject"
              data-testid="problem-subject-input"
              maxLength={120}
              value={subject}
              onChange={(e) => setSubject(e.target.value.slice(0, 120))}
              className="w-full h-12 px-3 rounded-control bg-surface border border-line text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line"
            />
          </div>
          <div>
            <label htmlFor="problem-text" className="block px-1 pb-1.5 text-footnote font-semibold text-ink-2">
              {t('problem.text')}
            </label>
            <textarea
              id="problem-text"
              data-testid="problem-text-input"
              maxLength={MAX_TEXT}
              rows={5}
              value={text}
              onChange={(e) => setText(e.target.value.slice(0, MAX_TEXT))}
              className="w-full p-3 rounded-control bg-surface border border-line text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line resize-none"
            />
            <div className="mt-1 px-1 flex justify-end text-caption text-ink-3 tnum">
              {text.length} / {MAX_TEXT}
            </div>
          </div>
          <div className="rounded-card bg-surface overflow-hidden">
            {image ? (
              <div className="min-h-14 px-4 py-2 flex items-center gap-3">
                <img
                  data-testid="problem-attach-thumb"
                  alt={t('problem.attach.label')}
                  src={`data:${image.mime};base64,${image.dataB64}`}
                  className="w-12 h-12 rounded-control object-cover"
                />
                <span className="min-w-0 flex-1 text-callout text-ink">
                  {t('problem.attach.label')}
                  <span className="block text-footnote text-ink-2 tnum">{Math.max(1, Math.round(image.size / 1024))} KB</span>
                </span>
                <button
                  type="button"
                  data-testid="problem-attach-remove"
                  aria-label={t('problem.attach.remove')}
                  onClick={() => setImage(null)}
                  className="w-11 h-11 flex items-center justify-center text-ink-2"
                >
                  <X className="w-5 h-5" strokeWidth={1.75} aria-hidden />
                </button>
              </div>
            ) : (
              <label className="w-full min-h-14 px-4 py-2 flex items-center gap-3 cursor-pointer text-callout font-medium text-ink">
                <ImagePlus className="w-5 h-5 text-ink-2" strokeWidth={1.75} aria-hidden />
                {t('problem.attach.add')}
                <input
                  type="file"
                  accept="image/*"
                  data-testid="problem-attach-input"
                  className="sr-only"
                  onChange={(e) => {
                    void pickImage(e.target.files?.[0]);
                    e.target.value = '';
                  }}
                />
              </label>
            )}
            {imageBad && (
              <p role="alert" data-testid="problem-attach-bad" className="px-4 pb-2 text-footnote text-danger">
                {t('problem.attach.bad')}
              </p>
            )}
          </div>
          <div className="rounded-card bg-surface overflow-hidden">
            <label className="w-full min-h-14 px-4 py-2 flex items-center justify-between gap-3 cursor-pointer">
              <span className="min-w-0 flex-1">
                <span className="block text-callout font-medium text-ink">{t('problem.diag.label')}</span>
                <span className="block text-footnote text-ink-2">{t('problem.diag.what')}</span>
                <span className="block text-footnote text-ink-3">{t('problem.diag.keep')}</span>
              </span>
              <input
                type="checkbox"
                data-testid="problem-diagnostics-toggle"
                checked={diag}
                onChange={() => setDiag((v) => !v)}
                className="w-5 h-5 accent-accent rounded"
              />
            </label>
          </div>
          <p className="px-1 flex items-center gap-1.5 text-footnote text-ink-2">
            <KeyRound className="w-3.5 h-3.5" strokeWidth={1.75} aria-hidden />
            {t('problem.sig')}
          </p>
        </div>
      )}

      {step === 'preview' && (
        <div
          key={shakeSeq}
          data-shake={failed && !reduced ? 'true' : 'false'}
          className={`px-4 pb-4 space-y-3 ${failed && !reduced ? 'f-shake' : ''}`}
        >
          {failed && (
            <div role="alert" data-testid="problem-failed" className="p-3.5 rounded-card bg-surface border border-line flex items-start gap-3">
              <AlertCircle className="w-5 h-5 shrink-0 text-danger mt-0.5" strokeWidth={1.75} aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="text-subhead font-semibold text-danger">{t('support.failed.title')}</p>
                <p className="text-footnote text-ink-2">{t('problem.failedCopy')}</p>
              </div>
            </div>
          )}
          <div data-testid="problem-preview" className="p-3.5 rounded-card bg-surface space-y-2">
            <p className="text-footnote font-semibold text-ink-2">{t('problem.previewTitle')}</p>
            <p className="text-callout text-ink-2">{type ? t(TOPIC_KEY[type]) : ''}</p>
            <p className="text-headline text-ink break-words">{subject.trim()}</p>
            <p className="text-callout text-ink break-words whitespace-pre-wrap">{text.trim()}</p>
            {image && (
              <img
                data-testid="problem-preview-image"
                alt={t('problem.attach.label')}
                src={`data:${image.mime};base64,${image.dataB64}`}
                className="max-h-40 rounded-control"
              />
            )}
            <p className="text-footnote text-ink-2">{diag ? t('problem.diag.label') : t('problem.diag.off')}</p>
            <p className="text-footnote text-ink-2 flex items-center gap-1.5">
              <KeyRound className="w-3.5 h-3.5" strokeWidth={1.75} aria-hidden />
              {t('problem.sig')}
            </p>
          </div>
        </div>
      )}

      {step === 'sent' && (
        <div className="px-4 py-6 flex flex-col items-center text-center space-y-2">
          <span
            data-testid="problem-sent-stamp"
            data-motion={stampMotion.end ? 'end' : 'stamp'}
            style={stampMotion.end ? undefined : { animationTimingFunction: stampMotion.easing, animationDuration: `${stampMotion.ms}ms` }}
            className={`inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-surface text-ink text-subhead font-semibold ${stampMotion.end ? '' : 'f-stamp-in'}`}
          >
            <Check className="w-4 h-4 text-success" strokeWidth={2} aria-hidden />
            {t('support.sent.stamp')}
          </span>
          <h3 className="text-title-1 text-ink">{t('problem.sentTitle')}</h3>
          <p className="text-callout text-ink-2">{t('problem.sentSub')}</p>
        </div>
      )}

      {step === 'queued' && (
        <div data-testid="problem-queued" className="px-4 py-6 flex flex-col items-center text-center space-y-2">
          <span className="w-12 h-12 rounded-full bg-surface flex items-center justify-center text-ink-2">
            <Clock className="w-6 h-6" strokeWidth={1.75} aria-hidden />
          </span>
          <h3 className="text-title-1 text-ink">{t('support.queued.title')}</h3>
          <p className="text-callout text-ink-2">{t(heldCopy)}</p>
        </div>
      )}
    </FormSheet>
  );
};

export default ProblemForm;

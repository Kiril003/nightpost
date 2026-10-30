import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { AlertCircle, Check, Clock, KeyRound } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { FormSheet, PrimaryButton } from '../messenger/FormSheet';
import { Button } from '../messenger/kit/Button';
import { messengerApi } from '../../services/messengerApi';
import { motionFor } from '../../feel/motionTokens';
import { prefersReduced } from '../../feel/physics';
import {
  REPORT_REASONS,
  UNDO_WINDOW_MS,
  nextClientUuid,
  queuedLabelKey,
  useSupportStore,
  type ReportReason,
  type ReportTarget,
  type SupportReport,
} from '../../stores/supportStore';

export interface ReportSheetProps {
  open: boolean;
  target: ReportTarget;
  onClose: () => void;
  onBlocked?: (blocked: boolean) => void;
  inline?: boolean;
}

const REASON_KEY: Record<ReportReason, MessageKey> = {
  spam: 'support.reason.spam',
  scam: 'support.reason.scam',
  harassment: 'support.reason.harassment',
  violence: 'support.reason.violence',
  sexual: 'support.reason.sexual',
  minor_safety: 'support.reason.minor_safety',
  impersonation: 'support.reason.impersonation',
  other: 'support.reason.other',
};

type Step = 'compose' | 'sent' | 'queued';

export const ReportSheet: React.FC<ReportSheetProps> = ({
  open,
  target,
  onClose,
  onBlocked,
  inline = false,
}) => {
  const { t } = useTranslation();
  const [step, setStep] = useState<Step>('compose');
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [sendError, setSendError] = useState(false);
  const [shakeSeq, setShakeSeq] = useState(0);
  const [submitted, setSubmitted] = useState<SupportReport | null>(null);
  const [canUndo, setCanUndo] = useState(false);
  const [blockChecked, setBlockChecked] = useState(false);
  const reportId = useRef<string | null>(null);
  const held = useSupportStore((s) => queuedLabelKey(s, 'support.queued.copy'));

  const peerId =
    target.peerId || (target.targetKind === 'person' ? target.targetId : undefined);
  const senderKey = target.senderKey || target.targetId;
  const blockFirst = !!peerId && target.surface !== 'public';
  const sharedCount = target.messageIds?.length ?? (target.messageText ? 1 : 0);
  const reduced = prefersReduced();
  const stampMotion = motionFor('stamp', { reduced });

  useEffect(() => {
    if (!open) return;
    setStep('compose');
    setReason(null);
    setNote('');
    setBusy(false);
    setSendError(false);
    setShakeSeq(0);
    setSubmitted(null);
    setCanUndo(false);
    setBlockChecked(false);
    reportId.current = null;
  }, [open, target.targetKind, target.targetId]);

  useEffect(() => {
    if (!submitted || !canUndo) return;
    const timer = window.setTimeout(() => {
      setCanUndo(false);
    }, UNDO_WINDOW_MS);
    return () => window.clearTimeout(timer);
  }, [submitted, canUndo]);

  if (!open) return null;

  const handleSend = async () => {
    if (!reason || busy) return;
    setBusy(true);
    setSendError(false);
    try {
      if (blockFirst && peerId && !blockChecked) {
        await messengerApi.blockPeer(peerId);
        setBlockChecked(true);
        onBlocked?.(true);
      }
      reportId.current ??= nextClientUuid();
      const report = await useSupportStore.getState().submitReport({
        id: reportId.current,
        targetKind: target.targetKind,
        targetId: target.targetId,
        targetName: target.targetName,
        chatId: target.chatId,
        messageIds:
          target.messageIds ??
          (target.targetKind === 'message' ? [target.targetId] : undefined),
        messageText: target.messageText,
        senderKey,
        surface: target.surface ?? 'private',
        reason,
        note,
      });
      setSubmitted(report);
      setCanUndo(report.status === 'queued');
      setStep(report.status === 'queued' ? 'queued' : 'sent');
    } catch {
      setSendError(true);
      setShakeSeq((n) => n + 1);
    } finally {
      setBusy(false);
    }
  };

  const handleUndo = () => {
    if (!submitted) return;
    const undone = useSupportStore.getState().undoReport(submitted.id);
    if (undone) {
      setCanUndo(false);
      onClose();
    }
  };

  const handleToggleBlock = async () => {
    if (!peerId) return;
    const next = !blockChecked;
    setBlockChecked(next);
    try {
      if (next) {
        await messengerApi.blockPeer(peerId);
      } else {
        await messengerApi.unblockPeer(peerId);
      }
      onBlocked?.(next);
    } catch {
      setBlockChecked(!next);
    }
  };

  const footer =
    step === 'compose' ? (
      <PrimaryButton
        onClick={() => void handleSend()}
        disabled={!reason || busy}
      >
        <span data-testid="report-send-btn">
          {sendError ? t('support.failed.retry') : t(blockFirst ? 'support.report.blockSend' : 'support.report.send')}
        </span>
      </PrimaryButton>
    ) : (
      <PrimaryButton onClick={onClose}>{t('support.sent.done')}</PrimaryButton>
    );

  return (
    <FormSheet
      open={open}
      onClose={onClose}
      inline={inline}
      title={t('support.report.title')}
      testId="report-sheet"
      footer={footer}
    >
      {step === 'compose' ? (
        <div
          key={shakeSeq}
          data-shake={sendError && !reduced ? 'true' : 'false'}
          className={`px-4 pb-4 space-y-4 ${sendError && !reduced ? 'f-shake' : ''}`}
        >
          {sendError && (
            <div
              data-testid="report-failed"
              role="alert"
              className="p-3.5 rounded-card bg-surface border border-line flex items-start gap-3"
            >
              <AlertCircle className="w-5 h-5 shrink-0 text-danger mt-0.5" strokeWidth={1.75} aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="text-subhead font-semibold text-danger">{t('support.failed.title')}</p>
                <p className="text-footnote text-ink-2">{t('support.failed.copy')}</p>
              </div>
            </div>
          )}

          <div>
            <p className="px-1 pb-2 text-footnote font-semibold text-ink-2">
              {t('support.report.reasonLabel')}
            </p>
            <div
              role="radiogroup"
              aria-label={t('support.report.reasonLabel')}
              className="rounded-card bg-surface overflow-hidden"
            >
              {REPORT_REASONS.map((r, idx) => {
                const selected = reason === r;
                return (
                  <button
                    key={r}
                    type="button"
                    role="radio"
                    aria-checked={selected}
                    data-testid={`report-reason-${r}`}
                    onClick={() => setReason(r)}
                    className={`w-full min-h-12 px-4 flex items-center justify-between gap-3 text-left text-callout text-ink active:bg-overlay hover:bg-overlay/60 transition-colors duration-quick ${
                      idx > 0 ? 'border-t border-line' : ''
                    }`}
                  >
                    <span>{t(REASON_KEY[r])}</span>
                    <span
                      aria-hidden="true"
                      className={`w-5 h-5 rounded-full border-2 flex items-center justify-center ${
                        selected ? 'border-accent bg-accent' : 'border-line-strong'
                      }`}
                    >
                      {selected && <span className="w-2 h-2 rounded-full bg-on-accent" />}
                    </span>
                  </button>
                );
              })}
            </div>
          </div>

          <div>
            <label
              htmlFor="report-note-field"
              className="block px-1 pb-1.5 text-footnote font-semibold text-ink-2"
            >
              {t('support.report.noteLabel')}
            </label>
            <textarea
              id="report-note-field"
              data-testid="report-note-input"
              maxLength={500}
              rows={3}
              value={note}
              onChange={(e) => setNote(e.target.value.slice(0, 500))}
              placeholder={t('support.report.notePlaceholder')}
              className="w-full p-3 rounded-control bg-surface border border-line text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line resize-none"
            />
            <div className="mt-1 px-1 flex justify-end">
              <span data-testid="report-note-counter" className="text-caption text-ink-3 tnum">
                {note.length} / 500
              </span>
            </div>
          </div>

          <div
            data-testid="report-preview"
            className="p-3.5 rounded-card bg-surface space-y-2"
          >
            <p className="text-footnote font-semibold text-ink-2">
              {t('support.report.previewTitle')}
            </p>
            {target.messageText && (
              <div className="p-2.5 rounded-control bg-raised text-callout text-ink break-words">
                {target.messageText}
              </div>
            )}
            <div className="space-y-1.5 text-footnote">
              <div data-testid="report-shared-messages" className="flex items-center justify-between gap-3">
                <span className="text-ink-2">{t('support.preview.messages')}</span>
                <span className="text-ink font-medium">
                  {t(sharedCount > 0 ? 'support.preview.messagesSelected' : 'support.preview.messagesNone')}
                </span>
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="text-ink-2">{t('support.report.reasonLabel')}</span>
                <span className="text-ink font-medium">
                  {reason ? t(REASON_KEY[reason]) : '—'}
                </span>
              </div>
              {note.trim() && (
                <div className="flex items-start justify-between gap-3">
                  <span className="text-ink-2 shrink-0">{t('support.report.noteLabel')}</span>
                  <span className="text-ink text-right break-words">{note.trim()}</span>
                </div>
              )}
              <div className="flex items-center justify-between gap-3">
                <span className="text-ink-2 flex items-center gap-1">
                  <KeyRound className="w-3.5 h-3.5" strokeWidth={1.75} aria-hidden />
                  {t('support.preview.senderKey')}
                </span>
                <span className="text-ink font-mono text-caption truncate max-w-[180px]">
                  {senderKey}
                </span>
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="text-ink-2">{t('support.preview.whoSees')}</span>
                <span className="text-ink-2">{t('support.preview.whoSeesValue')}</span>
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="text-ink-2">{t('support.preview.fromYou')}</span>
                <span className="text-ink-2">{t('support.preview.fromYouValue')}</span>
              </div>
              <div className="flex items-center justify-between gap-3">
                <span className="text-ink-2">{t('support.preview.retention')}</span>
                <span className="text-ink-2">{t('support.preview.retentionValue')}</span>
              </div>
            </div>
          </div>
        </div>
      ) : (
        <div className="px-4 py-6 flex flex-col items-center text-center space-y-5">
          {step === 'sent' ? (
            <div className="flex flex-col items-center space-y-2">
              <span
                data-testid="report-sent-stamp"
                data-motion={stampMotion.end ? 'end' : 'stamp'}
                style={
                  stampMotion.end
                    ? undefined
                    : { animationTimingFunction: stampMotion.easing, animationDuration: `${stampMotion.ms}ms` }
                }
                className={`inline-flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-surface text-ink text-subhead font-semibold ${
                  stampMotion.end ? '' : 'f-stamp-in'
                }`}
              >
                <Check className="w-4 h-4 text-success" strokeWidth={2} aria-hidden />
                {t('support.sent.stamp')}
              </span>
              <h3 className="text-title-1 text-ink">{t('support.sent.title')}</h3>
              <p className="text-callout text-ink-2">{t('support.sent.subtitle')}</p>
            </div>
          ) : (
            <div data-testid="report-queued" className="flex flex-col items-center space-y-2">
              <span className="w-12 h-12 rounded-full bg-surface flex items-center justify-center text-ink-2">
                <Clock className="w-6 h-6" strokeWidth={1.75} aria-hidden />
              </span>
              <h3 className="text-title-1 text-ink">{t('support.queued.title')}</h3>
              <p className="text-callout text-ink-2">{t(held)}</p>
            </div>
          )}

          {canUndo && (
            <Button
              variant="secondary"
              testId="report-undo-btn"
              onClick={handleUndo}
            >
              {t('support.sent.undo')}
            </Button>
          )}

          {peerId && (
            <div className="w-full rounded-card bg-surface overflow-hidden text-left">
              <label className="w-full min-h-14 px-4 py-2 flex items-center justify-between gap-3 cursor-pointer">
                <span className="min-w-0 flex-1">
                  <span className="block text-callout font-medium text-ink">
                    {t('support.sent.blockPeer')}
                  </span>
                  <span className="block text-footnote text-ink-2">
                    {t('support.sent.blockPeerSub')}
                  </span>
                </span>
                <input
                  type="checkbox"
                  data-testid="report-block-checkbox"
                  checked={blockChecked}
                  onChange={() => void handleToggleBlock()}
                  className="w-5 h-5 accent-accent rounded"
                />
              </label>
            </div>
          )}
        </div>
      )}
    </FormSheet>
  );
};

let primaryHostId: symbol | null = null;
const hostListeners = new Set<() => void>();

export const ReportSheetHost: React.FC<{ onBlocked?: (blocked: boolean) => void }> = ({ onBlocked }) => {
  const [id] = useState(() => Symbol('report-sheet-host'));
  const [isPrimary, setIsPrimary] = useState(false);

  useLayoutEffect(() => {
    const sync = () => {
      if (primaryHostId === null) primaryHostId = id;
      setIsPrimary(primaryHostId === id);
    };
    hostListeners.add(sync);
    sync();
    return () => {
      hostListeners.delete(sync);
      if (primaryHostId === id) {
        primaryHostId = null;
        hostListeners.forEach((fn) => fn());
      }
    };
  }, [id]);

  const target = useSupportStore((s) => s.activeTarget);
  const closeReport = useSupportStore((s) => s.closeReport);

  if (!isPrimary || !target) return null;

  return <ReportSheet open target={target} onClose={closeReport} onBlocked={onBlocked} />;
};

export default ReportSheet;

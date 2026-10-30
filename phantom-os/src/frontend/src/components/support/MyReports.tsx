import React, { useEffect, useState } from 'react';
import { Flag } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { dateTag } from '../../i18n/dateTag';
import { FormSheet, PrimaryButton } from '../messenger/FormSheet';
import { ListRow } from '../messenger/kit/ListRow';
import type { AppealReason, NoticeScope, SupportNotice } from '../../services/supportApi';
import {
  canAppealReport,
  DEVICE_REFUSED,
  heldLabelKey,
  openMyReports,
  reportStatusLabel,
  useSupportStore,
  type ReportReason,
  type ReportTargetKind,
} from '../../stores/supportStore';

export { openMyReports };

export interface MyReportsProps {
  open?: boolean;
  onClose?: () => void;
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

const TARGET_KEY: Record<ReportTargetKind, MessageKey> = {
  message: 'support.preview.targetMessage',
  person: 'support.preview.targetPerson',
  group: 'support.preview.targetGroup',
};

const APPEAL_REASONS: AppealReason[] = ['wrong_decision', 'missing_context', 'mistake', 'other'];

const APPEAL_REASON_KEY: Record<AppealReason, MessageKey> = {
  wrong_decision: 'support.appeal.reason.wrong_decision',
  missing_context: 'support.appeal.reason.missing_context',
  mistake: 'support.appeal.reason.mistake',
  other: 'support.appeal.reason.other',
};

const APPEAL_STATE_KEY = {
  submitted: 'support.appeal.submitted',
  upheld: 'support.appeal.upheld',
  overturned: 'support.appeal.overturned',
} as const satisfies Record<string, MessageKey>;

export const AppealSheet: React.FC<{
  onSend: (reason: AppealReason, text: string) => Promise<void>;
  onClose: () => void;
}> = ({ onSend, onClose }) => {
  const { t } = useTranslation();
  const [reason, setReason] = useState<AppealReason>('wrong_decision');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  const send = async () => {
    setBusy(true);
    setFailed(false);
    try {
      await onSend(reason, text);
      onClose();
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <FormSheet
      open
      onClose={onClose}
      title={t('support.appeal.title')}
      testId="appeal-sheet"
      footer={
        <PrimaryButton onClick={() => void send()} disabled={busy || text.trim().length === 0}>
          <span data-testid="appeal-send">{failed ? t('support.failed.retry') : t('support.appeal.send')}</span>
        </PrimaryButton>
      }
    >
      <div className="px-4 pb-4 space-y-4">
        <div role="radiogroup" aria-label={t('support.appeal.reasonLabel')} className="rounded-card bg-surface overflow-hidden">
          {APPEAL_REASONS.map((k, idx) => (
            <button
              key={k}
              type="button"
              role="radio"
              aria-checked={reason === k}
              data-testid={`appeal-reason-${k}`}
              onClick={() => setReason(k)}
              className={`w-full min-h-12 px-4 flex items-center justify-between text-left text-callout text-ink ${idx > 0 ? 'border-t border-line' : ''}`}
            >
              {t(APPEAL_REASON_KEY[k])}
              {reason === k && <span aria-hidden className="w-2 h-2 rounded-full bg-accent" />}
            </button>
          ))}
        </div>
        <div>
          <label htmlFor="appeal-text" className="block px-1 pb-1.5 text-footnote font-semibold text-ink-2">
            {t('support.appeal.text')}
          </label>
          <textarea
            id="appeal-text"
            data-testid="appeal-text"
            maxLength={1000}
            rows={5}
            value={text}
            onChange={(e) => setText(e.target.value.slice(0, 1000))}
            className="w-full p-3 rounded-control bg-surface border border-line text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line resize-none"
          />
        </div>
        {failed && (
          <p role="alert" data-testid="appeal-failed" className="px-1 text-footnote text-danger">
            {t('support.failed.title')}
          </p>
        )}
      </div>
    </FormSheet>
  );
};

export function formatReportTimeAgo(createdAt: number, locale: string, now = Date.now()): string {
  const diffSec = Math.max(0, Math.round((now - createdAt) / 1000));
  try {
    const rtf = new Intl.RelativeTimeFormat(dateTag(locale), { numeric: 'auto', style: 'short' });
    if (diffSec < 60) return rtf.format(0, 'second');
    const diffMin = Math.floor(diffSec / 60);
    if (diffMin < 60) return rtf.format(-diffMin, 'minute');
    const diffHours = Math.floor(diffMin / 60);
    if (diffHours < 24) return rtf.format(-diffHours, 'hour');
    const diffDays = Math.floor(diffHours / 24);
    return rtf.format(-diffDays, 'day');
  } catch {
    return new Date(createdAt).toLocaleTimeString(dateTag(locale), {
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}

export const MyReports: React.FC<MyReportsProps> = ({
  open = true,
  onClose,
  inline = false,
}) => {
  const { t, locale } = useTranslation();
  const reports = useSupportStore((s) => s.reports);
  const serverPending = useSupportStore((s) => s.serverPending);
  const clockSkew = useSupportStore((s) => s.clockSkew);
  const [appealing, setAppealing] = useState<string | null>(null);
  const appealTarget = reports.find((r) => r.id === appealing);

  useEffect(() => {
    if (!open) return;
    void useSupportStore.getState().refreshStatuses();
  }, [open]);

  if (!open) return null;

  return (
    <FormSheet
      open={open}
      onClose={onClose}
      inline={inline}
      tall
      title={t('support.myReports.title')}
      testId="my-reports-sheet"
    >
      {reports.length === 0 ? (
        <div
          data-testid="my-reports-empty"
          className="px-4 py-16 flex flex-col items-center justify-center text-center gap-3"
        >
          <span className="w-12 h-12 rounded-full bg-surface flex items-center justify-center text-ink-3">
            <Flag className="w-6 h-6" strokeWidth={1.75} aria-hidden />
          </span>
          <p className="text-callout text-ink-2">{t('support.myReports.empty')}</p>
        </div>
      ) : (
        <div className="py-2">
          {reports.map((r) => {
            const reasonText = REASON_KEY[r.reason] ? t(REASON_KEY[r.reason]) : r.reason;
            const targetText = r.targetName || t(TARGET_KEY[r.targetKind]) || r.targetId;
            const chipLabel =
              serverPending && r.status === 'queued' ? t(heldLabelKey(clockSkew)) : reportStatusLabel(r.status, locale);
            const timeAgo = formatReportTimeAgo(r.createdAt, locale);
            return (
              <React.Fragment key={r.id}>
              <ListRow
                data-testid={`my-report-row-${r.id}`}
                leading={
                  <span className="w-10 h-10 shrink-0 rounded-full bg-surface flex items-center justify-center text-ink-2">
                    <Flag className="w-5 h-5" strokeWidth={1.75} aria-hidden />
                  </span>
                }
                title={reasonText}
                subtitle={targetText}
                time={timeAgo}
                badge={
                  <span
                    data-testid={`report-status-chip-${r.id}`}
                    className="shrink-0 h-6 px-2.5 rounded-full bg-surface text-caption font-medium text-ink-2 flex items-center"
                  >
                    {chipLabel}
                  </span>
                }
              />
              {r.status === 'failed' && r.failReason && (
                <p data-testid={`report-refused-${r.id}`} className="px-4 pb-2 pl-[4.25rem] text-footnote text-ink-2">
                  {r.failReason === DEVICE_REFUSED ? t('support.refused.device') : r.failReason}
                </p>
              )}
              {r.appeal ? (
                <p data-testid={`appeal-state-${r.id}`} className="px-4 pb-2 pl-[4.25rem] text-footnote text-ink-2">
                  {t(APPEAL_STATE_KEY[r.appeal.status])}
                </p>
              ) : canAppealReport(r) ? (
                <div className="px-4 pb-2 pl-[4.25rem]">
                  <button
                    type="button"
                    data-testid={`appeal-open-${r.id}`}
                    onClick={() => setAppealing(r.id)}
                    className="min-h-11 text-callout font-medium text-ink underline underline-offset-4"
                  >
                    {t('support.appeal.action')}
                  </button>
                </div>
              ) : null}
              </React.Fragment>
            );
          })}
        </div>
      )}
      {appealTarget && (
        <AppealSheet
          onSend={(reason, text) => useSupportStore.getState().appealReport(appealTarget.id, reason, text)}
          onClose={() => setAppealing(null)}
        />
      )}
    </FormSheet>
  );
};

const SCOPE_KEY: Record<NoticeScope, MessageKey> = {
  key: 'support.notices.scope.key',
  group: 'support.notices.scope.group',
  channel: 'support.notices.scope.channel',
  space: 'support.notices.scope.space',
  place: 'support.notices.scope.place',
};

export const NoticesSheet: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { t, locale } = useTranslation();
  const notices = useSupportStore((s) => s.notices);
  const [appealing, setAppealing] = useState<SupportNotice | null>(null);
  return (
    <FormSheet open onClose={onClose} tall title={t('support.notices.title')} testId="notices-sheet">
      <div className="py-2">
        {notices.map((n) => (
          <React.Fragment key={n.id}>
            <ListRow
              data-testid={`notice-row-${n.id}`}
              leading={
                <span className="w-10 h-10 shrink-0 rounded-full bg-surface flex items-center justify-center text-ink-2">
                  <Flag className="w-5 h-5" strokeWidth={1.75} aria-hidden />
                </span>
              }
              title={t(SCOPE_KEY[n.scope] ?? 'support.notices.scope.key')}
              subtitle={REASON_KEY[n.reason] ? t(REASON_KEY[n.reason]) : n.reason}
              time={formatReportTimeAgo(Date.parse(n.decided_at), locale)}
            />
            {n.appeal ? (
              <p data-testid={`notice-appeal-state-${n.id}`} className="px-4 pb-2 pl-[4.25rem] text-footnote text-ink-2">
                {t(APPEAL_STATE_KEY[n.appeal.status])}
              </p>
            ) : (
              <div className="px-4 pb-2 pl-[4.25rem]">
                <button
                  type="button"
                  data-testid={`notice-appeal-${n.id}`}
                  onClick={() => setAppealing(n)}
                  className="min-h-11 text-callout font-medium text-ink underline underline-offset-4"
                >
                  {t('support.appeal.action')}
                </button>
              </div>
            )}
          </React.Fragment>
        ))}
      </div>
      {appealing && (
        <AppealSheet
          onSend={(reason, text) => useSupportStore.getState().appealNotice(appealing.id, appealing.target, reason, text)}
          onClose={() => setAppealing(null)}
        />
      )}
    </FormSheet>
  );
};

export default MyReports;

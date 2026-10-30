import React, { useState } from 'react';
import { useTranslation } from '../../i18n/useTranslation';
import { FormSheet, PrimaryButton } from '../messenger/FormSheet';
import { useAuthStore } from '../../stores/authStore';
import { dismissCrash, readPendingCrash, sendCrash, type PendingCrash } from '../../lib/crashReport';
import { heldLabelKey, holdKind } from '../../stores/supportStore';

export const CrashPrompt: React.FC = () => {
  const { t } = useTranslation();
  const [crash, setCrash] = useState<PendingCrash | null>(() => readPendingCrash());
  const signedIn = useAuthStore((st) => st.user !== null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [held, setHeld] = useState<'server' | 'clock' | null>(null);

  if (!crash || !signedIn) return null;

  const close = () => {
    dismissCrash();
    setCrash(null);
  };
  const send = async () => {
    setBusy(true);
    setFailed(false);
    setHeld(null);
    try {
      await sendCrash(crash);
      setCrash(null);
    } catch (e) {
      const kind = holdKind(e);
      if (kind) setHeld(kind);
      else setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  const rows: Array<[string, string]> = [
    [t('crash.name'), crash.name],
    [t('crash.hash'), crash.stackHash],
    [t('crash.version'), crash.appVersion],
  ];

  return (
    <FormSheet
      open
      onClose={close}
      title={t('crash.title')}
      testId="crash-prompt"
      footer={
        <PrimaryButton onClick={() => void send()} disabled={busy}>
          <span data-testid="crash-send">{failed ? t('support.failed.retry') : t('crash.send')}</span>
        </PrimaryButton>
      }
    >
      <div className="px-4 pb-4 space-y-3">
        <div data-testid="crash-preview" className="p-3.5 rounded-card bg-surface space-y-2">
          <p className="text-footnote font-semibold text-ink-2">{t('problem.previewTitle')}</p>
          {rows.map(([k, v]) => (
            <p key={k} className="flex justify-between gap-3 text-callout text-ink">
              <span className="text-ink-2">{k}</span>
              <span className="tnum break-all text-right">{v}</span>
            </p>
          ))}
        </div>
        {held && (
          <p data-testid="crash-held" className="px-1 text-footnote text-ink-2">
            {t(heldLabelKey(held === 'clock'))}
          </p>
        )}
        {failed && (
          <p role="alert" data-testid="crash-failed" className="px-1 text-footnote text-danger">
            {t('support.failed.title')}
          </p>
        )}
        <button
          type="button"
          data-testid="crash-later"
          onClick={close}
          className="w-full min-h-12 text-callout text-ink-2"
        >
          {t('crash.later')}
        </button>
      </div>
    </FormSheet>
  );
};

export default CrashPrompt;

import React from 'react';
import { ChevronRight, Phone, Settings, Video } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { FormSheet } from './FormSheet';
import { useCallLog, type CallLogEntry } from '../../services/callLog';
import { callLineText } from './feed/CallLineRow';
import { soundFx } from '../../utils/messengerSound';

const callBack = (e: CallLogEntry) => {
  window.dispatchEvent(
    new CustomEvent('phantom:start-call', {
      detail: { peerNodeId: e.peerNodeId, contactId: e.contactId, displayName: e.name, video: e.media === 'video' },
    }),
  );
};

/** Я › Дзвінки: журнал цього апарата, найновіші зверху, і налаштування. */
export const CallLogSheet: React.FC<{ onClose: () => void; onSettings: () => void }> = ({ onClose, onSettings }) => {
  const { t, locale } = useTranslation();
  const entries = useCallLog((s) => s.entries);
  const when = (at: number) =>
    new Date(at).toLocaleString(locale, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

  return (
    <FormSheet open onClose={onClose} title={t('callsHub.title')} tall testId="call-log-sheet">
      <button
        type="button"
        data-testid="call-log-settings"
        onClick={() => {
          soundFx.playTap();
          onSettings();
        }}
        className="w-full min-h-14 px-4 flex items-center gap-3 text-left active:bg-overlay"
      >
        <Settings className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
        <span className="flex-1 text-body text-ink">{t('callLog.settings')}</span>
        <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
      </button>
      <p className="px-4 pt-4 pb-1 text-footnote font-semibold text-ink-2">{t('callLog.recent')}</p>
      {entries.length === 0 ? (
        <p className="px-4 py-6 text-callout text-ink-3" data-testid="call-log-empty">
          {t('callLog.empty')}
        </p>
      ) : (
        <ul data-testid="call-log-list">
          {entries.map((e) => {
            const Icon = e.media === 'video' ? Video : Phone;
            const may = Boolean(e.peerNodeId);
            return (
              <li key={e.id} data-testid="call-log-row" data-call-kind={e.kind} className="min-h-14 px-4 flex items-center gap-3">
                <div className="flex-1 min-w-0">
                  <p className={`text-body truncate ${e.kind === 'missed' ? 'text-danger' : 'text-ink'}`}>{e.name}</p>
                  <p className="text-footnote text-ink-3 truncate">
                    {callLineText({ kind: e.kind, media: e.media, durationSec: e.durationSec }, t)} · {when(e.at)}
                  </p>
                </div>
                {may && (
                  <button
                    type="button"
                    data-testid={`call-back-${e.id}`}
                    aria-label={t('callLog.callBack')}
                    onClick={() => {
                      soundFx.playTap();
                      onClose();
                      callBack(e);
                    }}
                    className="touch-floor-inline w-11 h-11 shrink-0 rounded-full flex items-center justify-center text-ink-2 active:bg-overlay"
                  >
                    <Icon className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </FormSheet>
  );
};

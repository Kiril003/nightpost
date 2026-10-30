/** Налаштування моментів: лише те, що вузол справді виконує (типова аудиторія, «залишати», приглушені, закриття доступу). */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from '../../i18n/useTranslation';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { momentCircles } from '../../services/audienceCount';
import { useMoments, type Audience, type Reach } from '../../services/moments';
import { Avatar } from './Avatar';
import { TopBar } from './kit/TopBar';
import { circleLabel, momentPeople } from './MomentViewer';
import { chipTone } from './kit/FilterChip';

const reachOf = (a: Audience): Reach => (a.kind === 'circle' ? 'circles' : a.kind);

const Row: React.FC<{ label: string; sub?: string; on?: boolean; role?: 'radio' | 'switch'; onClick: () => void; testId?: string }> = ({ label, sub, on, role = 'radio', onClick, testId }) => (
  <button type="button" role={role} aria-checked={Boolean(on)} data-testid={testId} onClick={onClick} className="w-full min-h-14 min-w-0 flex items-center gap-3 text-left">
    <span className="flex-1 min-w-0">
      <span className="block text-body text-ink truncate">{label}</span>
      {sub && <span className="block text-footnote text-ink-3 truncate">{sub}</span>}
    </span>
    {role === 'radio' ? (
      <span aria-hidden className={`w-5 h-5 shrink-0 rounded-full border-2 flex items-center justify-center ${on ? 'border-ink' : 'border-line-strong'}`}>
        {on && <span className="w-2.5 h-2.5 rounded-full bg-ink" />}
      </span>
    ) : (
      <span aria-hidden className={`w-11 h-7 shrink-0 rounded-full p-0.5 flex ${on ? 'bg-ink justify-end' : 'bg-line-strong justify-start'}`}>
        <span className="w-6 h-6 rounded-full bg-canvas" />
      </span>
    )}
  </button>
);

export const MomentsSettings: React.FC<{ onClose: () => void }> = ({ onClose }) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const prefs = useMoments((s) => s.prefs);
  const loadPrefs = useMoments((s) => s.loadPrefs);
  const savePrefs = useMoments((s) => s.savePrefs);
  const mute = useMoments((s) => s.mute);
  const revokePeer = useMoments((s) => s.revokePeer);
  const [note, setNote] = useState('');
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer('moments:settings', true, () => closeRef.current()), []);
  useEffect(() => {
    void loadPrefs();
  }, [loadPrefs]);
  useEffect(() => {
    if (!note) return undefined;
    const timer = window.setTimeout(() => setNote(''), 2500);
    return () => window.clearTimeout(timer);
  }, [note]);

  const people = useMemo(() => momentPeople(chats), [chats]);
  const circles = useMemo(() => momentCircles(chats), [chats]);
  const byId = useMemo(() => new Map(people.map((p) => [p.id, p])), [people]);
  const aud: Audience = prefs?.audience ?? { kind: 'contacts' };
  const reach = reachOf(aud);
  const chosen = aud.kind === 'circles' ? aud.names : aud.kind === 'circle' ? [aud.name] : [];
  const keep = prefs?.keep ?? false;

  const save = async (next: Audience, nextKeep: boolean) => {
    try {
      await savePrefs(next, nextKeep);
      setNote(t('momentsR.saved'));
    } catch {
      setNote(t('momentsR.saveFailed'));
    }
  };
  const idsOf = (names: string[]) => [...new Set(circles.filter((c) => names.includes(c.name)).flatMap((c) => c.ids))];

  return createPortal(
    <div data-moments-settings role="dialog" aria-modal="true" aria-label={t('momentsR.settings')} className="fixed inset-0 z-[70] bg-canvas flex flex-col" style={{ backgroundColor: 'var(--ph-canvas)' }}>
      <TopBar title={t('momentsR.settings')} onBack={onClose} backLabel={t('moments.back')} safeTop />
      <div className="flex-1 min-h-0 overflow-y-auto px-4 pb-8">
        <h3 className="mt-3 text-footnote text-ink-3">{t('momentsR.defaultAudience')}</h3>
        <Row testId="set-aud-me" label={t('moments.rungMe')} sub={t('momentsR.deviceOnly')} on={reach === 'me'} onClick={() => void save({ kind: 'me' }, keep)} />
        <Row testId="set-aud-contacts" label={t('moments.rungContacts')} on={reach === 'contacts'} onClick={() => void save({ kind: 'contacts' }, keep)} />
        {circles.length > 0 && (
          <>
            <Row testId="set-aud-circles" label={t('moments.rungCircles')} on={reach === 'circles'} onClick={() => {
              const names = chosen.length > 0 ? chosen : circles.some((c) => c.name === 'close') ? ['close'] : [circles[0].name];
              void save({ kind: 'circles', names, ids: idsOf(names) }, keep);
            }} />
            {reach === 'circles' && (
              <div className="mb-2 flex gap-2 flex-wrap">
                {circles.map((c) => {
                  const on = chosen.includes(c.name);
                  return (
                    <button
                      key={c.name}
                      type="button"
                      data-testid={`set-circle-${c.name}`}
                      aria-pressed={on}
                      onClick={() => {
                        const names = on ? chosen.filter((x) => x !== c.name) : [...chosen, c.name];
                        if (names.length > 0) void save({ kind: 'circles', names, ids: idsOf(names) }, keep);
                      }}
                      className={`shrink-0 h-11 min-h-0 px-4 rounded-full border text-subhead ${chipTone(on)}`}
                    >
                      {`${circleLabel(t, c.name)} · ${c.ids.length}`}
                    </button>
                  );
                })}
              </div>
            )}
          </>
        )}
        <div aria-hidden className="my-2 h-px bg-line" />
        <Row testId="set-keep" role="switch" label={t('momentsR.defaultKeep')} on={keep} onClick={() => void save(aud, !keep)} />
        <h3 className="mt-5 text-footnote text-ink-3">{t('momentsR.mutedPeople')}</h3>
        {(prefs?.muted ?? []).length === 0 && <p data-set-muted-none className="mt-2 text-callout text-ink-3">{t('momentsR.mutedNone')}</p>}
        <ul>
          {(prefs?.muted ?? []).map((id) => {
            const p = byId.get(id);
            return (
              <li key={id} className="h-14 flex items-center gap-3">
                <Avatar src={p?.avatar} name={p?.name || id} tone={id} className="w-10 h-10 text-subhead" />
                <span className="flex-1 min-w-0 truncate text-body text-ink">{p?.name || t('chatList.unnamed')}</span>
                <button type="button" data-set-unmute={id} onClick={() => void mute(id, false).catch(() => setNote(t('momentsR.saveFailed')))} className="h-11 min-h-0 px-3 rounded-full bg-raised text-subhead text-ink">
                  {t('momentsR.unmute')}
                </button>
              </li>
            );
          })}
        </ul>
        {people.length > 0 && (
          <>
            <h3 className="mt-5 text-footnote text-ink-3">{t('momentsR.hideFrom')}</h3>
            <ul>
              {people.map((p) => (
                <li key={p.id} className="h-14 flex items-center gap-3">
                  <Avatar src={p.avatar} name={p.name} tone={p.id} className="w-10 h-10 text-subhead" />
                  <span className="flex-1 min-w-0 truncate text-body text-ink">{p.name}</span>
                  <button
                    type="button"
                    data-set-hide={p.id}
                    onClick={() => void revokePeer(p.id).then(() => setNote(t('momentsR.hideDone')), () => setNote(t('momentsR.hideFailed')))}
                    className="h-11 min-h-0 px-3 rounded-full bg-raised text-subhead text-ink"
                  >
                    {t('moments.close')}
                  </button>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>
      {note && (
        <div role="status" data-set-note className="pointer-events-none absolute inset-x-4 flex justify-center" style={{ bottom: 'calc(var(--sab, 0px) + 16px)' }}>
          <div className="min-h-12 px-4 py-3 rounded-card bg-raised border border-line-strong text-subhead text-ink">{note}</div>
        </div>
      )}
    </div>,
    document.body,
  );
};

export default MomentsSettings;

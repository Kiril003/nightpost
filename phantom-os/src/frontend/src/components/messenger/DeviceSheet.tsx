/**
 * «Мій пристрій» — одне вікно і на телефоні, і на ПК.
 *
 * Стан того боку (лише те, що він сам прислав), прямі дії, що він може робити
 * тут, що робилось, і одна кнопка «Зупинити все». Свої сходинки міняються
 * тут; сходинки того боку лише видно — їх міняє людина там.
 */
import { useTranslation } from '../../i18n/useTranslation';
import React, { useCallback, useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ChevronDown, Laptop, Smartphone, X } from 'lucide-react';
import {
  alwaysAsks,
  deviceApi,
  deviceFor,
  hereIsPhone,
  journalWords,
  levelTitle,
  actWords,
  deviceName,
  levelsFor,
  lineTime,
  seenWord,
  shownLevel,
  type JournalLine,
  type LadderSide,
  type Ladders,
  type Level,
  type OwnDevice,
} from '../../services/deviceApi';
import { hasWebSignInBridge, openWebSignIn } from './AddOwnDevice';
import { useMessengerStore } from '../../stores/messengerStore';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { useFocusTrap } from './FormSheet';
import { uk as l1Uk } from '../../i18n/messages/parts/l1';
import type { MessageKey } from '../../i18n/messages/uk';
import { soundFx } from '../../utils/messengerSound';

export interface DeviceSheetProps {
  /** Як пристрій зветься в списку — поки вікно не дізналось більшого. */
  title: string;
  peerNodeId?: string;
  onClose: () => void;
  /** Листування з цим пристроєм. Немає — рядка немає. */
  onWrite?: () => void;
  /** Картка контакту (звірка, «це не мій пристрій»). */
  onProfile?: () => void;
  /** Колонка ПК: вікно лежить у потоці колонки, без порталу, заслінки й свого «×». */
  inline?: boolean;
}

type Act = { verb: string; label: MessageKey; ask?: MessageKey; input?: 'text' | 'url' };

const TO_PC: Act[] = [
  { verb: 'pc.clipboard', label: 'deviceSheet.copyToPc', ask: 'deviceSheet.askCopy', input: 'text' },
  { verb: 'pc.open', label: 'deviceSheet.openLink', ask: 'deviceSheet.askLink', input: 'url' },
  { verb: 'pc.notify', label: 'deviceSheet.showNotification', ask: 'deviceSheet.askShow', input: 'text' },
  { verb: 'pc.lock', label: 'deviceSheet.lockScreen' },
];

const Btn: React.FC<{
  label: string;
  onClick: () => void;
  main?: boolean;
  disabled?: boolean;
  testId?: string;
}> = ({ label, onClick, main = false, disabled = false, testId }) => (
  <button
    type="button"
    data-testid={testId}
    disabled={disabled}
    onClick={() => {
      soundFx.playTap();
      onClick();
    }}
    className={`min-w-0 min-h-[52px] px-3 py-2 rounded-control text-footnote font-semibold font-semibold leading-tight text-center break-words disabled:opacity-50 active:opacity-90 ${
      main ? 'bg-accent text-on-accent' : 'bg-raised text-ink'
    }`}
  >
    {label}
  </button>
);

const SectionTitle: React.FC<{ children: React.ReactNode; aside?: React.ReactNode }> = ({ children, aside }) => (
  <div className="px-1 pb-2 flex items-baseline justify-between gap-3">
    <h3 className="text-title-2 text-ink">{children}</h3>
    {aside}
  </div>
);

const RungRow: React.FC<{
  title: string;
  word: string;
  irreversible: boolean;
  open?: boolean;
  onToggle?: () => void;
  first: boolean;
}> = ({ title, word, irreversible, open = false, onToggle, first }) => {
  const { t } = useTranslation();
  const body = (
    <>
      <span className="flex-1 min-w-0">
        <span className="block text-callout text-ink">{title}</span>
        {irreversible && <span className="block text-caption text-ink-3">{t('deviceSheet.irreversible', { always: alwaysAsks() })}</span>}
      </span>
      <span className="shrink-0 max-w-[55%] text-right text-callout text-ink-2">{word}</span>
      {onToggle && (
        <ChevronDown
          aria-hidden="true"
          strokeWidth={1.75}
          className={`w-5 h-5 shrink-0 text-ink-3 transition-transform duration-instant ${open ? 'rotate-180' : ''}`}
        />
      )}
    </>
  );
  return (
    <>
      {!first && <div aria-hidden="true" className="h-px bg-line ml-3.5" />}
      {onToggle ? (
        <button
          type="button"
          aria-expanded={open}
          onClick={onToggle}
          className="w-full min-h-[52px] py-2.5 px-3.5 flex items-center gap-3 text-left active:bg-raised"
        >
          {body}
        </button>
      ) : (
        <div className="min-h-[52px] py-2.5 px-3.5 flex items-center gap-3">{body}</div>
      )}
    </>
  );
};

export const DeviceSheet: React.FC<DeviceSheetProps> = ({ title, peerNodeId, onClose, onWrite, onProfile, inline = false }) => {
  const { t } = useTranslation();
  const layerId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer(`device${layerId}`, !inline, () => closeRef.current()), [layerId, inline]);
  useEscapeClose(!inline, () => closeRef.current());
  const layerRef = useRef<HTMLDivElement>(null);
  useFocusTrap(layerRef, !inline);

  const onPhone = hereIsPhone() || hasWebSignInBridge();
  const webSignInLabel = t('addOwnDevice.webSignIn');
  const [loaded, setLoaded] = useState(false);
  const [device, setDevice] = useState<OwnDevice | null>(null);
  const [stoppedHere, setStoppedHere] = useState(false);
  const [ladders, setLadders] = useState<Ladders | null>(null);
  const [journal, setJournal] = useState<JournalLine[] | null>(null);
  const [openSphere, setOpenSphere] = useState<string | null>(null);
  const [composing, setComposing] = useState<Act | null>(null);
  const [draft, setDraft] = useState('');
  const [said, setSaid] = useState('');
  const [busy, setBusy] = useState(false);
  const [ringing, setRinging] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const state = await deviceApi.list();
      setStoppedHere(state.stopped);
      const found = deviceFor(state.devices, peerNodeId);
      setDevice(found);
      if (found) {
        const [l, j] = await Promise.all([
          deviceApi.ladder(found.id).catch(() => null),
          deviceApi.journal(found.id).catch(() => null),
        ]);
        if (l) setLadders(l);
        if (j) setJournal(j.entries);
      }
    } catch {
      setDevice(null);
    } finally {
      setLoaded(true);
    }
  }, [peerNodeId]);

  useEffect(() => {
    void refresh();
    const id = window.setInterval(() => void refresh(), 15_000);
    return () => window.clearInterval(id);
  }, [refresh]);

  const name = (device && deviceName(device)) || title;
  const otherIsPc = onPhone;
  const Icon = otherIsPc ? Laptop : Smartphone;

  const run = async (verb: string, text = '') => {
    if (!device) return;
    setBusy(true);
    try {
      const reply = await deviceApi.act(device.id, verb, text);
      setSaid(actWords(reply));
      if (reply.ok) {
        if (verb === 'phone.find') setRinging(true);
        if (verb === 'phone.stop') setRinging(false);
        setComposing(null);
        setDraft('');
      }
    } catch {
      setSaid(t('deviceSheet.failed'));
    } finally {
      setBusy(false);
      void refresh();
    }
  };

  const tap = (act: Act) => {
    setSaid('');
    if (act.input) {
      setComposing(act);
      setDraft('');
    } else {
      setComposing(null);
      void run(act.verb);
    }
  };

  const showPlace = () => {
    if (!device?.place) return;
    const place = device.place;
    onClose();
    useMessengerStore.getState().openLocationSheet({
      name,
      category: '',
      address: '',
      distance: '',
      walkingTime: '',
      coords: { lat: place.lat, lng: place.lon },
    });
  };

  const setRung = async (sphere: string, level: Level) => {
    if (!device) return;
    setOpenSphere(null);
    try {
      setLadders(await deviceApi.setRung(device.id, sphere, level));
    } catch {
      setSaid(t('deviceSheet.permissionUnchanged'));
    }
    void refresh();
  };

  const stop = async (on: boolean) => {
    if (!device) return;
    setSaid('');
    setComposing(null);
    try {
      const r = await deviceApi.stop(device.id, on);
      setStoppedHere(r.stopped);
      setRinging(false);
    } catch {
      setSaid(t('deviceSheet.failed'));
    }
    void refresh();
  };

  const answer = async (entry: JournalLine, yes: boolean) => {
    if (!device) return;
    try {
      setSaid(actWords({ state: 'done', ...(await deviceApi.answer(device.id, entry.id, yes)) }));
    } catch {
      setSaid(t('deviceSheet.failed'));
    }
    void refresh();
  };

  const pcStopped = otherIsPc && Boolean(device?.stopped);
  const acts: Array<{ key: string; node: React.ReactNode }> = [];
  if (device) {
    if (otherIsPc) {
      if (!pcStopped) {
        TO_PC.forEach((a, i) =>
          acts.push({
            key: a.verb,
            node: <Btn label={t(a.label)} main={i === 0 && !stoppedHere} disabled={busy || stoppedHere} onClick={() => tap(a)} testId={`device-act-${a.verb}`} />,
          }),
        );
      }
    } else {
      acts.push({
        key: 'find',
        node: ringing ? (
          <Btn label={t('deviceSheet.stopRinging')} main disabled={busy} onClick={() => void run('phone.stop')} testId="device-act-phone.stop" />
        ) : (
          <Btn label={t('deviceSheet.findPhone')} main={!stoppedHere} disabled={busy || stoppedHere} onClick={() => void run('phone.find')} testId="device-act-phone.find" />
        ),
      });
      if (device.place) {
        acts.push({ key: 'where', node: <Btn label={t('deviceSheet.where')} onClick={showPlace} testId="device-act-where" /> });
      }
      acts.push({
        key: 'speak',
        node: (
          <Btn
            label={t('deviceSheet.speakAloud')}
            disabled={busy || stoppedHere}
            onClick={() => tap({ verb: 'phone.speak', label: 'deviceSheet.speakAloud', ask: 'deviceSheet.askSpeak', input: 'text' })}
            testId="device-act-phone.speak"
          />
        ),
      });
    }
  }
  const cols = acts.length === 4 ? 2 : acts.length;

  const status: string[] = [];
  if (device) {
    if (!device.online) {
      const seen = seenWord(device.seen_at);
      if (seen) status.push(seen);
    }
    if (typeof device.battery_pct === 'number') {
      status.push(t(device.charging ? 'deviceSheet.batteryCharging' : 'deviceSheet.battery', { pct: device.battery_pct }));
    }
  }

  const hereTitle = otherIsPc ? t('deviceSheet.pcCanDoOnPhone') : t('deviceSheet.phoneCanDoOnPc');
  const thereTitle = otherIsPc ? t('deviceSheet.thisPhoneOnPc') : t('deviceSheet.pcAllowsPhone');
  const thereNote = otherIsPc ? t('deviceSheet.changeOnPc') : t('deviceSheet.changeOnPhone');

  const side = (s: LadderSide, heading: string, note?: string, testId?: string) => (
    <section className="px-4 pt-6" data-testid={testId}>
      <SectionTitle>{heading}</SectionTitle>
      {note && <p className="px-1 -mt-1 pb-2 text-caption text-ink-3">{note}</p>}
      <div className="rounded-control bg-surface">
        {s.spheres.map((r, i) => (
          <div key={r.sphere} data-rung={r.sphere}>
            <RungRow
              title={r.title}
              word={levelTitle(shownLevel(r))}
              irreversible={r.irreversible}
              first={i === 0}
              open={openSphere === r.sphere}
              onToggle={s.editable ? () => setOpenSphere((v) => (v === r.sphere ? null : r.sphere)) : undefined}
            />
            {s.editable && openSphere === r.sphere && (
              <div role="radiogroup" aria-label={r.title} className="pb-2">
                {levelsFor(r).map((l) => (
                  <button
                    key={l.level}
                    type="button"
                    role="radio"
                    aria-checked={shownLevel(r) === l.level}
                    onClick={() => void setRung(r.sphere, l.level)}
                    className="w-full min-h-11 pl-6 pr-3.5 flex items-center gap-3 text-left text-callout active:bg-raised"
                  >
                    <span
                      aria-hidden="true"
                      className={`w-[18px] h-[18px] shrink-0 rounded-full border-2 ${
                        shownLevel(r) === l.level ? 'border-accent-line bg-accent' : 'border-f-ink-3'
                      }`}
                    />
                    <span className={shownLevel(r) === l.level ? 'text-ink font-semibold' : 'text-ink-2'}>{l.title}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </section>
  );

  const sheet = (
    <div
      ref={layerRef}
      tabIndex={inline ? undefined : -1}
      role={inline ? undefined : 'dialog'}
      aria-modal={inline ? undefined : true}
      aria-label={name}
      data-testid="device-sheet"
      className={
        inline
          ? 'flex-1 min-h-0 bg-canvas text-ink font-sans overflow-y-auto no-scrollbar'
          : 'f-dialog f-dialog-canvas fixed inset-0 z-[80] outline-none bg-canvas text-ink font-sans overflow-y-auto no-scrollbar f-fade-in'
      }
    >
      <div className="relative max-w-[560px] mx-auto pt-[calc(var(--sat,0px)+8px)] pb-[calc(var(--sab,0px)+24px)]">
        {inline ? (
          <div aria-hidden="true" className="h-3" />
        ) : (
          <div className="flex justify-end px-2">
            <button
              type="button"
              onClick={onClose}
              aria-label={t('deviceSheet.close')}
              className="w-11 h-11 flex items-center justify-center text-ink-2 rounded-full active:bg-raised"
            >
              <X className="w-5 h-5" strokeWidth={1.75} />
            </button>
          </div>
        )}

        <div className="px-4 flex items-center gap-3.5">
          <span className="w-16 h-16 shrink-0 rounded-full bg-raised text-ink-2 flex items-center justify-center">
            <Icon className="w-7 h-7" strokeWidth={1.75} aria-hidden="true" />
          </span>
          <div className="min-w-0 flex-1">
            <h2 className="text-[26px] leading-8 font-bold tracking-[-0.01em] text-ink break-words line-clamp-2" data-testid="device-name">
              {name}
            </h2>
            <p className="mt-0.5 text-callout" data-testid="device-status">
              {device?.online && <span className="text-success font-semibold">{t('deviceSheet.reachable')}</span>}
              {device?.online && status.length > 0 && <span className="text-ink-3"> · </span>}
              {status.length > 0 && <span className="text-ink-3">{status.join(' · ')}</span>}
              {loaded && !device && <span className="text-ink-3">{t('deviceSheet.notPaired')}</span>}
            </p>
          </div>
        </div>

        {stoppedHere && device && (
          <div className="mx-4 mt-5 p-4 rounded-control bg-surface flex items-center gap-3" data-testid="device-stopped">
            <p className="flex-1 min-w-0 text-callout text-ink">{t('deviceSheet.allStopped')}</p>
            <button
              type="button"
              onClick={() => void stop(false)}
              className="shrink-0 min-h-11 px-4 rounded-control bg-accent text-footnote font-semibold font-semibold text-on-accent"
            >
              {t('deviceSheet.resume')}
            </button>
          </div>
        )}
        {!stoppedHere && device?.stopped && (
          <p className="px-5 pt-4 text-callout text-ink-2" data-testid="device-stopped-there">
            {otherIsPc ? t('deviceSheet.stoppedOnPc') : t('deviceSheet.stoppedOnPhone')}
          </p>
        )}

        {acts.length > 0 && (
          <div
            className="px-4 pt-5 grid gap-2"
            style={{ gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))` }}
            data-testid="device-actions"
          >
            {acts.map((a) => (
              <React.Fragment key={a.key}>{a.node}</React.Fragment>
            ))}
          </div>
        )}

        {composing && (
          <form
            className="px-4 pt-3 flex items-center gap-2"
            data-testid="device-compose"
            onSubmit={(e) => {
              e.preventDefault();
              if (draft.trim()) void run(composing.verb, draft.trim());
            }}
          >
            <input
              autoFocus
              type={composing.input === 'url' ? 'url' : 'text'}
              inputMode={composing.input === 'url' ? 'url' : 'text'}
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              placeholder={composing.ask ? t(composing.ask) : undefined}
              aria-label={composing.ask ? t(composing.ask) : undefined}
              className="flex-1 min-w-0 h-12 px-3 rounded-control bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
            />
            <button
              type="submit"
              disabled={busy || !draft.trim()}
              className="shrink-0 h-12 px-4 rounded-control bg-accent text-footnote font-semibold font-semibold text-on-accent disabled:opacity-50"
            >
              {t('deviceSheet.send')}
            </button>
          </form>
        )}

        {said && (
          <p role="status" className="px-5 pt-3 text-callout text-ink-2" data-testid="device-said">
            {said}
          </p>
        )}

        {ladders && side(ladders.here, hereTitle, undefined, 'device-ladder-here')}
        {ladders?.there && ladders.there.spheres.length > 0 && side(ladders.there, thereTitle, thereNote, 'device-ladder-there')}

        {device && journal && (
          <section className="px-4 pt-6" data-testid="device-journal">
            <SectionTitle>{t('deviceSheet.history')}</SectionTitle>
            {journal.length === 0 ? (
              <p className="px-1 text-callout text-ink-3">{t('deviceSheet.nothingYet')}</p>
            ) : (
              <ul className="rounded-control bg-surface">
                {journal.slice(0, 12).map((e, i) => {
                  const whoWord =
                    e.who === 'this_phone'
                      ? t('deviceSheet.who.this_phone')
                      : e.who === 'pc'
                        ? t('deviceSheet.who.pc')
                        : e.who;
                  const whatKey = (e as JournalLine & { what_key?: string }).what_key;
                  const journalMsgKey = whatKey ? (`deviceSheet.journal.${whatKey}` as MessageKey) : null;
                  const hasJournalKey = Boolean(journalMsgKey && journalMsgKey in l1Uk);
                  let words: { what: string; state: string };
                  if (hasJournalKey && journalMsgKey && whatKey) {
                    const statusKey =
                      e.status && !whatKey.startsWith('status.') && e.status !== 'done'
                        ? (`deviceSheet.journal.status.${e.status}` as MessageKey)
                        : null;
                    const statusWord = statusKey && statusKey in l1Uk ? t(statusKey) : '';
                    words = {
                      what: t(journalMsgKey),
                      state: [whoWord, statusWord].filter(Boolean).join(' · '),
                    };
                  } else {
                    const fallback = journalWords({ ...e, who: whoWord });
                    words = { what: fallback.what || e.what, state: fallback.state };
                  }
                  return (
                  <li key={e.id} data-journal-line>
                    {i > 0 && <div aria-hidden="true" className="h-px bg-line ml-3.5" />}
                    <div className="py-2.5 px-3.5">
                      <div className="flex items-baseline gap-3">
                        <span className="flex-1 min-w-0 text-callout text-ink break-words">{words.what}</span>
                        <span className="shrink-0 text-caption text-ink-3">{lineTime(e.at)}</span>
                      </div>
                      {words.state && <p className="text-caption text-ink-3 break-words">{words.state}</p>}
                      {e.can_answer && (
                        <div className="pt-2 grid grid-cols-2 gap-2">
                          <Btn label={t('deviceSheet.yes')} main onClick={() => void answer(e, true)} testId={`device-yes-${e.id}`} />
                          <Btn label={t('deviceSheet.no')} onClick={() => void answer(e, false)} testId={`device-no-${e.id}`} />
                        </div>
                      )}
                    </div>
                  </li>
                  );
                })}
              </ul>
            )}
          </section>
        )}

        {(onWrite || onProfile || onPhone) && (
          <div className="mx-4 mt-6 rounded-control bg-surface">
            {onWrite && (
              <button
                type="button"
                onClick={onWrite}
                className="w-full min-h-12 px-4 text-left text-callout text-ink-2 active:bg-raised"
              >
                {t('deviceSheet.messages')}
              </button>
            )}
            {onWrite && onProfile && <div aria-hidden="true" className="h-px bg-line ml-4" />}
            {onProfile && (
              <button
                type="button"
                onClick={onProfile}
                className="w-full min-h-12 px-4 text-left text-callout text-ink-2 active:bg-raised"
              >
                {t('deviceSheet.aboutContact')}
              </button>
            )}
            {onPhone && (onWrite || onProfile) && <div aria-hidden="true" className="h-px bg-line ml-4" />}
            {onPhone && (
              <button
                type="button"
                data-web-signin
                data-testid="device-web-signin"
                onClick={() => {
                  soundFx.playTap();
                  openWebSignIn();
                  onClose();
                }}
                className="w-full min-h-12 px-4 text-left text-callout text-ink-2 active:bg-raised"
              >
                {webSignInLabel}
              </button>
            )}
          </div>
        )}

        {device && !stoppedHere && !pcStopped && (
          <div className="px-4 pt-6">
            <button
              type="button"
              data-testid="device-stop-all"
              onClick={() => {
                soundFx.playTap();
                void stop(true);
              }}
              className="w-full min-h-[52px] rounded-control bg-raised text-footnote font-semibold font-semibold text-danger active:opacity-90"
            >
              {t('deviceSheet.stopAll')}
            </button>
          </div>
        )}
      </div>
    </div>
  );
  return inline ? sheet : createPortal(sheet, document.body);
};

export default DeviceSheet;

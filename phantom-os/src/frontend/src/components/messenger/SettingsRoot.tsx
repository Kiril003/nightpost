/**
 * Я › Налаштування — корінь і підекрани: Сповіщення, Приватність, Вигляд, Мова,
 * Сховище, Про застосунок. Кожен перемикач тут справді щось вирішує; чого
 * застосунок не вміє забезпечити — того рядка немає.
 */
import React, { useEffect, useState, useSyncExternalStore } from 'react';
import { Bell, Check, ChevronRight, Eraser, HardDrive, Info, Languages, Lock, MonitorSmartphone, SunMoon, type LucideIcon } from 'lucide-react';
import { FormSheet } from './FormSheet';
import { Avatar, AVATAR_SIZE } from './Avatar';
import { Switch, QuietNightsCard } from './QuietNights';
import { ThemeChoice } from './MeSections';
import { useBlockedPeers } from './PeopleScreen';
import { HelpCentre } from '../support/HelpCentre';
import { soundFx } from '../../utils/messengerSound';
import { isPhoneShell } from './phoneShell';
import { messengerApi, type PrivacyState, type Visibility, type VisibilityKey } from '../../services/messengerApi';
import { supportApi } from '../../services/supportApi';
import { notificationPrefs } from '../../services/notificationPrefs';
import { chatHush } from '../../services/chatHush';
import { useMessengerStore } from '../../stores/messengerStore';
import { clockLabel, dayMonthLabel } from '../../utils/clockFormat';
import { readThemeMode, THEME_MODE_EVENT } from '../../services/theme';
import { LOCALES, LOCALE_LABELS, type Locale } from '../../i18n';
import { languageSwitchable, switchLanguage } from '../../services/nativeLanguage';
import { clearStorageCache, formatBytes, readStorageUsage, type StorageUsage } from '../../services/storageUsage';
import { readBundleInfo, type BundleInfo } from '../../services/bundleInfo';
import { ERASE_URL, LEGAL_URL, PRIVACY_URL } from '../../services/serverKnows';
import { REDUCE_CLASS, setAppReduceMotion } from '../../feel/motionKit';
import { useTranslation } from '../../i18n/useTranslation';

export type SettingsPage = 'root' | 'notifications' | 'privacy' | 'look' | 'language' | 'storage' | 'about';

interface Props {
  onClose: () => void;
  isSoundEnabled: boolean;
  onToggleSound: () => void;
  initial?: SettingsPage;
}

const openLink = (url: string) => {
  soundFx.playTap();
  window.open(url, '_blank', 'noopener');
};

const VIS_VALUES: readonly Visibility[] = ['all', 'people', 'nobody'];
const pickVis = (p: PrivacyState): Partial<Record<VisibilityKey, Visibility>> => {
  const out: Partial<Record<VisibilityKey, Visibility>> = {};
  for (const k of ['last_seen', 'photo', 'calls'] as const) if (p[k] && VIS_VALUES.includes(p[k] as Visibility)) out[k] = p[k];
  return out;
};

const Card: React.FC<{ children: React.ReactNode; testId?: string }> = ({ children, testId }) => (
  <div data-testid={testId} className="mx-4 rounded-[16px] bg-raised overflow-hidden">
    {children}
  </div>
);

const Title: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <h3 className="px-4 pt-6 pb-2 text-footnote font-semibold text-ink-3">{children}</h3>
);

const Row: React.FC<{
  icon?: LucideIcon;
  label: string;
  value?: string;
  onClick?: () => void;
  testId?: string;
  danger?: boolean;
}> = ({ icon: Icon, label, value, onClick, testId, danger }) => {
  const body = (
    <>
      {Icon && <Icon className={`w-5 h-5 shrink-0 ${danger ? 'text-danger' : 'text-ink-2'}`} strokeWidth={1.75} aria-hidden="true" />}
      <span className={`flex-1 min-w-0 text-body ${danger ? 'text-danger' : 'text-ink'}`}>{label}</span>
      {value && <span className="shrink-0 max-w-[50%] truncate text-caption text-ink-3">{value}</span>}
      {onClick && <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden="true" />}
    </>
  );
  const cls = 'relative w-full min-h-14 px-4 py-2 flex items-center gap-3 text-left before:absolute before:left-4 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden';
  return onClick ? (
    <button type="button" data-testid={testId} onClick={onClick} className={`${cls} hover:bg-overlay/60 active:bg-overlay`}>
      {body}
    </button>
  ) : (
    <div data-testid={testId} className={cls}>
      {body}
    </div>
  );
};

const SwitchRow: React.FC<{ label: string; meta?: string; on: boolean; disabled?: boolean; onToggle: () => void; testId: string }> = ({
  label,
  meta,
  on,
  disabled,
  onToggle,
  testId,
}) => (
  <div data-testid={testId} className="relative min-h-14 pl-4 pr-4 py-1 flex items-center gap-3 before:absolute before:left-4 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden">
    <span className="flex-1 min-w-0">
      <span className="block text-body text-ink">{label}</span>
      {meta && <span className="block text-caption text-ink-3">{meta}</span>}
    </span>
    {disabled ? (
      <span aria-disabled className="shrink-0 w-[52px] h-8 rounded-full bg-overlay opacity-50" />
    ) : (
      <Switch on={on} label={label} onToggle={onToggle} />
    )}
  </div>
);

export const NotificationsPage: React.FC<Pick<Props, 'isSoundEnabled' | 'onToggleSound'>> = ({ isSoundEnabled, onToggleSound }) => {
  const { t } = useTranslation();
  const prefs = useSyncExternalStore(notificationPrefs.subscribe, notificationPrefs.getSnapshot);
  return (
    <div className="pb-6" data-testid="settings-notifications">
      <Card>
        <SwitchRow
          testId="settings-sound"
          label={t('settingsRoot.sound')}
          on={isSoundEnabled}
          onToggle={() => {
            onToggleSound();
            if (!isSoundEnabled) soundFx.playTap();
          }}
        />
        {prefs.access !== 'unsupported' && (
          <SwitchRow
            testId="settings-call-banners"
            label={t('settingsRoot.callBanners')}
            meta={prefs.access === 'denied' ? t('settingsRoot.deniedBySystem') : undefined}
            on={prefs.effective}
            disabled={prefs.access === 'denied'}
            onToggle={() => (prefs.effective ? notificationPrefs.disable() : void notificationPrefs.enable())}
          />
        )}
      </Card>
      <QuietNightsCard />
      <HushExceptions />
    </div>
  );
};

const untilLabel = (until: number, nowMs: number): string => {
  const at = new Date(until);
  const today = new Date(nowMs).toDateString() === at.toDateString();
  return today ? clockLabel(at) : `${dayMonthLabel(at, false)}, ${clockLabel(at)}`;
};

/** Settings › Notifications › Exceptions: every chat muted on this phone, one tap to unmute. */
export const HushExceptions: React.FC = () => {
  const { t } = useTranslation();
  useSyncExternalStore(chatHush.subscribe, chatHush.version, chatHush.version);
  const chats = useMessengerStore((s) => s.chats);
  const now = Date.now();
  const rows = chatHush
    .list(now)
    .map((r) => ({ ...r, chat: chats.find((c) => c.id === r.id) }))
    .filter((r): r is typeof r & { chat: NonNullable<typeof r.chat> } => Boolean(r.chat));
  return (
    <>
      <Title>{t('settingsRoot.exceptions')}</Title>
      <Card testId="settings-hush-exceptions">
        {rows.length === 0 ? (
          <Row label={t('settingsRoot.exceptionsNone')} testId="settings-hush-none" />
        ) : (
          rows.map(({ id, until, chat }) => (
            <div
              key={id}
              data-testid="settings-hush-row"
              className="relative min-h-14 px-4 py-2 flex items-center gap-3 before:absolute before:left-4 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden"
            >
              <Avatar name={chat.title} src={chat.avatar} tone={chat.id} radius="rounded-full" className="w-9 h-9 text-subhead" />
              <span className="flex-1 min-w-0">
                <span className="block truncate text-body text-ink">{chat.title}</span>
                <span className="block text-caption text-ink-3">
                  {until === null ? t('settingsRoot.mutedForever') : t('settingsRoot.mutedUntil', { time: untilLabel(until, now) })}
                </span>
              </span>
              <button
                type="button"
                data-testid="settings-hush-unmute"
                onClick={() => {
                  soundFx.playTap();
                  chatHush.set(id, false);
                  useMessengerStore.setState((s) => ({ chats: s.chats.map((c) => (c.id === id ? { ...c, muted: false } : c)) }));
                }}
                className="shrink-0 min-h-11 px-3 rounded-full text-callout font-semibold text-accent-text hover:bg-overlay/60 active:bg-overlay"
              >
                {t('settingsRoot.unmute')}
              </button>
            </div>
          ))
        )}
      </Card>
    </>
  );
};

type Knows<T> = { state: 'loading' } | { state: 'failed' } | { state: 'ok'; value: T };

export const useServerKnows = (withSupport: boolean) => {
  const [handle, setHandle] = useState<Knows<{ handle: string | null; published: boolean }>>({ state: 'loading' });
  const [tickets, setTickets] = useState<Knows<number>>({ state: 'loading' });
  useEffect(() => {
    let alive = true;
    messengerApi
      .directoryMe()
      .then((r) => alive && setHandle({ state: 'ok', value: { handle: r.handle, published: !!r.published } }))
      .catch(() => alive && setHandle({ state: 'failed' }));
    if (withSupport) supportApi
      .listTickets()
      .then((r) => alive && setTickets({ state: 'ok', value: Array.isArray(r) ? r.length : 0 }))
      .catch(() => alive && setTickets({ state: 'failed' }));
    return () => {
      alive = false;
    };
  }, [withSupport]);
  return { handle, tickets };
};

export const ServerKnows: React.FC = () => {
  const { t } = useTranslation();
  const withSupport = !(__PHONE_BUILD__ || isPhoneShell());
  const { handle, tickets } = useServerKnows(withSupport);
  const published = handle.state === 'ok' && handle.value.published && !!handle.value.handle;
  const handleLine =
    handle.state === 'loading'
      ? undefined
      : handle.state === 'failed'
        ? t('settingsRoot.knowsUnknown')
        : published
          ? t('settingsRoot.knowsHandleOn', { handle: handle.value.handle ?? '' })
          : t('settingsRoot.knowsHandleOff');
  const supportLine =
    tickets.state === 'loading'
      ? undefined
      : tickets.state === 'failed'
        ? t('settingsRoot.knowsUnknown')
        : tickets.value === 0
          ? t('settingsRoot.knowsSupportNone')
          : t('settingsRoot.knowsSupportCount', { count: tickets.value });
  return (
    <>
      <Title>{t('settingsRoot.knows')}</Title>
      <Card testId="settings-knows">
        <Row label={t('settingsRoot.knowsHandle')} value={handleLine} testId="settings-knows-handle" />
        {published && <Row label={t('settingsRoot.knowsKey')} value={t('settingsRoot.knowsKeyOn')} testId="settings-knows-key" />}
        {withSupport && <Row label={t('settingsRoot.knowsSupport')} value={supportLine} testId="settings-knows-support" />}
      </Card>
      <div className="mt-4" />
      <Card>
        <Row icon={Eraser} label={t('settingsRoot.erase')} onClick={() => openLink(ERASE_URL)} testId="settings-erase" danger />
      </Card>
    </>
  );
};

const VIS_KEYS: { key: VisibilityKey; label: 'privacy.lastSeen' | 'privacy.photo' | 'privacy.calls' }[] = [
  { key: 'last_seen', label: 'privacy.lastSeen' },
  { key: 'photo', label: 'privacy.photo' },
  { key: 'calls', label: 'privacy.calls' },
];
const VIS_OPTIONS: { id: Visibility; label: 'privacy.all' | 'privacy.people' | 'privacy.nobody' }[] = [
  { id: 'all', label: 'privacy.all' },
  { id: 'people', label: 'privacy.people' },
  { id: 'nobody', label: 'privacy.nobody' },
];

const VisibilityRow: React.FC<{ field: VisibilityKey; label: string; value: Visibility; onPick: (v: Visibility) => void }> = ({ field, label, value, onPick }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const current = VIS_OPTIONS.find((o) => o.id === value) ?? VIS_OPTIONS[0];
  return (
    <div className="relative before:absolute before:left-4 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden">
      <button
        type="button"
        data-testid={`settings-vis-${field}`}
        aria-expanded={open}
        onClick={() => {
          soundFx.playTap();
          setOpen((v) => !v);
        }}
        className="w-full min-h-14 px-4 flex items-center gap-3 text-left active:bg-overlay"
      >
        <span className="flex-1 min-w-0 text-body text-ink">{label}</span>
        <span className="shrink-0 text-caption text-ink-3">{t(current.label)}</span>
      </button>
      {open && (
        <div role="radiogroup" aria-label={label} data-testid={`settings-vis-${field}-options`} className="pl-4">
          {VIS_OPTIONS.map((o) => (
            <button
              key={o.id}
              type="button"
              role="radio"
              aria-checked={o.id === value}
              data-testid={`settings-vis-${field}-${o.id}`}
              onClick={() => {
                soundFx.playTap();
                onPick(o.id);
                setOpen(false);
              }}
              className="relative w-full min-h-12 pr-4 flex items-center gap-3 text-left before:absolute before:left-0 before:right-0 before:top-0 before:h-px before:bg-line active:bg-overlay"
            >
              <span className="flex-1 text-body text-ink-2">{t(o.label)}</span>
              {o.id === value && <Check className="w-5 h-5 text-ink" strokeWidth={2} aria-hidden="true" />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

export const PrivacyPage: React.FC = () => {
  const { t } = useTranslation();
  const [readReceipts, setReadReceipts] = useState<boolean | null>(null);
  const [vis, setVis] = useState<Partial<Record<VisibilityKey, Visibility>>>({});
  const { blocked, unblock } = useBlockedPeers();
  const [blockedOpen, setBlockedOpen] = useState(false);

  useEffect(() => {
    let alive = true;
    void messengerApi
      .privacy()
      .then((p) => {
        if (!alive) return;
        setReadReceipts(p.read_receipts);
        setVis(pickVis(p));
      })
      .catch(() => alive && setReadReceipts(null));
    return () => {
      alive = false;
    };
  }, []);

  const toggleReceipts = async () => {
    if (readReceipts === null) return;
    soundFx.playTap();
    try {
      const p = await messengerApi.setPrivacy(!readReceipts);
      setReadReceipts(p.read_receipts);
    } catch {
      /* стан лишається тим, що сказав міст */
    }
  };

  const pick = async (key: VisibilityKey, v: Visibility) => {
    try {
      setVis(pickVis(await messengerApi.setVisibility({ [key]: v })));
    } catch {
      /* стан лишається тим, що сказав міст */
    }
  };
  const shown = VIS_KEYS.filter((k) => vis[k.key]);

  return (
    <div className="pb-6" data-testid="settings-privacy">
      {shown.length > 0 && (
        <>
          <Card testId="settings-visibility">
            {shown.map((k) => (
              <VisibilityRow key={k.key} field={k.key} label={t(k.label)} value={vis[k.key] as Visibility} onPick={(v) => void pick(k.key, v)} />
            ))}
          </Card>
          <div className="mt-4" />
        </>
      )}
      {(readReceipts !== null || blocked !== null) && (
        <Card>
          {readReceipts !== null && (
            <div data-settings-read-receipts={readReceipts ? 'on' : 'off'}>
              <SwitchRow testId="settings-receipts" label={t('phoneSettingsSheet.readReceipts')} on={readReceipts} onToggle={() => void toggleReceipts()} />
            </div>
          )}
          {blocked !== null && (
            <button
              type="button"
              data-settings-blocked
              aria-expanded={blockedOpen}
              onClick={() => {
                soundFx.playTap();
                setBlockedOpen((v) => !v);
              }}
              disabled={blocked.length === 0}
              className="relative w-full min-h-14 px-4 flex items-center gap-3 text-left before:absolute before:left-4 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden active:bg-overlay disabled:active:bg-transparent"
            >
              <span className="flex-1 min-w-0 text-body text-ink">{t('phoneSettingsSheet.blocked')}</span>
              <span className="shrink-0 text-caption text-ink-3">{blocked.length === 0 ? t('phoneSettingsSheet.nobody') : blocked.length}</span>
            </button>
          )}
        </Card>
      )}
      {blockedOpen && blocked !== null && blocked.length > 0 && (
        <ul data-settings-blocked-list className="mx-4 mt-2 rounded-[16px] bg-raised overflow-hidden">
          {blocked.map((b) => (
            <li key={b.id} className="min-h-14 px-4 flex items-center gap-3">
              <Avatar name={b.name || t('phoneSettingsSheet.noName')} tone={b.id} className={AVATAR_SIZE.rail} />
              <span className="flex-1 min-w-0 truncate text-body text-ink">{b.name || t('phoneSettingsSheet.noName')}</span>
              <button type="button" onClick={() => void unblock(b.id)} className="h-11 px-3 rounded-control bg-overlay text-footnote font-semibold text-ink">
                {t('phoneSettingsSheet.unblock')}
              </button>
            </li>
          ))}
        </ul>
      )}
      <ServerKnows />
    </div>
  );
};

export const LanguagePage: React.FC = () => {
  const { locale } = useTranslation();
  return (
    <div className="pb-6" data-testid="settings-language">
      <Card>
        <div role="radiogroup" aria-label={LOCALE_LABELS[locale]}>
          {LOCALES.map((id: Locale) => (
            <button
              key={id}
              type="button"
              role="radio"
              aria-checked={id === locale}
              data-locale={id}
              onClick={() => {
                soundFx.playTap();
                switchLanguage(id);
              }}
              className="relative w-full min-h-14 px-4 flex items-center gap-3 text-left before:absolute before:left-4 before:right-0 before:top-0 before:h-px before:bg-line first:before:hidden active:bg-overlay"
            >
              <span className="flex-1 text-body text-ink">{LOCALE_LABELS[id]}</span>
              {id === locale && <Check className="w-5 h-5 text-accent-text" strokeWidth={2} aria-hidden="true" />}
            </button>
          ))}
        </div>
      </Card>
    </div>
  );
};

export const StoragePage: React.FC<{ onUsage?: (u: StorageUsage) => void }> = ({ onUsage }) => {
  const { t } = useTranslation();
  const units = t('settingsRoot.storageUnits').split('|');
  const [usage, setUsage] = useState<StorageUsage | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [done, setDone] = useState(false);
  const load = () =>
    void readStorageUsage().then((u) => {
      setUsage(u);
      onUsage?.(u);
    });
  useEffect(load, []); // eslint-disable-line react-hooks/exhaustive-deps
  const fmt = (n: number | null) => (n === null ? undefined : formatBytes(n, units));
  const clear = async () => {
    soundFx.playTap();
    await clearStorageCache();
    setConfirming(false);
    setDone(true);
    load();
  };
  return (
    <div className="pb-6" data-testid="settings-storage">
      <Card>
        {usage?.used != null && <Row label={t('settingsRoot.storageUsed')} value={fmt(usage.used)} testId="settings-storage-used" />}
        {usage?.media != null && <Row label={t('settingsRoot.storageMedia')} value={fmt(usage.media)} testId="settings-storage-media" />}
      </Card>
      {usage?.media != null && (
        <div className="mx-4 mt-4">
          {confirming ? (
            <div role="alertdialog" aria-label={t('settingsRoot.storageConfirm')} data-testid="settings-storage-confirm" className="rounded-[16px] bg-raised p-4">
              <p className="text-body text-ink">{t('settingsRoot.storageConfirm')}</p>
              <div className="mt-3 flex gap-2">
                <button type="button" data-testid="settings-storage-do" onClick={() => void clear()} className="flex-1 h-12 rounded-control bg-accent text-on-accent text-headline">
                  {t('settingsRoot.storageDo')}
                </button>
                <button type="button" onClick={() => setConfirming(false)} className="flex-1 h-12 rounded-control bg-overlay text-headline text-ink">
                  {t('settingsRoot.storageCancel')}
                </button>
              </div>
            </div>
          ) : (
            <button
              type="button"
              data-testid="settings-storage-clear"
              onClick={() => {
                soundFx.playTap();
                setDone(false);
                setConfirming(true);
              }}
              className="w-full h-12 rounded-control bg-overlay text-headline text-ink active:bg-raised"
            >
              {done ? t('settingsRoot.storageDone') : t('settingsRoot.storageClear')}
            </button>
          )}
        </div>
      )}
    </div>
  );
};

export const AboutPage: React.FC<{ onHelp: () => void }> = ({ onHelp }) => {
  const { t } = useTranslation();
  const [info, setInfo] = useState<BundleInfo | null>(null);
  useEffect(() => {
    let alive = true;
    void readBundleInfo().then((i) => alive && setInfo(i));
    return () => {
      alive = false;
    };
  }, []);
  return (
    <div className="pb-6" data-testid="settings-about">
      <Card>
        {info && <Row label={t('settingsRoot.aboutVersion')} value={info.short} testId="settings-about-build" />}
        <Row label={t('help.title')} onClick={onHelp} testId="settings-about-help" />
        <Row label={t('settingsRoot.aboutLegal')} onClick={() => openLink(LEGAL_URL)} testId="settings-about-legal" />
        <Row label={t('settingsRoot.aboutPrivacy')} onClick={() => openLink(PRIVACY_URL)} testId="settings-about-privacy" />
      </Card>
    </div>
  );
};

const TITLE: Record<Exclude<SettingsPage, 'root'>, Parameters<ReturnType<typeof useTranslation>['t']>[0]> = {
  notifications: 'settingsRoot.notifications',
  privacy: 'meSections.privacy',
  look: 'meSections.look',
  language: 'settingsRoot.language',
  storage: 'settingsRoot.storage',
  about: 'settingsRoot.about',
};

export const SettingsRoot: React.FC<Props> = ({ onClose, isSoundEnabled, onToggleSound, initial = 'root' }) => {
  const { t, locale } = useTranslation();
  const [page, setPage] = useState<SettingsPage>(initial);
  const [helpOpen, setHelpOpen] = useState(false);
  const [storage, setStorage] = useState<StorageUsage | null>(null);
  const [mode, setMode] = useState(readThemeMode);
  const [reduced, setReduced] = useState(() => document.documentElement.classList.contains(REDUCE_CLASS));
  const toggleReduced = () => {
    soundFx.playTap();
    setAppReduceMotion(!reduced);
    setReduced(!reduced);
  };
  useEffect(() => {
    void readStorageUsage().then(setStorage);
  }, []);
  const themeWord = (m: typeof mode) =>
    m === 'day' ? t('meSections.themeDay') : m === 'night' ? t('meSections.themeNight') : t('meSections.themeAuto');
  const go = (p: SettingsPage) => () => {
    soundFx.playTap();
    setPage(p);
  };
  const switchable = languageSwitchable();
  const toDevices = () => {
    soundFx.playTap();
    onClose();
    window.setTimeout(
      () => document.querySelector('[data-testid="me-devices"]')?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }),
      0,
    );
  };

  return (
    <>
      <FormSheet
        open
        onClose={onClose}
        onBack={page === 'root' ? undefined : () => setPage('root')}
        title={page === 'root' ? t('settingsRoot.title') : t(TITLE[page])}
        tall
        testId={page === 'root' ? 'me-prefs' : `settings-${page}-sheet`}
      >
        {page === 'root' && (
          <div className="pb-6" data-testid="phone-settings">
            <Card testId="settings-root">
              <Row icon={Bell} label={t('settingsRoot.notifications')} value={t('settingsRoot.notificationsValue')} onClick={go('notifications')} testId="settings-row-notifications" />
              <Row icon={Lock} label={t('meSections.privacy')} onClick={go('privacy')} testId="settings-row-privacy" />
              <Row icon={SunMoon} label={t('meSections.look')} value={themeWord(mode)} onClick={go('look')} testId="settings-row-look" />
              <Row
                icon={Languages}
                label={t('settingsRoot.language')}
                value={LOCALE_LABELS[locale]}
                onClick={switchable ? go('language') : undefined}
                testId="settings-row-language"
              />
              <Row icon={HardDrive} label={t('settingsRoot.storage')} value={storage?.used != null ? formatBytes(storage.used, t('settingsRoot.storageUnits').split('|')) : undefined} onClick={go('storage')} testId="settings-row-storage" />
              <Row icon={MonitorSmartphone} label={t('meSections.devices')} onClick={toDevices} testId="settings-row-devices" />
              <Row icon={Info} label={t('settingsRoot.about')} onClick={go('about')} testId="settings-row-about" />
            </Card>
          </div>
        )}
        {page === 'notifications' && <NotificationsPage isSoundEnabled={isSoundEnabled} onToggleSound={onToggleSound} />}
        {page === 'privacy' && <PrivacyPage />}
        {page === 'look' && (
          <div className="pb-6" data-testid="settings-look">
            <Card>
              <ThemeChoice
                onChange={(m) => {
                  setMode(m);
                  window.dispatchEvent(new Event(THEME_MODE_EVENT));
                }}
              />
            </Card>
            <div className="mt-4" />
            <Card>
              <SwitchRow testId="settings-reduce-motion" label={t('settingsRoot.reduceMotion')} on={reduced} onToggle={toggleReduced} />
            </Card>
          </div>
        )}
        {page === 'language' && <LanguagePage />}
        {page === 'storage' && <StoragePage onUsage={setStorage} />}
        {page === 'about' && <AboutPage onHelp={() => setHelpOpen(true)} />}
      </FormSheet>
      {helpOpen && <HelpCentre open onClose={() => setHelpOpen(false)} />}
    </>
  );
};

export default SettingsRoot;

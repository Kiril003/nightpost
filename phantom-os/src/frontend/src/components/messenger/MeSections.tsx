/**
 * Частини екрана «Я», кожна зі свого джерела:
 *  - поділитися й показати поруч — `/messenger/identity` (ключ беремо лише на дотик:
 *    кожен виклик витрачає одноразовий prekey);
 *  - мої пристрої — `/messenger/devices` (ПК: routes_messenger_devices.py,
 *    телефон: WebMessengerDevices.kt);
 *  - памʼять — `/messenger/self/facts` (лише телефон, PhantomSelfRoute.kt);
 *  - мережа — розмови зі стору.
 * Немає відповіді — немає частини.
 */
import type { MessageKey } from '../../i18n';
import { pickPlural } from '../chat/tNow';
import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import QRCode from 'qrcode';
import { Check, ChevronRight, Laptop, Pencil, QrCode, Share2, Smartphone, SunMoon, X } from 'lucide-react';
import { AddOwnDevice } from './AddOwnDevice';
import { Avatar } from './Avatar';
import { DeviceSheet } from './DeviceSheet';
import { chatKind } from './chatListFormat';
import { isPhoneShell } from './phoneShell';
import { LIST_FILTER_EVENT, type ChatFilter } from './ChatTypeChips';
import { messengerApi, type NodeIdentity } from '../../services/messengerApi';
import { isMyDevice } from '../../services/peopleIdentity';
import { readThemeMode, setThemeMode, type ThemeMode } from '../../services/theme';
import { phantomSelfApi, type KnownFact } from '../../services/phantomSelfApi';
import { deviceApi, deviceName, seenWord, type OwnDevice } from '../../services/deviceApi';
import { useMessengerStore } from '../../stores/messengerStore';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { copyText, encodeInvite, findInText, inviteShareText, phoneCodeOf, selfAddressGuess } from '../../utils/messengerInvite';
import { useTranslation } from '../../i18n/useTranslation';
import { soundFx } from '../../utils/messengerSound';
import { LIT } from '../../styles/phantom/literals';

export const SectionTitle: React.FC<{ children: React.ReactNode; aside?: React.ReactNode }> = ({ children, aside }) => (
  <div className="mx-4 mt-6 mb-2 px-1 flex items-center justify-between gap-3">
    <h3 className="text-caption text-ink-3">{children}</h3>
    {aside}
  </div>
);

const Divider = () => <div aria-hidden="true" className="ml-4 h-px bg-line" />;

/**
 * Рядок запрошення з відповіді вузла — так само, як в аркуші «Додати людину»
 * (D6): на телефоні це сирий код, і саме він іде в QR; `phantom://` — ні.
 */
const inviteOf = (me: NodeIdentity): string => {
  const ph = phoneCodeOf(me);
  if (ph) return ph;
  if (__PHONE_BUILD__ || isPhoneShell()) return me.link || me.compact || '';
  if (me.link) return me.link;
  const name = typeof me.bundle?.name === 'string' ? me.bundle.name.trim() : '';
  if (me.compact && !me.compact.startsWith('PH')) {
    try {
      return encodeInvite({ compact: me.compact, address: selfAddressGuess(), name });
    } catch {
      return me.compact;
    }
  }
  return me.compact ?? '';
};

type ShareOutcome = 'shared' | 'copied' | 'failed';

const handOver = async (invite: string, title: string): Promise<ShareOutcome> => {
  const bridge = (window as unknown as { PhantomBridge?: { shareText?: (t: string, title?: string) => boolean } }).PhantomBridge;
  if (bridge && typeof bridge.shareText === 'function' && bridge.shareText(invite, title)) return 'shared';
  if (typeof navigator.share === 'function') {
    try {
      await navigator.share({ text: invite });
      return 'shared';
    } catch {
      return 'failed';
    }
  }
  return (await copyText(invite)) ? 'copied' : 'failed';
};

/** QR на весь екран — щоб людина поруч навела камеру. */
const QrFull: React.FC<{ invite: string; handle: string | null; onShare: () => void; onClose: () => void }> = ({
  invite,
  handle,
  onShare,
  onClose,
}) => {
  const { t } = useTranslation();
  const me = useMessengerStore((s) => s.currentUser);
  const [src, setSrc] = useState<string | null>(null);
  const layer = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => registerPhoneLayer(`me-qr${layer}`, true, () => closeRef.current()), [layer]);
  useEffect(() => {
    // Рівень L: у коді 400+ символів, із «M» модулі дрібнішають і камера бере через раз.
    void QRCode.toDataURL(invite, { errorCorrectionLevel: 'L', margin: 2, width: 720, color: { dark: LIT['ink-brown'], light: LIT['white'] } })
      .then(setSrc)
      .catch(() => setSrc(null));
  }, [invite]);

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={t('meSections.showCode')}
      data-testid="me-qr-full"
      className="f-dialog f-dialog-canvas fixed inset-0 z-[1300] bg-canvas text-ink font-sans flex flex-col f-fade-in"
    >
      <div className="pt-[calc(var(--sat)+8px)] px-2 flex justify-end">
        <button
          type="button"
          onClick={onClose}
          aria-label={t('meSections.close')}
          className="w-11 h-11 min-h-0 min-w-0 flex items-center justify-center text-ink-2 rounded-full active:bg-raised"
        >
          <X className="w-5 h-5" strokeWidth={1.75} />
        </button>
      </div>
      <div className="flex-1 px-6 flex flex-col items-center justify-center">
        <div className="flex items-center gap-3">
          <Avatar src={me.avatar} name={me.name || t('meScreen.initial')} tone={me.id || me.name} radius="rounded-full" className="w-12 h-12 text-title-2" />
          <span className="min-w-0">
            <span className="block truncate text-title-2 text-ink">{me.name || t('meScreen.initial')}</span>
            {handle && <span className="block truncate text-callout text-ink-2">@{handle}</span>}
          </span>
        </div>
        {src ? (
          // Тло кодом, не класом: нічна тема перефарбовує `.bg-white` у темне, і камера губить поля.
          <img src={src} alt={t('meSections.cameraInvite')} style={{ backgroundColor: LIT['white'] }} className="mt-6 w-full max-w-[320px] aspect-square rounded-sheet p-3" />
        ) : (
          <div className="mt-6 w-full max-w-[320px] aspect-square rounded-sheet bg-raised" aria-hidden="true" />
        )}
      </div>
      <div className="px-4 pb-[calc(var(--sab)+20px)]">
        <button
          type="button"
          onClick={onShare}
          className="w-full min-h-12 py-2 rounded-control bg-accent text-on-accent text-headline flex items-center justify-center gap-2 hover:bg-accent-hover active:bg-accent-press"
        >
          <Share2 className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
          {t('meSections.sendInvite')}
        </button>
      </div>
    </div>,
    document.body,
  );
};

/** Ряд дій під іменем: «Мій код» (акцент), «Змінити» і «Поділитися». */
export const FindMe: React.FC<{ handle: string | null; onEdit?: () => void }> = ({ handle, onEdit }) => {
  const { t, locale } = useTranslation();
  const [invite, setInvite] = useState<string | null>(null);
  const [qr, setQr] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const ensure = async (): Promise<string | null> => {
    if (invite) return invite;
    setBusy(true);
    try {
      const me = await messengerApi.identity();
      let got = phoneCodeOf(me) ?? '';
      if (!got && !__PHONE_BUILD__ && !isPhoneShell() && !me.link) {
        const name =
          (typeof me.bundle?.name === 'string' ? me.bundle.name.trim() : '') ||
          useMessengerStore.getState().currentUser.name?.trim();
        const ph = await messengerApi.phoneInvite(name || undefined).catch(() => null);
        if (ph?.code) got = ph.code;
      }
      if (!got) got = inviteOf(me);
      if (!got) throw new Error('empty');
      setInvite(got);
      return got;
    } catch {
      setNote(t('meSections.inviteFailed'));
      return null;
    } finally {
      setBusy(false);
    }
  };

  const share = async () => {
    soundFx.playTap();
    setNote(null);
    const got = await ensure();
    if (!got) return;
    // Сирий код телефона людям — https-посиланням і кодом запасом (D6).
    const out = await handOver(findInText(got) === got ? inviteShareText(got, locale) : got, t('msgr.add.shareTitle'));
    if (out === 'copied') setNote(t('meSections.inviteCopied'));
  };

  const showCode = async () => {
    soundFx.playTap();
    setNote(null);
    if (await ensure()) setQr(true);
  };

  return (
    <section data-testid="me-find" aria-label={t('meSections.findMe')} className="mt-3.5 px-4">
      <div className="flex justify-center gap-2">
        <button
          type="button"
          data-testid="me-code"
          disabled={busy}
          onClick={() => void showCode()}
          className="h-11 px-[18px] rounded-full bg-accent text-on-accent text-subhead font-semibold flex items-center gap-1.5 hover:bg-accent-hover active:bg-accent-press disabled:opacity-40"
        >
          <QrCode className="w-[18px] h-[18px]" strokeWidth={1.75} aria-hidden="true" />
          {t('meSections.myCode')}
        </button>
        {onEdit && (
          <button
            type="button"
            data-testid="me-edit"
            onClick={onEdit}
            className="h-11 px-[18px] rounded-full border border-line-strong text-ink bg-transparent text-subhead font-semibold flex items-center gap-1.5 hover:bg-overlay/60 active:bg-overlay"
          >
            <Pencil className="w-[18px] h-[18px]" strokeWidth={1.75} aria-hidden="true" />
            {t('meSections.edit')}
          </button>
        )}
        <button
          type="button"
          data-testid="me-share"
          disabled={busy}
          onClick={() => void share()}
          aria-label={t('meSections.share')}
          className="w-11 h-11 rounded-full border border-line-strong text-ink flex items-center justify-center hover:bg-overlay/60 active:bg-overlay disabled:opacity-40"
        >
          <Share2 className="w-[18px] h-[18px]" strokeWidth={1.75} aria-hidden="true" />
        </button>
      </div>
      {note && (
        <p role="status" className="mt-2 px-1 text-center text-callout text-ink-2">
          {note}
        </p>
      )}
      {qr && invite && <QrFull invite={invite} handle={handle} onShare={() => void share()} onClose={() => setQr(false)} />}
    </section>
  );
};

export const useOwnDevices = (): OwnDevice[] => {
  const [devices, setDevices] = useState<OwnDevice[]>([]);
  useEffect(() => {
    let alive = true;
    deviceApi
      .list()
      .then((s) => alive && setDevices(Array.isArray(s?.devices) ? s.devices : []))
      .catch(() => alive && setDevices([]));
    return () => {
      alive = false;
    };
  }, []);
  return devices;
};

/** Свої телефони й компʼютери — з вікна «Мій пристрій»; тап відкриває його. */
export const MyDevices: React.FC<{ devices: OwnDevice[]; onWrite: (chatId: string) => void }> = ({ devices, onWrite }) => {
  const { t } = useTranslation();
  const chats = useMessengerStore((s) => s.chats);
  const [open, setOpen] = useState<OwnDevice | null>(null);

  const chatOf = (d: OwnDevice) => (d.peer_id ? chats.find((c) => c.peerNodeId === d.peer_id) : undefined);
  const openChat = open ? chatOf(open) : undefined;

  return (
    <section data-testid="me-devices">
      <SectionTitle>{t('meSections.myDevices')}</SectionTitle>
      <div className="mx-4 rounded-[16px] bg-raised overflow-hidden">
        {devices.map((d, i) => {
          const Glyph = d.kind === 'phone' ? Smartphone : Laptop;
          return (
            <React.Fragment key={d.id}>
              {i > 0 && <Divider />}
              <button
                type="button"
                onClick={() => {
                  soundFx.playTap();
                  setOpen(d);
                }}
                className="w-full min-h-[60px] py-2 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
              >
                <span className="w-10 h-10 shrink-0 rounded-full bg-overlay text-ink-2 flex items-center justify-center">
                  <Glyph className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
                </span>
                <span className="flex-1 min-w-0">
                  <span className="block truncate text-headline text-ink">{deviceName(d)}</span>
                  {d.online ? (
                    <span className="block text-caption text-success">{t('meSections.online')}</span>
                  ) : (
                    seenWord(d.seen_at) && <span className="block text-caption text-ink-3">{seenWord(d.seen_at)}</span>
                  )}
                </span>
                <ChevronRight className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
              </button>
            </React.Fragment>
          );
        })}
        {devices.length > 0 && <Divider />}
        <AddOwnDevice />
      </div>
      {open && (
        <DeviceSheet
          title={deviceName(open)}
          peerNodeId={open.peer_id}
          onClose={() => setOpen(null)}
          onWrite={openChat ? () => onWrite(openChat.id) : undefined}
        />
      )}
    </section>
  );
};

const SHOWN_FACTS = 4;

/** Що PHANTOM памʼятає: кілька свіжих рядків, кожен можна забути. */
export const MyMemory: React.FC<{ onAll: () => void }> = ({ onAll }) => {
  const { t } = useTranslation();
  const [facts, setFacts] = useState<KnownFact[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    phantomSelfApi
      .facts()
      .then((f) => alive && setFacts(Array.isArray(f) ? f : null))
      .catch(() => alive && setFacts(null));
    return () => {
      alive = false;
    };
  }, []);

  if (facts === null) return null;

  const forget = async (id: string) => {
    soundFx.playTap();
    setBusy(id);
    setFailed(false);
    try {
      await phantomSelfApi.forget(id, 'forget');
      setFacts((f) => (f ? f.filter((x) => x.id !== id) : f));
    } catch {
      setFailed(true);
    } finally {
      setBusy(null);
    }
  };

  const shown = facts.filter((f) => !f.old).slice(0, SHOWN_FACTS);
  const more =
    facts.length > shown.length ? (
      <button type="button" onClick={onAll} className="-my-3 -mr-2 h-11 min-h-0 px-2 text-subhead text-accent-text">
        {t('meSections.all', { n: facts.length })}
      </button>
    ) : undefined;

  return (
    <section data-testid="me-knows">
      <SectionTitle aside={more}>{t('meSections.memoryTitle')}</SectionTitle>
      {facts.length === 0 ? (
        <p data-testid="me-knows-empty" className="mx-4 px-1 text-callout text-ink-3">
          {t('meSections.memoryEmpty')}
        </p>
      ) : (
        shown.length > 0 && (
          <ul className="mx-4 rounded-[16px] bg-raised overflow-hidden">
            {shown.map((f, i) => (
              <li key={f.id}>
                {i > 0 && <Divider />}
                <div className="min-h-[60px] py-2 pl-4 pr-2 flex items-center gap-3">
                  <span className="flex-1 min-w-0">
                    <span className="block text-callout text-ink break-words">{f.text}</span>
                    {f.from_label && <span className="block text-caption text-ink-3">{f.from_label}</span>}
                  </span>
                  <button
                    type="button"
                    disabled={busy === f.id}
                    onClick={() => void forget(f.id)}
                    aria-label={t('meSections.forgetAria', { text: f.text })}
                    className="shrink-0 h-11 min-h-0 px-3 rounded-control text-subhead text-ink-2 hover:bg-overlay active:bg-overlay disabled:opacity-40"
                  >
                    {t('meSections.forget')}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )
      )}
      {failed && <p className="mx-4 mt-2 px-1 text-callout text-danger">{t('meSections.forgetFailed')}</p>}
    </section>
  );
};

/** Підпис і перехід для плитки «Мої люди»; свої пристрої не рахуються. */
export const useNetworkLine = (onGo: () => void) => {
  const { t, locale } = useTranslation();
  const count = (n: number, one: MessageKey, few: MessageKey, many: MessageKey) =>
    t(pickPlural(locale, n, one, few, many));
  const chats = useMessengerStore((s) => s.chats);
  const { people, groups } = useMemo(() => {
    const live = chats.filter((c) => !isMyDevice(c));
    return {
      people: live.filter((c) => chatKind(c) === 'person').length,
      groups: live.filter((c) => chatKind(c) === 'group').length,
    };
  }, [chats]);
  const line = [
    people > 0 && `${people}\u00A0${count(people, 'meSections.person1', 'meSections.person2', 'meSections.person5')}`,
    groups > 0 && `${groups}\u00A0${count(groups, 'meSections.group1', 'meSections.group2', 'meSections.group5')}`,
  ]
    .filter(Boolean)
    .join(' · ');
  const go = () => {
    soundFx.playTap();
    const filter: ChatFilter = people === 0 && groups > 0 ? 'group' : 'person';
    window.dispatchEvent(new CustomEvent<ChatFilter>(LIST_FILTER_EVENT, { detail: filter }));
    onGo();
  };
  return { line, go };
};

/**
 * Тема (рішення власника 01.10): за справжнім сонцем — типово; або стала
 * Ніч чи День. Перемикач на три (§7.2: сегменти лише для 2–3 варіантів).
 */
/** Мініатюра розмови в темі `theme`: дві бульбашки (surface і self) на canvas. */
const ThemeMini: React.FC<{ theme: 'day' | 'night'; className?: string }> = ({ theme, className = '' }) => (
  <span data-theme={theme} aria-hidden className={`flex flex-col justify-center gap-2 bg-canvas p-3 ${className}`}>
    <span data-theme-bubble="other" className="block h-4 w-3/5 rounded-[6px] rounded-bl-[2px] bg-surface" />
    <span data-theme-bubble="self" className="ml-auto block h-4 w-1/2 rounded-[6px] rounded-br-[2px] bg-self" />
  </span>
);

export const ThemeChoice: React.FC<{ onChange?: (mode: ThemeMode) => void }> = ({ onChange }) => {
  const { t } = useTranslation();
  const [mode, setMode] = useState<ThemeMode>(() => readThemeMode());
  const options: Array<{ key: ThemeMode; label: string }> = [
    { key: 'day', label: t('meSections.themeDay') },
    { key: 'night', label: t('meSections.themeNight') },
    { key: 'auto', label: t('meSections.themeAuto') },
  ];
  return (
    <div data-testid="me-theme" className="px-4 py-3">
      <div className="flex items-center gap-3 mb-3">
        <SunMoon className="w-5 h-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
        <span className="flex-1 text-body text-ink">{t('meSections.theme')}</span>
      </div>
      <div role="radiogroup" aria-label={t('meSections.theme')} className="grid grid-cols-3 gap-2">
        {options.map((o) => {
          const on = o.key === mode;
          return (
            <button
              key={o.key}
              type="button"
              role="radio"
              aria-checked={on}
              data-theme-mode={o.key}
              onClick={() => {
                soundFx.playTap();
                setMode(o.key);
                setThemeMode(o.key);
                onChange?.(o.key);
              }}
              className="group flex flex-col items-center gap-1.5 focus-visible:outline-none"
            >
              <span
                className={`relative w-full h-24 rounded-control overflow-hidden transition-colors duration-quick group-focus-visible:outline group-focus-visible:outline-2 group-focus-visible:outline-accent-line ${
                  on ? 'border-2 border-accent-line' : 'border border-line-strong'
                }`}
              >
                {o.key === 'auto' ? (
                  <>
                    <ThemeMini theme="day" className="absolute inset-0 [clip-path:polygon(0_0,100%_0,0_100%)]" />
                    <ThemeMini theme="night" className="absolute inset-0 [clip-path:polygon(100%_0,100%_100%,0_100%)]" />
                  </>
                ) : (
                  <ThemeMini theme={o.key} className="absolute inset-0" />
                )}
                {on && (
                  <span className="absolute top-1.5 right-1.5 w-5 h-5 rounded-full bg-accent text-on-accent flex items-center justify-center" aria-hidden>
                    <Check className="w-3.5 h-3.5" strokeWidth={1.75} />
                  </span>
                )}
              </span>
              <span className={`text-footnote ${on ? 'font-semibold text-ink' : 'text-ink-2'}`}>{o.label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
};

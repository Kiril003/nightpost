import React, { useEffect, useState } from 'react';
import {
  ShieldCheck,
  ShieldAlert,
  AlertTriangle,
  ChevronDown,
  ChevronUp,
  Pencil,
  Eraser,
  QrCode,
  ScanLine,
  Trash2,
  Check,
  Bell,
  BellOff,
} from 'lucide-react';
import QRCode from 'qrcode';
import { QrScanner } from './QrScanner';
import { messengerApi } from '../../services/messengerApi';
import type { NodeContact } from '../../services/messengerApi';
import { soundFx } from '../../utils/messengerSound';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { isPhoneShell } from './phoneShell';
import { displayTitle } from './chatListFormat';
import { useIceRoads } from '../../hooks/useIceRoads';
import { useMessengerStore } from '../../stores/messengerStore';
import { Avatar, AVATAR_SIZE } from './Avatar';
import { DoorRow, FormSheet, PrimaryButton } from './FormSheet';
import { useTranslation } from '../../i18n/useTranslation';
import { LIT } from '../../styles/phantom/literals';

// У код кладемо число звірки, а не ключ: звіряються саме числа. Префікс
// потрібен, щоб відрізнити його від QR з ключовим бандлом у панелі ідентичності.
const SAFETY_QR_PREFIX = 'phantom-safety:';

// Порівнюємо голі цифри: пробіли, префікс і переноси в різних збірках можуть
// відрізнятись, а число — ні.
const digitsOf = (text: string): string => text.replace(/\D/g, '');

type CompareResult = 'match' | 'mismatch' | 'unreadable';

export const contactForPeer = (list: NodeContact[], peerId: string): NodeContact | null => {
  const rows = list.filter((c) => c.peer_node_id === peerId);
  return rows.find((c) => c.key_changed) ?? rows[0] ?? null;
};

export const needsVerify = (c: NodeContact): boolean => !(c.verified && !c.key_changed);

// Звірка одного контакту: число 12×5, підказка і кнопка підтвердження.
// Живе тут, щоб і панель ідентичності, і шапка чату звіряли однаково.
export const SafetyVerifyBlock: React.FC<{
  contact: NodeContact;
  onVerified: (contact: NodeContact) => void;
  onDismiss?: () => void;
  /** Людина вже натиснула «Звірити ключ» — механіку не ховаємо за ще одним дотиком. */
  startOpen?: boolean;
  /** Відкрити одразу панель QR; передається разом зі startOpen, щоб блок був розгорнутий. */
  startPanel?: 'qr';
}> = ({ contact, onVerified, onDismiss, startOpen = false, startPanel }) => {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [panel, setPanel] = useState<'none' | 'qr' | 'scan'>(startPanel ?? 'none');
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [compared, setCompared] = useState<CompareResult | null>(null);
  const [typed, setTyped] = useState('');
  const [helpOpen, setHelpOpen] = useState(false);
  // Числа розійшлися — підтвердження закрите. Замок липкий: перемикання
  // панелей його не знімає, бо один випадковий клік після «між вами хтось є»
  // ставить фальшиве «Звірено голосом», і далі людина довіряє підробці.
  const [locked, setLocked] = useState(false);
  // Замок відкриває лише свідомий новий цикл: людина натиснула «Порівняти
  // заново» і після цього отримала збіг. Закрити й відкрити картку — теж
  // новий цикл, там стан вмирає разом із компонентом.
  const [recompare, setRecompare] = useState(false);
  // Механіка звірки згорнута, поки її не покликали. Розгортає її або палець,
  // або подія — зміна ключа, і тоді вона вже стоїть розгорнутою.
  const [open, setOpen] = useState(startOpen || Boolean(startPanel));

  const mine = digitsOf(contact.safety_number);

  // Зміна ключа скасовує звірку. Без цього рядок «Ключ змінився — порівняйте
  // наново» стояв над зеленим «Звірено», а самої дороги порівняти не було:
  // блок звірки ховається саме за `verified`.
  const claimStands = !needsVerify(contact);

  useEffect(() => {
    if (panel !== 'qr') return;
    void QRCode.toDataURL(`${SAFETY_QR_PREFIX}${mine}`, {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 220,
      color: { dark: LIT['ink-green'], light: LIT['white'] },
    })
      .then(setQrDataUrl)
      .catch(() => setQrDataUrl(null));
  }, [panel, mine]);

  // fromCompare — виклик із порівняння, де замок уже знято цим самим кліком:
  // стан ще не встиг оновитись, тож перевіряти його тут означало б відкинути
  // законний збіг.
  const confirm = async (fromCompare = false) => {
    if (locked && !fromCompare) return;
    setBusy(true);
    setFailed(false);
    try {
      const updated = await messengerApi.verifyContact(contact.id);
      soundFx.playTap();
      onVerified(updated);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  // Скан і вставлене руками число йдуть однією дорогою. Чужий QR (наприклад,
  // ключовий бандл) — це не «числа різні», а «це не число»: плутати ці два
  // стани не можна, бо перший означає посередника, а другий — не той код.
  const compareWith = (raw: string) => {
    const seen = digitsOf(raw);
    if (!seen || seen.length !== mine.length) {
      setCompared('unreadable');
      return;
    }
    if (seen !== mine) {
      setCompared('mismatch');
      setLocked(true);
      setRecompare(false);
      return;
    }
    setCompared('match');
    // Збіг після розбіжності зараховуємо тільки в новому циклі: інакше та сама
    // підміна знімає свій же замок, варто лише вписати правильне число.
    if (locked && !recompare) return;
    setLocked(false);
    setRecompare(false);
    void confirm(true);
  };

  // Свідомий новий цикл: чистимо вердикт і повертаємо в скан, але замок
  // лишається — його зніме тільки збіг.
  const startRecompare = () => {
    setCompared(null);
    setTyped('');
    setRecompare(true);
    setPanel('scan');
  };

  if (claimStands) {
    return (
      <div className="space-y-2">
        <div className="flex items-center gap-1.5 text-ink-2 text-callout font-semibold">
          <ShieldCheck className="w-4 h-4" strokeWidth={1.75} />
          <span>{contact.verified_by_camera ? t('verifyContact.verifiedByCamera') : t('verifyContact.verified')}</span>
        </div>
        <code className="block text-callout text-ink-3 leading-relaxed break-words bg-canvas p-2.5 rounded-control ">
          {contact.safety_number_pretty || contact.safety_number}
        </code>
      </div>
    );
  }

  // Ключ той самий, звірки ще не було: це не подія, а можливість. Один рядок,
  // за яким уся машинерія, замість неї самої перед очима.
  if (!contact.key_changed && !open) {
    return (
      <button
        onClick={() => setOpen(true)}
        data-safety-open
        className="touch-floor min-h-[44px] flex items-center gap-1.5 text-footnote font-semibold text-ink-3 hover:text-ink transition-colors"
      >
        <ShieldCheck className="w-4 h-4 text-ink-2" strokeWidth={1.75} />
        <span>{t('verifyContact.ensureHim')}</span>
        <ChevronDown className="w-3.5 h-3.5" strokeWidth={1.75} />
      </button>
    );
  }

  return (
    <div className="space-y-2">
      {/* Єдина подія, заради якої безпека тут озивається сама. Сказано не про
          ключ, а про те, що людина мусить вирішити. */}
      {contact.key_changed && (
        <div
          data-safety-key-changed
          className="p-3 bg-raised rounded-control border flex items-start gap-2"
        >
          <ShieldAlert className="w-4 h-4 text-danger mt-0.5 shrink-0" strokeWidth={1.75} />
          <span className="text-callout text-danger leading-relaxed">
            <b className="font-semibold">{t('verifyContact.deviceChanged')}</b> {t('verifyContact.deviceChangedBody')}
          </span>
        </div>
      )}
      <code className="block text-callout text-ink-3 leading-relaxed break-words bg-canvas p-2.5 rounded-control ">
        {contact.safety_number_pretty}
      </code>
      {(
        <>
          <span className="text-callout text-ink-3 block leading-relaxed">
            {t('verifyContact.readAloud')}
          </span>

          {/* Читати вголос 60 цифр здатні не всі й не завжди — той самий
              результат дає звірка кодом, і вона ще й порівнює за вас. */}
          <div className="pt-0.5 space-y-2" data-safety-qr>
            <button
              onClick={() => {
                setPanel((v) => (v === 'none' ? 'qr' : 'none'));
                setCompared(null);
              }}
              className="touch-floor min-h-[44px] flex items-center gap-1.5 text-footnote font-semibold text-ink-2 transition-transform"
            >
              <QrCode className="w-4 h-4" strokeWidth={1.75} />
              <span>{t('verifyContact.compareByCode')}</span>
              {panel === 'none' ? (
                <ChevronDown className="w-3.5 h-3.5" strokeWidth={1.75} />
              ) : (
                <ChevronUp className="w-3.5 h-3.5" strokeWidth={1.75} />
              )}
            </button>

            {panel === 'qr' && (
              <div className="space-y-2" data-safety-qr-open>
                <div className="p-3 bg-raised rounded-control flex flex-col items-center gap-2">
                  {qrDataUrl ? (
                    <img
                      src={qrDataUrl}
                      alt={t('verifyContact.codeLabel')}
                      className="w-full max-w-[190px] rounded-control bg-white p-2.5 "
                    />
                  ) : (
                    <span className="text-callout text-ink-3 py-6">{t('verifyContact.drawing')}</span>
                  )}
                  <span className="text-callout text-ink-3 leading-relaxed text-center">
                    {t('verifyContact.yourNumber')}
                  </span>
                </div>
                <button
                  onClick={() => {
                    setPanel('scan');
                    setCompared(null);
                  }}
                  className="touch-floor min-h-[44px] flex items-center gap-1.5 text-footnote font-semibold text-ink-2 transition-transform"
                >
                  <ScanLine className="w-4 h-4" strokeWidth={1.75} />
                  <span>{t('verifyContact.scan')}</span>
                </button>
              </div>
            )}

            {panel === 'scan' && (
              <div className="space-y-2" data-safety-scan-open>
                <QrScanner onFound={compareWith} onCancel={() => setPanel('qr')} />
                {/* Камери може не бути — тоді число вводять руками, як і диктували б голосом. */}
                <div className="flex items-center gap-2">
                  <input
                    value={typed}
                    onChange={(e) => setTyped(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') compareWith(typed);
                    }}
                    placeholder={t('verifyContact.typePlaceholder')}
                    data-safety-manual
                    className="flex-1 min-w-0 min-h-[44px] px-3 py-2 text-callout rounded-control bg-canvas text-ink placeholder:text-ink-3 focus:outline-none "
                  />
                  <button
                    onClick={() => compareWith(typed)}
                    data-safety-compare-btn
                    className="touch-floor min-h-[44px] px-3.5 py-2 rounded-control bg-raised hover:bg-raised text-ink-2 text-callout font-semibold transition-all shrink-0"
                  >
                    {t('verifyContact.compare')}
                  </button>
                </div>
              </div>
            )}

            {compared === 'match' && (
              <div
                data-safety-compare="match"
                className="p-3 bg-raised rounded-control border flex items-start gap-2"
              >
                <ShieldCheck className="w-4 h-4 text-ink-2 mt-0.5 shrink-0" strokeWidth={1.75} />
                <span className="text-callout text-ink-2 leading-relaxed font-semibold">
                  {locked
                    ? t('verifyContact.matchLocked')
                    : t('verifyContact.matchOk')}
                </span>
              </div>
            )}

            {compared === 'mismatch' && (
              <div
                data-safety-compare="mismatch"
                className="p-3 bg-raised rounded-control border flex items-start gap-2"
              >
                <ShieldAlert className="w-4 h-4 text-danger mt-0.5 shrink-0" strokeWidth={1.75} />
                <div className="space-y-1.5">
                  <span className="text-callout text-danger leading-relaxed block">
                    <b className="font-semibold">{t('verifyContact.mismatch')}</b>{' '}
                    {t('verifyContact.mismatchBody')}
                  </span>
                  <button
                    onClick={startRecompare}
                    data-safety-recompare
                    className="touch-floor min-h-[44px] text-callout font-semibold text-danger transition-transform"
                  >
                    {t('verifyContact.compareAgain')}
                  </button>
                </div>
              </div>
            )}

            {compared === 'unreadable' && (
              <div
                data-safety-compare="unreadable"
                className="p-3 bg-raised rounded-control border flex items-start gap-2"
              >
                <AlertTriangle className="w-4 h-4 text-ink-2 mt-0.5 shrink-0" strokeWidth={1.75} />
                <span className="text-callout text-ink-2 leading-relaxed">
                  {t('verifyContact.notSafetyNumber')}
                </span>
              </div>
            )}
          </div>

          <div className="flex items-center gap-3 pt-1">
            <button
              onClick={() => void confirm()}
              disabled={busy || locked}
              data-safety-confirm
              data-safety-locked={locked ? 'yes' : 'no'}
              title={
                locked
                  ? t('verifyContact.confirmLockedTitle')
                  : t('verifyContact.confirmTitle')
              }
              className={`touch-floor min-h-[44px] px-4 py-2 rounded-control text-callout font-semibold transition-all disabled:opacity-50 ${
                locked
                  ? 'bg-raised text-ink-3 cursor-not-allowed '
                  : 'bg-accent hover:bg-accent text-on-accent  '
              }`}
            >
              {busy ? t('verifyContact.verifying') : t('verifyContact.confirm')}
            </button>
            {onDismiss && (
              <button
                onClick={onDismiss}
                className="touch-floor min-h-[44px] px-3.5 py-2 rounded-control text-callout font-semibold text-ink-3 hover:text-ink hover:bg-raised transition-colors"
              >
                {t('verifyContact.notYet')}
              </button>
            )}
          </div>
          {locked && (
            <div data-safety-lock-note className="space-y-1">
              <span className="text-callout text-danger block leading-relaxed">
                {t('verifyContact.lockedBody')}
              </span>
              {compared !== 'mismatch' && (
                <button
                  onClick={startRecompare}
                  data-safety-recompare
                  className="touch-floor min-h-[44px] text-callout font-semibold text-danger transition-transform"
                >
                  {t('verifyContact.compareAgain')}
                </button>
              )}
            </div>
          )}
          {failed && (
            <span className="text-callout text-danger block">
              {t('verifyContact.nodeRejected')}
            </span>
          )}
        </>
      )}


      {/* Стоїть скрізь, де відкрито саму звірку: відповідь мусить лежати поруч
          із числом, а не шукатись у паніці після розбіжності. */}
      <div className="pt-1.5 border-t border-line">
        <button
          onClick={() => setHelpOpen((v) => !v)}
          data-safety-help
          className="touch-floor min-h-[44px] flex items-center gap-1 text-footnote font-semibold text-ink-3 hover:text-ink transition-colors"
        >
          <span>{t('verifyContact.whatIfDiffer')}</span>
          {helpOpen ? <ChevronUp className="w-3.5 h-3.5" strokeWidth={1.75} /> : <ChevronDown className="w-3.5 h-3.5" strokeWidth={1.75} />}
        </button>
        {helpOpen && (
          <p className="mt-1 text-callout text-ink-3 leading-relaxed bg-canvas p-2.5 rounded-control ">
            {t('verifyContact.differHelp')}
          </p>
        )}
      </div>
    </div>
  );
};

export interface SheetChat {
  id: string;
  title: string;
  peerNodeId?: string;
  isGroup?: boolean;
  avatar?: string;
  muted?: boolean;
}

/** Відбиток ключа — групами по 4, рядками по 4 (4×4). */
export const fingerprintGrid = (raw?: string | null): string[][] => {
  const hex = (raw ?? '').replace(/[^0-9a-f]/gi, '').toUpperCase();
  if (!hex) return [];
  // 16 груп на будь-яку довжину: 32 hex → по 2, 64 hex → по 4.
  const size = Math.max(1, Math.ceil(hex.length / 16));
  const groups = hex.match(new RegExp(`.{1,${size}}`, 'g'))?.slice(0, 16) ?? [];
  const rows: string[][] = [];
  for (let i = 0; i < groups.length; i += 4) rows.push(groups.slice(i, i + 4));
  return rows;
};

interface ContactSheetProps {
  chat: SheetChat;
  /** Лишено для сумісності: картка тепер — аркуш знизу, а не спливайка біля шапки. */
  anchor?: { top: number; left: number };
  onClose: () => void;
  onOpenProfile?: () => void;
  onVerified?: (verified: boolean) => void;
  onRenamed?: (title: string) => void;
  onCleared?: () => void;
  onDeleted?: () => void;
}

// Лист контакту з шапки: усе про співрозмовника й дії з розмовою за 1 клік.
export const ContactSheet: React.FC<ContactSheetProps> = ({
  chat,
  onClose,
  onOpenProfile: _onOpenProfile,
  onVerified,
  onRenamed,
  onCleared,
  onDeleted,
}) => {
  const { t } = useTranslation();
  // `failed` окремо від `none`: «вузол не відповів» — це не «ключа ще немає».
  const [state, setState] = useState<'loading' | 'none' | 'ready' | 'failed'>('loading');
  const [contact, setContact] = useState<NodeContact | null>(null);
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState(chat.title);
  const [confirmKind, setConfirmKind] = useState<'clear' | 'delete' | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  // Картка живе, лише поки її змонтовано, тож умова завжди істинна.
  const ice = useIceRoads();
  useEscapeClose(true, onClose);

  useEffect(() => {
    let alive = true;
    // Обнуляємо ДО запиту: інакше при переході з розмови A в розмову B поки
    // летить запит, у картці B стоїть зелене «Звірено» від A.
    setContact(null);
    setState('loading');
    void (async () => {
      // Групі й розмові без ключа звіряти нема кого — чесний стан замість порожнечі.
      if (chat.isGroup || !chat.peerNodeId) {
        if (alive) setState('none');
        return;
      }
      try {
        const list = await messengerApi.listContacts();
        if (!alive) return;
        // `find` віддавав ПЕРШИЙ рядок: при двох записах на один вузол старе
        // `verified: true` перемагало свіже `key_changed: true` — навпаки, ніж
        // мусить бути для твердження про безпеку. Тривога виграє завжди.
        const found = contactForPeer(list, chat.peerNodeId);
        setContact(found);
        setState(found ? 'ready' : 'none');
      } catch {
        if (alive) setState('failed');
      }
    })();
    return () => {
      alive = false;
    };
  }, [chat.peerNodeId, chat.isGroup]);

  const saveRename = async () => {
    const trimmed = nameDraft.trim();
    if (!trimmed || trimmed === chat.title) {
      setRenaming(false);
      return;
    }
    setBusy(true);
    try {
      await messengerApi.renameConversation(chat.id, trimmed);
      onRenamed?.(trimmed);
      soundFx.playTap();
      setRenaming(false);
    } catch {
      setNote(t('verifyContact.renameRejected'));
    } finally {
      setBusy(false);
    }
  };

  const doClear = async () => {
    setBusy(true);
    try {
      await messengerApi.clearConversation(chat.id);
      soundFx.playTap();
      onCleared?.();
      onClose();
    } catch {
      setNote(t('verifyContact.clearFailed'));
    } finally {
      setBusy(false);
    }
  };

  const doDelete = async () => {
    setBusy(true);
    try {
      await messengerApi.deleteConversation(chat.id);
      soundFx.playTap();
      onDeleted?.();
      onClose();
    } catch {
      setNote(t('verifyContact.deleteFailed'));
    } finally {
      setBusy(false);
    }
  };

  const verifiedNow = Boolean(contact?.verified && !contact?.key_changed);
  const keyLine =
    state === 'loading'
      ? { text: t('verifyContact.reading'), tone: 'text-ink-3' }
      : state === 'failed'
      ? { text: t('verifyContact.checkFailed'), tone: 'text-warning' }
      : chat.isGroup
      ? { text: t('verifyContact.groupChecks'), tone: 'text-ink-3' }
      : !contact
      ? { text: t('verifyContact.notReachable'), tone: 'text-danger' }
      : contact.key_changed
      ? { text: t('verifyContact.keyChanged'), tone: 'text-danger' }
      : contact.verified
      ? { text: contact.verified_by_camera ? t('verifyContact.verifiedShort') : t('verifyContact.verifiedShort'), tone: 'text-ink-3' }
      : { text: t('verifyContact.notVerified'), tone: 'text-warning' };
  const grid = fingerprintGrid(contact?.fingerprint || contact?.safety_number);
  const muted = useMessengerStore((st) => st.chats.find((c) => c.id === chat.id)?.muted ?? chat.muted ?? false);
  const [verifying, setVerifying] = useState(false);
  const ICO = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

  const primary = chat.isGroup || state !== 'ready' || !contact ? null : verifiedNow ? (
    <PrimaryButton
      onClick={() => {
        onClose();
        window.setTimeout(() => document.querySelector<HTMLTextAreaElement>('[data-composer-input]')?.focus(), 0);
      }}
    >
      {t('verifyContact.message')}
    </PrimaryButton>
  ) : verifying ? null : (
    <PrimaryButton onClick={() => setVerifying(true)}>{t('verifyContact.verifyPerson')}</PrimaryButton>
  );

  return (
    <FormSheet open tall hideTitle onClose={onClose} title={t('verifyContact.cardLabel')} testId="contact-sheet">
      <div className="px-4 pb-2">
        <Avatar src={chat.avatar} name={displayTitle(chat.title)} className={AVATAR_SIZE.profile} />
        {renaming ? (
          <form
            className="mt-4 flex items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              void saveRename();
            }}
          >
            <input
              autoFocus
              value={nameDraft}
              onChange={(e) => setNameDraft(e.target.value)}
              aria-label={t('verifyContact.chatName')}
              className="flex-1 min-w-0 h-12 px-3 rounded-control bg-raised text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line"
            />
            <button
              type="submit"
              disabled={busy}
              aria-label={t('verifyContact.saveName')}
              className="w-12 h-12 min-h-0 min-w-0 shrink-0 rounded-control bg-raised text-ink-2 flex items-center justify-center"
            >
              <Check {...ICO} />
            </button>
          </form>
        ) : (
          <h2 className="mt-4 text-title-1 text-ink break-words">{displayTitle(chat.title)}</h2>
        )}
        <p className={`mt-1  text-caption ${keyLine.tone}`} data-testid={state === 'failed' ? 'contact-state-unknown' : 'contact-key-state'}>
          {keyLine.text}
        </p>
        {state === 'ready' && contact && !chat.isGroup && !contact.peer_address && (
          <p className="mt-1 text-callout text-ink-2">{t('verifyContact.noDirectRoute')}</p>
        )}

        {grid.length > 0 && (
          <div className="mt-5" aria-label={t('verifyContact.codeAttr')}>
            <p className="text-footnote font-semibold text-ink-2 mb-2">{t('verifyContact.codeTitle')}</p>
            <div className="grid grid-cols-4 gap-x-5 gap-y-1 text-body text-ink w-fit" data-testid="contact-fingerprint">
              {grid.flat().map((g, i) => (
                <span key={i} className="tnum">{g}</span>
              ))}
            </div>
          </div>
        )}

        {primary && <div className="mt-6">{primary}</div>}

        {state === 'ready' && contact && (verifying || contact.key_changed) && (
          <div className="mt-4">
            <SafetyVerifyBlock
              contact={contact}
              startOpen
              onVerified={(updated) => {
                setContact(updated);
                setVerifying(false);
                onVerified?.(updated.verified);
              }}
              onDismiss={onClose}
            />
          </div>
        )}
      </div>

      {(__PHONE_BUILD__ || isPhoneShell()) && ice?.inbound !== true && !chat.isGroup && (
        <p data-phone-no-call className="px-4 pt-2 text-callout text-ink-3">
          {t('verifyContact.callsUnavailable')}
        </p>
      )}

      {confirmKind === null ? (
        <div className="mt-4 border-t border-line pt-2 pb-2">
          <DoorRow
            icon={muted ? <Bell {...ICO} /> : <BellOff {...ICO} />}
            label={muted ? t('verifyContact.unmute') : t('verifyContact.mute')}
            onClick={() => {
              soundFx.playTap();
              useMessengerStore.getState().toggleMuteChat(chat.id);
            }}
          />
          <DoorRow
            icon={<Pencil {...ICO} />}
            label={t('verifyContact.rename')}
            onClick={() => {
              setNameDraft(chat.title);
              setRenaming(true);
            }}
          />
          <DoorRow icon={<Eraser {...ICO} />} label={t('verifyContact.clearHistory')} onClick={() => setConfirmKind('clear')} />
          <DoorRow
            icon={<Trash2 {...ICO} className="w-5 h-5 text-danger" />}
            label={t('verifyContact.deleteChat')}
            tone="danger"
            onClick={() => setConfirmKind('delete')}
          />
        </div>
      ) : (
        <div className="mt-4 border-t border-line px-4 pt-4 pb-4">
          <p className="text-callout text-ink-2">
            {confirmKind === 'clear'
              ? t('verifyContact.confirmClear')
              : t('verifyContact.confirmDelete')}
          </p>
          <button
            type="button"
            onClick={() => (confirmKind === 'clear' ? void doClear() : void doDelete())}
            disabled={busy}
            className="mt-4 w-full h-12 min-h-0 rounded-control bg-raised text-headline text-danger"
          >
            {busy ? t('verifyContact.working') : confirmKind === 'clear' ? t('verifyContact.clear') : t('verifyContact.delete')}
          </button>
          <button
            type="button"
            onClick={() => setConfirmKind(null)}
            className="mt-2 w-full h-12 min-h-0 rounded-control text-headline text-ink-2"
          >
            {t('verifyContact.cancel')}
          </button>
        </div>
      )}

      {note && <p className="px-4 pb-4 text-callout text-danger">{note}</p>}
    </FormSheet>
  );
};

export default ContactSheet;

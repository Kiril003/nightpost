import React, { useEffect, useState } from 'react';
import { Check, Copy, QrCode, Share2, X } from 'lucide-react';
import QRCode from 'qrcode';
import { messengerApi } from '../../services/messengerApi';
import type { NodeIdentity } from '../../services/messengerApi';
import { soundFx } from '../../utils/messengerSound';
import { copyText, encodeInvite, inviteShareText, phoneCodeOf, selfAddressGuess } from '../../utils/messengerInvite';
import { useTranslation } from '../../i18n/useTranslation';
import { isPhoneShell } from './phoneShell';
import { Avatar, AVATAR_SIZE } from './Avatar';
import { useMessengerStore } from '../../stores/messengerStore';
import { useProfiles } from '../../stores/profilesStore';
import { LIT } from '../../styles/phantom/literals';

const NAME_KEY = 'phantom_invite_name';
const ADDR_KEY = 'phantom_invite_address';

interface InviteCardProps {
  /** Хрестик показуємо лише там, де картку можна згорнути. */
  onClose?: () => void;
  /** «Мій код» у «Я»: QR одразу розгорнутий, бо по нього й прийшли. */
  qrFirst?: boolean;
}

/** Імʼя вузла з відповіді моста: `bundle.name` — те саме, що вшите в рядок PH2. */
const hostNameOf = (me: NodeIdentity | null): string => {
  const raw = me?.bundle?.name;
  return typeof raw === 'string' ? raw.trim() : '';
};


/**
 * Одне запрошення — один рядок, і три способи його віддати.
 *
 * До цього, щоб покликати людину, треба було передати їй окремо адресу вузла
 * і окремо ключ, і вона мусила вкласти їх у два різні поля. Тут усе разом:
 * що скопіював — те й працює.
 *
 * На телефоні рядок складає не веб, а хост: міст віддає `link` (PeerInvite.link),
 * `compact` (PeerInvite.encode) і `address`. Веб їх лише показує. Дефект зі скла
 * 04.09: поле «Адреса вашого вузла» брало `location.origin` — а в WebView це
 * вигаданий https://phantom.local перехоплювача, тоді як рядок PH2 поруч ніс
 * справжню адресу. Поле брехало про рядок. Клас дефекту — «спитай, хто вже це
 * робить»: хост робив, веб складав своє поруч.
 */
/**
 * Запрошення говорить іменем активного профілю (SPEC-2810 §3): своє імʼя профілю, інакше —
 * облікового запису; «Без імені» імені не дає. Зміна в непервинному профілі — імʼя профілю.
 */
function useInviteName(hostName: string) {
  const active = useProfiles((s) => s.profiles.find((p) => p.id === s.activeId) ?? null);
  const unnamed = active?.kind === 'unnamed';
  const profileName = active?.ownName.trim() ?? '';
  const shownName = unnamed ? '' : profileName || hostName;
  const ownsName = Boolean(active && !unnamed && (profileName || active.kind !== 'personal'));
  return { active, unnamed, shownName, ownsName };
}

export const InviteCard: React.FC<InviteCardProps> = ({ onClose, qrFirst = false }) => {
  // Збірка стоїть першою навмисне: у phone-бандлі це літерал `true`, і гілка
  // ПК нижче (адреса з origin, конверт PHI, підпис «Підставили ту…») з нього
  // вирізається ще на збірці — сторож `origin-as-node-address` у
  // bundlePatterns.ts це міряє. Міст — щоб у jsdom і на стенді той самий
  // код умикався заглушкою.
  const phone = __PHONE_BUILD__ || isPhoneShell();
  const { t, locale } = useTranslation();

  const [me, setMe] = useState<NodeIdentity | null>(null);
  const self = useMessengerStore((s) => s.currentUser);
  const [name, setName] = useState(() => (phone ? '' : (localStorage.getItem(NAME_KEY) ?? '')));
  const [address, setAddress] = useState(() =>
    phone ? '' : (localStorage.getItem(ADDR_KEY) ?? selfAddressGuess()),
  );
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [shared, setShared] = useState(false);
  const [showQr, setShowQr] = useState(qrFirst);
  const [showRaw, setShowRaw] = useState(false);
  const [qrDataUrl, setQrDataUrl] = useState<string | null>(null);
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState('');
  const [renameBusy, setRenameBusy] = useState(false);

  // Ключ беремо один раз: кожен виклик /identity витрачає одноразовий prekey,
  // а запас у вузла скінченний — смикати його на кожен рух пальця не можна.
  useEffect(() => {
    void (async () => {
      try {
        const got = await messengerApi.identity();
        setMe(got);
        // Відповідь є, а рядка в ній нема — це не «беру ключ…», а відмова.
        if (phone && !got.link && !got.compact) {
          setError(t('inviteCard.phoneNoInvite'));
        }
      } catch {
        setError(t('inviteCard.prepareFailed'));
      }
    })();
  }, [phone]);

  // Телефон: рядок і QR — рівно те, що дав міст; веб тут нічого не складає.
  // ПК: посилання вузла, якщо є, інакше конверт PHI з ключа, адреси та імені —
  // код телефона (PH2-…) у нього не влазить.
  const invite = phone
    ? me?.link || me?.compact || ''
    : me?.link
      ? me.link
      : me?.compact && !me.compact.startsWith('PH')
        ? encodeInvite({ compact: me.compact, address, name })
        : (me?.compact ?? '');
  // D6 (08.10): у QR — сирий код телефона, а «Надіслати»/«Копія» віддають
  // https-посилання сайту і той самий код запасом. Без PH-коду (стара
  // збірка, ПК) — як і було, рядок моста.
  const code = phone ? phoneCodeOf(me) : null;
  const qrPayload = code ?? invite;
  const handOut = code ? inviteShareText(code, locale) : invite;

  useEffect(() => {
    if (!phone) localStorage.setItem(NAME_KEY, name);
  }, [phone, name]);
  useEffect(() => {
    if (!phone) localStorage.setItem(ADDR_KEY, address);
  }, [phone, address]);

  // Рівень корекції L, бо в коді лежать усі 400+ символів: із «M» модулі
  // дрібнішають так, що камера телефона бере код через раз.
  useEffect(() => {
    if (!qrPayload || !showQr) return;
    void QRCode.toDataURL(qrPayload, {
      errorCorrectionLevel: 'L',
      margin: 2,
      width: 640,
      color: { dark: LIT['ink-green'], light: LIT['white'] },
    })
      .then(setQrDataUrl)
      .catch(() => setQrDataUrl(null));
  }, [qrPayload, showQr]);

  const onCopy = async () => {
    if (!invite) return;
    const ok = await copyText(handOut);
    soundFx.playTap();
    if (!ok) {
      setError(t('inviteCard.clipboardDown'));
      return;
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 2200);
  };

  const onShare = async () => {
    if (!invite) return;
    try {
      const bridge = (typeof window !== 'undefined' && (window as unknown as { PhantomBridge?: { shareText?: (t: string, title?: string) => boolean } }).PhantomBridge);
      if (bridge && typeof bridge.shareText === 'function') {
        const ok = bridge.shareText(handOut, t('msgr.add.shareTitle'));
        if (ok) {
          soundFx.playTap();
          setShared(true);
          setTimeout(() => setShared(false), 2200);
          return;
        }
      }
      if (typeof navigator !== 'undefined' && typeof navigator.share === 'function') {
        await navigator.share({ text: handOut });
        soundFx.playTap();
        setShared(true);
        setTimeout(() => setShared(false), 2200);
      }
    } catch {
      /* людина передумала або застосунок не вибрано — це не помилка */
    }
  };

  const canShare = typeof window !== 'undefined' && (
    (typeof navigator !== 'undefined' && typeof navigator.share === 'function') ||
    Boolean((window as unknown as { PhantomBridge?: { shareText?: unknown } }).PhantomBridge?.shareText)
  );

  const hostName = hostNameOf(me);
  const { active, unnamed, shownName, ownsName } = useInviteName(hostName);

  // Телефон: код — головне, імʼя під ним, дії одним рядком. Без пояснень і
  // без самого рядка PH2 на екрані: він лишається прихованим полем, яке
  // розгортається лише тоді, коли буфер обміну відмовив.
  if (phone) {
    const pill = 'h-12 min-h-0 rounded-full text-callout font-semibold flex items-center justify-center gap-2 active:opacity-90';
    return (
      <div data-testid="invite-card" className="text-ink">
        {showQr && invite && (
          <div className="flex justify-center pb-4" data-testid="invite-qr-view">
            {qrDataUrl ? (
              <img src={qrDataUrl} alt={t('inviteCard.qrAlt')} className="w-full max-w-[280px] aspect-square rounded-card bg-white p-3" />
            ) : (
              <div className="w-full max-w-[280px] aspect-square rounded-card bg-raised" aria-label={t('inviteCard.preparingCode')} />
            )}
          </div>
        )}

        {me &&
          (renaming ? (
            <form
              className="flex items-center gap-2"
              onSubmit={async (e) => {
                e.preventDefault();
                const next = nameDraft.trim();
                if (!next || next === shownName) {
                  setRenaming(false);
                  return;
                }
                if (ownsName && active) {
                  useProfiles.getState().update(active.id, { ownName: next });
                  setRenaming(false);
                  return;
                }
                setRenameBusy(true);
                try {
                  setMe(await messengerApi.renameSelf(next));
                  setRenaming(false);
                } catch {
                  setError(t('inviteCard.renameRefused'));
                } finally {
                  setRenameBusy(false);
                }
              }}
            >
              <input
                autoFocus
                data-testid="invite-name-input"
                value={nameDraft}
                maxLength={64}
                onChange={(e) => setNameDraft(e.target.value)}
                aria-label={t('msgr.add.yourName')}
                className="flex-1 min-w-0 h-12 px-4 rounded-full bg-raised text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line"
              />
              <button type="submit" disabled={renameBusy} className="h-12 min-h-0 px-5 rounded-full bg-accent text-on-accent text-callout font-semibold">
                {renameBusy ? '…' : t('msgr.add.done')}
              </button>
            </form>
          ) : (
            <button
              type="button"
              data-testid="invite-name"
              onClick={() => {
                if (unnamed) return;
                soundFx.playTap();
                setNameDraft(shownName);
                setRenaming(true);
              }}
              className="w-full min-h-[64px] px-3 py-2 rounded-card bg-surface flex items-center gap-3 text-left active:bg-raised"
            >
              <Avatar src={self.avatar} name={shownName || t('inviteCard.meInitial')} tone={self.id || me.node_id} radius="rounded-full" className={AVATAR_SIZE.row} />
              <span className="flex-1 min-w-0">
                <span className="block text-caption text-ink-3">{t('inviteCard.nameInInvite')}</span>
                <span className={`block truncate text-headline ${shownName ? 'text-ink' : 'text-ink-3'}`}>{shownName || t('msgr.add.unnamed')}</span>
              </span>
              {!unnamed && <span className="shrink-0 pr-1 text-callout text-accent-text">{t('inviteCard.change')}</span>}
            </button>
          ))}

        {invite ? (
          <>
            <div className={`mt-3 grid gap-2 ${canShare ? 'grid-cols-3' : 'grid-cols-2'}`}>
              <button type="button" onClick={onCopy} data-testid="invite-copy" className={`${pill} bg-raised text-ink`}>
                {copied ? <Check className="w-[18px] h-[18px]" strokeWidth={2} /> : <Copy className="w-[18px] h-[18px]" strokeWidth={2} />}
                <span>{copied ? t('msgr.add.done') : t('inviteCard.copyShort')}</span>
              </button>
              <button
                type="button"
                onClick={() => {
                  soundFx.playTap();
                  setShowQr((v) => !v);
                }}
                aria-pressed={showQr}
                data-testid="invite-qr"
                className={`${pill} ${showQr ? 'bg-self text-ink' : 'bg-raised text-ink'}`}
              >
                <QrCode className="w-[18px] h-[18px]" strokeWidth={2} />
                <span>{t('inviteCard.code')}</span>
              </button>
              {canShare && (
                <button type="button" onClick={onShare} data-testid="invite-share" className={`${pill} bg-accent text-on-accent`}>
                  <Share2 className="w-[18px] h-[18px]" strokeWidth={2} />
                  <span>{shared ? t('inviteCard.handed') : t('inviteCard.send')}</span>
                </button>
              )}
            </div>
            <textarea
              readOnly
              value={handOut}
              data-testid="invite-string"
              rows={3}
              onFocus={(e) => e.currentTarget.select()}
              className={`mt-3 w-full px-4 py-3 text-callout rounded-card bg-raised text-ink-2 focus:outline-none resize-none ${error ? 'block' : 'hidden'}`}
            />
            {error && <p className="mt-2 text-callout text-ink-2">{error}</p>}
          </>
        ) : (
          <p className="mt-3 text-callout text-ink-3">{error ?? t('inviteCard.preparing')}</p>
        )}
      </div>
    );
  }

  return (
    <div
      data-testid="invite-card"
      className="pt-4 border-t border-line text-ink space-y-3.5"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h4 className="text-headline text-ink leading-tight">
            {t('inviteCard.yourInvite')}
          </h4>
          <p className="text-callout text-ink-3 leading-snug mt-0.5">
            {t('inviteCard.oneLine')}
          </p>
        </div>
        {onClose && (
          <button
            onClick={onClose}
            className="p-2 min-h-[44px] min-w-[44px] touch-floor flex items-center justify-center text-ink-3 hover:text-ink hover:bg-raised rounded-control shrink-0 transition-colors"
            aria-label={t('inviteCard.collapse')}
          >
            <X className="w-4 h-4" strokeWidth={1.75} />
          </button>
        )}
      </div>

      {phone ? (
        // Імʼя вшите в рядок хостом — тож і міняється через хост (renameSelf),
        // після чого запрошення перечитується вже з новим.
        me && (
          <div className="space-y-2.5">
            <div>
              <span className="text-footnote font-semibold text-ink-3 block">{t('inviteCard.signedAs')}</span>
              {renaming ? (
                <form
                  className="mt-1 flex items-center gap-2"
                  onSubmit={async (e) => {
                    e.preventDefault();
                    const next = nameDraft.trim();
                    if (!next || next === hostName) {
                      setRenaming(false);
                      return;
                    }
                    setRenameBusy(true);
                    try {
                      setMe(await messengerApi.renameSelf(next));
                      setRenaming(false);
                    } catch {
                      setError(t('inviteCard.renameRefused'));
                    } finally {
                      setRenameBusy(false);
                    }
                  }}
                >
                  <input
                    autoFocus
                    data-testid="invite-name-input"
                    value={nameDraft}
                    maxLength={64}
                    onChange={(e) => setNameDraft(e.target.value)}
                    aria-label={t('msgr.add.yourName')}
                    className="flex-1 min-w-0 h-11 px-3 rounded-control bg-raised text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line"
                  />
                  <button type="submit" disabled={renameBusy} className="h-11 px-4 rounded-control bg-raised text-footnote font-semibold text-ink">
                    {renameBusy ? '…' : t('msgr.add.done')}
                  </button>
                </form>
              ) : (
                <button
                  type="button"
                  data-testid="invite-name"
                  onClick={() => {
                    soundFx.playTap();
                    setNameDraft(hostName);
                    setRenaming(true);
                  }}
                  className="mt-1 w-full flex items-center justify-between gap-3 px-3.5 py-2.5 min-h-[44px] text-callout rounded-control bg-raised text-left"
                >
                  <span className={hostName ? 'text-ink font-semibold truncate' : 'text-ink-3'}>{hostName || t('msgr.add.unnamed')}</span>
                  <span className="shrink-0 text-footnote font-semibold text-ink-2">{t('inviteCard.change')}</span>
                </button>
              )}
              <span className="text-callout text-ink-3 block mt-1 leading-snug">
                {t('inviteCard.seenAs')}
              </span>
            </div>
          </div>
        )
      ) : (
        <div className="space-y-2.5">
          <label className="block">
            <span className="text-footnote font-semibold text-ink-3">{t('inviteCard.signAs')}</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={t('msgr.add.yourName')}
              data-testid="invite-name"
              className="mt-1 w-full px-3.5 py-2.5 min-h-[44px] text-callout rounded-control bg-canvas text-ink placeholder:text-ink-3 focus:outline-none transition-colors"
            />
          </label>
          <label className="block">
            <span className="text-footnote font-semibold text-ink-3">{t('inviteCard.computerAddress')}</span>
            <input
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              placeholder="http://192.168.1.5:8000"
              data-testid="invite-address"
              className="mt-1 w-full px-3.5 py-2.5 min-h-[44px] text-callout rounded-control bg-canvas text-ink placeholder:text-ink-3 focus:outline-none transition-colors"
            />
            <span className="text-callout text-ink-3 block mt-1 leading-snug">
              {t('inviteCard.addressHint')}
            </span>
          </label>
        </div>
      )}

      {invite ? (
        <>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onCopy}
              data-testid="invite-copy"
              /* Друга дія аркуша, тож не акцент: головна тут — «Відкрити розмову». */
              className="flex-1 h-12 min-h-0 rounded-control bg-raised text-ink text-footnote font-semibold flex items-center justify-center gap-1.5"
            >
              {copied ? (
                <Check className="w-4 h-4" strokeWidth={2} />
              ) : (
                <Copy className="w-4 h-4" strokeWidth={2} />
              )}
              <span>{copied ? t('msgr.add.copied') : t('inviteCard.copy')}</span>
            </button>
            <button
              type="button"
              onClick={() => {
                soundFx.playTap();
                setShowQr((v) => !v);
              }}
              data-testid="invite-qr"
              className="flex-1 py-2.5 min-h-[44px] touch-floor rounded-control bg-raised hover:bg-raised text-ink-3 hover:text-ink text-footnote font-semibold flex items-center justify-center gap-1.5 transition-colors "
            >
              <QrCode className="w-4 h-4" strokeWidth={2} />
              <span>{showQr ? t('inviteCard.hideCode') : t('inviteCard.code')}</span>
            </button>
            {canShare && (
              <button
                type="button"
                onClick={onShare}
                data-testid="invite-share"
                className="flex-1 py-2.5 min-h-[44px] touch-floor rounded-control bg-raised hover:bg-raised text-ink-3 hover:text-ink text-footnote font-semibold flex items-center justify-center gap-1.5 transition-colors "
              >
                <Share2 className="w-4 h-4" strokeWidth={2} />
                <span>{shared ? t('inviteCard.handed') : t('inviteCard.share')}</span>
              </button>
            )}
          </div>

          {showQr && (
            <div className="flex flex-col items-center gap-2 pt-1" data-testid="invite-qr-view">
              {qrDataUrl ? (
                <>
                  <img
                    src={qrDataUrl}
                    alt={t('inviteCard.qrAlt')}
                    className="w-full max-w-[320px] rounded-control bg-white p-3 "
                  />
                  <span className="text-callout text-ink-3 text-center leading-snug">
                    {t('inviteCard.aimCamera')}
                  </span>
                </>
              ) : (
                <span className="text-callout text-ink-3">{t('inviteCard.preparingCode')}</span>
              )}
            </div>
          )}

          {/* Сам рядок PH2 на пів екрана — це механіка. Три кнопки вище вже
              віддають його цілим; поле потрібне лише тоді, коли буфер обміну
              відмовив, і тоді воно розгортається само (`error` вище). */}
          <textarea
            readOnly
            value={invite}
            data-testid="invite-string"
            rows={3}
            onFocus={(e) => e.currentTarget.select()}
            className={`w-full px-3.5 py-2.5 text-callout  rounded-control bg-canvas text-ink-3 focus:outline-none resize-none leading-relaxed ${
              showRaw || error ? 'block' : 'hidden'
            }`}
          />
          {!showRaw && !error && (
            <button
              type="button"
              onClick={() => setShowRaw(true)}
              data-testid="invite-show-raw"
              className="touch-floor min-h-[44px] w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-control text-footnote font-semibold text-ink-3 hover:text-ink hover:bg-raised transition-colors"
            >
              {t('inviteCard.showString')}
            </button>
          )}

          <p className="text-callout text-ink-3 leading-snug">
            {t('inviteCard.sendAny')}
          </p>
        </>
      ) : (
        <span className="text-callout text-ink-3">
          {error ?? t('inviteCard.preparing')}
        </span>
      )}

      {error && invite && (
        <div className="text-callout text-warning">
          <span>{error}</span>
        </div>
      )}
    </div>
  );
};

/** SVG як картинка: без innerHTML, і data:-картинки CSP телефона вже пускає. */
const svgSrc = (svg: string) => `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;

/**
 * Мій QR в аркуші «Додати людину»: великий, білий, із сирим кодом телефона
 * (D6). SVG, а не PNG на 640 px: рядок генерується без полотна, а
 * картинка гостра на будь-якому екрані. Поки коду ще немає — тихий квадрат
 * того самого розміру, щоб аркуш не стрибав, коли QR приїде.
 */
export const MyQr: React.FC<{ code: string | null; failed?: string | null; onRetry?: () => void }> = ({
  code,
  failed = null,
  onRetry,
}) => {
  const { t } = useTranslation();
  const [svg, setSvg] = useState<string | null>(null);
  useEffect(() => {
    if (!code) {
      setSvg(null);
      return;
    }
    let alive = true;
    QRCode.toString(code, {
      type: 'svg',
      errorCorrectionLevel: 'L',
      margin: 1,
      color: { dark: LIT['ink-green'], light: LIT['white'] },
    })
      .then((s) => alive && setSvg(s))
      .catch(() => alive && setSvg(null));
    return () => {
      alive = false;
    };
  }, [code]);
  return (
    <div
      data-testid="invite-qr-view"
      className={`relative w-full aspect-square rounded-sheet overflow-hidden transition-colors duration-standard ${
        svg ? 'bg-white p-3 shadow-e2' : 'bg-overlay'
      }`}
    >
      {svg ? (
        <img src={svgSrc(svg)} alt={t('msgr.add.qrAlt')} className="w-full h-full f-fade-in" draggable={false} />
      ) : (
        <span className="absolute inset-0 px-6 flex flex-col items-center justify-center gap-3 text-center text-callout text-ink-3">
          <span>{failed ?? t('msgr.add.preparing')}</span>
          {/* Холодний старт: ключі ще готуються, і перший /identity відмовив.
              Без цієї кнопки QR повертався лише після закриття аркуша. */}
          {failed && onRetry && (
            <button
              type="button"
              onClick={onRetry}
              className="h-11 min-h-0 px-5 rounded-full bg-accent-soft text-accent-text text-callout font-semibold active:opacity-80"
            >
              {t('msgr.add.retry')}
            </button>
          )}
        </span>
      )}
    </div>
  );
};

/**
 * Моє імʼя під QR — те, що побачить людина, яка його відсканує. Дотик —
 * змінити; імʼя вшите в код хостом, тож міняється через хост (`renameSelf`).
 */
export const MyQrName: React.FC<{ me: NodeIdentity; onRenamed: (me: NodeIdentity) => void }> = ({ me, onRenamed }) => {
  const { t } = useTranslation();
  const self = useMessengerStore((s) => s.currentUser);
  const hostName = hostNameOf(me);
  const { active, unnamed, shownName, ownsName } = useInviteName(hostName);
  const [renaming, setRenaming] = useState(false);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [refused, setRefused] = useState(false);

  if (renaming) {
    return (
      <form
        className="w-full flex items-center gap-2"
        onSubmit={async (e) => {
          e.preventDefault();
          const next = draft.trim();
          if (!next || next === shownName) {
            setRenaming(false);
            return;
          }
          if (ownsName && active) {
            useProfiles.getState().update(active.id, { ownName: next });
            setRenaming(false);
            return;
          }
          setBusy(true);
          setRefused(false);
          try {
            onRenamed(await messengerApi.renameSelf(next));
            setRenaming(false);
          } catch {
            setRefused(true);
          } finally {
            setBusy(false);
          }
        }}
      >
        <input
          autoFocus
          value={draft}
          maxLength={64}
          onChange={(e) => setDraft(e.target.value)}
          aria-label={t('msgr.add.yourName')}
          aria-invalid={refused || undefined}
          className="flex-1 min-w-0 h-11 px-4 rounded-full bg-overlay text-body text-ink focus:outline focus:outline-2 focus:outline-accent-line"
        />
        <button
          type="submit"
          disabled={busy}
          className="h-11 min-h-0 px-4 rounded-full bg-accent text-on-accent text-callout font-semibold"
        >
          {busy ? '…' : t('msgr.add.done')}
        </button>
      </form>
    );
  }
  return (
    <button
      type="button"
      data-testid="my-qr-name"
      onClick={() => {
        if (unnamed) return;
        soundFx.playTap();
        setDraft(shownName);
        setRenaming(true);
      }}
      aria-label={unnamed ? t('msgr.add.unnamed') : `${shownName || t('msgr.add.unnamed')} · ${t('msgr.add.rename')}`}
      className="max-w-full min-h-11 px-2 flex items-center justify-center gap-2 rounded-full active:bg-overlay"
    >
      <Avatar
        src={self.avatar}
        name={shownName || '?'}
        tone={self.id || me.node_id}
        radius="rounded-full"
        className="w-7 h-7 text-footnote shrink-0"
      />
      <span className={`min-w-0 truncate text-headline ${shownName ? 'text-ink' : 'text-ink-3'}`}>
        {shownName || t('msgr.add.unnamed')}
      </span>
    </button>
  );
};

export default InviteCard;

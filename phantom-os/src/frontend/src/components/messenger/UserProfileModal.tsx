/**
 * Свій профіль у мові форми 24.09. Чужий — `PeerProfileSheet` (ранній return нижче).
 *
 * Будова та сама, що в картці співрозмовника (`VerifyContact.ContactSheet`):
 * аватар 72, імʼя короною, рядок правди в meta, відбиток 4×4, ОДНА головна дія
 * і нижче рядки-двері. «Спільних медіа» немає свідомо: вузол їх не рахує.
 *
 * ВІДНЯТО (не сховано), бо не робило того, що обіцяло:
 *  - пресети аватарів-SVG з ⚡🛡️🚀🧠 і статуси-пресети «P2P онлайн» — емодзі
 *    в інтерфейсі, а SVG дротом не їде взагалі;
 *  - «Місто» й «Телефон» — лягали в сховище вкладки й нікуди не їхали;
 *  - «P2P Нікнейм (Handle)» — ніхто з контактів його не бачить;
 *  - імʼя на телефоні — його вшиває в запрошення сам телефон, поле його не
 *    змінювало (так само сказано в «Людина»);
 *  - вкладки «P2P Ключі & QR» і «Сховище & Дані»: запрошення й QR живуть у
 *    «Людина», експорт JSON у WebView не качається, «очистити кеш» стирав
 *    памʼять, до якої цей екран стосунку не має;
 *  - у чужому — «Адреса вузла» з копіюванням і «Криптографічні реквізити»:
 *    рішення з них людина не приймає; відбиток лишився.
 */
import React, { useEffect, useState } from 'react';
import { ImagePlus, Trash2 } from 'lucide-react';
import { messengerApi } from '../../services/messengerApi';
import type { NodeContact, NodeIdentity } from '../../services/messengerApi';
import { UserProfile, ChatMember } from '../../types/messenger';
import { soundFx } from '../../utils/messengerSound';
import { Avatar } from './Avatar';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { globalP2PMesh } from '../../services/globalP2PMesh';
import { storagePersistence, StorageWriteError } from '../../services/storagePersistence';
import { useMessengerStore } from '../../stores/messengerStore';
import { PROFILE_WIRE_LIMITS, byteLength, truncateToByteLimit } from '../../services/profileWireCodec';
import { compressAvatarFile, base64Of, sayRefusal, thumbB64OfDataUrl, openImage } from '../../services/avatarCompress';
import { isFacePhotoUrl } from '../../services/peerProfile';
import { PhotoCropSheet } from './PhotoCropSheet';
// Два різні `sayRefusal`: один пояснює, чому не стиснулось фото, другий
// переказує слово ВУЗЛА про відмову кадру — дві різні відмови на двох кінцях.
import { sayRefusal as sayNodeRefusal } from '../../services/nodeRefusal';
import { AvatarViewerModal } from './AvatarViewerModal';
import { isPhoneShell } from './phoneShell';
import { FormSheet, PrimaryButton } from './FormSheet';
import { PeerProfileSheet } from './PeerProfileSheet';
import { peerProfileEdits } from './peerProfileEdits';
import { useTranslation } from '../../i18n/useTranslation';
import { navigateTo } from '../../services/nav';

interface UserProfileModalProps {
  isOpen: boolean;
  onClose: () => void;
  currentUser: UserProfile;
  targetUser?: UserProfile | ChatMember | null;
  onUpdateCurrentUser?: (updated: Partial<UserProfile>) => void;
  onStartDirectChat?: (target: { id: string; contactId?: string; name: string; avatar?: string; handle?: string }) => void;
}

const Label: React.FC<{ children: React.ReactNode; htmlFor?: string; count?: string }> = ({ children, htmlFor, count }) => (
  <span className="flex items-baseline justify-between gap-3 mb-2">
    <label htmlFor={htmlFor} className="text-footnote font-semibold text-ink-2">
      {children}
    </label>
    {count && <span className=" text-caption text-ink-3 tnum">{count}</span>}
  </span>
);

export const UserProfileModal: React.FC<UserProfileModalProps> = ({
  isOpen,
  onClose,
  currentUser,
  targetUser,
  onUpdateCurrentUser,
  onStartDirectChat,
}) => {
  const { t } = useTranslation();
  const isSelf = !targetUser || targetUser.id === currentUser.id || ('isMe' in targetUser && Boolean(targetUser.isMe));
  const phoneShell = __PHONE_BUILD__ || isPhoneShell();
  /** На телефоні свій профіль один — рідна вкладка; тут лише передаємо туди. */
  const nativeFace =
    isSelf &&
    typeof (window as unknown as { PhantomBridge?: { navigate?: unknown } }).PhantomBridge?.navigate === 'function';
  useEffect(() => {
    if (isOpen && nativeFace && navigateTo('profile')) onClose();
  }, [isOpen, nativeFace, onClose]);

  // ── чужий ─────────────────────────────────────────────────────────────
  const [peerContact, setPeerContact] = useState<NodeContact | null>(null);
  useEffect(() => {
    if (!isOpen || isSelf || !targetUser) return;
    let alive = true;
    setPeerContact(null);
    void (async () => {
      try {
        const list = await messengerApi.listContacts();
        if (!alive) return;
        const targetPeerNodeId = ('peerNodeId' in targetUser && targetUser.peerNodeId) || targetUser.id;
        // Лише за ключем: однакове імʼя — ще не та сама людина.
        const rows = list.filter(
          (c) => (targetPeerNodeId && c.peer_node_id === targetPeerNodeId) || c.id === targetUser.id,
        );
        // Тривога виграє: з двох рядків на один вузол — той, де ключ змінився.
        setPeerContact(rows.find((c) => c.key_changed) ?? rows[0] ?? null);
      } catch {
        /* без картки — ключ людини з самого запису */
      }
    })();
    return () => {
      alive = false;
    };
  }, [isOpen, isSelf, targetUser]);

  // ── свій ──────────────────────────────────────────────────────────────
  const [name, setName] = useState(currentUser.name || '');
  /** На телефоні імʼя за замовчуванням — з вузла; власний рядок — лише після дотику. */
  const [nameTouched, setNameTouched] = useState(false);
  const [avatar, setAvatar] = useState(currentUser.avatar || '');
  const [statusText, setStatusText] = useState(currentUser.status || '');
  const [bio, setBio] = useState(currentUser.bio || '');
  /** Речення про відмову запису. Не гасне саме — на відміну від «збережено». */
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  /** Байти мініатюри, готові до кадру. null — фото цього разу не чіпали, '' — прибрали. */
  const [avatarThumbB64, setAvatarThumbB64] = useState<string | null>(null);
  const [avatarPhotoB64, setAvatarPhotoB64] = useState<string | null>(null);
  const [avatarNote, setAvatarNote] = useState<{ ok: boolean; say: string } | null>(null);
  const [avatarBusy, setAvatarBusy] = useState(false);
  const [wireReport, setWireReport] = useState<string | null>(null);
  const [wireError, setWireError] = useState<string | null>(null);
  /** Лічильник, який знає веб. Телефон однаково підніме свій — це лише підлога. */
  const [knownRev, setKnownRev] = useState(0);
  const [sending, setSending] = useState(false);
  const [viewerOpen, setViewerOpen] = useState(false);
  const [picked, setPicked] = useState<HTMLImageElement | null>(null);
  const [nodeIdentity, setNodeIdentity] = useState<NodeIdentity | null>(null);

  useEffect(() => {
    if (!currentUser) return;
    setNameTouched(false);
    setName(currentUser.name || '');
    setAvatar(currentUser.avatar || '');
    setStatusText(currentUser.status || '');
    setBio(currentUser.bio || '');
    setAvatarThumbB64(null);
    setAvatarPhotoB64(null);
    setAvatarNote(null);
    setSaveError(null);
    setSaved(false);
    setWireReport(null);
    setWireError(null);
    setPicked(null);
  }, [currentUser, isOpen]);

  useEffect(() => {
    if (!isOpen || !isSelf) return;
    let alive = true;
    messengerApi
      .identity()
      .then((id) => alive && setNodeIdentity(id))
      .catch(() => alive && setNodeIdentity(null));
    return () => {
      alive = false;
    };
  }, [isOpen, isSelf]);

  const chats = useMessengerStore((s) => s.chats);
  const peerKey: string =
    (targetUser && 'peerNodeId' in targetUser && (targetUser.peerNodeId as string | undefined)) ||
    peerContact?.peer_node_id ||
    targetUser?.id ||
    '';
  /** Розмова вдвох саме з цією людиною — якщо вона взагалі заведена. */
  const peerChat = isSelf
    ? undefined
    : chats.find((c) => (c.type === 'dm' || c.type === 'direct') && Boolean(peerKey) && (c.peerNodeId === peerKey || c.id === targetUser?.id));

  useEscapeClose(isOpen, onClose);

  if (!isOpen || nativeFace) return null;

  // Чужий профіль — той самий екран, що з шапки розмови: один профіль людини, не два.
  if (!isSelf && targetUser) {
    const notInRoster = 'inRoster' in targetUser && targetUser.inRoster === false && !peerContact;
    const write = peerChat
      ? () => {
          useMessengerStore.getState().setActiveChat(peerChat.id);
          onClose();
        }
      : onStartDirectChat && !notInRoster
      ? () => {
          onStartDirectChat({ id: peerKey, contactId: peerContact?.id, name: targetUser.name, avatar: targetUser.avatar });
          onClose();
        }
      : undefined;
    return (
      <PeerProfileSheet
        peerNodeId={peerKey || undefined}
        name={targetUser.name}
        avatar={targetUser.avatar}
        chatId={peerChat?.id}
        onClose={onClose}
        onWrite={write}
        {...(peerChat ? peerProfileEdits(peerChat.id) : {})}
      />
    );
  }

  const b64Bytes = (s: string): number => {
    try {
      return atob(s).length;
    } catch {
      return 0;
    }
  };
  const myThumbB64 = avatarThumbB64 || (avatar ? thumbB64OfDataUrl(avatar) : null);
  const myThumbBytes = myThumbB64 ? b64Bytes(myThumbB64) : 0;
  /** Імʼя на телефоні дає сам телефон (`bundle.name` у запрошенні). */
  const hostName = (nodeIdentity as { bundle?: { name?: string } } | null)?.bundle?.name?.trim() || '';
  const shownName = phoneShell ? hostName || currentUser.name : name;

  /**
   * Що покласти в тег аватара — і чи класти його взагалі:
   * `null` — «фото немає» (прибрали або не було), рядок — байти, `undefined` —
   * тега не буде: поточне фото дротом не їде, і про це сказано словами.
   */
  const wireAvatarField = (): { value?: string | null; say: string | null } => {
    if (avatarThumbB64 === '') return { value: null, say: null };
    if (avatarThumbB64) return { value: avatarThumbB64, say: null };
    const current = avatar.trim();
    if (!current) return { value: null, say: null };
    // Фото вже тримає телефон і сам дошле його кожному.
    if (isFacePhotoUrl(current)) return { value: undefined, say: null };
    const reused = thumbB64OfDataUrl(current);
    if (reused) return { value: reused, say: null };
    return { value: undefined, say: t('userProfileModal.photoNotShared') };
  };

  /** Свій профіль — усім контактам одним кадром на кожного; звіт не гасне. */
  const publishToWire = async () => {
    setWireError(null);
    setWireReport(null);
    const av = wireAvatarField();
    try {
      const report = await messengerApi.publishProfile({
        rev: knownRev,
        ...(av.value !== undefined ? { avatar: av.value } : {}),
        ...(avatarPhotoB64 ? { photo: avatarPhotoB64 } : {}),
        // Емодзі статусу більше не вибирається окремо: емодзі людина пише в
        // самому статусі, як у будь-якому тексті. Старий «⚡» за замовчуванням
        // (його ніхто не обирав) цим кадром знімається.
        status_emoji: null,
        status_text: statusText.trim() || null,
        bio: bio.trim() || null,
      });
      setKnownRev(report.rev);
      setWireReport(
        report.sent_count > 0
          ? t(report.sent_count === 1 ? 'userProfileModal.sentOne' : 'userProfileModal.sentMany', { count: report.sent_count })
          : t('userProfileModal.savedPending'),
      );
      if (av.say) setWireReport((line) => `${line ?? ''} ${av.say}`.trim());
    } catch (err) {
      setWireError(sayNodeRefusal(err, t('userProfileModal.sendFailed')));
    }
  };

  const handleSave = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    soundFx.playSend();
    setSending(true);
    const updated: Partial<UserProfile> = {
      ...(phoneShell ? {} : { name: name.trim() || currentUser.name }),
      avatar: avatar.trim(),
      status: statusText.trim(),
      statusEmoji: '',
      bio: bio.trim(),
    };
    onUpdateCurrentUser?.(updated);
    // «Збережено» — ПІСЛЯ запису, а не поруч із ним.
    try {
      await storagePersistence.saveUserProfile({ ...currentUser, ...updated });
      setSaveError(null);
      setSaved(true);
      window.setTimeout(() => setSaved(false), 2500);
    } catch (err) {
      setSaved(false);
      setSaveError(
        err instanceof StorageWriteError && err.isQuota
          ? t('userProfileModal.storageFull')
          : t('userProfileModal.notSaved'),
      );
    }
    if (!phoneShell) globalP2PMesh.updateIdentity(currentUser.handle || '', updated.name, updated.avatar);
    const nextName = name.trim();
    if (phoneShell && nameTouched && nextName && nextName !== hostName) {
      try {
        const id = await messengerApi.renameSelf(nextName);
        setNodeIdentity(id);
        onUpdateCurrentUser?.({ name: nextName });
        setNameTouched(false);
      } catch (err) {
        setSaveError(sayNodeRefusal(err, t('userProfileModal.renameFailed')));
      }
    }
    await publishToWire();
    setSending(false);
  };

  /** Фото з пристрою — СТИСНУТЕ під стелю кадру; не влізло — кажемо, скільки бракує. */
  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setAvatarBusy(true);
    setAvatarNote(null);
    if (phoneShell) {
      const opened = await openImage(file);
      setAvatarBusy(false);
      e.target.value = '';
      if (opened.ok) setPicked(opened.img);
      else setAvatarNote({ ok: false, say: t('userProfileModal.notAPhoto') });
      return;
    }
    try {
      const res = await compressAvatarFile(file);
      if (!res.ok) {
        setAvatarNote({ ok: false, say: sayRefusal(res.refusal) });
        return;
      }
      soundFx.playTap();
      setAvatar(res.thumb.dataUrl);
      setAvatarThumbB64(base64Of(res.thumb.bytes));
      setAvatarNote({
        ok: true,
        say: t('userProfileModal.photoDone'),
      });
    } finally {
      setAvatarBusy(false);
      e.target.value = '';
    }
  };

  /** Прибрати фото — це ВЧИНОК: кадр несе явне очищення. */
  const handleAvatarClear = () => {
    soundFx.playTap();
    setAvatar('');
    setAvatarThumbB64('');
    setAvatarPhotoB64(null);
    setAvatarNote({ ok: true, say: t('userProfileModal.photoRemoved') });
  };

  return (
    <>
      <FormSheet
        open
        tall
        hideTitle
        onClose={onClose}
        title={t('userProfileModal.title')}
        testId="profile-sheet"
        footer={
          <PrimaryButton onClick={() => void handleSave()} disabled={avatarBusy || sending}>
            {sending ? t('userProfileModal.sending') : t('userProfileModal.saveAndSend')}
          </PrimaryButton>
        }
      >
        {
          <form onSubmit={handleSave} className="px-4 pb-4">
            <div className="flex justify-center pt-2">
            <button
              type="button"
              data-testid="profile-own-avatar"
              onClick={() => {
                soundFx.playTap();
                if (avatar) setViewerOpen(true);
              }}
              disabled={!avatar}
              className="rounded-full focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line disabled:cursor-default"
              aria-label={avatar ? t('userProfileModal.viewPhoto') : t('userProfileModal.noPhoto')}
            >
              <Avatar src={avatar} name={shownName} radius="rounded-full" className="w-[112px] h-[112px] text-[40px]" />
            </button>
            </div>

            <div className="mt-4">
              <Label htmlFor="profile-name">{t('userProfileModal.name')}</Label>
              <input
                id="profile-name"
                data-testid="profile-name"
                value={phoneShell && !nameTouched ? shownName : name}
                maxLength={64}
                onChange={(e) => {
                  setNameTouched(true);
                  setName(e.target.value);
                }}
                placeholder={t('userProfileModal.nameLabel')}
                className="w-full h-12 px-3 rounded-control bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
              />
              {phoneShell && (
                <p className="mt-1 text-caption text-ink-3">{t('userProfileModal.nameHint')}</p>
              )}
            </div>

            <div className="mt-4 flex flex-wrap items-center gap-2">
              <label
                className={`h-11 px-3 rounded-control bg-raised text-footnote font-semibold text-ink flex items-center gap-2 ${
                  avatarBusy ? 'opacity-60' : 'cursor-pointer active:opacity-90'
                }`}
              >
                <ImagePlus className="w-5 h-5 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
                <span>{avatarBusy ? t('userProfileModal.compressing') : avatar ? t('userProfileModal.otherPhoto') : t('userProfileModal.choosePhoto')}</span>
                <input type="file" accept="image/*" onChange={handleFileUpload} disabled={avatarBusy} className="hidden" />
              </label>
              {avatar && (
                <button
                  type="button"
                  data-testid="profile-avatar-clear"
                  disabled={avatarBusy}
                  onClick={handleAvatarClear}
                  className="h-11 px-3 rounded-control text-footnote font-semibold text-danger flex items-center gap-2 active:bg-raised"
                >
                  <Trash2 className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
                  <span>{t('userProfileModal.removePhoto')}</span>
                </button>
              )}
            </div>
            {avatarNote && (
              <p data-testid="profile-avatar-note" role="status" className={`mt-2 text-callout ${avatarNote.ok ? 'text-ink-2' : 'text-warning'}`}>
                {avatarNote.say}
              </p>
            )}

            <div className="mt-6">
              <Label htmlFor="profile-status" count={`${byteLength(statusText)} / ${PROFILE_WIRE_LIMITS.statusTextBytes}`}>
                {t('userProfileModal.status')}
              </Label>
              <input
                id="profile-status"
                value={statusText}
                onChange={(e) => setStatusText(truncateToByteLimit(e.target.value, PROFILE_WIRE_LIMITS.statusTextBytes))}
                placeholder={t('userProfileModal.statusLabel')}
                className="w-full h-12 px-3 rounded-control bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
              />
            </div>

            <div className="mt-4">
              <Label htmlFor="profile-bio" count={`${byteLength(bio)} / ${PROFILE_WIRE_LIMITS.bioBytes}`}>
                {t('userProfileModal.about')}
              </Label>
              <textarea
                id="profile-bio"
                rows={3}
                value={bio}
                onChange={(e) => setBio(truncateToByteLimit(e.target.value, PROFILE_WIRE_LIMITS.bioBytes))}
                className="f-field w-full px-3 py-3 rounded-control bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line resize-none"
              />
            </div>

            <p className="mt-3 text-callout text-ink-3">{t('userProfileModal.contactsSee')}</p>

            {/* Доля запису й доля кадру — два різні факти, два різні рядки. */}
            {saveError && (
              <p data-testid="profile-save-error" role="status" className="mt-3 text-callout text-danger">
                {saveError}
              </p>
            )}
            {saved && !saveError && <p className="mt-3 text-caption text-ink-3">{t('userProfileModal.savedLocal')}</p>}
            {wireError && (
              <p data-testid="profile-wire-error" role="status" className="mt-2 text-callout text-danger">
                {wireError}
              </p>
            )}
            {wireReport && !wireError && (
              <p data-testid="profile-wire-report" role="status" className="mt-2 text-caption text-ink-2">
                {wireReport}
              </p>
            )}

          </form>
        }
      </FormSheet>

      {picked && (
        <PhotoCropSheet
          img={picked}
          onClose={() => setPicked(null)}
          onDone={(url) => {
            setPicked(null);
            setAvatar(url);
            setAvatarThumbB64(null);
            setAvatarPhotoB64(null);
            setAvatarNote({ ok: true, say: t('userProfileModal.photoDone') });
          }}
          onCropped={({ avatarDataUrl, thumbB64, photoB64 }) => {
            setPicked(null);
            setAvatar(avatarDataUrl);
            setAvatarThumbB64(thumbB64);
            setAvatarPhotoB64(photoB64);
            setAvatarNote({ ok: true, say: t('userProfileModal.photoDone') });
          }}
        />
      )}
      {/* Фото на повний екран — власне й чуже, одним шаром, поверх аркуша. */}
      <AvatarViewerModal
        isOpen={viewerOpen}
        onClose={() => setViewerOpen(false)}
        src={avatar || null}
        name={shownName || t('userProfileModal.yourPhoto')}
        bytes={myThumbBytes}
        isMine
      />
    </>
  );
};

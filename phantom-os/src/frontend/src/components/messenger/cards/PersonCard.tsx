/**
 * Картка людини: поділитися контактом зі своїх людей. Отримувач бачить
 * профіль і «Написати», якщо людина вже серед його людей; інакше — чесний стан.
 */
import React, { useEffect, useState } from 'react';
import { MessageCircle, UserRound } from 'lucide-react';
import type { Message, PersonData } from '../../../types/messenger';
import { messengerApi, type NodeContact } from '../../../services/messengerApi';
import { isOwnDeviceContact } from '../../../services/peopleIdentity';
import { useMessengerStore } from '../../../stores/messengerStore';
import { chatKeep } from '../../../services/chatKeep';
import { selfAdded } from '../../../services/selfAdded';
import { soundFx } from '../../../utils/messengerSound';
import { useTranslation } from '../../../i18n/useTranslation';
import { TicketCard } from '../kit/TicketCard';
import { FormSheet } from '../FormSheet';
import { Avatar } from '../Avatar';
import { PeerProfileSheet } from '../PeerProfileSheet';

export const PERSON_KIND = 'widget:person';

export function personOf(msg: Message): PersonData | null {
  const d = msg.type === PERSON_KIND ? msg.personData : undefined;
  if (!d || typeof d !== 'object') return null;
  const name = typeof d.name === 'string' ? d.name.trim() : '';
  if (typeof d.id !== 'string' || !name || typeof d.peerId !== 'string' || !d.peerId) return null;
  return { v: 1, id: d.id, name: name.slice(0, 80), peerId: d.peerId };
}

export async function openChatWith(c: NodeContact) {
  const list = await messengerApi.listConversations();
  const existing = list.find((x) => x.contact_id === c.id);
  const row = existing ?? (await messengerApi.createConversation({ title: c.display_name, kind: 'dm', circle: 'all', contact_id: c.id }));
  if (!existing) chatKeep.add(row.id);
  selfAdded.mark(row.id);
  const store = useMessengerStore.getState();
  await store.refreshConversations();
  store.setActiveChat(row.id);
}

const ICO = { className: 'w-5 h-5', strokeWidth: 1.75 } as const;

const Action: React.FC<{ icon: React.ReactNode; label: string; testId: string; onClick: () => void; disabled?: boolean }> = ({
  icon,
  label,
  testId,
  onClick,
  disabled,
}) => (
  <button
    type="button"
    data-testid={testId}
    disabled={disabled}
    onClick={(e) => {
      e.stopPropagation();
      onClick();
    }}
    className="flex-1 min-w-0 min-h-14 py-1.5 flex flex-col items-center gap-1 text-caption font-medium text-ink-2 disabled:opacity-40 active:opacity-80"
  >
    <span className="w-10 h-8 rounded-full flex items-center justify-center text-ink">{icon}</span>
    <span className="max-w-full truncate px-0.5">{label}</span>
  </button>
);

export const PersonCard: React.FC<{ msg: Message; meId: string }> = ({ msg, meId }) => {
  const { t } = useTranslation();
  const p = personOf(msg);
  const self = !!p && p.peerId === meId;
  const [contact, setContact] = useState<NodeContact | null | undefined>(undefined);
  const [profile, setProfile] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const chats = useMessengerStore((s) => s.chats);

  useEffect(() => {
    if (!p || self) return;
    let live = true;
    messengerApi
      .listContacts()
      .then((list) => live && setContact(list.find((c) => c.peer_node_id === p.peerId) ?? null))
      .catch(() => live && setContact(null));
    return () => {
      live = false;
    };
  }, [p?.peerId, self]);

  if (!p) return <TicketCard testId="card-unreadable" title={t('msgTypes.person')} status={t('messageCard.unreadable')} />;

  const write = async () => {
    if (!contact || busy) return;
    soundFx.playTap();
    setBusy(true);
    setFailed(false);
    try {
      setProfile(false);
      await openChatWith(contact);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };
  const chatId = contact ? chats.find((c) => c.peerNodeId === contact.peer_node_id && c.type === 'dm')?.id : undefined;
  const status = self ? t('msgTypes.personYou') : contact ? t('msgTypes.personKnown') : contact === null ? t('msgTypes.personUnknown') : '';

  return (
    <>
      <TicketCard
        testId="card-person"
        title={p.name}
        kind={{ label: t('msgTypes.person'), icon: <UserRound strokeWidth={1.75} /> }}
        status={status}
        lines={
          <div className="mt-2">
            <Avatar name={p.name} tone={p.peerId} src={contact?.avatar_thumb ?? null} className="w-12 h-12 text-callout" />
          </div>
        }
      >
        {contact && (
          <div data-testid="person-actions" className="px-1 flex">
            <Action icon={<UserRound {...ICO} />} label={t('msgTypes.personProfile')} testId="person-profile" onClick={() => setProfile(true)} />
            <Action icon={<MessageCircle {...ICO} />} label={t('msgTypes.personWrite')} testId="person-write" disabled={busy} onClick={() => void write()} />
          </div>
        )}
        {failed && <p className="px-4 pt-1 text-caption text-danger">{t('createChatModal.openFailed')}</p>}
      </TicketCard>
      {profile && contact && (
        <PeerProfileSheet
          peerNodeId={contact.peer_node_id}
          name={contact.display_name}
          chatId={chatId}
          onClose={() => setProfile(false)}
          onWrite={() => void write()}
        />
      )}
    </>
  );
};

/** Вибір людини зі своїх, щоб поділитися нею в розмові. */
export const PersonPickSheet: React.FC<{ open: boolean; onClose: () => void }> = ({ open, onClose }) => {
  const { t } = useTranslation();
  const [people, setPeople] = useState<NodeContact[] | null>(null);
  const chats = useMessengerStore((s) => s.chats);
  const sendCard = useMessengerStore((s) => s.sendCard);

  useEffect(() => {
    if (!open) return;
    let live = true;
    setPeople(null);
    messengerApi
      .listContacts()
      .then((list) => live && setPeople(list))
      .catch(() => live && setPeople([]));
    return () => {
      live = false;
    };
  }, [open]);

  const shown = (people ?? []).filter((c) => !isOwnDeviceContact(c, chats));
  const share = async (c: NodeContact) => {
    soundFx.playTap();
    const id = `p_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
    const refusal = await sendCard(PERSON_KIND, { v: 1, id, name: c.display_name, peerId: c.peer_node_id });
    if (!refusal) onClose();
  };

  return (
    <FormSheet open={open} onClose={onClose} title={t('msgTypes.personShare')} testId="person-pick">
      <div className="pb-2">
        {people !== null && shown.length === 0 && (
          <p className="px-4 py-3 text-subhead text-ink-2" data-testid="person-pick-empty">
            {t('msgTypes.personNone')}
          </p>
        )}
        {shown.map((c) => (
          <button
            key={c.id}
            type="button"
            data-testid={`person-pick-${c.id}`}
            onClick={() => void share(c)}
            className="w-full min-h-14 px-4 flex items-center gap-3 text-left active:bg-raised"
          >
            <Avatar name={c.display_name} tone={c.peer_node_id} src={c.avatar_thumb ?? null} className="w-10 h-10 text-callout" />
            <span className="flex-1 min-w-0 truncate text-callout text-ink">{c.display_name}</span>
          </button>
        ))}
      </div>
    </FormSheet>
  );
};

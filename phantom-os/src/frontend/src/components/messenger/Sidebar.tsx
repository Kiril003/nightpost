/**
 * Колонка списку розмов — одна на телефоні й на ПК (дизайн-система §4, §10.1).
 *
 * На ПК тут жила окрема бічна панель: рейка папок, фільтри, «Особисті/Канали»,
 * плаваючий док — три системи навігації одночасно (AUDIT, «Three navigation
 * systems at once»). Глобальну навігацію ПК несе рейка оболонки (DeskShell),
 * а месенджер — це список 340 і розмова. Тож список той самий, що й на
 * телефоні (`PhoneChatList`), лише щільніший на широкому екрані.
 */
import React, { useEffect, useState } from 'react';
import type { Chat, UserProfile } from '../../types/messenger';
import { soundFx } from '../../utils/messengerSound';
import { messengerApi } from '../../services/messengerApi';
import { useMessengerStore } from '../../stores/messengerStore';
import { PhoneChatList } from './PhoneChatList';
import { ChatActionsSheet } from './ChatActionsSheet';
import { Toast } from './Toast';
import { isPhoneShell } from './phoneShell';
import { useTranslation } from '../../i18n/useTranslation';

interface SidebarProps {
  chats: Chat[];
  activeChatId: string;
  onSelectChat: (id: string) => void;
  currentUser: UserProfile;
  onNewChat: () => void;
  onOpenUserProfile?: () => void;
}

/** Широкий екран (≥ 768): щільні рядки, вибраний рядок, без свайпів. */
const useWide = (): boolean => {
  const query = '(min-width: 768px)';
  const [wide, setWide] = useState(() => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches);
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return undefined;
    const mq = window.matchMedia(query);
    const on = () => setWide(mq.matches);
    mq.addEventListener?.('change', on);
    return () => mq.removeEventListener?.('change', on);
  }, []);
  return wide;
};

export const Sidebar: React.FC<SidebarProps> = ({ chats, activeChatId, onSelectChat, currentUser, onNewChat }) => {
  const { t } = useTranslation();
  const updateChat = useMessengerStore((s) => s.updateChat);
  const wide = useWide();
  const [actionsChat, setActionsChat] = useState<Chat | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  // Черга — єдине, чого людина не дізнається нізвідки більше: листи, що ще не
  // пішли. Опитуємо, лише поки список відкритий; міст мовчить — число стоїть.
  const [queued, setQueued] = useState(0);
  useEffect(() => {
    let alive = true;
    const tick = async () => {
      try {
        const { queued: n } = await messengerApi.queueStatus();
        if (alive) setQueued(typeof n === 'number' ? n : 0);
      } catch {
        /* міст недоступний — не чіпаємо попереднє значення */
      }
    };
    void tick();
    const id = window.setInterval(tick, 15000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);

  // Позначаємо прочитаним до останнього листа: вузол сам обріже seq до наявного.
  const markRead = async (chat: Chat) => {
    try {
      await messengerApi.markRead(chat.id, Number.MAX_SAFE_INTEGER);
      updateChat(chat.id, { unreadCount: 0 });
      soundFx.playTap();
    } catch {
      setToast(t('sidebar.markReadFailed'));
    }
  };

  // Історія зникає на ЦЬОМУ пристрої; копію співрозмовника ніхто не чіпає.
  const clearChat = async (chat: Chat) => {
    try {
      await messengerApi.clearConversation(chat.id);
      updateChat(chat.id, { messages: [], lastSnippet: undefined, lastKind: undefined, lastAuthor: undefined, unreadCount: 0, pinnedMessage: null });
      setToast(t('sidebar.cleared'));
    } catch {
      setToast(t('sidebar.clearFailed'));
    }
  };

  const deleteChat = async (chat: Chat) => {
    const standing = useMessengerStore.getState().chats.find((c) => c.id === chat.id)?.standing ?? chat.standing;
    const member = standing !== 'left' && standing !== 'removed';
    if ((chat.type === 'group' || chat.type === 'channel') && member) {
      try {
        await messengerApi.leaveGroup(chat.id);
      } catch (e) {
        if ((e as { code?: string }).code !== 'not_in_group') {
          setToast(t('sidebar.leaveFailed'));
          return;
        }
      }
      useMessengerStore.setState((s) => ({
        chats: s.chats.map((c) => (c.id === chat.id ? { ...c, standing: 'left' as const } : c)),
      }));
    }
    try {
      await messengerApi.deleteConversation(chat.id);
      useMessengerStore.setState((s) => {
        const rest = s.chats.filter((c) => c.id !== chat.id);
        const { [chat.id]: _dropped, ...drafts } = s.drafts;
        return { chats: rest, drafts, activeChatId: s.activeChatId === chat.id ? '' : s.activeChatId };
      });
      setToast(t('sidebar.deleted'));
    } catch {
      setToast(t('sidebar.deleteFailed'));
    }
  };

  const refreshed = (id: string) => chats.find((c) => c.id === id) ?? null;
  return (
    <div className="relative flex h-full w-full md:w-[340px] md:border-r md:border-line bg-canvas">
      <PhoneChatList
        chats={chats}
        currentUser={currentUser}
        onSelectChat={onSelectChat}
        onNewChat={onNewChat}
        onNewGroup={() => useMessengerStore.getState().setCreateChatModalOpen(true, 'group')}
        onOpenChatActions={(chat) => setActionsChat(chat)}
        onDeleteChat={deleteChat}
        onMarkRead={markRead}
        desk={wide}
        activeChatId={activeChatId}
        queued={queued}
        ask={__PHONE_BUILD__ ? !wide && isPhoneShell() : true}
      />
      <ChatActionsSheet
        chat={actionsChat ? refreshed(actionsChat.id) : null}
        onClose={() => setActionsChat(null)}
        onMarkRead={async (chat) => {
          await markRead(chat);
          setActionsChat(null);
        }}
        onTogglePin={(chat) => {
          useMessengerStore.getState().togglePinChat(chat.id);
          setActionsChat(null);
        }}
        onToggleMute={(chat) => {
          useMessengerStore.getState().toggleMuteChat(chat.id);
          setActionsChat(null);
        }}
        onToggleArchive={(chat) => {
          useMessengerStore.getState().toggleArchiveChat(chat.id);
          setActionsChat(null);
        }}
        onRename={async (chat, title) => {
          setActionsChat(null);
          if (!title || title === chat.title) return;
          try {
            await messengerApi.renameConversation(chat.id, title);
            updateChat(chat.id, { title });
          } catch {
            setToast(t('sidebar.renameFailed'));
          }
        }}
        onClear={async (chat) => {
          await clearChat(chat);
          setActionsChat(null);
        }}
        onDelete={async (chat) => {
          await deleteChat(chat);
          setActionsChat(null);
        }}
      />
      {toast && <Toast message={toast} onDone={() => setToast(null)} />}
    </div>
  );
};

export default Sidebar;

import { useMessengerStore } from '../../stores/messengerStore';

/** Що стор робить, коли профіль людини щось змінив у розмові: шапка й колонка ПК кличуть одне й те саме. */
export function peerProfileEdits(chatId: string) {
  const store = useMessengerStore;
  return {
    // Звірка після нового ключа знімає і замок над полем вводу, і тривогу в шапці.
    onVerified: (verified: boolean) =>
      store.getState().updateChat(
        chatId,
        verified
          ? { contactVerified: true, contactKeyHold: false, contactKeyChanged: false, contactKeyChangedAt: undefined }
          : { contactVerified: false },
      ),
    onRenamed: (title: string) => store.getState().updateChat(chatId, { title }),
    onCleared: () =>
      store.getState().updateChat(chatId, {
        messages: [],
        lastSnippet: undefined,
        lastKind: undefined,
        lastAuthor: undefined,
        unreadCount: 0,
      }),
    onDeleted: () =>
      store.setState((s) => {
        const chats = s.chats.filter((c) => c.id !== chatId);
        const { [chatId]: _dropped, ...drafts } = s.drafts;
        return {
          chats,
          drafts,
          activeChatId: s.activeChatId === chatId ? '' : s.activeChatId,
        };
      }),
  };
}

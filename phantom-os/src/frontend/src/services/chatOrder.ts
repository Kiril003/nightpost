const at = (chat: { lastAt?: string; messages?: Array<{ sentAt?: string }> }): number => {
  const fromList = chat.lastAt ? Date.parse(chat.lastAt) : NaN;
  if (Number.isFinite(fromList)) return fromList;
  const last = chat.messages?.[chat.messages.length - 1];
  const fromMessage = last?.sentAt ? Date.parse(last.sentAt) : NaN;
  return Number.isFinite(fromMessage) ? fromMessage : 0;
};

/** Закріплені зверху, далі — за останнім листом; порядок стабільний для рівних. */
export function sortChatsByRecent<T extends { pinned?: boolean; lastAt?: string; messages?: Array<{ sentAt?: string }> }>(chats: T[]): T[] {
  return chats
    .map((chat, index) => ({ chat, index, when: at(chat) }))
    .sort((a, b) => {
      const pin = Number(Boolean(b.chat.pinned)) - Number(Boolean(a.chat.pinned));
      if (pin !== 0) return pin;
      if (b.when !== a.when) return b.when - a.when;
      return a.index - b.index;
    })
    .map((entry) => entry.chat);
}

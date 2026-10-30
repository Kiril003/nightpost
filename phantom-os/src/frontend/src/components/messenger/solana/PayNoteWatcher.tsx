import React, { useEffect } from 'react';
import { dropPayNote, judgePayNotes, pendingPayNotes } from '../../../services/money/payNotes';
import { useMessengerStore } from '../../../stores/messengerStore';
import type { Message } from '../../../types/messenger';

const NONE: readonly Message[] = [];
const postedRefs = new Set<string>();

export const PayNoteWatcher: React.FC = () => {
  const activeChatId = useMessengerStore((s) => s.activeChatId);
  const meId = useMessengerStore((s) => s.currentUser.id);
  const messages = useMessengerStore(
    (s) => s.chats.find((c) => c.id === s.activeChatId)?.messages ?? NONE,
  );

  useEffect(() => {
    if (!activeChatId) return;
    const notes = pendingPayNotes();
    if (notes.length === 0) return;
    const { post, drop } = judgePayNotes(notes, activeChatId, messages, meId, Date.now());
    for (const d of drop) {
      dropPayNote(d.ref);
    }
    for (const p of post) {
      dropPayNote(p.ref);
      if (postedRefs.has(p.ref)) continue;
      postedRefs.add(p.ref);
      useMessengerStore.getState().sendMessage(p.note);
    }
  }, [activeChatId, messages, meId]);

  return null;
};

export default PayNoteWatcher;

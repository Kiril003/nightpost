import React, { useEffect, useState } from 'react';
import { useMessengerStore } from '../../../stores/messengerStore';
import { onWorkOpen, type WorkOpen } from '../../../services/workBus';
import { isWorkGroup } from '../../../services/workEvents';
import { displayTitle } from '../chatListFormat';
import { TaskSheet } from './TaskSheet';
import { WorkHub } from './WorkHub';

/** Двері в «Роботу»: шапка розмови і довгий дотик по листу кажуть сюди, що відкрити. */
export const WorkHost: React.FC<{ chatId: string }> = ({ chatId }) => {
  const [open, setOpen] = useState<WorkOpen | null>(null);
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  useEffect(() => onWorkOpen((d) => d.chatId === chatId && setOpen(d)), [chatId]);
  if (!open || !chat || !isWorkGroup(chat)) return null;
  const close = () => setOpen(null);
  return open.taskFrom ? (
    <TaskSheet chat={chat} srcMsgId={open.taskFrom} onClose={close} />
  ) : (
    <WorkHub chatId={chatId} title={displayTitle(chat.title)} view={open.view} onClose={close} />
  );
};

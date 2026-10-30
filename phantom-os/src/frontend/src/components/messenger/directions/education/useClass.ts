import { useEffect, useMemo } from 'react';
import { useMessengerStore } from '../../../../stores/messengerStore';
import {
  assignmentsOf,
  attendanceOf,
  classOf,
  eduItems,
  studentsOf,
  topicsOf,
  type AssignView,
  type AttendView,
  type ClassInfo,
  type Student,
  type TopicView,
} from '../../../../services/edu/eduModel';
import type { Chat } from '../../../../types/messenger';

export interface ClassState {
  chat: Chat;
  cls: ClassInfo;
  meId: string;
  students: Student[];
  assignments: AssignView[];
  attendance: AttendView[];
  topics: TopicView[];
}

/** Клас з журналу групи. null — групи немає або вона не клас. */
export function useClass(chatId: string): ClassState | null {
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const me = useMessengerStore((s) => s.currentUser);
  const needMembers = Boolean(chat && chat.type === 'group' && !chat.members?.length);
  useEffect(() => {
    if (needMembers) void Promise.resolve(useMessengerStore.getState().loadGroupMembers(chatId)).catch(() => undefined);
  }, [needMembers, chatId]);
  return useMemo(() => {
    if (!chat) return null;
    const items = eduItems(chat, me.id);
    const cls = classOf(items, me.id);
    if (!cls) return null;
    const students = studentsOf(chat, cls, me.id, me.name, me.avatar);
    return {
      chat,
      cls,
      meId: me.id,
      students,
      assignments: assignmentsOf(items, cls, students, me.id),
      attendance: attendanceOf(items, cls, students),
      topics: topicsOf(items, cls),
    };
  }, [chat, me.id, me.name, me.avatar]);
}

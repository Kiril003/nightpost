import React, { useEffect } from 'react';
import { useSpaceNav } from '../../../services/spaces/spaceNav';
import { registerPhoneLayer } from '../../../stores/phoneLayerStore';
import { SpaceChannel } from './SpaceChannel';
import { SpaceCreate } from './SpaceCreate';
import { SpaceForum } from './SpaceForum';
import { SpaceHome } from './SpaceHome';
import { SpaceMembers } from './SpaceMembers';
import { SpaceRoleEdit, SpaceRoles } from './SpaceRoles';
import { SpaceThread } from './SpaceThread';

/** Шар екранів простору: верхній маршрут на весь екран, системне «назад» знімає його. */
export const SpaceHost: React.FC = () => {
  const stack = useSpaceNav((s) => s.stack);
  const pop = useSpaceNav((s) => s.pop);
  const top = stack[stack.length - 1];
  useEffect(() => registerPhoneLayer('space-host', Boolean(top), pop), [top, pop]);
  if (!top) return null;
  switch (top.s) {
    case 'home':
      return <SpaceHome key={top.chatId} chatId={top.chatId} />;
    case 'create':
      return <SpaceCreate />;
    case 'channel':
      return <SpaceChannel key={`${top.chatId}:${top.channelId}`} chatId={top.chatId} channelId={top.channelId} />;
    case 'forum':
      return <SpaceForum key={`${top.chatId}:${top.channelId}`} chatId={top.chatId} channelId={top.channelId} />;
    case 'thread':
      return <SpaceThread key={`${top.chatId}:${top.channelId}:${top.topicId}`} chatId={top.chatId} channelId={top.channelId} topicId={top.topicId} />;
    case 'members':
      return <SpaceMembers key={`${top.chatId}:members`} chatId={top.chatId} />;
    case 'roles':
      return <SpaceRoles key={`${top.chatId}:roles`} chatId={top.chatId} />;
    case 'role':
      return <SpaceRoleEdit key={`${top.chatId}:role:${top.roleId}`} chatId={top.chatId} roleId={top.roleId} />;
  }
};

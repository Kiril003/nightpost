/**
 * Посилання-запрошення в гілку (`https://phantom-os.dev/g/#…`). Код несе все сам,
 * відповідає телефон того, хто ділився (міст `/messenger/group-links/…`).
 */
import { request } from './api';

export type GroupLinkState = { may: boolean; link: string; ready: boolean };
export type GroupJoinStage = 'none' | 'asked' | 'pending' | 'joined' | 'refused';
export type GroupJoinView = {
  group_id: string;
  name: string;
  count: number;
  sharer: string;
  stage: GroupJoinStage;
  why?: 'gone' | 'noright' | 'full' | 'in' | 'no';
};

export type GroupJoinRequest = { peer: string; name: string; at: number };
export type GroupRequestsState = { may: boolean; ask: boolean; requests: GroupJoinRequest[] };

export const GROUP_LINK_EVENT = 'phantom:group-link';

const base = (id: string) => `/messenger/group-links/${encodeURIComponent(id)}`;

export const groupLinksApi = {
  get: (groupId: string) => request<GroupLinkState>('GET', base(groupId)),
  renew: (groupId: string) => request<GroupLinkState>('POST', base(groupId)),
  revoke: (groupId: string) => request<GroupLinkState>('DELETE', base(groupId)),
  open: (link: string) => request<GroupJoinView>('POST', '/messenger/group-links/open', { link }),
  join: (link: string) => request<GroupJoinView>('POST', '/messenger/group-links/join', { link }),
};

const requestsPath = (id: string) => `/messenger/group-requests/${encodeURIComponent(id)}`;

export const groupRequestsApi = {
  list: (groupId: string) => request<GroupRequestsState>('GET', requestsPath(groupId)),
  decide: (groupId: string, peer: string, accept: boolean) =>
    request<GroupRequestsState>('POST', requestsPath(groupId), { peer, accept }),
};

export function isGroupLink(url: string | null | undefined): boolean {
  if (!url) return false;
  try {
    const u = new URL(url.trim());
    return u.protocol === 'https:' && u.host === 'phantom-os.dev' && (u.pathname === '/g/' || u.pathname === '/g') && u.hash.length > 1;
  } catch {
    return false;
  }
}

/** Тап по посиланню в розмові: відчиняє аркуш вступу замість браузера. */
export function openGroupLink(url: string): boolean {
  if (!isGroupLink(url)) return false;
  window.dispatchEvent(new CustomEvent<string>(GROUP_LINK_EVENT, { detail: url.trim() }));
  return true;
}

export function groupLinkErrorCode(e: unknown): string {
  const code = (e as { code?: unknown } | null)?.code;
  return typeof code === 'string' ? code : 'group_link_failed';
}

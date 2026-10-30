import type { Chat } from '../types/messenger';
import { useProfiles, type Profile, type StaffMember } from '../stores/profilesStore';
import { chatKind } from '../components/messenger/chatListFormat';
import { ownerOf, useChatProfiles } from '../stores/chatProfileStore';
import { messengerApi, type StaffLine, type StaffPassBody } from './messengerApi';

const SENT_KEY = 'np.staffsent.v1';
const LINES_MAX = 12;
const TEXT_MAX = 280;
const NAME_MAX = 60;
const PHONE_RE = /^\+?[\d\s().-]{6,}$/;

type Sent = Record<string, { business: string; sig: string }>;

type Source = {
  getState: () => { chats: Chat[]; hydrated: boolean; currentUser?: { id?: string } | null };
  subscribe?: (fn: () => void) => () => void;
};

const atOf = (iso?: string) => (iso ? Date.parse(iso) || 0 : 0);

function linesOf(chat: Chat, meId: string): StaffLine[] {
  const text = (chat.messages ?? [])
    .filter((m) => m.type === 'text' && typeof m.text === 'string' && m.text.trim())
    .slice(-LINES_MAX)
    .map((m) => ({ mine: m.senderId === meId, text: m.text!.slice(0, TEXT_MAX), at: atOf(m.sentAt ?? m.timestamp) }));
  if (text.length || !chat.lastSnippet) return text;
  return [{ mine: !!chat.lastAt && chat.lastSelfAt === chat.lastAt, text: chat.lastSnippet.slice(0, TEXT_MAX), at: atOf(chat.lastAt) }];
}

// D7: імʼя, яке людина бачить у списку, і нічого більше; номер замість імені — не імʼя.
const clientOf = (chat: Chat) => {
  const title = chat.title.trim();
  return PHONE_RE.test(title) ? '' : title.slice(0, NAME_MAX);
};

export function passFor(
  profile: Profile,
  member: StaffMember,
  chats: Chat[],
  map: Record<string, string>,
  profiles: Profile[],
  meId: string,
  rev: number,
): StaffPassBody {
  const threads = member.threads
    .map((id) => chats.find((c) => c.id === id))
    .filter((c): c is Chat => !!c && chatKind(c) === 'person' && ownerOf(c.id, map, profiles) === profile.id)
    .map((c) => ({ id: c.id, client: clientOf(c), lines: linesOf(c, meId) }));
  return { business: profile.name.trim().slice(0, NAME_MAX), rev, threads };
}

export const staffNode = (handle: string, chats: Chat[]): string | undefined =>
  chats.find((c) => c.type === 'dm' && c.handle === handle && c.peerNodeId)?.peerNodeId;

function hash(s: string): string {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36) + s.length.toString(36);
}

function readSent(): Sent {
  try {
    const raw = JSON.parse(localStorage.getItem(SENT_KEY) ?? '{}');
    return raw && typeof raw === 'object' && !Array.isArray(raw) ? (raw as Sent) : {};
  } catch {
    return {};
  }
}

function writeSent(sent: Sent): void {
  try {
    localStorage.setItem(SENT_KEY, JSON.stringify(sent));
  } catch {
    // Без сховища відкликання після перезапуску не дійде; пропуск у працівника все одно лише в памʼяті.
  }
}

let sent: Sent | null = null;
let running: Promise<void> | null = null;

export function resetStaffSync(opts: { keepSent?: boolean } = {}): void {
  sent = null;
  running = null;
  if (!opts.keepSent) writeSent({});
}

async function syncOnce(source: Source): Promise<void> {
  const st = source.getState();
  if (!st.hydrated) return;
  const meId = st.currentUser?.id ?? '';
  const { profiles } = useProfiles.getState();
  const map = useChatProfiles.getState().map;
  const held = (sent ??= readSent());
  if (!profiles.some((p) => p.staff.length) && !Object.keys(held).length) return;
  const rev = Date.now();
  const want = new Map<string, StaffPassBody>();
  for (const p of profiles) {
    for (const m of p.staff) {
      const node = staffNode(m.handle, st.chats);
      if (node && !want.has(node)) want.set(node, passFor(p, m, st.chats, map, profiles, meId, rev));
    }
  }
  for (const [node, prev] of Object.entries(held)) {
    if (!want.has(node)) want.set(node, { business: prev.business, rev, threads: [] });
  }
  for (const [node, pass] of want) {
    const sig = pass.threads.length ? hash(JSON.stringify([pass.business, pass.threads])) : '';
    if ((held[node]?.sig ?? '') === sig) continue;
    try {
      await messengerApi.staffPush(node, pass);
    } catch {
      continue;
    }
    if (sig) held[node] = { business: pass.business, sig };
    else delete held[node];
    writeSent(held);
  }
}

export function syncStaffNow(source: Source): Promise<void> {
  const next = (running ?? Promise.resolve()).then(() => syncOnce(source));
  running = next.catch(() => {});
  return next;
}

const DEBOUNCE_MS = 1500;

export function startStaffSync(source: Required<Source>): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const kick = () => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      void syncStaffNow(source);
    }, DEBOUNCE_MS);
  };
  const offChats = source.subscribe(kick);
  const offProfiles = useProfiles.subscribe(kick);
  const offMap = useChatProfiles.subscribe(kick);
  kick();
  return () => {
    if (timer) clearTimeout(timer);
    offChats();
    offProfiles();
    offMap();
  };
}

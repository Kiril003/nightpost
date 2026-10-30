import type { Chat } from '../types/messenger';
import { chatKind } from '../components/messenger/chatListFormat';
import { chatHush, effectiveRule, hushPort, type HushRule } from './chatHush';

/** The shade keys a person chat by the peer's node, a group by its id. */
export function nativeHushRules(chats: readonly Chat[]): Record<string, HushRule> {
  const out: Record<string, HushRule> = {};
  for (const chat of chats) {
    const kind = chatKind(chat);
    if (kind === 'other') continue;
    const rule = effectiveRule(chat);
    if (!rule) continue;
    const key = kind === 'person' ? chat.peerNodeId || chat.id : chat.id;
    const native: HushRule = {};
    if (rule.from !== undefined && rule.to !== undefined) {
      native.from = rule.from;
      native.to = rule.to;
    }
    if (rule.ask) native.ask = true;
    out[key] = native;
  }
  return out;
}

interface ChatSource {
  getState: () => { chats: Chat[]; hydrated?: boolean };
  subscribe: (listener: () => void) => () => void;
}

export function startHushRulesSync(source: ChatSource): () => void {
  if (!hushPort.on()) return () => {};
  let sent: string | null = null;
  const push = () => {
    const { chats, hydrated } = source.getState();
    if (!hydrated) return;
    const rules = nativeHushRules(chats);
    const body = JSON.stringify(rules);
    if (body === sent) return;
    sent = body;
    hushPort.putRules(rules).catch(() => {});
  };
  const offStore = source.subscribe(push);
  const offHush = chatHush.subscribe(push);
  push();
  return () => {
    offStore();
    offHush();
  };
}

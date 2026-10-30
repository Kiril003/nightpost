/**
 * Ключ постачальника моделі не живе в клієнті: не зберігається, не читається,
 * не їде в адресі запиту. Помічник відповідає через вузол (`POST /chat/message`).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';

const LEGACY = 'phantom_sovereign_ai_config';
const FAKE = 'AIzaFAKE-legacy-0000';
const SRC = resolve(__dirname, '../..');

const legacyConfig = () =>
  JSON.stringify({ provider: 'gemini', apiKey: FAKE, model: 'gemini-2.5-flash' });

type Seen = { url: string; init?: RequestInit };

async function askAssistant(): Promise<Seen[]> {
  const seen: Seen[] = [];
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    seen.push({ url: String(input), init });
    return {
      ok: true,
      status: 200,
      json: async () => ({ message: { content: 'ok' }, session_id: 's1' }),
      text: async () => '',
    } as unknown as Response;
  }));
  const { useMessengerStore } = await import('../../stores/messengerStore');
  const { messengerApi } = await import('../messengerApi');
  vi.spyOn(messengerApi, 'appendMessage').mockResolvedValue({ id: 'n1', delivery: 'local' } as never);
  useMessengerStore.setState({
    currentUser: { id: 'me', name: 'Я', avatar: '' } as never,
    chats: [{ id: 'chat_phantom_assistant', title: 'Thought Architect', avatar: '', type: 'phantom', circle: 'all', unreadCount: 0, messages: [] }] as never,
    activeChatId: 'chat_phantom_assistant',
    typingStatus: {},
  });
  const sending = useMessengerStore.getState().sendMessage('привіт') as unknown as Promise<void>;
  await vi.advanceTimersByTimeAsync(3000);
  await sending;
  return seen;
}

function productFiles(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name !== '__tests__') productFiles(p, out);
    } else if (/\.(ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name)) {
      out.push(p);
    }
  }
  return out;
}

describe('ключ постачальника не живе в клієнті', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('(a) жоден запит помічника не несе key= і не йде до постачальника напряму', async () => {
    localStorage.setItem(LEGACY, legacyConfig());
    const seen = await askAssistant();

    for (const s of seen) {
      expect(s.url).not.toMatch(/[?&]key=/);
      expect(s.url).not.toMatch(/googleapis|openai\.com|anthropic\.com|groq\.com/);
      expect(JSON.stringify(s.init ?? {})).not.toContain(FAKE);
      expect(JSON.stringify(s.init ?? {})).not.toMatch(/gemini-|gpt-|claude-/);
    }
    expect(seen.some((s) => s.url.endsWith('/chat/message'))).toBe(true);
  });

  it('(b) ніщо не пише ключ у сховище браузера', async () => {
    const writes: string[] = [];
    const orig = Storage.prototype.setItem;
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, k: string, v: string) {
      writes.push(`${k}=${v}`);
      return orig.call(this, k, v);
    });
    await askAssistant();
    expect(writes.filter((w) => /apiKey|phantom_sovereign_ai_config/.test(w))).toEqual([]);

    const persisting = productFiles(SRC).filter((f) => {
      const code = readFileSync(f, 'utf8');
      return /\bapiKey\b/.test(code) && /(localStorage|sessionStorage)\.setItem|indexedDB/.test(code);
    });
    expect(persisting.map((f) => f.slice(SRC.length + 1))).toEqual([]);
  });

  it('(c) збережений колись ключ стирається на старті', async () => {
    localStorage.setItem(LEGACY, legacyConfig());
    sessionStorage.setItem(LEGACY, legacyConfig());
    const modPath = '../legacyProviderKey';
    const { wipeLegacyProviderKey } = await import(/* @vite-ignore */ modPath);
    wipeLegacyProviderKey();
    expect(localStorage.getItem(LEGACY)).toBeNull();
    expect(sessionStorage.getItem(LEGACY)).toBeNull();

    const main = readFileSync(join(SRC, 'main.tsx'), 'utf8');
    expect(main).toMatch(/import \{ wipeLegacyProviderKey \} from '\.\/services\/legacyProviderKey';/);
    expect(main).toMatch(/^wipeLegacyProviderKey\(\);$/m);
  });
});

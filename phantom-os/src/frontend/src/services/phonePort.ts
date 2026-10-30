/**
 * Асинхронний порт у телефон (MESSENGER_V2 §2.2, хвиля 1Б).
 *
 * Доти кожен запит ішов `PhantomBridge.call` — синхронно: JS-потік сторінки
 * стояв, поки телефон рахував, а в дзвінку це до 12 с мертвого екрана.
 * Тепер телефон ставить `window.PhantomPort` (addWebMessageListener): запит
 * `{id, method, path, body}` іде листом, відповідь `{id, r}` приходить окремо.
 *
 * Старий APK порту не має — тоді той самий синхронний `call`, як і було.
 * Байти (фото з галереї) їдуть `ArrayBuffer`-ом за заголовком, без base64,
 * якщо телефон на привітання сказав `bin: true`.
 */
import { translate } from '../i18n';
import { DEFAULT_LOCALE, isLocale } from '../i18n/locales';

// Мову беремо з <html lang>, а не з settingsStore: стор тягне api, а api — цей порт (цикл).
const portLocale = () => {
  const lang = typeof document === 'undefined' ? '' : document.documentElement.lang;
  return isLocale(lang) ? lang : DEFAULT_LOCALE;
};

export interface BridgeEnvelope<T = unknown> {
  ok: boolean;
  status: number;
  data?: T;
  code?: string;
  detail?: string;
}

interface PortObject {
  postMessage(message: string | ArrayBuffer): void;
  addEventListener?: (type: 'message', cb: (e: { data: unknown }) => void) => void;
  onmessage?: ((e: { data: unknown }) => void) | null;
}

interface SyncBridge {
  call: (m: string, p: string, b: string | null) => string;
}

/** Найдовше, що телефон законно робить: група дзвінка ногу за ногою. */
export const PORT_TIMEOUT_MS = 60_000;
const HELLO_TIMEOUT_MS = 1_500;

export class PortTimeout extends Error {
  constructor(public path: string) {
    super(translate('port.timeout', portLocale()));
    this.name = 'PortTimeout';
  }
}

interface Waiting {
  resolve: (r: BridgeEnvelope) => void;
  reject: (e: Error) => void;
  timer: ReturnType<typeof setTimeout>;
}

export class PhonePort {
  private next = 1;
  private waiting = new Map<number, Waiting>();
  private hello: Promise<{ bin: boolean }> | null = null;
  private helloResolve: ((h: { bin: boolean }) => void) | null = null;

  constructor(
    private port: PortObject,
    private timeoutMs = PORT_TIMEOUT_MS,
  ) {
    const onMessage = (e: { data: unknown }) => this.receive(e.data);
    if (typeof port.addEventListener === 'function') port.addEventListener('message', onMessage);
    else port.onmessage = onMessage;
  }

  request(method: string, path: string, body: string | null): Promise<BridgeEnvelope> {
    const id = this.next++;
    const done = this.expect(id, path);
    this.port.postMessage(JSON.stringify({ id, method, path, body }));
    return done;
  }

  /** Чи можна слати байти `ArrayBuffer`-ом — питає телефон один раз. */
  canSendBytes(): Promise<boolean> {
    if (!this.hello) {
      this.hello = new Promise((resolve) => {
        this.helloResolve = resolve;
        setTimeout(() => resolve({ bin: false }), HELLO_TIMEOUT_MS);
      });
      this.port.postMessage(JSON.stringify({ t: 'hello' }));
    }
    return this.hello.then((h) => h.bin);
  }

  /** Заголовок і одразу байти: порядок листів одного відправника тримає WebView. */
  sendBytes(conversationId: string, bytes: ArrayBuffer): Promise<BridgeEnvelope> {
    const id = this.next++;
    const done = this.expect(id, '/messenger/files');
    this.port.postMessage(JSON.stringify({ id, bin: { conversation_id: conversationId } }));
    this.port.postMessage(bytes);
    return done;
  }

  private expect(id: number, path: string): Promise<BridgeEnvelope> {
    return new Promise<BridgeEnvelope>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiting.delete(id);
        reject(new PortTimeout(path));
      }, this.timeoutMs);
      this.waiting.set(id, { resolve, reject, timer });
    });
  }

  private receive(raw: unknown) {
    if (typeof raw !== 'string') return;
    let msg: { id?: unknown; r?: unknown; t?: unknown; bin?: unknown };
    try {
      msg = JSON.parse(raw);
    } catch {
      return;
    }
    if (msg.t === 'hello') {
      this.helloResolve?.({ bin: msg.bin === true });
      return;
    }
    if (typeof msg.id !== 'number') return;
    const w = this.waiting.get(msg.id);
    // Друга відповідь на той самий id (або запізніла після тайм-ауту) — мовчки геть.
    if (!w) return;
    this.waiting.delete(msg.id);
    clearTimeout(w.timer);
    w.resolve(msg.r as BridgeEnvelope);
  }
}

let shared: PhonePort | null = null;
let sharedFor: unknown = null;

function portObject(): PortObject | null {
  const p = (globalThis as { PhantomPort?: PortObject }).PhantomPort;
  return p && typeof p.postMessage === 'function' ? p : null;
}

function syncBridge(): SyncBridge | null {
  const b = (globalThis as { PhantomBridge?: SyncBridge }).PhantomBridge;
  return b && typeof b.call === 'function' ? b : null;
}

/** Порт телефона, якщо телефон його поставив. */
export function phonePort(): PhonePort | null {
  const obj = portObject();
  if (!obj) return null;
  if (!shared || sharedFor !== obj) {
    shared = new PhonePort(obj);
    sharedFor = obj;
  }
  return shared;
}

export function onPhone(): boolean {
  return portObject() !== null || syncBridge() !== null;
}

/**
 * Запит у телефон: порт, а без нього — старий синхронний `call`.
 * null — ми не в телефоні (ПК, браузер): тоді вузол по HTTP.
 */
export function phoneRequest(method: string, path: string, body: string | null): Promise<BridgeEnvelope> | null {
  const port = phonePort();
  if (port) return port.request(method, path, body);
  const bridge = syncBridge();
  if (!bridge) return null;
  try {
    return Promise.resolve(JSON.parse(bridge.call(method, path, body)) as BridgeEnvelope);
  } catch (e) {
    return Promise.reject(e);
  }
}

export function resetPhonePortForTests() {
  shared = null;
  sharedFor = null;
}

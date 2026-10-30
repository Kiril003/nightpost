/**
 * Віртуальна стрічка: у DOM живе те, що на екрані, плюс запас, — а не вся
 * історія і не «хвіст, що росте вгору без меж», як було з вікном на 120.
 *
 * Причина — вимір, не смак. `e2e/phone-feed-perf.spec.ts` на телефонному
 * бандлі показав, що одна зміна стану ChatArea коштувала 2,8 мс на 50 листах,
 * 9,1 мс на 200 і 23,6 мс на 600: лінійно, бо весь список малювався одним
 * `.map()` поруч із трьома десятками `useState` у тому самому компоненті.
 * Вікно на 120 обрізало хвіст, але росло вгору і не стискалось ніколи.
 *
 * jsdom не має розкладки, тож тут розкладку ЗАДАНО: прокрутник 800 px,
 * рядок 60 px. Швидкість доводиться приладами в Playwright
 * (`e2e/phone-feed-perf.spec.ts`, `e2e/phone-feed-inbound-perf.spec.ts`);
 * тут доводиться СКЛАД стрічки.
 */
import { afterEach, beforeEach, describe, it, expect } from 'vitest';
import { act, render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import { OVERSCAN, STUCK_IDLE_MS } from '../components/messenger/feed/VirtualFeed';
import type { Chat, Message } from '../types/messenger';

const VIEWPORT = 800;
const ROW = 60;

const CHAT = {
  id: 'chat_dm_peer',
  title: 'Оксана',
  avatar: '',
  type: 'dm',
  circle: 'all',
  pinned: false,
  unreadCount: 0,
  messages: [],
} as unknown as Chat;

const noop = () => {};

const ui = (messages: Message[]) => (
  <MemoryRouter>
    <ChatArea
      currentChat={CHAT}
      messages={messages}
      currentUserId="me"
      onOpenLocation={noop}
      onVotePoll={noop}
      onPayBillShare={noop}
      onAddReaction={noop}
      onReplyMessage={noop}
      selectedMessageIds={[]}
      onToggleSelectMessage={noop}
      isSelectionMode={false}
      isSearching={false}
      onCloseSearch={noop}
    />
  </MemoryRouter>
);

const longTalk = (n: number): Message[] =>
  Array.from({ length: n }, (_, i) =>
    ({
      id: `m${i}`,
      senderId: i % 2 === 0 ? 'me' : 'peer',
      senderName: i % 2 === 0 ? 'Я' : 'Оксана',
      senderAvatar: '',
      timestamp: '14:32',
      type: 'text',
      isSelf: i % 2 === 0,
      text: `лист номер ${i}`,
    }) as unknown as Message,
  );

/** Спостерігачі розміру, які браузер смикнув би після розкладки. */
const observers = new Set<{ cb: ResizeObserverCallback; self: ResizeObserver; targets: Set<Element> }>();
/** «Браузер переклав сторінку»: кожен спостерігач чує про свої елементи. */
async function settleLayout() {
  for (let i = 0; i < 3; i++) {
    await act(async () => {
      for (const o of [...observers]) {
        const entries = [...o.targets].map((target) => ({ target }) as unknown as ResizeObserverEntry);
        if (entries.length) o.cb(entries, o.self);
      }
    });
  }
}

/** Розкладка для jsdom: прокрутник має висоту, рядок — свою, прокрутка справжня. */
function withLayout() {
  const savedRO = globalThis.ResizeObserver;
  globalThis.ResizeObserver = class {
    private rec: { cb: ResizeObserverCallback; self: ResizeObserver; targets: Set<Element> };
    constructor(cb: ResizeObserverCallback) {
      this.rec = { cb, self: this as unknown as ResizeObserver, targets: new Set() };
      observers.add(this.rec);
    }
    observe(el: Element) {
      this.rec.targets.add(el);
    }
    unobserve(el: Element) {
      this.rec.targets.delete(el);
    }
    disconnect() {
      this.rec.targets.clear();
      observers.delete(this.rec);
    }
  } as unknown as typeof ResizeObserver;
  const scrollTops = new WeakMap<Element, number>();
  const saved = {
    offsetHeight: Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')!,
    offsetWidth: Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth')!,
    scrollTop: Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop')!,
    scrollHeight: Object.getOwnPropertyDescriptor(Element.prototype, 'scrollHeight')!,
    clientHeight: Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight')!,
    scrollTo: Element.prototype.scrollTo,
  };
  const isScroller = (el: Element) => el.getAttribute('data-testid') === 'messages-scroller';
  const listHeight = (el: Element) =>
    parseFloat((el.querySelector('[data-testid="feed-list"]') as HTMLElement | null)?.style.height || '0');
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get(this: HTMLElement) {
      if (isScroller(this)) return VIEWPORT;
      if (this.hasAttribute('data-index')) return ROW;
      return 0;
    },
  });
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', {
    configurable: true,
    get(this: HTMLElement) {
      return isScroller(this) ? 393 : 0;
    },
  });
  Object.defineProperty(Element.prototype, 'clientHeight', {
    configurable: true,
    get(this: Element) {
      return isScroller(this) ? VIEWPORT : 0;
    },
  });
  Object.defineProperty(Element.prototype, 'scrollHeight', {
    configurable: true,
    get(this: Element) {
      return isScroller(this) ? Math.max(VIEWPORT, listHeight(this)) : 0;
    },
  });
  Object.defineProperty(Element.prototype, 'scrollTop', {
    configurable: true,
    get(this: Element) {
      return scrollTops.get(this) ?? 0;
    },
    set(this: Element, v: number) {
      const max = Math.max(0, this.scrollHeight - this.clientHeight);
      scrollTops.set(this, Math.min(max, Math.max(0, v)));
      this.dispatchEvent(new Event('scroll'));
    },
  });
  Element.prototype.scrollTo = function (this: Element, opts?: ScrollToOptions | number) {
    const top = typeof opts === 'object' ? opts?.top : undefined;
    if (typeof top === 'number') this.scrollTop = top;
  } as Element['scrollTo'];
  return () => {
    globalThis.ResizeObserver = savedRO;
    observers.clear();
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', saved.offsetHeight);
    Object.defineProperty(HTMLElement.prototype, 'offsetWidth', saved.offsetWidth);
    Object.defineProperty(Element.prototype, 'scrollTop', saved.scrollTop);
    Object.defineProperty(Element.prototype, 'scrollHeight', saved.scrollHeight);
    Object.defineProperty(Element.prototype, 'clientHeight', saved.clientHeight);
    Element.prototype.scrollTo = saved.scrollTo;
  };
}

const bubbles = (c: HTMLElement) => c.querySelectorAll('.msg-bubble-fluid').length;
/** Скільки рядків МОЖЕ бути в DOM: видимі плюс запас з обох боків і рядок на межі. */
const MAX_ROWS = Math.ceil(VIEWPORT / ROW) + 2 * OVERSCAN + 2;

beforeEach(() => {
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

describe('стрічка без розкладки (jsdom, прихована колонка)', () => {
  it('малює всі рядки — без розкладки віртуалізувати нема за чим, а порожнеча була б брехнею', () => {
    Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
    const { container } = render(ui(longTalk(30)));
    expect(bubbles(container)).toBe(30);
  });
});

describe('віртуальна стрічка', () => {
  let restore: () => void = () => {};
  beforeEach(() => {
    restore = withLayout();
  });
  afterEach(() => restore());

  it('на 600 листах у DOM — екран плюс запас, а не історія', async () => {
    const { container } = render(ui(longTalk(600)));
    await settleLayout();
    const drawn = bubbles(container);
    expect(drawn, `у DOM ${drawn} бульбашок на 600 листів`).toBeLessThanOrEqual(MAX_ROWS);
    expect(drawn).toBeGreaterThan(Math.floor(VIEWPORT / ROW) - 1);
  });

  it('відкриття садить у НИЗ: останній лист на екрані, найдавнішого в DOM немає', async () => {
    const { container } = render(ui(longTalk(600)));
    await settleLayout();
    expect(container.querySelector('#message-m599')).not.toBeNull();
    expect(container.querySelector('#message-m0')).toBeNull();
  });

  it('гортання вгору МІНЯЄ склад DOM, а не дописує до нього: стелі немає де завгодно в історії', async () => {
    const { container } = render(ui(longTalk(600)));
    await settleLayout();
    const scroller = container.querySelector('[data-testid="messages-scroller"]') as HTMLElement;
    for (const top of [20_000, 9_000, 0]) {
      await act(async () => {
        scroller.scrollTop = top;
      });
      await settleLayout();
      expect(bubbles(container)).toBeLessThanOrEqual(MAX_ROWS);
    }
    // Догорнули до самого початку — тепер є найдавніший і немає останнього.
    expect(container.querySelector('#message-m0')).not.toBeNull();
    expect(container.querySelector('#message-m599')).toBeNull();
  });

  it('прилипла пігулка дня сходить, щойно стрічка стала: інакше вона лежить на першій бульбашці (A56, клавіатура)', async () => {
    const noon = (daysAgo: number) => {
      const d = new Date();
      d.setDate(d.getDate() - daysAgo);
      d.setHours(12, 0, 0, 0);
      return d.toISOString();
    };
    const talk = longTalk(60).map((m, i) => ({ ...m, sentAt: noon(i < 30 ? 1 : 0) }) as Message);
    const { container } = render(ui(talk));
    await settleLayout();
    const scroller = container.querySelector('[data-testid="messages-scroller"]') as HTMLElement;
    await act(async () => {
      scroller.scrollTop = 30 * ROW + 100;
    });
    await settleLayout();
    expect(container.querySelector('[data-day-stuck]'), 'під час гортання пігулка має бути').not.toBeNull();
    await act(async () => {
      await new Promise((r) => setTimeout(r, STUCK_IDLE_MS + 100));
    });
    expect(container.querySelector('[data-day-stuck]')).toBeNull();
  });

  it('новий лист знизу, коли людина внизу, — одразу в DOM', async () => {
    const talk = longTalk(600);
    const { container, rerender } = render(ui(talk));
    await settleLayout();
    const fresh = { ...talk[1], id: 'fresh', text: 'щойно прийшов' } as Message;
    await act(async () => {
      rerender(ui([...talk, fresh]));
    });
    await settleLayout();
    expect(container.querySelector('#message-fresh')).not.toBeNull();
  });
});

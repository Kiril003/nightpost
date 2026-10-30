/**
 * Ширина бульбашки — ВИМІРЯНА в рушії, а не виведена з наявності класу.
 *
 * ── ЧОМУ ЩЕ ОДИН СТОРОЖ ПОРУЧ ІЗ messageBubbleFits.test.tsx ─────────────
 *
 * Сусідній чесно називає свою межу: jsdom не рахує layout, усі ширини там
 * нулі, тож він доводить лише те, що `min-w-0` СТОЇТЬ на елементі. Це доказ
 * правопису, не поведінки. Перевірено 17.09: `min-w-0` на бульбашці можна
 * зняти й ширина не зміниться ані на піксель (309 px і так, і так) — його
 * роботу вже роблять `width: fit-content` самої бульбашки й `min-w-0` на
 * рядку. Тобто сторож правопису стереже не ту річ, яка тримає межу.
 *
 * ── ДЕ МІРЯТИ. ЦЕ ГОЛОВНЕ ───────────────────────────────────────────────
 *
 * Над стрічкою стоять ТРИ предки з `overflow-x: hidden`. Будь-який вимір,
 * узятий вище за них, показує рівно ширину вікна: переповнення обрізане й
 * приладу його не видно. Перша редакція цього тесту так і міряла — вона
 * лишалась зеленою, коли справжня ширина вмісту була 3 139 px.
 * [[phantom-instrument-wrong-layer]]
 *
 * Правильний шар — `.msg-scroller`: у нього `overflow-x: auto`, тож його
 * `scrollWidth` чесно показує, наскільки вміст ширший за `clientWidth`.
 *
 * ── ЧОМУ ТУТ Є КОНТРОЛЬНИЙ ПРОГІН ───────────────────────────────────────
 *
 * Зелений тест, який не вміє почервоніти, нічого не стереже
 * [[phantom-green-that-cannot-redden]]. Тому останній випадок навмисно
 * ламає обидва пояси перенесення й ВИМАГАЄ переповнення. Стане зеленим і
 * він — значить прилад осліп, а не код полагодився.
 *
 * ── ДВА ПОЯСИ ───────────────────────────────────────────────────────────
 *
 * Перенос тримають двоє незалежно: утиліта `break-words` у розмітці й
 * `overflow-wrap: break-word` у `.msg-bubble-fluid`. Виміряно: зняти
 * будь-який ОДИН — ширина лишається 360 px; зняти ОБИДВА — 3 139 px.
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import postcss from 'postcss';
import tailwind from 'tailwindcss';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { chromium, type Browser } from '@playwright/test';
import { ChatArea } from '../components/messenger/ChatArea';
import type { Chat, Message } from '../types/messenger';

/** Ширина скла A52 у CSS-пікселях — та, на якій власник це й побачив. */
const VIEWPORT = 360;

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

const letter = (text: string, id: string): Message =>
  ({
    id,
    senderId: 'peer',
    senderName: 'Оксана',
    senderAvatar: '',
    timestamp: '14:32',
    type: 'text',
    isSelf: false,
    text,
  }) as unknown as Message;

/**
 * Сім способів зламати перенос. Число й RTL тут не для повноти: цифри не
 * мають точок переносу взагалі, а RTL міняє бік, з якого рахується
 * переповнення, тож ловиться воно іншим знаком.
 */
const HARD: Array<[name: string, text: string]> = [
  ['довге слово без пробілів', 'а'.repeat(400)],
  ['посилання', `https://phantom-os.dev/${'segment-'.repeat(40)}?q=${'x'.repeat(120)}`],
  ['код', '`' + 'const identifier_without_any_spaces_at_all = 1;'.repeat(12) + '`'],
  ['довге число', '9'.repeat(220)],
  ['RTL', 'مرحبا بالعالم ' + 'ا'.repeat(300)],
  ['емодзі', '🛰️🔐🗺️'.repeat(150)],
  [
    'слово + посилання + код в одному листі',
    `${'б'.repeat(160)} https://x.dev/${'y'.repeat(160)} \`${'z'.repeat(160)}\``,
  ],
];

const noop = () => {};

const feed = (messages: Message[]): string =>
  renderToStaticMarkup(
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
    </MemoryRouter>,
  );

const productCss = (): string =>
  readFileSync(join(process.cwd(), 'src/styles/messenger.css'), 'utf8');

let browser: Browser;
beforeAll(async () => {
  // Свій Chromium Playwright, коли він є (ноутбук); інакше — встановлений поруч
  // (хмара: /opt/pw-browsers/chromium іншої збірки, ніж чекає цей Playwright).
  const own = chromium.executablePath();
  const fallback = [process.env.PHANTOM_CHROMIUM, '/opt/pw-browsers/chromium'].find((p): p is string => Boolean(p && existsSync(p)));
  browser = await chromium.launch(existsSync(own) || !fallback ? {} : { executablePath: fallback });
}, 90_000);
afterAll(async () => {
  await browser?.close();
});

/** Ширина вмісту стрічки проти ширини її вікна, у справжньому рушії. */
async function measure(markup: string, css: string): Promise<{ content: number; window: number }> {
  // Ті самі утиліти, що й у збірці: Tailwind по класах ЦІЄЇ розмітки.
  const utilities = await postcss([
    tailwind({ content: [{ raw: markup, extension: 'html' }] }),
  ]).process('@tailwind base;@tailwind utilities;', { from: undefined });

  const page = await browser.newPage({ viewport: { width: VIEWPORT, height: 800 } });
  try {
    await page.setContent(
      `<style>${utilities.css}\n${css}</style><div style="width:${VIEWPORT}px">${markup}</div>`,
    );
    return await page.evaluate(() => {
      const scroller = document.querySelector('.msg-scroller') as HTMLElement | null;
      if (!scroller) throw new Error('.msg-scroller у розмітці немає — міряти нема на чому');
      return { content: scroller.scrollWidth, window: scroller.clientWidth };
    });
  } finally {
    await page.close();
  }
}

describe('бульбашка не розтягує стрічку — виміряно на 360 px', () => {
  it('усі важкі листи в одній розмові', async () => {
    const markup = feed(HARD.map(([, text], i) => letter(text, `m${i}`)));
    expect(markup.length, 'ChatArea нічого не намалювала — міряти нема чого').toBeGreaterThan(500);

    const { content, window } = await measure(markup, productCss());
    expect(
      content,
      `вміст стрічки ${content} px проти вікна ${window} px — щось розтягує рядок`,
    ).toBeLessThanOrEqual(window + 1);
  }, 90_000);

  it('кожен важкий випадок окремо — збій називає винного', async () => {
    const broken: string[] = [];
    const css = productCss();
    for (const [name, text] of HARD) {
      const { content, window } = await measure(feed([letter(text, 'only')]), css);
      if (content > window + 1) broken.push(`${name}: ${content} px проти ${window} px`);
    }
    expect(broken, 'ці випадки розтягують стрічку').toEqual([]);
  }, 180_000);

  it('КОНТРОЛЬ: без обох поясів перенесення прилад мусить почервоніти', async () => {
    const markup = feed([letter('а'.repeat(400), 'only')]);
    // Знімаємо обидва незалежні пояси: утиліту в розмітці й правило в CSS.
    const noClass = markup.replace(/\bbreak-words\b/g, '').replace(/\bbreak-all\b/g, '');
    const noRule = productCss().replace('overflow-wrap: break-word;', '');

    const { content, window } = await measure(noClass, noRule);
    expect(
      content,
      'прилад осліп: навіть без жодного перенесення він не бачить переповнення. ' +
        'Найімовірніше вимір поїхав на шар вище за .msg-scroller, де три предки ' +
        'ріжуть overflow-x і показують рівно ширину вікна.',
    ).toBeGreaterThan(window * 2);
  }, 90_000);
});

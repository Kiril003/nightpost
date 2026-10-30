/**
 * Сторож дизайн-системи PHANTOM v1 у вебі (docs/design/system, §9.2).
 *
 * 1. Токени в застосунку — рівно те, що дає генератор із tokens.json
 *    (src/styles/phantom/* ≡ docs/design/system/build/*), і генератор зелений
 *    на контрасті (`--check`).
 * 2. Тем рівно дві — Ніч і День; світанку, сутінків і старих тем у CSS немає.
 * 3. Один шрифт: Manrope 400–700 (+ JetBrains Mono 400 для коду), латиниця
 *    несе апостроф ʼ U+02BC; Onest/Space Grotesk/Playfair не підвантажуються.
 * 4. Аватар — плоска монограма одного з шести тонів, без градієнта.
 * 5. Хром не тонується людиною: ні сяйва, ні горизонталей.
 *
 * Червоніти вміє кожен пункт: поверніть `radial-gradient` в Avatar, `@fontsource/onest`
 * у main.tsx, `box-shadow` в identity.css чи відредагуйте tokens.css руками.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { render, screen } from '@testing-library/react';
import { Avatar, personTone } from '../components/messenger/Avatar';
import { PeerProfileSheet } from '../components/messenger/PeerProfileSheet';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { PEOPLE_TONES, contrast, toneIndex } from '../styles/formTokens';

const ROOT = process.cwd();
const REPO = join(ROOT, '../../..');
const read = (p: string) => readFileSync(join(ROOT, p), 'utf8');
const TOKENS = JSON.parse(readFileSync(join(REPO, 'docs/design/system/tokens.json'), 'utf8'));

describe('токени: одне джерело', () => {
  it('tokens.css і пресет Tailwind у застосунку — байт у байт вихід генератора', () => {
    for (const f of ['tokens.css', 'tailwind-preset.ts', 'literals.ts']) {
      const built = readFileSync(join(REPO, 'docs/design/system/build', f), 'utf8');
      expect(read(`src/styles/phantom/${f}`), `${f} розійшовся з генератором — перегенеруйте`).toBe(built);
    }
  });

  it('генератор зелений на контрасті (--check)', () => {
    const out = execFileSync('node', [join(REPO, 'docs/design/system/build-tokens.mjs'), '--check'], { encoding: 'utf8' });
    expect(out).toMatch(/contrast fails: 0/);
  });

  it('Tailwind бере пресет, globals.css — токени', () => {
    expect(read('tailwind.config.ts')).toMatch(/presets:\s*\[phantom/);
    expect(read('src/styles/globals.css')).toContain("@import './phantom/tokens.css';");
  });

  it('бурштин D — #E2A048 уночі, #C27A16 удень; кнопка й бурштиновий текст тримають AA', () => {
    expect(TOKENS.color.night.accent.value).toBe('#E2A048');
    expect(TOKENS.color.day.accent.value).toBe('#C27A16');
    expect(contrast(TOKENS.color.day['on-accent'].value, TOKENS.color.day.accent.value)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(TOKENS.color.day['accent-text'].value, TOKENS.color.day.canvas.value)).toBeGreaterThanOrEqual(4.5);
  });
});

describe('дві теми', () => {
  it('у CSS форми немає сяйва й горизонталей', () => {
    const css = read('src/styles/form.css');
    expect(css).not.toMatch(/\.f-glow|\.f-topo|radial-gradient/);
  });

  it('токени й ідентичність без градієнтів, сяйва, розмиття й тіней', () => {
    const files = ['src/styles/phantom/tokens.css', 'src/styles/identity.css'];
    for (const f of files) {
      const css = read(f).replace(/\/\*[\s\S]*?\*\//g, '');
      expect(css, f).not.toMatch(/gradient|glow|blur/);
      expect(css.replace(/box-shadow:\s*none/g, ''), f).not.toMatch(/box-shadow/);
    }
  });

  it('стара змінна форми — лише псевдонім токена, не власне число', () => {
    const css = read('src/styles/form.css');
    const root = /html:root \{([\s\S]*?)\n\}/.exec(css)?.[1] ?? '';
    for (const m of root.matchAll(/--f-(canvas|surface|raise|ink|ink-2|ink-3|accent|line):\s*([^;]+);/g)) {
      expect(m[2], `--f-${m[1]}`).toMatch(/^var\(--ph-/);
    }
  });
});

describe('один шрифт', () => {
  const main = read('src/main.tsx');
  const fonts = read('src/styles/phantom/fonts.css');

  it('Onest, Space Grotesk, Playfair не підвантажуються; Manrope — лише 400–700', () => {
    expect(main).not.toMatch(/@fontsource\/(onest|space-grotesk|playfair-display)/);
    expect(main).toContain("import './styles/phantom/fonts.css';");
    const weights = new Set([...fonts.matchAll(/font-family: 'Manrope';[\s\S]*?font-weight: (\d+);/g)].map((m) => m[1]));
    expect([...weights].sort()).toEqual(['400', '500', '600', '700']);
    expect(fonts).not.toMatch(/Onest|Playfair|Space Grotesk/);
  });

  it('латинська підмножина Manrope несе ʼ (U+02BC), кирилиця — Ґ і №; файли існують', () => {
    expect(fonts).toMatch(/manrope-latin-400-normal\.woff2[\s\S]*?U\+02BB-02BC/);
    expect(fonts).toMatch(/manrope-cyrillic-400-normal\.woff2[\s\S]*?U\+0490-0491[\s\S]*?U\+2116/);
    for (const m of fonts.matchAll(/url\('([^']+)'\)/g)) {
      expect(existsSync(join(ROOT, 'src/styles/phantom', m[1])), m[1]).toBe(true);
    }
  });

  it('Tailwind: sans, serif і display — усі Manrope (жодної серифної «корони»)', () => {
    const tw = read('tailwind.config.ts');
    expect(tw).toMatch(/serif:\s*\['var\(--ph-font-sans\)'\]/);
    expect(tw).toMatch(/display:\s*\['var\(--ph-font-sans\)'\]/);
  });
});

describe('аватар — плоска монограма', () => {
  afterEach(() => vi.restoreAllMocks());

  it('тон людини: заливка й літера одного з шести тонів, без градієнта', () => {
    render(<Avatar name="Оксана" tone="peer_ok" />);
    const el = screen.getByLabelText('Оксана');
    const i = toneIndex('peer_ok');
    const s = el.getAttribute('style') ?? '';
    expect(s).not.toContain('gradient');
    expect(s).toContain(`background-color: var(--ph-person-${i})`);
    expect(s).toContain(`color: var(--ph-person-${i}-ink)`);
    expect(el.textContent).toBe('О');
  });

  it('без тону («Збережене», Nightpost) — нейтральний raised', () => {
    render(<Avatar name="Збережене" />);
    expect(screen.getByLabelText('Збережене').className).toContain('bg-raised');
  });

  it('тонів рівно шість; ключ сталий і розкиданий', () => {
    expect(PEOPLE_TONES).toBe(6);
    expect(TOKENS.person.night).toHaveLength(6);
    expect(toneIndex('peer_c1')).toBe(toneIndex('peer_c1'));
    const used = new Set(Array.from({ length: 64 }, (_, i) => toneIndex(`peer_${i}`)));
    expect(used.size).toBe(6);
    expect(personTone('x').fill).toMatch(/^var\(--ph-person-[0-5]\)$/);
  });

  it('постер профілю без фото — плоский тон людини, не куля', () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
    render(<PeerProfileSheet peerNodeId="peer_ok" name="Оксана" onClose={() => {}} />);
    const s = screen.getByTestId('peer-profile-initial').getAttribute('style') ?? '';
    expect(s).not.toContain('gradient');
    expect(s).toContain(`var(--ph-person-${toneIndex('peer_ok')})`);
  });
});

describe('хром не тонується людиною', () => {
  it('корінь розмови, стрічка, список і «Я» — без сяйва й горизонталей', () => {
    for (const f of ['MessengerRoot.tsx', 'ChatArea.tsx', 'PhoneChatList.tsx', 'MeScreen.tsx', 'chatListFormat.ts']) {
      const src = read(`src/components/messenger/${f}`);
      expect(src, f).not.toMatch(/f-glow|f-topo|talkWarmth|topoMask/);
    }
  });
});

describe('розмітка месенджера говорить лише токенами (§9.2, §11)', () => {
  const { readdirSync, statSync } = require('node:fs') as typeof import('node:fs');
  const walk = (dir: string): string[] =>
    readdirSync(dir).flatMap((n: string) => {
      const p = join(dir, n);
      return statSync(p).isDirectory() ? (n === '__tests__' ? [] : walk(p)) : p.endsWith('.tsx') ? [p] : [];
    });
  const files = walk(join(ROOT, 'src/components/messenger'));
  const hits = (re: RegExp) =>
    files.flatMap((f) => [...readFileSync(f, 'utf8').matchAll(re)].map((m) => `${f.split('/messenger/')[1]}: ${m[0]}`));

  it('файлів досить, щоб вимір щось важив', () => {
    expect(files.length).toBeGreaterThan(60);
  });
  it('жодного hex-кольору в класі', () => {
    expect(hits(/(?:bg|text|border|ring|fill|stroke|from|to|via|outline)-\[#[0-9A-Fa-f]{3,8}\]/g)).toEqual([]);
  });
  it('жодного тексту, дрібнішого за 12 px', () => {
    expect(hits(/text-\[(?:[0-9]|1[01])(?:\.\d+)?px\]/g)).toEqual([]);
  });
  it('ні ВЕЛИКИХ ЛІТЕР-«HUD», ні розрідженого трекінгу, ні серифа', () => {
    expect(hits(/(?<![\w-])(?:uppercase|tracking-wide|tracking-wider|tracking-widest|font-serif)(?![\w-])/g)).toEqual([]);
  });
  it('ні декоративних петель (pulse/ping/bounce), ні скла', () => {
    expect(hits(/(?<![\w-])(?:animate-pulse|animate-ping|animate-bounce|backdrop-blur(?:-\w+)?)(?![\w-])/g).filter((h) => !/MediaLightbox|CallOverlay/.test(h))).toEqual([]);
  });
});

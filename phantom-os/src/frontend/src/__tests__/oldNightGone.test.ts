import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import postcss from 'postcss';
import tailwindcss from 'tailwindcss';

/**
 * Стара зеленкувата ніч (полотно #0C110D, картка й діалог #0E1410, панель
 * #121A15, заглиблення #18221C, бурштин #F4AF25) пережила перехід на форму:
 * екран «Я» і кожен діалог малювались rgb(14,20,16) замість #0B0B0D, бо
 * глобальне правило силою фарбувало [role=dialog]. Нова ніч — лише `--f-*`.
 */
const OLD = ['#0C110D', '#0E1410', '#121A15', '#18221C', '#F4AF25'];
const SRC = join(process.cwd(), 'src');

const walk = (dir: string): string[] =>
  readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return name === '__tests__' ? [] : walk(p);
    return /\.tsx?$/.test(name) ? [p] : [];
  });

/** Рукописна частина messenger.css — до згенерованих блоків дня й акценту. */
const handWritten = (): string => {
  const css = readFileSync(join(SRC, 'styles/messenger.css'), 'utf8');
  return css.slice(0, css.indexOf('/* >>> ДЕНЬ'));
};

describe('стара ніч прибрана', () => {
  it('рукописна частина messenger.css не знає старих нічних чисел', () => {
    const css = handWritten().toUpperCase();
    expect(OLD.filter((hex) => css.includes(hex))).toEqual([]);
    expect(css).not.toContain('244, 175, 37');
    expect(css).not.toContain('14, 20, 16');
  });

  it('нічне правило діалогів не перебиває власну поверхню екрана', () => {
    const css = handWritten();
    const rule = css.slice(css.indexOf('[role="dialog"], .modal-content'));
    const body = rule.slice(rule.indexOf('{'), rule.indexOf('}'));
    expect(body).toContain('var(--f-canvas)');
    expect(body).not.toContain('!important');
  });

  it('розмітка не носить старих нічних утиліт', () => {
    const hits = walk(SRC)
      .filter((f) => !f.endsWith('styles/dayTheme.ts'))
      .flatMap((f) =>
        [...readFileSync(f, 'utf8').matchAll(/[a-z-]+-\[(#[0-9A-Fa-f]{6})\]/g)]
          .filter((m) => OLD.includes(m[1].toUpperCase()))
          .map((m) => `${f.slice(SRC.length + 1)}: ${m[0]}`),
      );
    expect(hits).toEqual([]);
  });

  it('типовий акцент — бурштин Nightpost (#F2A93B), а не теракота', () => {
    const css = readFileSync(join(SRC, 'styles/form.css'), 'utf8');
    expect(css).toContain('--msg-accent: var(--ph-accent-text);');
    expect(css).toContain('--msg-accent-ink: var(--ph-accent-text);');
    expect(css.toUpperCase()).not.toContain('#E87A42');
  });

  it('барви токенів і старі псевдоніми вміють прозорість: bg-accent/15 справді народжується', async () => {
    // Шлях змінною: tsc не тягне конфіг Node-проєкту в проєкт застосунку.
    const path = join(process.cwd(), 'tailwind.config.ts');
    const { default: config } = await import(/* @vite-ignore */ path);
    const raw = '<i class="bg-accent/15 border-accent-line/30 text-accent-text bg-canvas from-f-accent"></i>';
    const { css } = await postcss([tailwindcss({ ...config, content: [{ raw }] })]).process('@tailwind utilities;', {
      from: undefined,
    });
    // Старі імена — псевдоніми токенів PHANTOM v1: заливка — `accent`, текст — `accent-text`.
    expect(css).toContain('color-mix(in srgb, var(--ph-accent) calc(0.15 * 100%), transparent)');
    expect(css).toContain('color-mix(in srgb, var(--ph-accent-line) calc(0.3 * 100%), transparent)');
    expect(css).toMatch(/\.text-accent-text \{[^}]*color: var\(--ph-accent-text\)/);
    // Градієнт кличе барву з числовою прозорістю 0 — збірка падала на ній.
    expect(css).toContain('color-mix(in srgb, var(--ph-accent-text) calc(0 * 100%), transparent)');
    expect(css).toMatch(/\.bg-canvas \{[^}]*background-color: var\(--ph-canvas\)/);
  });
});

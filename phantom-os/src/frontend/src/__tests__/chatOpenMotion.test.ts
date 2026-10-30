/**
 * T7: відкрити розмову на телефоні — один короткий наїзд без відскоку, як
 * системний «назад» Android. До 28.10 екран їхав пружиною 438 мс із
 * перельотом, а аватар і імʼя окремо летіли в шапку (514 мс) — тестерам це
 * не подобалось.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { withViewTransition } from '../services/viewTransition';

const STYLES = join(__dirname, '..', 'styles');
const strip = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '');
const FORM = strip(readFileSync(join(STYLES, 'form.css'), 'utf8'));
const IDENTITY = strip(readFileSync(join(STYLES, 'identity.css'), 'utf8'));
const ALL = FORM + IDENTITY;

afterEach(() => {
  delete (document as { startViewTransition?: unknown }).startViewTransition;
  document.body.innerHTML = '';
});

describe('відкрити розмову (vt-push)', () => {
  it('рядок списку не віддає аватар і імʼя в окремий переліт', () => {
    const seen: string[] = [];
    const avatar = document.createElement('span');
    const name = document.createElement('span');
    document.body.append(avatar, name);
    (document as { startViewTransition?: unknown }).startViewTransition = vi.fn((cb: () => void) => {
      seen.push(avatar.style.getPropertyValue('view-transition-name'), name.style.getPropertyValue('view-transition-name'));
      cb();
      return { finished: Promise.resolve() };
    });
    const update = vi.fn();
    withViewTransition(update, { pairs: [[avatar, 'chat-avatar'], [name, 'chat-name']], kind: 'vt-push' });
    expect(update).toHaveBeenCalledTimes(1);
    expect(seen).toEqual(['', '']);
  });

  it('фото й далі розростається зі своєї мініатюри', () => {
    let named = '';
    const img = document.createElement('img');
    document.body.append(img);
    (document as { startViewTransition?: unknown }).startViewTransition = vi.fn((cb: () => void) => {
      named = img.style.getPropertyValue('view-transition-name');
      cb();
      return { finished: Promise.resolve() };
    });
    withViewTransition(() => {}, { pairs: [[img, 'photo-zoom']], kind: 'vt-zoom' });
    expect(named).toBe('photo-zoom');
  });

  it('екран наїжджає 240 мс із уповільненням, без пружини', () => {
    const rules = ALL.match(/html\.vt-push::view-transition-new\(root\)\s*\{[^}]*\}/g) ?? [];
    expect(rules.length).toBe(1);
    expect(rules[0]).toContain('var(--ph-dur-standard) var(--ph-ease-decelerate)');
    expect(rules[0]).not.toContain('spring');
  });

  it('шапка не має спільних імен під час наїзду', () => {
    expect(ALL).not.toMatch(/html\.vt-push \[data-shared/);
    expect(ALL).not.toMatch(/view-transition-group\(chat-(avatar|name)\)/);
  });
});

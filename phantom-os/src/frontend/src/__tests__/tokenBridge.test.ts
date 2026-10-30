import { describe, it, expect, beforeEach } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import postcss, { type Rule } from 'postcss';
import tailwindcss from 'tailwindcss';
import { dayThemeCss } from '../styles/dayTheme';
import { useSettingsStore } from '../stores/settingsStore';

/**
 * МІСТ СТАРИХ ТОКЕНІВ — одна палітра під кожним екраном (03.10).
 *
 * Виміряно на чистому cloud/beta (b629fd08), телефон, Ніч: `--surface-base`
 * = #f8f7f5, `--ink-primary` = #120f0b, тоді як `--ph-canvas` = #0A0C10.
 * Старий словник (styles/tokens.css «Sunrise Build») стояв на `:root` СВІТЛИМ
 * і темнів лише під `html[data-desk][data-theme=night] [data-desk-night]` —
 * у брунатний еспресо #0e0a05, не в чорнило «Ніч». Звідси кремова заставка
 * з помаранчевою кулею вночі, брунатна сторінка помилки, еспресо-панелі ПК
 * поруч із чорнильною рейкою (кадри before-web-token-bridge/).
 *
 * Тепер старі імена — ПСЕВДОНІМИ `--ph-*` на `:root`, тобто на тому самому
 * <html>, що несе `data-theme`: вони самі йдуть за Ніччю й Днем.
 *
 * Мутації (заміряні на цьому файлі): повернути `--surface-base: #f8f7f5` —
 * червоніє «кольорова змінна мосту»; повернути блок `[data-theme="amber-night"]`
 * — червоніє «старі теми»; повернути `!important`-полотно в messenger.css —
 * червоніє «полотно не перебито»; повернути `applyThemeToDom` у setTheme —
 * червоніють «один записувач» і «setTheme не чіпає DOM».
 */

const ROOT = process.cwd();
const SRC = join(ROOT, 'src');
const read = (p: string): string => readFileSync(join(ROOT, p), 'utf8');
const stripComments = (css: string): string => css.replace(/\/\*[\s\S]*?\*\//g, '');

/** Рукописна частина messenger.css — до згенерованих блоків дня й акценту. */
const messengerHandWritten = (): string => {
  const css = read('src/styles/messenger.css');
  return css.slice(0, css.indexOf('/* >>> ДЕНЬ'));
};

/** Останній складник селектора (суб'єкт), без комбінаторів; дужки поважаються. */
const subjectOf = (selector: string): string => {
  let depth = 0;
  let last = 0;
  for (let i = 0; i < selector.length; i += 1) {
    const c = selector[i];
    if (c === '(' || c === '[') depth += 1;
    else if (c === ')' || c === ']') depth -= 1;
    else if (depth === 0 && /[\s>+~]/.test(c)) last = i + 1;
  }
  return selector.slice(last).trim();
};

/** Суб'єкт — сам документ: html, body, #root чи :root (той самий <html>). */
const isDocumentSubject = (subject: string): boolean =>
  /^(html|body)(?![\w-])/.test(subject) || /^:root(?![\w-])/.test(subject) || /^#root(?![\w-])/.test(subject);

const NON_COLOUR = [
  /^--fs-/, // кегль (globals body, ErrorBoundary, ПК-чат)
  /^--lh-/,
  /^--tracking-/,
  /^--radius-/,
  /^--touch-min$/,
  /^--motion-scale/,
];

describe('міст старих токенів', () => {
  it('у tokens.css і рукописному messenger.css немає старих тем, станів і перемикача акценту', () => {
    const tokens = stripComments(read('src/styles/tokens.css'));
    const msgr = stripComments(messengerHandWritten());
    const OLD = [
      'sunrise-warm',
      'amber-night',
      'cyberdeck',
      'pro-console',
      'data-desk-night',
      '[data-state=',
      'data-msg-accent',
    ];
    const hits = [
      ...OLD.filter((s) => tokens.includes(s)).map((s) => `tokens.css: ${s}`),
      ...OLD.filter((s) => msgr.includes(s)).map((s) => `messenger.css: ${s}`),
    ];
    expect(hits).toEqual([]);
  });

  it('полотно документа не перебито: жодного !important-тла на html, body чи #root', () => {
    const offenders: string[] = [];
    for (const file of ['tokens.css', 'globals.css', 'messenger.css', 'form.css', 'identity.css']) {
      postcss.parse(read(`src/styles/${file}`)).walkRules((rule: Rule) => {
        const subjects = rule.selectors.map(subjectOf).filter(isDocumentSubject);
        if (subjects.length === 0) return;
        rule.walkDecls((d) => {
          if (d.prop.startsWith('background') && d.important) {
            offenders.push(`${file}: ${rule.selector.replace(/\s+/g, ' ')} { ${d.prop}: ${d.value} !important }`);
          }
        });
      });
    }
    expect(offenders).toEqual([]);
  });

  it('кожна кольорова змінна мосту на :root — псевдонім --ph-*, transparent або none', () => {
    const rootVars = new Map<string, string>();
    postcss.parse(read('src/styles/tokens.css')).walkRules((rule: Rule) => {
      if (!rule.selectors.includes(':root')) return;
      rule.walkDecls(/^--/, (d) => {
        rootVars.set(d.prop, d.value.trim());
      });
    });
    expect(rootVars.size, 'у tokens.css немає :root — міст ні про що').toBeGreaterThan(30);

    const bad: string[] = [];
    for (const [name, value] of rootVars) {
      if (NON_COLOUR.some((re) => re.test(name))) continue;
      if (/^var\(--ph-[a-z0-9-]+\)$/.test(value) || value === 'transparent' || value === 'none') continue;
      bad.push(`${name}: ${value}`);
    }
    expect(bad, 'старе ім’я тримає власне число — під ним житиме стара палітра').toEqual([]);

    // Ролі, а не лише форма запису: полотно — полотно, чорнило — чорнило.
    expect(rootVars.get('--surface-base')).toBe('var(--ph-canvas)');
    expect(rootVars.get('--surface-raised')).toBe('var(--ph-raised)');
    expect(rootVars.get('--glass-panel')).toBe('var(--ph-surface)');
    expect(rootVars.get('--glass-border')).toBe('var(--ph-line)');
    expect(rootVars.get('--ink-primary')).toBe('var(--ph-text)');
    expect(rootVars.get('--ink-muted')).toBe('var(--ph-text-3)');
    expect(rootVars.get('--primary')).toBe('var(--ph-accent)');
    expect(rootVars.get('--signal-alert')).toBe('var(--ph-danger)');
    expect(rootVars.get('--signal-ok')).toBe('var(--ph-success)');
    expect(rootVars.get('--font-display')).toBe('var(--ph-font-sans)');
    expect(rootVars.get('--font-mono')).toBe('var(--ph-font-mono)');
    // Сяйва в новій мові немає.
    expect(rootVars.get('--accent-glow')).toBe('transparent');
  });

  it('у tokens.css лише одне правило — :root мосту', () => {
    const selectors: string[] = [];
    postcss.parse(read('src/styles/tokens.css')).walkRules((rule: Rule) => {
      selectors.push(rule.selector.replace(/\s+/g, ' '));
    });
    expect(selectors).toEqual([':root']);
  });

  it('картка дзвінка: «завершити» — danger, «перевірено» — success, і в Ночі, і в Дні', () => {
    const css = read('src/styles/messenger.css');
    const values = (name: string): string[] =>
      [...css.matchAll(new RegExp(`${name}:\\s*([^;]+);`, 'g'))].map((m) => m[1].trim());
    expect(values('--call-end')).toEqual(['var(--ph-danger)']);
    expect(values('--call-trust-ok')).toEqual(['var(--ph-success)']);
  });

  it('у src (поза тестами) data-theme на <html> пише лише services/theme.ts', () => {
    const walk = (dir: string): string[] =>
      readdirSync(dir).flatMap((name) => {
        const p = join(dir, name);
        if (statSync(p).isDirectory()) return name === '__tests__' ? [] : walk(p);
        return /\.(tsx?|jsx?)$/.test(name) ? [p] : [];
      });
    const WRITE = /setAttribute\(\s*['"`]data-theme['"`]|dataset\.theme\s*=(?!=)/;
    const writers = walk(SRC)
      .filter((f) => WRITE.test(readFileSync(f, 'utf8')))
      .map((f) => f.slice(SRC.length + 1));
    expect(writers).toEqual(['services/theme.ts']);
  });
});

describe('один записувач теми', () => {
  beforeEach(() => {
    document.documentElement.setAttribute('data-theme', 'night');
  });

  it('setTheme зі старого вибору (ПК › Налаштування) не перефарбовує корінь', async () => {
    await useSettingsStore.getState().setTheme('cyberdeck-cold');
    expect(document.documentElement.getAttribute('data-theme')).toBe('night');
    // Підпис і кеш — ті самі: вибір лишається в сховищі, просто не малює.
    expect(useSettingsStore.getState().values.ui_theme).toBe('cyberdeck-cold');
  });
});

describe('денний блок не перебиває форму', () => {
  const css = dayThemeCss(new Set());

  it('не фарбує body, поля вводу й бульбашки старими числами', () => {
    const offenders: string[] = [];
    postcss.parse(css).walkRules((rule: Rule) => {
      for (const sel of rule.selectors) {
        const subject = subjectOf(sel);
        if (isDocumentSubject(subject) && !/^:root\[data-theme='day'\]$/.test(subject)) offenders.push(sel);
        if (/^(input|textarea)/.test(subject)) offenders.push(sel);
        if (/\.msg-bubble-/.test(subject)) offenders.push(sel);
        if (/::placeholder/.test(subject)) offenders.push(sel);
      }
    });
    expect(offenders).toEqual([]);
  });

  it('діалог — під :where() і токенами, щоб власна поверхня аркуша вигравала', () => {
    const dialog: string[] = [];
    postcss.parse(css).walkRules((rule: Rule) => {
      if (rule.selector.includes('[role="dialog"]')) dialog.push(rule.toString());
    });
    expect(dialog.length).toBe(1);
    expect(dialog[0]).toMatch(/^:where\(/);
    expect(dialog[0]).not.toMatch(/#[0-9A-Fa-f]{3,8}\b|rgba?\(/);
    expect(dialog[0]).toContain('var(--ph-');
  });

  it('корінь дня не перевизначає змінних дзвінка, діаграми й акценту — їх несе --ph-*', () => {
    const root = /:root\[data-theme='day'\] \{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(root).not.toMatch(/--(call|chart|msg-meta|msg-scrim|msg-accent)/);
  });
});

describe('глобальні стилі', () => {
  const globals = read('src/styles/globals.css');

  it('поле без власного класу бере raised/text/line, а не тло браузера — і без перевищеної питомості', () => {
    const rule = /:where\(input\[type=['"]?text['"]?\],\s*input\[type=['"]?search['"]?\],\s*textarea\)\s*\{([^}]*)\}/.exec(globals);
    expect(rule, 'нульової бази полів немає').not.toBeNull();
    expect(rule?.[1]).toContain('var(--ph-raised)');
    expect(rule?.[1]).toContain('var(--ph-text)');
    expect(rule?.[1]).toContain('var(--ph-line)');
    expect(rule?.[1]).not.toContain('!important');
  });

  it('скло без розмиття, хореографії станів більше немає, бурштин — не для смуги прокрутки', () => {
    const css = stripComments(globals);
    expect(css).not.toContain('backdrop-filter');
    expect(css).not.toContain('state-transitioning');
    expect(css).not.toMatch(/@property --accent\b/);
    const thumb = /::-webkit-scrollbar-thumb\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(thumb).toContain('var(--ph-line-strong)');
    const hover = /::-webkit-scrollbar-thumb:hover\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(hover).not.toContain('accent');
  });

  it('на суцільній бурштиновій заливці старий --primary-shadow — чорнило на акценті, а не бурштин', () => {
    // Міст віддає --primary-shadow бурштинові тексту (--ph-accent-text), бо
    // здебільшого він стоїть на полотні. Але PlaceCard, MapRail і TacticalMap
    // кладуть його на bg-amber-500 — там бурштин на бурштині не читається.
    const vars = new Map<string, string>();
    postcss.parse(globals).walkRules((rule: Rule) => {
      if (!rule.selectors.includes('.bg-amber-500')) return;
      rule.walkDecls(/^--/, (d) => {
        vars.set(d.prop, d.value.trim());
      });
    });
    expect(vars.get('--primary-shadow')).toBe('var(--ph-on-accent)');
  });

  it('заслінка старих модалок — --ph-scrim і без розмиття', () => {
    const css = stripComments(messengerHandWritten());
    expect(css).toMatch(/--msg-scrim:\s*var\(--ph-scrim\);/);
    const scrim = /\.phantom-scrim\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(scrim).toContain('var(--msg-scrim)');
    // Розмиття в мові PHANTOM немає — ні під заслінкою, ні деінде в месенджері.
    expect(css).not.toContain('backdrop-filter');
    expect(css).not.toContain('--msg-scrim-blur');
  });
});

describe('Tailwind: старі барви — псевдоніми токенів із прозорістю', () => {
  it('bg-primary/10, text-ink-primary, bg-glass-panel, text-signal-alert ідуть у --ph-*', async () => {
    const path = join(ROOT, 'tailwind.config.ts');
    const { default: config } = await import(/* @vite-ignore */ path);
    const raw =
      '<i class="bg-primary/10 text-ink-primary bg-glass-panel text-signal-alert border-glass-border text-ink-muted"></i>';
    const { css } = await postcss([tailwindcss({ ...config, content: [{ raw }] })]).process('@tailwind utilities;', {
      from: undefined,
    });
    expect(css).toContain('color-mix(in srgb, var(--ph-accent) calc(0.1 * 100%), transparent)');
    expect(css).toMatch(/\.text-ink-primary \{[^}]*color: var\(--ph-text\)/);
    expect(css).toMatch(/\.bg-glass-panel \{[^}]*background-color: var\(--ph-surface\)/);
    expect(css).toMatch(/\.text-signal-alert \{[^}]*color: var\(--ph-danger\)/);
    expect(css).toMatch(/\.border-glass-border \{[^}]*border-color: var\(--ph-line\)/);
    expect(css).toMatch(/\.text-ink-muted \{[^}]*color: var\(--ph-text-3\)/);
  });
});

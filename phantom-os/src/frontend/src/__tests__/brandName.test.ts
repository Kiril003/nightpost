/**
 * Публічне імʼя — Nightpost (docs/brand/BRAND.md §1): слово «PHANTOM» людина не
 * бачить ніде — ні в розмітці, ні в підказках, ні в aria-label, ні в тексті
 * помічника. До збірки 16 у src/ їх було ~150: «Спитати PHANTOM», «Розмова з
 * PHANTOM», заголовок вкладки, `<title>PHANTOM OS</title>`, «Ти PHANTOM —» у
 * приписі моделі.
 *
 * Сторож читає вихідники без коментарів: імена компонентів (PhantomHome),
 * ключі сховищ (`phantom.ui.*`), адреси (`phantom://`, phantom-os.dev) — це код,
 * а не слова для людини, і в них слово стоїть не окремо або малими літерами.
 */
import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { uk } from '../i18n/messages/uk';

const ROOT = join(__dirname, '..', '..');
const OLD = /\bPHANTOM\b|\bPhantom\b|Фантом|ФАНТОМ/;

const walk = (dir: string): string[] =>
  readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return name === '__tests__' ? [] : walk(p);
    return /\.(tsx?|json|css)$/.test(name) && !/\.test\.tsx?$/.test(name) ? [p] : [];
  });

/** Код без коментарів: блокових (і JSX `{/* … *\/}`), рядкових, HTML. */
const code = (src: string): string =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:'"`\\])\/\/.*$/gm, '$1');

describe('імʼя продукту, яке бачить людина', () => {
  it('жоден рядок інтерфейсу, приписів і заставки не каже старого імені', () => {
    const files = [
      ...walk(join(ROOT, 'src')),
      ...walk(join(ROOT, '..', 'shared')),
      join(ROOT, 'index.html'),
      join(ROOT, 'public', 'boot-theme.js'),
      join(ROOT, 'public', 'phantom-icon.svg'),
    ];
    expect(files.length).toBeGreaterThan(300);
    const offenders = files.flatMap((f) =>
      code(readFileSync(f, 'utf8'))
        .split('\n')
        .filter((line) => OLD.test(line))
        .map((line) => `${relative(ROOT, f)}: ${line.trim().slice(0, 100)}`),
    );
    expect(offenders).toEqual([]);
  });

  it('капсула над списком кличе помічника його іменем', () => {
    const list = readFileSync(join(ROOT, 'src/components/messenger/PhoneChatList.tsx'), 'utf8');
    // Рядок живе в каталозі (uk — джерело правди), капсула бере його за ключем.
    expect(list).toContain("t('phoneChatList.searchOrAsk')");
    expect(uk['phoneChatList.searchOrAsk']).toBe('Пошук або спитати Nightpost');
    expect(readFileSync(join(ROOT, 'index.html'), 'utf8')).toContain('<title>Nightpost</title>');
  });

  // Малими літерами старе імʼя сторож вище пропускає свідомо (ключі, адреси),
  // але імʼя файла, збереженого на телефон, людина бачить у «Завантаженнях».
  it('збережений файл без імені не зветься «phantom»', () => {
    const save = code(readFileSync(join(ROOT, 'src/services/saveMedia.ts'), 'utf8'));
    expect(save).not.toMatch(/name \|\| 'phantom'/);
  });

  // Вікно й пакет ПК-застосунку: заголовок вікна, опис у менеджері пакетів,
  // копірайт. `productName`/`publisher` — імена артефактів і шляхів встановлення,
  // їх не чіпаємо (те саме правило, що для пакетів і ключів).
  it('вікно й опис пакета ПК кажуть Nightpost', () => {
    const conf = JSON.parse(readFileSync(join(ROOT, 'src-tauri', 'tauri.conf.json'), 'utf8'));
    const seen = [
      ...conf.app.windows.map((w: { title: string }) => w.title),
      conf.bundle.shortDescription,
      conf.bundle.longDescription,
      conf.bundle.copyright,
    ];
    expect(seen.filter((t: string) => OLD.test(t))).toEqual([]);
  });
});

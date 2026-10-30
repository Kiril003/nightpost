import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import type { PageMeta } from '../i18n/types';
import { useLanguage } from '../i18n';

export const SITE_ORIGIN = 'https://phantom-os.dev';

/** Сервер ліцензій. Запасне значення тут ламалося двічі й по-різному. Спершу
 *  поруч стояв `license.phantom-os.dev`, у якого не було запису DNS. Його
 *  замінили на домен сайту — але домен сайту віддає SPA на КОЖЕН шлях
 *  (`netlify.toml`: `/*` → `/index.html`, 200). Тобто збірка без змінної
 *  оточення діставала на `/api/me` сторінку з кодом 200 і падала на розборі
 *  JSON, показуючи «не вдалося достукатись» там, де хоста просто не питали.
 *  Запасним мусить бути хост, який справді відповідає: `/healthz` на ньому —
 *  це JSON, а не HTML. Перевіряти саме так, а не `getent hosts`. */
export const LICENSE_SERVER = (
  import.meta.env.VITE_LICENSE_SERVER ?? 'https://api.phantom-os.dev'
).replace(/\/+$/, '');

function setTag(selector: string, attr: 'content' | 'href', value: string) {
  document.querySelector(selector)?.setAttribute(attr, value);
}

/**
 * Тримає заголовок, опис і канонічну адресу в парі зі сторінкою й мовою.
 *
 * Канонічна адреса й og:url були зашиті в index.html на корінь: усі дев'ять
 * сторінок казали пошуковикам, що вони — головна, тобто вісім із них не
 * потрапляли в індекс окремо.
 */
export function usePageMeta(meta: PageMeta) {
  const { pathname } = useLocation();
  const { lang } = useLanguage();

  useEffect(() => {
    const url = `${SITE_ORIGIN}${pathname === '/' ? '/' : pathname}`;
    document.title = meta.title;
    setTag('meta[name="description"]', 'content', meta.description);
    setTag('meta[property="og:title"]', 'content', meta.title);
    setTag('meta[property="og:description"]', 'content', meta.description);
    setTag('meta[property="og:url"]', 'content', url);
    setTag('meta[property="og:locale"]', 'content', lang === 'uk' ? 'uk_UA' : 'en_US');
    setTag('meta[property="og:locale:alternate"]', 'content', lang === 'uk' ? 'en_US' : 'uk_UA');
    setTag('link[rel="canonical"]', 'href', url);
  }, [meta, pathname, lang]);
}

// Адреси звернень переїхали в lib/company.ts: одна скринька на демо, рахунки
// й розкриття вразливостей була найпомітнішою ознакою «сайту одного проєкту».

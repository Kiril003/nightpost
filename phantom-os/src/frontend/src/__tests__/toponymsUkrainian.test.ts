/**
 * Українські міста й області звучать в усіх мовах за українською формою, не за російською
 * (Kyiv, Kharkiv, Odesa, Lviv…). Сторож читає всі мовні файли: веб (`messages/**`) і рідні
 * `values*` усіх модулів телефона. Виняток — стандартний екзонім мови, що походить не з
 * російської; кожен із джерелом.
 */
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { join, resolve, basename } from 'node:path';

const L = '\\p{L}';
const RUSSIAN_FORMS: Array<[RegExp, string]> = [
  [/Kiev|Kiew|Kíev|Kijew|Kiyev/, 'Kyiv (de Kyjiw, pl Kijów, cs Kyjev)'],
  [/Kharkov|Charkow|Charkov|Járkov|Harkov/, 'Kharkiv'],
  [/Odess\p{L}*/u, 'Odesa'],
  [/Lvov|Lwow|Lemberg/, 'Lviv (pl Lwów)'],
  [/Nikolaev|Nikolajew|Nikolaiev|Nikolayev/, 'Mykolaiv'],
  [/Zaporozh(?:ye|e|ie)|Saporoschje|Zaporojie/, 'Zaporizhzhia'],
  [/Rovno|Rowno/, 'Rivne'],
  [/Chernigov|Tschernigow/, 'Chernihiv'],
  [/Lugansk/, 'Luhansk'],
  [/Zhitomir|Schitomir|Jitomir/, 'Zhytomyr'],
  [/Dnepr\p{L}*/u, 'Dnipro'],
  [/Kirovograd/, 'Kirovohrad'],
  [/Krivo[yi] Rog/, 'Kryvyi Rih'],
];
const PATTERNS = RUSSIAN_FORMS.map(
  ([re, want]) => [new RegExp(`(?<![${L}])(?:${re.source})(?![${L}])`, 'u'), want] as const,
);

/** Мова → форми, які є її стандартним екзонімом (не з російської), і джерело. */
const ALLOWED: Record<string, Record<string, string>> = {
  pl: { Odessa: 'KSNG, Urzędowe nazwy państw… (2019): Odessa is the official Polish exonym', Odessy: 'Polish genitive of Odessa' },
};

const FRONTEND = resolve(__dirname, '../..');
const MONO = resolve(FRONTEND, '../../..');

function walk(dir: string, keep: (f: string) => boolean): string[] {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return walk(p, keep);
    return keep(p) ? [p] : [];
  });
}

const WEB_LANGS = new Set(['en', 'uk', 'cs', 'es', 'pt-BR', 'de', 'fr', 'pl', 'tr', 'it', 'id']);
function webLang(file: string): string | null {
  const name = basename(file).replace(/\.(json|ts)$/, '');
  if (WEB_LANGS.has(name)) return name;
  const parent = basename(resolve(file, '..'));
  return WEB_LANGS.has(parent) ? parent : null;
}

function nativeLang(file: string): string {
  const dir = basename(resolve(file, '..'));
  const tag = dir === 'values' ? 'en' : dir.replace(/^values-/, '');
  return tag === 'pt-rBR' ? 'pt-BR' : tag === 'in' ? 'id' : tag;
}

function localeFiles(): Array<[string, string]> {
  const web = walk(join(FRONTEND, 'src/i18n/messages'), (f) => /\.(json|ts)$/.test(f))
    .map((f) => [f, webLang(f)] as const)
    .filter((x): x is [string, string] => x[1] !== null);
  const companion = join(MONO, 'phantom-companion');
  const native = existsSync(companion)
    ? readdirSync(companion)
        .map((m) => join(companion, m, 'src/main/res'))
        .filter(existsSync)
        .flatMap((res) => readdirSync(res).filter((d) => /^values(-[a-zA-Z-]+)?$/.test(d)).map((d) => join(res, d)))
        .flatMap((dir) => walk(dir, (f) => f.endsWith('.xml')))
        .map((f) => [f, nativeLang(f)] as [string, string])
    : [];
  return [...web, ...native];
}

function russianForms(text: string, lang: string): string[] {
  const allowed = ALLOWED[lang] ?? {};
  const hits: string[] = [];
  for (const [re, want] of PATTERNS) {
    const global = new RegExp(re.source, 'gu');
    for (const m of text.matchAll(global)) if (!(m[0] in allowed)) hits.push(`${m[0]} → ${want}`);
  }
  return hits;
}

describe('українські топоніми в усіх мовах', () => {
  it('ловить російські форми й лишає українські та стандартні екзоніми', () => {
    expect(russianForms('Región de Kiev', 'es')).toEqual(['Kiev → Kyiv (de Kyjiw, pl Kijów, cs Kyjev)']);
    expect(russianForms('Gebiet Kiew', 'de')).toHaveLength(1);
    expect(russianForms('Odessa', 'fr')).toHaveLength(1);
    expect(russianForms('Harkov · Lugansk', 'tr')).toHaveLength(2);
    expect(russianForms('Kyiv, Kyjiw, Kharkiv, Odesa, Lviv, Luhansk, Dnipro', 'de')).toEqual([]);
    expect(russianForms('Kijów, Lwów, Charków, Odessa', 'pl')).toEqual([]);
    expect(russianForms('Kyjev', 'cs')).toEqual([]);
    expect(russianForms('Kievan', 'en')).toEqual([]);
  });

  it('сторож справді бачить і веб, і рідні мовні файли', () => {
    const files = localeFiles();
    expect(files.filter(([f]) => f.includes('/lazy/')).length).toBeGreaterThanOrEqual(8);
    expect(files.filter(([f]) => f.includes('/res/values-de/')).length).toBeGreaterThan(0);
    expect(files.filter(([f]) => f.endsWith('/res/values-uk/strings.xml')).length).toBeGreaterThan(0);
  });

  it('жоден мовний файл не має російської форми українського міста', () => {
    const offenders = localeFiles().flatMap(([file, lang]) =>
      russianForms(readFileSync(file, 'utf8'), lang).map((hit) => `${file.replace(MONO + '/', '')} [${lang}]: ${hit}`),
    );
    expect(offenders).toEqual([]);
  });
});

/**
 * Сторож CSP вікна ПК (`src-tauri/tauri.conf.json` → app.security.csp).
 *
 * 27.09 власник на Windows: «мапа чорна». CSP вікна пускав мережу лише до
 * вузла на петлі, тож стиль, тайли, спрайти й рельєф мапи відбивались ще в
 * браузері — MapLibre не мав чого малювати. У браузері й телефоні CSP інший,
 * тому жоден тест цього не бачив.
 *
 * Список адрес тут НЕ вписано руками: тест читає код мапи й месенджера і
 * вимагає, щоб кожна мережева адреса з нього проходила `connect-src`. Нова
 * адреса в коді без правки CSP — червоне. І навпаки: у CSP не може бути `*`
 * чи голої схеми (`https:`), які пустили б будь-куди.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { lookupUrl } from '../services/peerDirectory';

const SRC = resolve(__dirname, '..');
const CONF = resolve(SRC, '../src-tauri/tauri.conf.json');

/** Код, чиї адреси вікно ПК справді тягне: мапа, її воркер, вузол месенджера. */
const NEEDS = ['components/map', 'lib/maplibreWorker.ts', 'services/nodeOrigin.ts'];

function readCsp(): Map<string, string[]> {
  const conf = JSON.parse(readFileSync(CONF, 'utf8')) as { app: { security: { csp: string } } };
  const out = new Map<string, string[]>();
  for (const part of conf.app.security.csp.split(';')) {
    const [name, ...sources] = part.trim().split(/\s+/);
    if (name) out.set(name, sources);
  }
  return out;
}

function files(rel: string): string[] {
  const abs = join(SRC, rel);
  if (statSync(abs).isFile()) return [abs];
  return readdirSync(abs, { recursive: true, encoding: 'utf8' })
    .map((f) => join(abs, f))
    .filter((f) => /\.(ts|tsx)$/.test(f) && !/(__tests__|\.test\.)/.test(f));
}

/** Рядкові літерали `http(s)://…` поза коментарями. `{z}` тощо → `0`. */
export function networkAddresses(): string[] {
  const found = new Set<string>();
  for (const rel of NEEDS) {
    for (const file of files(rel)) {
      const code = readFileSync(file, 'utf8')
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .replace(/(^|[^:'"`])\/\/.*$/gm, '$1');
      for (const m of code.matchAll(/['"`](https?:\/\/[^'"`\s$]+)/g)) {
        found.add(m[1].replace(/\{[^}]*\}/g, '0'));
      }
    }
  }
  return [...found].sort();
}

/** Чи пускає джерело CSP цю адресу (схема+хост+порт, шлях — префіксом на «/»). */
function allows(source: string, target: URL): boolean {
  if (!/^[a-z]+:\/\//.test(source)) return false;
  const s = new URL(source);
  if (s.protocol !== target.protocol || s.host !== target.host) return false;
  const path = s.pathname;
  if (path === '/' || path === '') return true;
  return path.endsWith('/') ? target.pathname.startsWith(path) : target.pathname === path;
}

function connectAllows(csp: Map<string, string[]>, url: string): boolean {
  const list = csp.get('connect-src') ?? csp.get('default-src') ?? [];
  const target = new URL(url);
  return list.some((src) => allows(src, target));
}

const stubLocation = (href: string) => {
  const u = new URL(href);
  vi.stubGlobal('location', { href: u.href, protocol: u.protocol, hostname: u.hostname, host: u.host, origin: u.origin });
};

afterEach(() => vi.unstubAllGlobals());

describe('CSP вікна ПК', () => {
  const csp = readCsp();

  it('код мапи і месенджера справді має мережеві адреси (тест не порожній)', () => {
    const urls = networkAddresses();
    expect(urls.some((u) => u.includes('tiles.openfreemap.org'))).toBe(true);
    expect(urls).toContain('http://127.0.0.1:8000');
  });

  it('кожна адреса з коду мапи й вузла проходить connect-src', () => {
    const blocked = networkAddresses().filter((u) => !connectAllows(csp, u));
    expect(blocked).toEqual([]);
  });

  it('вузол месенджера — і HTTP, і WebSocket', () => {
    for (const u of networkAddresses().filter((x) => x.startsWith('http://127.0.0.1'))) {
      expect(connectAllows(csp, u.replace(/^http:/, 'ws:') + '/ws')).toBe(true);
    }
  });

  it('довідник імен у вікні ПК іде через вузол, а не напряму на сервер', () => {
    stubLocation('http://tauri.localhost/messenger');
    const url = lookupUrl('a'.repeat(64));
    expect(url.startsWith('http://127.0.0.1:8000/')).toBe(true);
    expect(connectAllows(csp, url)).toBe(true);
  });

  it('воркер MapLibre: свій файл і blob:', () => {
    const worker =
      csp.get('worker-src') ?? csp.get('child-src') ?? csp.get('script-src') ?? csp.get('default-src') ?? [];
    expect(worker).toContain("'self'");
    expect(worker).toContain('blob:');
  });

  it('жодної зірочки й жодної голої мережевої схеми', () => {
    for (const [name, sources] of csp) {
      for (const src of sources) {
        expect(src.includes('*'), `${name}: ${src}`).toBe(false);
        expect(/^(https?|wss?):$/.test(src), `${name}: ${src}`).toBe(false);
      }
    }
  });
});

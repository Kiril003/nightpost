/**
 * Dev-сервер слухає 0.0.0.0, а /__dev/login віддає бутстрап-PIN. Сусід у
 * мережі не повинен його прочитати — лише браузер на цій машині.
 */
import { describe, it, expect } from 'vitest';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { devLoginPlugin } from '../../devLogin';
import viteConfigSrc from '../../vite.config.ts?raw';

const pinFile = join(mkdtempSync(join(tmpdir(), 'devlogin-')), 'bootstrap_pin');
writeFileSync(pinFile, '424242\n');

type Handler = (req: { socket?: { remoteAddress?: string } }, res: Res) => void;
type Res = { statusCode: number; body: string; setHeader: () => void; end: (b?: string) => void };

function handler(): Handler {
  let h: Handler | undefined;
  devLoginPlugin(pinFile).configureServer({
    middlewares: { use: (_p: string, fn: unknown) => { h = fn as Handler; } },
  });
  if (!h) throw new Error('no dev login middleware');
  return h;
}

function call(h: Handler, remoteAddress: string): Res {
  const res: Res = { statusCode: 200, body: '', setHeader: () => {}, end(b) { this.body = b ?? ''; } };
  h({ socket: { remoteAddress } }, res);
  return res;
}

describe('/__dev/login лише для цієї машини', () => {
  it('сусід у мережі — 404 без PIN', () => {
    const h = handler();
    for (const addr of ['192.168.1.20', '::ffff:10.0.0.7', '172.31.250.1']) {
      const res = call(h, addr);
      expect(res.statusCode).toBe(404);
      expect(res.body).not.toContain('424242');
    }
  });

  it('петля — як і було', () => {
    const h = handler();
    for (const addr of ['127.0.0.1', '::1', '::ffff:127.0.0.1']) {
      expect(JSON.parse(call(h, addr).body)).toEqual({ username: 'phantom', pin: '424242' });
    }
  });

  it('dev-сервер справді бере цей плагін', () => {
    expect(viteConfigSrc).toMatch(/plugins:\s*\[devLoginPlugin\(/);
  });
});

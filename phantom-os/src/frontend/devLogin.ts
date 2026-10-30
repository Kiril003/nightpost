import { readFileSync } from 'node:fs';

export function isLoopbackPeer(addr: string | undefined): boolean {
  if (!addr) return false;
  const a = addr.startsWith('::ffff:') ? addr.slice(7) : addr;
  return a === '::1' || /^127\.\d+\.\d+\.\d+$/.test(a);
}

type Req = { socket?: { remoteAddress?: string } };
type Res = { setHeader: (k: string, v: string) => void; statusCode: number; end: (b?: string) => void };

// Локальний вхід для розробки: PIN читається з диска на боці dev-сервера,
// тож у бандл не потрапляє і в прод-збірці цього маршруту не існує.
export function devLoginPlugin(pinFile: string) {
  return {
    name: 'phantom-dev-login',
    apply: 'serve' as const,
    configureServer(server: { middlewares: { use: (p: string, h: (req: Req, res: Res) => void) => void } }) {
      server.middlewares.use('/__dev/login', (req, res) => {
        res.setHeader('Content-Type', 'application/json');
        // Сервер слухає 0.0.0.0 — PIN віддаємо лише браузеру на цій машині.
        if (!isLoopbackPeer(req.socket?.remoteAddress)) {
          res.statusCode = 404;
          res.end('{}');
          return;
        }
        try {
          const pin = readFileSync(pinFile, 'utf8').trim();
          res.end(JSON.stringify({ username: 'phantom', pin }));
        } catch {
          res.statusCode = 404;
          res.end('{}');
        }
      });
    },
  };
}

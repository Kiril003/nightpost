#!/usr/bin/env node
/**
 * Скільки живих ніг тримає апарат — заміряно, а не вгадано.
 *
 * ЩО ЦЕ МІРЯЄ. Нога — це одне з'єднання з одним співрозмовником: власний
 * `RTCPeerConnection`, власний кодер Opus, власний декодер, власний
 * DTLS/ICE-контекст. Повна сітка на N учасників дає (N-1) ніг НА КОЖНОМУ
 * апараті. Прилад підіймає ноги по одній і після кожної питає рушій три речі:
 *
 *   1. скільки БІТ НА ДРОТІ коштує нога — `bytesSent`, тобто голос РАЗОМ із
 *      заголовками RTP/UDP/IP і міткою SRTP. Це те число, яке треба ділити на
 *      аплінк; «64 кбіт/с» із драбини — лише половина правди;
 *   2. чи виходять пакети вчасно — `packetsSent` проти того, скільки їх МАЄ
 *      бути за ptime сходинки. Кодер, який не встигає, мовчки шле рідше, і
 *      саме це чути як «розсипається»;
 *   3. чи не став головний потік липким — тики по 20 мс, скільки з них
 *      спізнились удвічі. Рушій дзвінка живе в тому самому потоці, що й екран.
 *
 * ЧОМУ НЕ ВІКНО. Попередня спроба міряла у вкладці й отримала нуль: Chromium
 * душить таймери і `getStats` у вкладці, яку не видно. Тому тут — свій
 * Chromium без вікна і БЕЗ ДУШІННЯ (`--disable-background-timer-throttling`,
 * `--disable-renderer-backgrounding`, `--disable-backgrounding-occluded-windows`),
 * сторінка з локального сервера, відповідь назад по HTTP. Жодної залежності
 * з npm: у дереві телефона їх немає й бути не повинно.
 *
 * ЧОГО ЦЕ НЕ МІРЯЄ — і це головне обмеження, яке не можна проковтнути.
 * Прилад тримає ОБИДВА кінці кожної ноги в одному процесі, тож стенд несе
 * РІВНО ВДВІЧІ більше, ніж ніс би телефон на тій самій кількості ніг
 * (2 кодери + 2 декодери на ногу замість одного й одного). І стенд — не
 * телефон власника: x86 проти ARM, розетка проти акумулятора, петля
 * 127.0.0.1 проти мобільного аплінка. Число «біт на дроті» переноситься на
 * телефон чесно (це протокол, не процесор). Число «скільки ніг тримає
 * процесор» — НЕ переноситься, і в `docs/MEETS_SCALE.md` воно так і названо.
 *
 * Запуск:
 *   node scripts/measure-mesh-legs.mjs                 # усі три сходинки
 *   node scripts/measure-mesh-legs.mjs --legs 16       # інша стеля
 *   node scripts/measure-mesh-legs.mjs --hold 8000     # довше тримати
 *   node scripts/measure-mesh-legs.mjs --json out.json # зберегти сирі числа
 */

import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync, readdirSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { existsSync } from 'node:fs';

/* ── сходинки драбини ─────────────────────────────────────────────────────
 * Дзеркало `src/frontend/src/services/callOpus.ts` (AUDIO_PROFILES) із
 * веб-дерева. Дзеркало, а не імпорт: дерева окремі, і прилад мусить бігати
 * без веб-збірки. Розбіжність тут = вимір не того, що їде на телефон, тож
 * будь-яка зміна драбини мусить приїхати і сюди.
 */
const RUNGS = [
  { name: 'full', bitrate: 64000, ptime: 20, maxptime: 60, cbr: false },
  { name: 'thrifty', bitrate: 24000, ptime: 60, maxptime: 60, cbr: false },
  { name: 'narrow', bitrate: 10000, ptime: 120, maxptime: 120, cbr: true },
];

const arg = (flag, fallback) => {
  const i = process.argv.indexOf(flag);
  return i < 0 || i + 1 >= process.argv.length ? fallback : process.argv[i + 1];
};

const MAX_LEGS = Number(arg('--legs', '12'));
const HOLD_MS = Number(arg('--hold', '6000'));
const SETTLE_MS = Number(arg('--settle', '2500'));
const JSON_OUT = arg('--json', null);

const BROWSERS = [
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/usr/bin/google-chrome',
  '/usr/bin/google-chrome-stable',
];

let cpuTrail = [];

const browser = BROWSERS.find((p) => existsSync(p));
if (!browser) {
  console.error('немає Chromium: шукав ' + BROWSERS.join(', '));
  process.exit(2);
}

/* ── сторінка ─────────────────────────────────────────────────────────────
 * Живе рядком, а не окремим файлом: прилад має бути ОДНИМ файлом, який можна
 * запустити з чистого дерева. Другий файл поруч розійшовся б із цим на
 * першій же правці.
 */
const PAGE = `<!doctype html><meta charset="utf-8"><title>mesh legs</title><body>
<script>
const RUNGS = ${JSON.stringify(RUNGS)};
const MAX_LEGS = ${MAX_LEGS}, HOLD_MS = ${HOLD_MS}, SETTLE_MS = ${SETTLE_MS};

/* Звук мусить ШУМІТИ. Opus із usedtx=1 кодує тишу майже задарма, тож на
   мовчазному джерелі прилад заміряв би вартість мовчання, а не розмови. */
function noiseTrack(ctx) {
  const buf = ctx.createBuffer(1, ctx.sampleRate * 3, ctx.sampleRate);
  const d = buf.getChannelData(0);
  // Рожевуватий шум замість білого: спектр ближчий до голосу, і кодер не
  // отримує задачу, легшу за справжню.
  let b0 = 0, b1 = 0, b2 = 0;
  for (let i = 0; i < d.length; i++) {
    const w = Math.random() * 2 - 1;
    b0 = 0.99765 * b0 + w * 0.0990460;
    b1 = 0.96300 * b1 + w * 0.2965164;
    b2 = 0.57000 * b2 + w * 1.0526913;
    d[i] = (b0 + b1 + b2 + w * 0.1848) * 0.12;
  }
  const src = ctx.createBufferSource();
  src.buffer = buf; src.loop = true;
  const dst = ctx.createMediaStreamDestination();
  src.connect(dst); src.start();
  return dst.stream.getAudioTracks()[0];
}

/* Той самий мунгінг SDP, що й у рушієві (callOpus.tuneOpus): без нього
   ptime лишається браузерним 20 мс і нижні сходинки не виміряні, а вигадані. */
function tuneOpus(sdp, rung) {
  const lines = sdp.split(/\\r\\n|\\n/);
  const eol = sdp.includes('\\r\\n') ? '\\r\\n' : '\\n';
  let pt = null;
  for (const l of lines) { const m = /^a=rtpmap:(\\d+)\\s+opus\\/48000/i.exec(l); if (m) { pt = m[1]; break; } }
  if (!pt) return sdp;
  const want = 'useinbandfec=1;usedtx=1;stereo=0;sprop-stereo=0'
    + ';maxaveragebitrate=' + rung.bitrate
    + ';maxptime=' + rung.maxptime + ';ptime=' + rung.ptime
    + ';cbr=' + (rung.cbr ? 1 : 0);
  const prefix = 'a=fmtp:' + pt + ' ';
  const hasFmtp = lines.some(l => l.startsWith(prefix));
  const out = [];
  for (const line of lines) {
    if (line.startsWith(prefix)) {
      out.push(prefix + want, 'a=ptime:' + rung.ptime, 'a=maxptime:' + rung.maxptime);
      continue;
    }
    if (/^a=(ptime|maxptime):/i.test(line)) continue;
    out.push(line);
    if (!hasFmtp && /^a=rtpmap:\\d+\\s+opus\\/48000/i.test(line)) {
      out.push(prefix + want, 'a=ptime:' + rung.ptime, 'a=maxptime:' + rung.maxptime);
    }
  }
  return out.join(eol);
}

/* Одна нога = два кінці. Обидва шлють звук, як і в справжній розмові:
   нога, де говорить лише один, коштувала б половину. */
async function leg(ctx, rung) {
  const a = new RTCPeerConnection(), b = new RTCPeerConnection();
  a.onicecandidate = e => e.candidate && b.addIceCandidate(e.candidate).catch(()=>{});
  b.onicecandidate = e => e.candidate && a.addIceCandidate(e.candidate).catch(()=>{});
  a.addTrack(noiseTrack(ctx));
  b.addTrack(noiseTrack(ctx));
  const o = await a.createOffer();
  await a.setLocalDescription({ type: 'offer', sdp: tuneOpus(o.sdp, rung) });
  await b.setRemoteDescription(a.localDescription);
  const ans = await b.createAnswer();
  await b.setLocalDescription({ type: 'answer', sdp: tuneOpus(ans.sdp, rung) });
  await a.setRemoteDescription(b.localDescription);
  for (const pc of [a, b]) {
    const s = pc.getSenders().find(x => x.track && x.track.kind === 'audio');
    const p = s.getParameters();
    if (!p.encodings || !p.encodings.length) p.encodings = [{}];
    p.encodings[0].maxBitrate = rung.bitrate;
    p.encodings[0].ptime = rung.ptime;
    await s.setParameters(p);
  }
  await new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('ICE не встав за 10 с')), 10000);
    const check = () => {
      if (a.iceConnectionState === 'connected' || a.iceConnectionState === 'completed') {
        clearTimeout(t); res();
      }
    };
    a.oniceconnectionstatechange = check; check();
  });
  return { a, b };
}

/* Сирі лічильники ОДНОГО кінця.

   Три числа, а не одне, бо «біт на дроті» — це сума трьох різних речей, і
   рушій жодного разу не віддає її готовою:
     bytesSent       — тільки корисне навантаження (голос + мітка SRTP);
     headerBytesSent — заголовок RTP і набивка, окремим лічильником;
     а IP і UDP рушій не рахує взагалі — їх немає в його світі. Це рівно
     28 байтів на пакет для IPv4 (20 + 8), і вони так само їдуть каналом,
     тож додаються ТУТ і названі вголос, а не тихо загорнуті в «виміряно». */
const IP_UDP_BYTES = 28;

async function tally(pc) {
  let packets = 0, payload = 0, header = 0;
  (await pc.getStats()).forEach(r => {
    if (r.type === 'outbound-rtp' && r.kind === 'audio') {
      packets += r.packetsSent || 0;
      payload += r.bytesSent || 0;
      header += r.headerBytesSent || 0;
    }
  });
  return { packets, payload, header, wire: payload + header + packets * IP_UDP_BYTES };
}

async function measure(rung) {
  const ctx = new AudioContext();
  const legs = [], rows = [];
  const wantPps = 1000 / rung.ptime;      // ptime 20 мс -> 50 пакетів/с
  for (let n = 1; n <= MAX_LEGS; n++) {
    legs.push(await leg(ctx, rung));
    await new Promise(r => setTimeout(r, SETTLE_MS));

    const before = await Promise.all(legs.map(l => tally(l.a)));
    const t0 = performance.now(), wall0 = Date.now();
    let late = 0, ticks = 0, last = performance.now();
    const id = setInterval(() => {
      const now = performance.now(); ticks++;
      if (now - last > 40) late++;
      last = now;
    }, 20);
    await new Promise(r => setTimeout(r, HOLD_MS));
    clearInterval(id);
    const dt = (performance.now() - t0) / 1000;
    const after = await Promise.all(legs.map(l => tally(l.a)));

    const pps = after.map((v, i) => (v.packets - before[i].packets) / dt);
    const wire = after.map((v, i) => (v.wire - before[i].wire) * 8 / dt);
    const voice = after.map((v, i) => (v.payload - before[i].payload) * 8 / dt);
    const mean = xs => xs.reduce((x, y) => x + y, 0) / xs.length;
    rows.push({
      rung: rung.name,
      legs: n,
      wantPps: +wantPps.toFixed(2),
      meanPps: +mean(pps).toFixed(2),
      worstPps: +Math.min(...pps).toFixed(2),
      keptPct: +(100 * mean(pps) / wantPps).toFixed(1),
      voiceBpsPerLeg: Math.round(mean(voice)),
      wireBpsPerLeg: Math.round(mean(wire)),
      wireBpsTotal: Math.round(wire.reduce((x, y) => x + y, 0)),
      lateTicksPct: +(100 * late / Math.max(1, ticks)).toFixed(1),
      // Межі вікна за стінним годинником: процесор рахує node зовні, і без
      // цих двох чисел він не знав би, ЯКОМУ саме вікну приписати такти.
      wall0,
      wall1: Date.now(),
    });
  }
  legs.forEach(l => { l.a.close(); l.b.close(); });
  await ctx.close();
  return rows;
}

(async () => {
  const all = [];
  try {
    for (const rung of RUNGS) all.push(...await measure(rung));
    await fetch('/result', { method: 'POST', body: JSON.stringify({ ok: true, rows: all }) });
  } catch (e) {
    await fetch('/result', { method: 'POST', body: JSON.stringify({ ok: false, error: String(e && e.stack || e), rows: all }) });
  }
})();
</script></body>`;

/* ── процесор ─────────────────────────────────────────────────────────────
 * Скільки ядер з'їдає стенд. Читається з /proc: сума utime+stime УСІХ
 * процесів дерева chromium (рушій медіа живе в окремому процесі, і рахувати
 * лише батька означало б показати нуль).
 *
 * Одиниця — ядра: 1.0 це одне ядро, повністю зайняте. Число стенда, не
 * телефона: переносити його на ARM без виміру на ARM не можна, і документ
 * цього не робить.
 */
const HZ = 100; // USER_HZ у Linux; getconf CLK_TCK на цій машині
const cpuTicks = (root) => {
  // Спершу ВСЯ мапа (pid → ppid, такти), і лише потім обхід дерева: порядок
  // у /proc не гарантує, що дитина трапиться після батька, а «майже завжди»
  // тут означало б лічильник, який іноді недоліковує процес медіа.
  const kids = new Map();
  const own = new Map();
  try {
    for (const name of readdirSync('/proc')) {
      if (!/^\d+$/.test(name)) continue;
      let stat;
      try { stat = readFileSync(`/proc/${name}/stat`, 'utf8'); } catch { continue; }
      const rest = stat.slice(stat.lastIndexOf(')') + 2).split(' ');
      const pid = Number(name), ppid = Number(rest[1]);
      own.set(pid, Number(rest[11]) + Number(rest[12]));
      if (!kids.has(ppid)) kids.set(ppid, []);
      kids.get(ppid).push(pid);
    }
  } catch { return null; }
  if (!own.has(root)) return null;
  let total = 0;
  const stack = [root];
  const seen = new Set();
  while (stack.length) {
    const pid = stack.pop();
    if (seen.has(pid)) continue;
    seen.add(pid);
    total += own.get(pid) ?? 0;
    for (const k of kids.get(pid) ?? []) stack.push(k);
  }
  return total;
};

/* ── прогін ───────────────────────────────────────────────────────────── */

const done = new Promise((resolve) => {
  const server = createServer((req, res) => {
    if (req.method === 'POST' && req.url === '/result') {
      let body = '';
      req.on('data', (c) => { body += c; });
      req.on('end', () => {
        res.writeHead(204).end();
        server.close();
        resolve(JSON.parse(body));
      });
      return;
    }
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }).end(PAGE);
  });
  server.listen(0, '127.0.0.1', () => {
    const port = server.address().port;
    const profile = mkdtempSync(join(tmpdir(), 'mesh-legs-'));
    const child = spawn(browser, [
      '--headless=new',
      '--no-sandbox',
      '--disable-gpu',
      // ОСЬ ЧОМУ ЦЕЙ ПРИЛАД БАЧИТЬ ТЕ, ЧОГО НЕ БАЧИЛА ВКЛАДКА:
      '--disable-background-timer-throttling',
      '--disable-renderer-backgrounding',
      '--disable-backgrounding-occluded-windows',
      '--autoplay-policy=no-user-gesture-required',
      '--use-fake-device-for-media-stream',
      '--use-fake-ui-for-media-stream',
      `--user-data-dir=${profile}`,
      `http://127.0.0.1:${port}/`,
    ], { stdio: ['ignore', 'ignore', 'pipe'] });
    child.stderr.on('data', (c) => {
      const s = String(c);
      if (/ERROR|FATAL/.test(s)) process.stderr.write(s);
    });
    // Доріжка процесора: пара (стінний час, такти) кожні пів секунди.
    const trail = [];
    const cpuTimer = setInterval(() => {
      const t = cpuTicks(child.pid);
      if (t !== null) trail.push([Date.now(), t]);
    }, 500);
    cpuTrail = trail;
    const kill = () => {
      clearInterval(cpuTimer); try { child.kill('SIGKILL'); } catch { /* уже мертвий */ } rmSync(profile, { recursive: true, force: true }); };
    done.then(kill, kill);
    // Сторож: прилад, який зависнув, мусить упасть, а не вдавати, що міряє.
    const budget = (SETTLE_MS + HOLD_MS + 2500) * MAX_LEGS * RUNGS.length + 30000;
    setTimeout(() => {
      kill();
      resolve({ ok: false, error: `не вклався у ${Math.round(budget / 1000)} с`, rows: [] });
      try { server.close(); } catch { /* вже закритий */ }
    }, budget).unref();
  });
});

const result = await done;

/** Скільки ядер з'їв стенд у вікні [a, b] за стінним годинником. */
const coresIn = (a, b) => {
  const at = (t) => {
    let prev = null;
    for (const p of cpuTrail) {
      if (p[0] >= t) {
        if (!prev) return p[1];
        const k = (t - prev[0]) / (p[0] - prev[0]);
        return prev[1] + k * (p[1] - prev[1]);
      }
      prev = p;
    }
    return prev ? prev[1] : null;
  };
  const x = at(a), y = at(b);
  if (x === null || y === null || b <= a) return null;
  return ((y - x) / HZ) / ((b - a) / 1000);
};

for (const r of result.rows) {
  if (r.wall0 && r.wall1) {
    const cores = coresIn(r.wall0, r.wall1);
    if (cores !== null) {
      r.cores = +cores.toFixed(2);
      // Стенд тримає ОБИДВА кінці кожної ноги; телефон нестиме половину.
      r.coresPerLegPhone = +(cores / (2 * r.legs)).toFixed(4);
    }
  }
}

if (JSON_OUT) writeFileSync(JSON_OUT, JSON.stringify(result, null, 2));

const pad = (s, n) => String(s).padStart(n);
console.log(`Chromium: ${browser}`);
console.log(`стенд: ${process.platform} ${process.arch}, ядер ${(await import('node:os')).cpus().length}`);
console.log('');
console.log('сходинка ноги хочем/с вийшло/с тримає% голос·нога дріт·нога разом(вгору) спізн.% ядра ядро/ногу(тел.)');
for (const r of result.rows) {
  console.log(
    pad(r.rung, 8), pad(r.legs, 5), pad(r.wantPps, 7), pad(r.meanPps, 9), pad(r.keptPct, 8),
    pad((r.voiceBpsPerLeg / 1000).toFixed(1), 11),
    pad((r.wireBpsPerLeg / 1000).toFixed(1), 10),
    pad((r.wireBpsTotal / 1000).toFixed(1), 13),
    pad(r.lateTicksPct, 8),
    pad(r.cores ?? '—', 5),
    pad(r.coresPerLegPhone ?? '—', 16),
  );
}
console.log('\nкбіт/с. «дріт» = голос + SRTP + заголовок RTP + 28 Б IPv4/UDP на пакет.');
console.log('«разом(вгору)» — що виходить із ОДНОГО апарата, тобто те, що ділить аплінк.');
console.log('«ядра» — стенд, що тримає ОБИДВА кінці; «ядро/ногу(тел.)» — половина цього, на ногу.');
if (!result.ok) {
  console.error('\nПРИЛАД НЕ ДОБІГ: ' + result.error);
  process.exit(1);
}

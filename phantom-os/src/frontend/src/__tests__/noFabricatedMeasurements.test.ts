/**
 * Вимір не пишеться літералом і не кидається кубиком.
 *
 * Клас вади, який цей сторож закриває: поле, назва якого обіцяє СПОСТЕРЕЖЕННЯ
 * за залізом чи мережею, отримує число, вписане в код або згенероване
 * `Math.random()`. На екрані таке число виглядає точнісінько як зняте
 * приладом — тим самим шрифтом, у тій самій клітинці, — і людина не має
 * жодного способу відрізнити. Виміряно 06.09 на цьому дереві:
 *
 *   meshStore                  — три вузли з пінгом 2/18/24 мс, LoRa 868.1 МГц,
 *                                SNR 9.2 дБ, RSSI -74 dBm, 38/142 пакети;
 *   NodeDashboardModal         — CPU 18%, RAM 2.4/8.0 ГБ, диск 42.1/128.0 ГБ,
 *                                аптайм 74 год., і `Math.random()` кожні 2 с;
 *   IoTEqsTelemetryModal       — 41.2 °C, 5.04 V, 24 пакети/с, RTT 6 мс;
 *   HeadlessInfrastructure     — три тунелі WireGuard із затримкою 8/14/22 мс;
 *   AgenticWorkspaceVirt.      — три машини з CPU 24/78/12% і зарядом 42%;
 *   MessengerRoot              — `?? (isWsConnected ? 12 : null)` у шапці.
 *
 * ТРИ ПРАВИЛА, і кожне звужене так, щоб не ловити налаштування:
 *
 *   A. Числовий літерал у полі виміру:  `pingMs: 18`
 *   B. `Math.random()` у полі виміру:   `tempC: +(40.5 + Math.random() * 2)`
 *   C. Числовий запасний варіант:       `latencyMs ?? 12`
 *      Найпідступніша форма: справжнє джерело є, але коли воно мовчить, код
 *      підставляє красиве число замість того, щоб не показати нічого.
 *
 * ЧОМУ НУЛЬ ДОЗВОЛЕНО. `0` нічого не стверджує — це загальноприйняте «ще не
 * питали» (див. `messengerInitialData.batteryLevel`). Вигадка завжди
 * ПРАВДОПОДІБНА: 41.2 °C, -74 dBm, 868.1 МГц. Забороняти нуль означало б
 * тертя без користі для чесності.
 *
 * ЩО СЮДИ СВІДОМО НЕ ПОТРАПИЛО, і чому — межа сторожа має бути названа, а не
 * схована. `durationMs` двозначне: у `FamiliarReactor` це РОЗКЛАД («тримай
 * стан 4200 мс»), а не спостереження, і таких місць десять. Ловити обидва
 * значення одним іменем не можна, а список винятків згнив би у схованку. Тож
 * `durationMs` у словнику немає — свідома діра, не недогляд.
 *
 * ЦЕЙ СТОРОЖ УМІЄ ЧЕРВОНІТИ. Перевірено 06.09: із порожнім списком щойно
 * прибраного він дає 27 влучань у 5 файлах (див. звіт хвилі). Щоб побачити
 * самому — допишіть у будь-який компонент `cpuUsage: 42` або
 * `latencyMs ?? 12`, і тест упаде з іменем файла й номером рядка.
 */
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

/** vitest ходить із кореня фронтенду — звідти й починаємо обхід. */
const ROOT = join(process.cwd(), 'src');

/**
 * Словник СПОСТЕРЕЖЕНЬ за залізом і мережею — того, що застосунок може лише
 * зміряти, але не вирішити. Налаштувань тут немає навмисне: `timeoutMs`,
 * `intervalMs`, `maxTokens` — це воля розробника, і літерал у них доречний.
 */
const MEASUREMENT_FIELDS = [
  // мережа
  'latencyMs', 'latencyAvgMs', 'pingMs', 'lastPingMs', 'rttMs', 'jitterMs', 'ttftMs',
  'packetLoss', 'packetLossPct', 'packetsSent', 'packetsReceived', 'packetsSec',
  'hops', 'hopCount', 'dtnHopCount', 'peersCount', 'p2pPeersCount', 'connectedClientsCount',
  'bandwidthMbps', 'throughputMbps', 'uploadSpeedMbps', 'downloadSpeedMbps',
  // радіо
  'rssi', 'rssiDbm', 'snr', 'snrDb', 'signalDbm', 'loraSignalDbm', 'frequencyMhz',
  // процесор, памʼять, диск
  'cpuUsage', 'cpuLoad', 'cpuPct', 'gpuLoad', 'gpuLoadPct',
  'ramUsedGb', 'ramUsedMb', 'ramTotalGb', 'ramTotalMb',
  'vramUsedMb', 'vramTotalMb', 'memoryUsage', 'memoryUsedMb', 'memoryUsedMB',
  'diskUsedGb', 'diskTotalGb', 'storageReadSpeedMb',
  'uptimeHours', 'uptimeSeconds', 'uptimeDays', 'tokensPerSec', 'hashRate',
  // фізика плати
  'tempC', 'tempCelsius', 'gpuTempCelsius', 'voltageV', 'currentA', 'powerW',
  'batteryLevel', 'batteryPct',
  // рій
  'seedersCount', 'leechersCount', 'computeShares',
];

const FIELD = `(?:${MEASUREMENT_FIELDS.join('|')})`;

/**
 * A. `поле: <число>` — але не `: 0`. Дужка й знак попереду теж рахуються:
 *    `tempC: +(40.5 ...)`, `rssiDbm: -74`.
 */
const LITERAL = new RegExp(`\\b${FIELD}\\s*:\\s*[+-]?\\(?\\s*-?(?!0\\b)\\d`);

/** B. `поле: ... Math.random() ...` — кубик під іменем приладу. */
const RANDOM_IN_FIELD = new RegExp(`\\b${FIELD}\\s*:.*Math\\.random`);

/** C. `поле ?? <число>` — вигаданий запасний варіант замість порожнечі. */
const NUMERIC_FALLBACK = new RegExp(`\\b${FIELD}\\b[^:;]{0,80}?\\?\\?\\s*\\(?\\s*-?(?!0\\b)\\d`);

/** D. Функція, названа виміром, чиї числа дає `Math.random()`. */
const DIAGNOSTIC_NAME = /\b(?:const|let|var|function|async)\s+(\w*(?:iagnost|elemetr|easure|enchmark)\w*)\b/;

const walk = (dir: string): string[] => {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (name === '__tests__' || name === 'node_modules') continue;
      out.push(...walk(p));
      continue;
    }
    if (!/\.(ts|tsx)$/.test(name)) continue;
    if (/\.(test|spec)\.tsx?$/.test(name)) continue;
    out.push(p);
  }
  return out;
};

/** Коментар — не код: саме в коментарях і описано, що тут колись малювали. */
const isComment = (line: string): boolean => {
  const t = line.trim();
  return t.startsWith('//') || t.startsWith('*') || t.startsWith('/*');
};

const stripComment = (line: string): string => line.replace(/\/\/.*$/, '');

describe('жодного вигаданого виміру в src/', () => {
  const files = walk(ROOT);

  // Файли справді прочитано — інакше «нуль порушень» означав би нуль файлів.
  // Саме так сторож і вміє бути зеленим, нічого не побачивши.
  it('обхід дійсно бачить дерево', () => {
    expect(files.length).toBeGreaterThan(200);
  });

  it('поле виміру ніде не отримує вписане число або Math.random()', () => {
    const hits: string[] = [];
    for (const file of files) {
      readFileSync(file, 'utf8')
        .split('\n')
        .forEach((line, i) => {
          if (isComment(line)) return;
          const code = stripComment(line);
          if (LITERAL.test(code) || RANDOM_IN_FIELD.test(code) || NUMERIC_FALLBACK.test(code)) {
            hits.push(`${relative(ROOT, file)}:${i + 1}: ${line.trim()}`);
          }
        });
    }
    expect(hits).toEqual([]);
  });

  it('функція з іменем приладу не бере чисел із Math.random()', () => {
    const hits: string[] = [];
    for (const file of files) {
      const lines = readFileSync(file, 'utf8').split('\n');
      lines.forEach((line, i) => {
        if (isComment(line)) return;
        const declared = DIAGNOSTIC_NAME.exec(stripComment(line));
        if (!declared) return;
        // Тіло: від оголошення до кінця блоку за балансом дужок.
        let depth = 0;
        let seen = false;
        for (let j = i; j < lines.length; j++) {
          const body = stripComment(lines[j]);
          if (!isComment(lines[j]) && /Math\.random/.test(body) && seen) {
            hits.push(`${relative(ROOT, file)}:${j + 1}: ${declared[1]} → ${lines[j].trim()}`);
            break;
          }
          for (const ch of body) {
            if (ch === '{') { depth++; seen = true; }
            if (ch === '}') depth--;
          }
          if (seen && depth <= 0) break;
        }
      });
    }
    expect(hits).toEqual([]);
  });
});

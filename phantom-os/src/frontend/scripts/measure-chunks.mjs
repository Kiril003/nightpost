/**
 * Прилад для ваги бандла. Існує тому, що `vite build` друкує лише розмір
 * ЧАНКА — а «чому він такий» у тому друці немає, і числа доводилось вигадувати.
 *
 * Тут rollup сам каже, з яких модулів чанк складено (`chunk.modules`,
 * `renderedLength` — байти ПІСЛЯ мінізації, до gzip), і ми зводимо це в
 * таблицю: чанк → 25 найважчих модулів усередині.
 *
 *   node scripts/measure-chunks.mjs phone   # збірка під телефон
 *   node scripts/measure-chunks.mjs         # збірка під ПК
 *
 * Друкує JSON у stdout і людську таблицю у stderr, щоб число можна було
 * і прочитати очима, і зіставити машиною до/після.
 */
import { build } from 'vite';
import { gzipSync } from 'node:zlib';

const phone = process.argv[2] === 'phone';
const TOP = Number(process.env.TOP || 25);

/** Зібране з rollup: чанк → модулі з вагою. Пишемо в замикання, не у файл. */
const report = { chunks: [] };

const probe = {
  name: 'phantom-chunk-probe',
  generateBundle(_options, bundle) {
    for (const [fileName, out] of Object.entries(bundle)) {
      if (out.type !== 'chunk') continue;
      const modules = Object.entries(out.modules || {})
        .map(([id, m]) => ({ id: id.replace(process.cwd() + '/', ''), bytes: m.renderedLength }))
        .filter((m) => m.bytes > 0)
        .sort((a, b) => b.bytes - a.bytes);
      report.chunks.push({
        fileName,
        isEntry: out.isEntry === true,
        isDynamicEntry: out.isDynamicEntry === true,
        bytes: Buffer.byteLength(out.code),
        gzip: gzipSync(Buffer.from(out.code)).length,
        moduleCount: modules.length,
        modules: modules.slice(0, TOP),
      });
    }
  },
};

await build({
  mode: phone ? 'phone' : undefined,
  logLevel: 'warn',
  plugins: [probe],
});

report.chunks.sort((a, b) => b.bytes - a.bytes);
report.total = report.chunks.reduce((n, c) => n + c.bytes, 0);
report.totalGzip = report.chunks.reduce((n, c) => n + c.gzip, 0);

const kb = (n) => `${(n / 1024).toFixed(1)} КБ`;
const lines = [`ЦІЛЬ: ${phone ? 'phone' : 'pc'}   JS усього ${kb(report.total)} (gzip ${kb(report.totalGzip)})`];
for (const c of report.chunks) {
  lines.push(`\n${c.fileName}  ${kb(c.bytes)} (gzip ${kb(c.gzip)})  модулів: ${c.moduleCount}`);
  for (const m of c.modules) lines.push(`    ${kb(m.bytes).padStart(10)}  ${m.id}`);
}
process.stderr.write(lines.join('\n') + '\n');
process.stdout.write(JSON.stringify(report, null, 1));

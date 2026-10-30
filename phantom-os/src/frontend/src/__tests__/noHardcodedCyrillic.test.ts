/**
 * Every word a person reads comes from the catalogue (src/i18n), never from a
 * Cyrillic literal in a component: owner 29.10 saw Ukrainian on an English
 * phone. String literals, template parts and JSX text are read from the
 * TypeScript AST, so comments and regex literals never count. Console output
 * is for developers and is skipped.
 *
 * DATA lists files whose Cyrillic is input vocabulary, not copy (each with its
 * reason). A single literal of that kind (a wire marker, a typed word the code
 * recognises) carries `// i18n-data: <reason>` on its own line instead. LEDGER is the ratchet: a file may only lose literals; any file not
 * in it must have none.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import { CYRILLIC_LEDGER } from './cyrillicLedger';

const SRC = resolve(__dirname, '..');
const CYRILLIC = /[\u0400-\u04FF]/;
const DATA_MARK = /\/\/ i18n-data: .{10,}/;

export const DATA: Record<string, string> = {
  'components/messenger/glyphs/glyphData.ts': 'glyph names stored per locale (uk/en), picked by reactionGlyphs',
  'components/messenger/ReactionPickerModal.tsx': 'emoji search keywords, uk + en side by side',
  'services/translit.ts': 'Cyrillic ↔ Latin transliteration table for map search',
  'services/understand.ts': 'parses dates and days typed in Ukrainian',
  'services/signalFold.ts': 'recognises short acknowledgements typed in Ukrainian',
  'services/whereAsk.ts': 'recognises «де ти» questions typed in Ukrainian',
  'services/messageSearch.ts': 'Cyrillic/Latin look-alike letters for search folding',
  'services/moneyWords.ts': 'currency words typed in Ukrainian that ringGuard recognises as an amount',
  'services/callReasons.ts': 'call-engine reason codes parsed by callWords, never rendered raw',
  'styles/dayTheme.ts': 'markers inside a generated CSS file, never rendered',
};

// LLM system prompt, not UI.
export const PROMPTS: readonly string[] = ['services/askAbout.ts'];

function sourceFiles(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name !== 'i18n' && e.name !== '__tests__') sourceFiles(p, out);
    } else if (/\.tsx?$/.test(e.name) && !/\.test\.|\.d\.ts$/.test(e.name)) out.push(p);
  }
  return out;
}

const isConsoleCall = (n: ts.Node) =>
  ts.isCallExpression(n) &&
  ts.isPropertyAccessExpression(n.expression) &&
  ts.isIdentifier(n.expression.expression) &&
  n.expression.expression.text === 'console';

export function cyrillicLiterals(file: string): string[] {
  const text = readFileSync(file, 'utf8');
  if (!CYRILLIC.test(text)) return [];
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true,
    file.endsWith('x') ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
  const found: string[] = [];
  const lines = text.split('\n');
  const visit = (n: ts.Node): void => {
    if (isConsoleCall(n)) return;
    let t: string | undefined;
    if (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n) || ts.isTemplateLiteralToken(n) || ts.isJsxText(n)) t = n.text;
    if (t && CYRILLIC.test(t)) {
      const line = sf.getLineAndCharacterOfPosition(n.getStart()).line + 1;
      if (!DATA_MARK.test(lines[line - 1])) found.push(`${line}: ${t.trim().slice(0, 60)}`);
    }
    ts.forEachChild(n, visit);
  };
  visit(sf);
  return found;
}

export function scanCyrillic(): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const f of sourceFiles(SRC)) {
    const rel = relative(SRC, f).split('\\').join('/');
    if (DATA[rel] || PROMPTS.includes(rel)) continue;
    const hits = cyrillicLiterals(f);
    if (hits.length) out[rel] = hits;
  }
  return out;
}

describe('no hardcoded Cyrillic outside i18n', () => {
  const found = scanCyrillic();

  it('no file gains a Cyrillic literal; files outside the ledger have none', () => {
    const grown = Object.entries(found)
      .filter(([f, hits]) => hits.length > (CYRILLIC_LEDGER[f] ?? 0))
      .map(([f, hits]) => `${f} (${hits.length} > ${CYRILLIC_LEDGER[f] ?? 0}):\n  ${hits.slice(0, 5).join('\n  ')}`);
    expect(grown, 'move these strings to src/i18n/messages').toEqual([]);
  });

  it('the prompt exemption holds exactly one file and cannot grow silently', () => {
    expect(PROMPTS).toEqual(['services/askAbout.ts']);
    expect(() => readFileSync(join(SRC, PROMPTS[0]))).not.toThrow();
  });

  it('every data exemption names a reason and a file that exists', () => {
    for (const [f, why] of Object.entries(DATA)) {
      expect(why.length, f).toBeGreaterThan(10);
      expect(() => readFileSync(join(SRC, f)), f).not.toThrow();
    }
  });

  it('the ledger only shrinks: entries match what is left', () => {
    const stale = Object.entries(CYRILLIC_LEDGER)
      .filter(([f, n]) => (found[f]?.length ?? 0) < n)
      .map(([f, n]) => `${f}: ledger ${n}, now ${found[f]?.length ?? 0}`);
    expect(stale, 'lower these entries in cyrillicLedger.ts').toEqual([]);
  });
});

import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const CEILING = 0;
const ROOT = join(process.cwd(), 'src/components');
const LITERAL = /#[0-9a-fA-F]{8}\b|#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3,4}\b|\brgba?\(/g;

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => {
    const p = join(dir, n);
    if (statSync(p).isDirectory()) return sources(p);
    return /\.tsx$/.test(n) && !/\.test\.tsx$/.test(n) ? [p] : [];
  });
}

const stripComments = (s: string) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|\s)\/\/.*$/gm, '$1');

function counts() {
  return sources(ROOT)
    .map((f) => [f.slice(ROOT.length + 1), (stripComments(readFileSync(f, 'utf8')).match(LITERAL) ?? []).length] as const)
    .filter(([, n]) => n > 0);
}

describe('ратчет: колір лише токенами', () => {
  it('літералів #hex і rgb()/rgba() у components не більше за стелю', () => {
    const per = counts();
    const total = per.reduce((a, [, n]) => a + n, 0);
    const top = [...per].sort((a, b) => b[1] - a[1]).slice(0, 8).map(([f, n]) => `${f}: ${n}`).join('\n');
    expect(total, `літералів ${total} > стелі ${CEILING}; найбільше:\n${top}`).toBeLessThanOrEqual(CEILING);
  });

  it('кожен var(--ph-lit-*) у components оголошено в tokens.css', () => {
    const css = readFileSync(join(process.cwd(), 'src/styles/phantom/tokens.css'), 'utf8');
    const defined = new Set([...css.matchAll(/--ph-lit-([a-z0-9-]+):/g)].map((m) => m[1]));
    const used = sources(ROOT).flatMap((f) => [...readFileSync(f, 'utf8').matchAll(/--ph-lit-([a-z0-9-]+)/g)].map((m) => m[1]));
    expect(used.filter((n) => !defined.has(n))).toEqual([]);
  });
});

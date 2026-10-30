/**
 * LOOK-SPEC §3: the web kit, as far as it ships: StatusChip on the Today row, FilterChip in the
 * top sheet. ListRow, Card, Amount, ActionButton and
 * AvatarStack had no screen yet, so the orphan guard kept them out; they return from 52939a87
 * with W4/W6/W8.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { StatusChip, type StatusChipKind } from '../components/messenger/kit/StatusChip';
import { FilterChip } from '../components/messenger/kit/FilterChip';

afterEach(() => {
  cleanup();
});

const CHIPS: StatusChipKind[] = ['neutral', 'pending', 'done', 'failed'];

const Everything = () => (
  <div>
    {CHIPS.map((k) => (
      <StatusChip key={k} kind={k} on="surface">
        {k}
      </StatusChip>
    ))}
    <StatusChip>Draft</StatusChip>
    <FilterChip active count={3}>
      All
    </FilterChip>
    <FilterChip>Groups</FilterChip>
  </div>
);

const FORBIDDEN: [string, RegExp][] = [
  ['gradient', /gradient/],
  ['blur', /blur/],
  ['ring', /(^|:)ring(-|$)/],
  ['border-dashed', /border-dashed/],
  ['shadow-e2/e3', /shadow-e[23]/],
  ['raw hex', /#[0-9a-f]{3,8}\b/i],
];

const classTokens = (root: ParentNode): string[] =>
  [...root.querySelectorAll('[class]')].flatMap((e) => (e.getAttribute('class') ?? '').split(/\s+/)).filter(Boolean);

const el = (sel: string): HTMLElement => {
  const found = document.querySelector(sel);
  if (!found) throw new Error(`missing ${sel}`);
  return found as HTMLElement;
};

const has = (node: Element, ...classes: string[]) => {
  const have = new Set((node.getAttribute('class') ?? '').split(/\s+/));
  for (const c of classes) expect(have.has(c), `${c} in "${node.getAttribute('class')}"`).toBe(true);
};

const hasNot = (node: Element, ...classes: string[]) => {
  const have = new Set((node.getAttribute('class') ?? '').split(/\s+/));
  for (const c of classes) expect(have.has(c), `${c} must be absent from "${node.getAttribute('class')}"`).toBe(false);
};


describe('kit guard', () => {
  it('every rendered class and inline style avoids gradient, blur, ring, dashed borders, e2/e3 shadows and raw hex', () => {
    const { container } = render(<Everything />);
    const classes = classTokens(container);
    expect(classes.length).toBeGreaterThan(20);
    for (const [name, re] of FORBIDDEN) {
      expect(classes.filter((c) => re.test(c)), name).toEqual([]);
    }
    const styles = [...container.querySelectorAll('[style]')].map((e) => e.getAttribute('style') ?? '');
    expect(styles.filter((s) => /#[0-9a-f]{3,8}\b/i.test(s) || /gradient|blur/.test(s))).toEqual([]);
  });

  it('the kit sources carry none of the forbidden classes either, so a hidden branch cannot smuggle one in', () => {
    const dir = join(__dirname, '..', 'components', 'messenger', 'kit');
    const files = readdirSync(dir).filter((f) => f.endsWith('.tsx'));
    expect(files.sort()).toEqual(['AskField.tsx', 'Avatar.tsx', 'Button.tsx', 'EmptyState.tsx', 'FilterChip.tsx', 'IconTile.tsx', 'ListRow.tsx', 'NavBar.tsx', 'SheetFrame.tsx', 'StatusChip.tsx', 'TestPayTag.tsx', 'TicketCard.tsx', 'TopBar.tsx', 'WalletCard.tsx']);
    for (const f of files) {
      const code = readFileSync(join(dir, f), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
      for (const [name, re] of FORBIDDEN) {
        const src = name === 'ring' ? /(^|[\s'"`:])ring(-|\b)/ : re;
        expect(src.test(code), `${f}: ${name}`).toBe(false);
      }
    }
  });
});

describe('StatusChip', () => {
  it('is h-6 px-2 rounded-chip bg-raised with caption ink-2 text', () => {
    render(<StatusChip>Draft</StatusChip>);
    const chip = el('[data-kit="chip"]');
    has(chip, 'h-6', 'px-2', 'rounded-chip', 'bg-raised', 'text-caption', 'text-ink-2');
    expect(chip.querySelector('svg')).toBeNull();
    expect(chip.getAttribute('data-kind')).toBe('neutral');
  });

  it('on a surface it takes bg-overlay instead of bg-raised', () => {
    render(<StatusChip on="surface">Draft</StatusChip>);
    const chip = el('[data-kit="chip"]');
    has(chip, 'bg-overlay');
    hasNot(chip, 'bg-raised');
  });

  it.each(['pending', 'done', 'failed'] as const)('%s carries a 14 icon', (kind) => {
    render(<StatusChip kind={kind}>Label</StatusChip>);
    const svg = el('[data-kit="chip"] svg');
    has(svg, 'w-3.5', 'h-3.5');
    expect(el('[data-kit="chip"]').getAttribute('data-kind')).toBe(kind);
  });

  it('only a failed chip uses danger, and no kind is ever filled green or amber', () => {
    for (const kind of CHIPS) {
      render(<StatusChip kind={kind}>{kind}</StatusChip>);
      const chip = el('[data-kit="chip"]');
      const tokens = classTokens(chip.parentElement!).concat((chip.getAttribute('class') ?? '').split(/\s+/));
      expect(tokens.filter((c) => /^bg-(success|accent|warning|danger)/.test(c)), kind).toEqual([]);
      const danger = tokens.filter((c) => c === 'text-danger').length > 0;
      expect(danger, kind).toBe(kind === 'failed');
      cleanup();
    }
  });
});

/**
 * Kit parts for the look-x re-skin: every colour, radius and type size comes from a token class,
 * so a new look is a token swap. Each part is adopted at a real call site with the same markup.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render } from '@testing-library/react';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import { Button } from '../components/messenger/kit/Button';
import { TicketCard } from '../components/messenger/kit/TicketCard';
import { WalletCard } from '../components/messenger/kit/WalletCard';
import { IconTile } from '../components/messenger/kit/IconTile';
import { EmptyState } from '../components/messenger/kit/EmptyState';
import { ListRow, RowBadge } from '../components/messenger/kit/ListRow';
import { AskField } from '../components/messenger/kit/AskField';
import { SheetFrame } from '../components/messenger/kit/SheetFrame';
import { TopBar } from '../components/messenger/kit/TopBar';
import { Avatar as KitAvatar } from '../components/messenger/kit/Avatar';
import * as LegacyAvatar from '../components/messenger/Avatar';
import { NavBar, type NavItem } from '../components/messenger/kit/NavBar';
import { Coins, Image as ImageIcon, Users } from 'lucide-react';

afterEach(() => {
  cleanup();
});

const KIT = join(__dirname, '..', 'components', 'messenger', 'kit');
const MESSENGER = join(__dirname, '..', 'components', 'messenger');
const strip = (code: string) => code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

const el = (sel: string, root: ParentNode = document): HTMLElement => {
  const found = root.querySelector(sel);
  if (!found) throw new Error(`missing ${sel}`);
  return found as HTMLElement;
};

const classesOf = (node: Element) => new Set((node.getAttribute('class') ?? '').split(/\s+/).filter(Boolean));

const has = (node: Element, ...classes: string[]) => {
  const have = classesOf(node);
  for (const c of classes) expect(have.has(c), `${c} in "${node.getAttribute('class')}"`).toBe(true);
};

const hasNot = (node: Element, ...classes: string[]) => {
  const have = classesOf(node);
  for (const c of classes) expect(have.has(c), `${c} must be absent from "${node.getAttribute('class')}"`).toBe(false);
};

const importsKit = (file: string, part: string, module = part) => {
  const src = readFileSync(join(MESSENGER, file), 'utf8');
  expect(src, `${file} imports ${part}`).toMatch(new RegExp(`import \\{[^}]*\\b${part}\\b[^}]*\\} from '[./]+(?:messenger/)?kit/${module}'`));
  expect(src, `${file} renders ${part}`).toMatch(new RegExp(`<${part}[\\s>]`));
};

describe('kit sources take colour, radius and type only from tokens', () => {
  const files = readdirSync(KIT).filter((f) => f.endsWith('.tsx'));
  it.each(files)('%s has no raw colour, arbitrary type size or arbitrary radius', (f) => {
    const code = strip(readFileSync(join(KIT, f), 'utf8'));
    expect(code).not.toMatch(/#[0-9a-f]{3,8}\b/i);
    expect(code).not.toMatch(/\brgba?\(|\bhsla?\(/);
    expect(code).not.toMatch(/(?<![\w-])text-\[\d/);
    expect(code).not.toMatch(/(?<![\w-])rounded(?:-[a-z]{1,2})?-\[/);
    expect(code).not.toMatch(/(?:color|background|backgroundColor|borderColor)\s*:\s*['"`](?!var\()/);
  });
});

describe('Button', () => {
  it('primary is the accent pill: h-9 px-4 rounded-full bg-accent text-on-accent callout semibold', () => {
    render(<Button variant="primary">Pay</Button>);
    const b = el('[data-kit="button"]');
    has(b, 'touch-floor-inline', 'h-9', 'px-4', 'rounded-full', 'bg-accent', 'text-on-accent', 'text-callout', 'font-semibold', 'active:opacity-80');
    expect(b.getAttribute('type')).toBe('button');
    expect(b.getAttribute('data-kit-variant')).toBe('primary');
  });

  it('secondary is the quiet ink pill and is the default', () => {
    render(<Button>Open</Button>);
    const b = el('[data-kit="button"]');
    has(b, 'h-9', 'px-4', 'rounded-full', 'bg-ink/[.08]', 'text-ink', 'text-callout', 'font-medium', 'transition-opacity', 'duration-instant');
    hasNot(b, 'bg-accent');
  });

  it('text is accent words with no fill and no fixed height', () => {
    render(<Button variant="text">Settle</Button>);
    const b = el('[data-kit="button"]');
    has(b, 'touch-floor-inline', 'text-callout', 'font-medium', 'text-accent');
    expect([...classesOf(b)].filter((c) => /^(bg-|h-|rounded)/.test(c))).toEqual([]);
  });

  it('passes layout classes, test id and clicks through', () => {
    const onClick = vi.fn();
    render(
      <Button variant="primary" className="shrink-0 tnum" testId="pay" onClick={onClick}>
        Pay
      </Button>,
    );
    const b = el('[data-testid="pay"]');
    has(b, 'shrink-0', 'tnum');
    fireEvent.click(b);
    expect(onClick).toHaveBeenCalledTimes(1);
  });
});

describe('TicketCard', () => {
  it('is the D card frame: rounded-bubble bg-surface with a line border, title-2 title and one subhead status line', () => {
    render(<TicketCard testId="t" title="Cafe X" status="2 of 3 paid" />);
    const card = el('[data-testid="t"]');
    expect(card.tagName).toBe('SECTION');
    has(card, 'rounded-bubble', 'bg-surface', 'border', 'border-line', 'text-ink', 'overflow-hidden');
    has(el('[data-testid="card-title"]'), 'text-title-2', 'text-ink');
    has(el('[data-testid="card-status"]'), 'text-subhead', 'text-ink-2');
    expect(card.querySelector('button')).toBeNull();
  });

  it('the one action is an outline button in the bottom row that does not open the row under it', () => {
    const onRow = vi.fn();
    const onOpen = vi.fn();
    render(
      <div onClick={onRow}>
        <TicketCard title="Meetup" status="19:00" action={{ label: 'Open', onClick: onOpen }}>
          <p data-testid="body">body</p>
        </TicketCard>
      </div>,
    );
    const b = el('[data-kit="button"]');
    expect(b.getAttribute('data-kit-variant')).toBe('outline');
    has(b, 'border', 'border-line-strong');
    expect(b.closest('[data-card-actions]')).not.toBeNull();
    fireEvent.click(b);
    expect(onOpen).toHaveBeenCalledTimes(1);
    expect(onRow).not.toHaveBeenCalled();
    has(el('[data-testid="body"]').parentElement!, 'pb-3');
  });

  it('is reached from the bill and meetup cards, and CardShell is the same part', () => {
    importsKit('meetup/BillCard.tsx', 'TicketCard');
    importsKit('meetup/MeetupCard.tsx', 'TicketCard');
    importsKit('meetup/BillCard.tsx', 'Button');
    expect(readFileSync(join(MESSENGER, 'cards/CardShell.tsx'), 'utf8')).toMatch(/export const CardShell = TicketCard;/);
  });
});

describe('WalletCard', () => {
  it('is the D card frame: 300 wide, rounded-bubble, line border on surface, caption kind label with a 16 icon', () => {
    render(
      <WalletCard testId="w" icon={Coins} label="Transfer" footer={<span data-testid="foot">ok</span>}>
        <p data-testid="amount">2.5 USDC</p>
      </WalletCard>,
    );
    const card = el('[data-testid="w"]');
    has(card, 'w-[300px]', 'max-w-full', 'rounded-bubble', 'border', 'border-line', 'bg-surface', 'px-4', 'pt-3.5', 'pb-3', 'text-ink');
    const label = el('[data-kit-wallet-label]');
    has(label, 'text-caption', 'font-semibold', 'text-ink-3');
    has(el('svg', label), 'w-4', 'h-4');
    expect(label.textContent).toBe('Transfer');
    has(el('[data-testid="foot"]').parentElement!, 'mt-2.5', 'border-t', 'border-line', 'pt-2.5');
  });

  it('without a footer there is no divider', () => {
    render(<WalletCard icon={Coins} label="Cash">x</WalletCard>);
    expect(document.querySelector('.border-t')).toBeNull();
  });

  it('is reached from the Solana card in the feed', () => {
    importsKit('feed/SolanaCard.tsx', 'WalletCard');
  });
});

describe('IconTile', () => {
  it('is a 56 rounded-card overlay tile with a 24 icon and a two-line caption under it', () => {
    const onClick = vi.fn();
    render(<IconTile icon={ImageIcon} label="Photo" testId="tile" onClick={onClick} />);
    const b = el('[data-testid="tile"]');
    expect(b.tagName).toBe('BUTTON');
    has(b, 'min-w-0', 'flex', 'flex-col', 'items-center', 'gap-2', 'rounded-control', 'focus-visible:outline-accent-line');
    const tile = el('[data-kit-tile-face]', b);
    has(tile, 'w-14', 'h-14', 'rounded-card', 'bg-overlay', 'text-ink');
    has(el('svg', tile), 'w-6', 'h-6');
    const label = el('[data-kit-tile-label]', b);
    has(label, 'line-clamp-2', 'text-caption', 'text-ink-2', 'text-center');
    fireEvent.click(b);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('is reached from the attach sheet', () => {
    importsKit('AttachSheet.tsx', 'IconTile');
  });
});

describe('EmptyState', () => {
  it('is a 48 surface tile with the icon, one headline label and one quiet action', () => {
    const onClick = vi.fn();
    render(<EmptyState testId="e" icon={<Users data-testid="icon" />} label="No groups yet" action={{ label: 'Create group', onClick }} />);
    const root = el('[data-testid="e"]');
    has(root, 'min-h-full', 'flex', 'flex-col', 'items-center', 'text-center');
    const tile = el('[data-empty-tile]', root);
    has(tile, 'w-12', 'h-12', 'rounded-control', 'bg-surface', 'text-ink-2');
    expect(tile.getAttribute('aria-hidden')).toBe('true');
    expect(tile.querySelector('[data-testid="icon"]')).not.toBeNull();
    const h = el('h2', root);
    has(h, 'text-headline', 'text-ink');
    expect(h.textContent).toBe('No groups yet');
    const b = el('button', root);
    has(b, 'touch-floor-inline', 'h-9', 'px-4', 'rounded-full', 'bg-ink/[.08]', 'text-ink', 'text-callout', 'font-medium');
    fireEvent.click(b);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('labels only: no paragraph of prose, and no button without an action', () => {
    render(<EmptyState icon={<Users />} label="Nothing here" />);
    expect(document.querySelector('p')).toBeNull();
    expect(document.querySelector('button')).toBeNull();
  });

  it('is reached from the chat list empty states', () => {
    importsKit('PhoneChatList.tsx', 'EmptyState');
  });
});

describe('ListRow', () => {
  it('phone density: 72 list row on canvas with the avatar slot, a headline title and a subhead subtitle', () => {
    render(
      <ul>
        <ListRow leading={<span data-testid="lead" />} title="Devices" subtitle="Laptop, Phone" badge={<RowBadge n={3} label="3 unread" />} data-row="x" />
      </ul>,
    );
    const row = el('[data-kit="list-row"]');
    expect(row.tagName).toBe('BUTTON');
    has(row, 'w-full', 'h-[72px]', 'ph-list-row', 'px-4', 'flex', 'items-center', 'gap-3', 'bg-canvas', 'active:bg-overlay', 'focus-visible:outline-accent-line');
    expect(row.getAttribute('data-row')).toBe('x');
    expect(row.firstElementChild?.getAttribute('data-testid')).toBe('lead');
    has(el('[data-kit-row-title]', row), 'min-w-0', 'flex-1', 'truncate', 'text-headline', 'text-ink');
    has(el('[data-kit-row-subtitle]', row), 'min-w-0', 'flex-1', 'truncate', 'text-subhead', 'font-normal', 'text-ink-2');
    expect(el('[data-kit-row-subtitle]', row).textContent).toBe('Laptop, Phone');
  });

  it('desk density is the 60 rounded-control row', () => {
    render(<ListRow density="desk" title="Archive" />);
    const row = el('[data-kit="list-row"]');
    has(row, 'h-[60px]', 'rounded-control');
    hasNot(row, 'h-[72px]', 'ph-list-row');
    expect(row.querySelector('[data-kit-row-subtitle]')).toBeNull();
  });

  it('time sits at the end of the title line, ink-2 when strong and ink-3 when quiet', () => {
    render(<ListRow title="Olena" time="18:20" timeStrong meta={<span data-testid="meta" />} />);
    const time = el('[data-row-time]');
    has(time, 'shrink-0', 'text-footnote', 'tnum', 'text-ink-2');
    expect(time.previousElementSibling?.getAttribute('data-testid')).toBe('meta');
    cleanup();
    render(<ListRow title="Olena" time="18:20" />);
    has(el('[data-row-time]'), 'text-ink-3');
  });

  it('is swipe-ready: ref, style transform and touch handlers reach the row', () => {
    const ref = { current: null as HTMLButtonElement | null };
    const onTouchStart = vi.fn();
    render(<ListRow ref={ref} title="Olena" style={{ transform: 'translateX(-72px)' }} onTouchStart={onTouchStart} className="ph-row-settle" />);
    const row = el('[data-kit="list-row"]');
    expect(ref.current).toBe(row);
    expect(row.style.transform).toBe('translateX(-72px)');
    has(row, 'ph-row-settle');
    fireEvent.touchStart(row, { touches: [{ clientX: 0, clientY: 0 }] });
    expect(onTouchStart).toHaveBeenCalledTimes(1);
  });

  it('RowBadge is the 22 pill: accent when lit, ink otherwise, quiet when muted, 99+ cap', () => {
    render(<RowBadge n={120} lit label="120 unread" />);
    const b = el('[data-kit="row-badge"]');
    has(b, 'h-[22px]', 'min-w-[22px]', 'px-1.5', 'rounded-full', 'text-footnote', 'font-semibold', 'tnum', 'bg-accent', 'text-on-accent');
    expect(b.textContent).toBe('99+');
    expect(b.getAttribute('aria-label')).toBe('120 unread');
    cleanup();
    render(<RowBadge n={2} label="2" />);
    has(el('[data-kit="row-badge"]'), 'bg-ink-2', 'text-canvas');
    cleanup();
    render(<RowBadge n={2} quiet label="2" />);
    has(el('[data-kit="row-badge"]'), 'bg-line-strong', 'text-ink');
  });

  it('is reached from the chat list folder rows and unread counters', () => {
    importsKit('PhoneChatList.tsx', 'ListRow');
    importsKit('PhoneChatList.tsx', 'RowBadge', 'ListRow');
  });
});

describe('AskField', () => {
  it('is the 44 filled field (D: raised, r12, no outline) with a search icon and a body-size field', () => {
    const onChange = vi.fn();
    render(<AskField value="" onChange={onChange} label="Search or ask" placeholder="Search or ask" data-search-box />);
    const box = el('[data-kit="ask-field"]');
    expect(box.hasAttribute('data-search-box')).toBe(true);
    has(box, 'h-11', 'rounded-control', 'bg-raised', 'border', 'border-transparent', 'focus-within:border-accent-line');
    has(el('svg', box), 'w-5', 'h-5', 'text-ink-3');
    const input = el('input', box) as HTMLInputElement;
    expect(input.type).toBe('search');
    expect(input.getAttribute('aria-label')).toBe('Search or ask');
    has(input, 'text-body', 'text-ink', 'placeholder:text-ink-3', 'bg-transparent');
    expect(input.className).not.toMatch(/focus-visible:outline-(?!none)/);
    fireEvent.change(input, { target: { value: 'plan' } });
    expect(onChange).toHaveBeenCalledWith('plan');
    expect(box.querySelector('button')).toBeNull();
  });

  it('the voice button lives inside the field only when asking is on', () => {
    const onVoice = vi.fn();
    render(<AskField value="" onChange={() => undefined} label="Ask" onVoice={onVoice} voiceLabel="Talk" voiceTestId="voice" />);
    const b = el('[data-testid="voice"]');
    expect(b.getAttribute('aria-label')).toBe('Talk');
    has(b, 'touch-floor-inline', 'w-10', 'h-10', 'rounded-full', 'text-ink-2');
    fireEvent.click(b);
    expect(onVoice).toHaveBeenCalledTimes(1);
  });

  it('is reached from the chat list search', () => {
    importsKit('PhoneChatList.tsx', 'AskField');
  });
});


describe('SheetFrame', () => {
  it('is the D sheet with sheet corners, a 36×4 handle, a title-1 header and a scrolling body', () => {
    const onPointerDown = vi.fn();
    render(
      <SheetFrame title="Folder" handleProps={{ onPointerDown }} role="dialog" aria-label="Folder" className="shadow-e3">
        <p data-testid="body">body</p>
      </SheetFrame>,
    );
    const frame = el('[data-kit="sheet-frame"]');
    expect(frame.getAttribute('role')).toBe('dialog');
    has(frame, 'bg-canvas', 'text-ink', 'rounded-t-sheet', 'md:rounded-sheet', 'flex', 'flex-col', 'max-h-[calc(100%-48px)]', 'shadow-e3');
    const handle = el('[data-kit-sheet-handle]', frame);
    has(handle.firstElementChild!, 'w-9', 'h-1', 'rounded-full', 'bg-line-strong');
    fireEvent.pointerDown(handle);
    expect(onPointerDown).toHaveBeenCalledTimes(1);
    const h2 = el('h2', frame);
    has(h2, 'text-title-1', 'text-ink', 'truncate');
    has(h2.parentElement!, 'pl-5');
    has(el('[data-testid="body"]').parentElement!, 'flex-1', 'min-h-0', 'overflow-y-auto');
    expect(frame.querySelector('[aria-label]:not([data-kit])')).toBeNull();
  });

  it('crown, tall, back and footer', () => {
    const onBack = vi.fn();
    render(
      <SheetFrame title="People" crown tall onBack={onBack} backLabel="Back" footer={<button type="button">Save</button>}>
        x
      </SheetFrame>,
    );
    const frame = el('[data-kit="sheet-frame"]');
    has(frame, 'h-[calc(100%-48px)]', 'md:h-[84vh]');
    hasNot(frame, 'max-h-[calc(100%-48px)]');
    has(el('h2', frame), 'text-display');
    const back = el('[aria-label="Back"]', frame);
    has(back, 'w-11', 'h-11', 'rounded-full', 'text-ink-2');
    has(back.parentElement!, 'pl-2');
    fireEvent.click(back);
    expect(onBack).toHaveBeenCalledTimes(1);
    has(el('button:not([aria-label])', frame).parentElement!, 'px-5', 'pt-3');
  });

  it('a hidden title stays for the screen reader and drops the header bar', () => {
    render(<SheetFrame title="Profile" hideTitle>x</SheetFrame>);
    const h2 = el('h2');
    has(h2, 'sr-only');
    has(h2.parentElement!, 'hidden');
  });

  it('is reached from FormSheet', () => {
    importsKit('FormSheet.tsx', 'SheetFrame');
  });
});

describe('TopBar', () => {
  it('is the 56 bar: a 48 back button and a title-2 title', () => {
    const onBack = vi.fn();
    render(<TopBar title="Moments" onBack={onBack} backLabel="Back" backProps={{ 'data-moments-back': true }} safeTop />);
    const bar = el('[data-kit="top-bar"]');
    expect(bar.tagName).toBe('HEADER');
    has(bar, 'h-14', 'shrink-0', 'px-1', 'flex', 'items-center', 'gap-1');
    expect(renderToStaticMarkup(<TopBar title="Moments" safeTop />)).toContain('style="margin-top:env(safe-area-inset-top)"');
    const back = el('[data-moments-back]', bar);
    expect(back.getAttribute('aria-label')).toBe('Back');
    has(back, 'w-12', 'h-12', 'rounded-full', 'text-ink');
    has(el('svg', back), 'w-6', 'h-6');
    has(el('h2', bar), 'text-title-2', 'text-ink');
    fireEvent.click(back);
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('without onBack there is no back button and no inset unless asked', () => {
    render(<TopBar title="Today" trailing={<span data-testid="act" />} />);
    const bar = el('[data-kit="top-bar"]');
    expect(bar.querySelector('button')).toBeNull();
    expect(renderToStaticMarkup(<TopBar title="Today" />)).not.toContain('style=');
    expect(bar.lastElementChild?.getAttribute('data-testid')).toBe('act');
  });

  it('is reached from the Moments feed page', () => {
    importsKit('MomentsFeed.tsx', 'TopBar');
  });
});

describe('Avatar', () => {
  it('without a photo is a flat person-tone monogram from tokens, one letter', () => {
    render(<KitAvatar name="Олена" tone="peer-1" className="w-12 h-12" />);
    const a = el('[aria-label="Олена"]');
    has(a, 'w-12', 'h-12', 'rounded-full', 'font-semibold');
    expect(a.textContent).toBe('О');
    expect(a.getAttribute('style')).toMatch(/^background-color: var\(--ph-person-\d\); color: var\(--ph-person-\d-ink\);$/);
  });

  it('without a tone it is neutral raised + ink-2, and a nameless person is a silhouette', () => {
    render(<KitAvatar name="" />);
    const a = el('[aria-label=""]');
    has(a, 'bg-raised', 'text-ink-2');
    expect(a.hasAttribute('data-nameless')).toBe(true);
    expect(a.querySelector('svg')).not.toBeNull();
  });

  it('a photo is an object-cover image on raised', () => {
    render(<KitAvatar name="Олена" src="https://x/y.png" radius="rounded-control" />);
    const img = el('img');
    has(img, 'rounded-control', 'object-cover', 'bg-raised');
  });

  it('the Moments ring: ink 1.5 border when unseen, a line border when seen, 56 around the face', () => {
    render(<KitAvatar name="Олена" tone="peer-1" className="w-12 h-12" moments="unseen" />);
    const ringEl = el('[data-moment-ring]');
    expect(ringEl.getAttribute('data-ring')).toBe('unseen');
    has(ringEl, 'w-14', 'h-14', 'rounded-full', 'border-[1.5px]', 'border-ink');
    expect(ringEl.firstElementChild?.getAttribute('aria-label')).toBe('Олена');
    cleanup();
    render(<KitAvatar name="Олена" moments="seen" />);
    has(el('[data-moment-ring]'), 'border', 'border-line');
    hasNot(el('[data-moment-ring]'), 'border-ink');
  });

  it('the old messenger Avatar module is the same part, and Moments tiles use its ring', () => {
    expect(LegacyAvatar.Avatar).toBe(KitAvatar);
    const strip = readFileSync(join(MESSENGER, 'MomentsStrip.tsx'), 'utf8');
    expect(strip).toMatch(/<Avatar[^>]*moments=\{m\.seen \? 'seen' : 'unseen'\}/);
    expect(strip).not.toMatch(/const Ring\b/);
  });
});

describe('NavBar', () => {
  const tabs = (onClick = vi.fn()): NavItem[] => [
    { key: 'chats', label: 'Chats', icon: <svg data-testid="i-chats" />, badge: 3, active: true, onClick },
    { key: 'map', label: 'Map', icon: <svg />, active: false, onClick },
    { key: 'wallet', label: 'Wallet', icon: <svg />, active: false, onClick },
    { key: 'me', label: 'Profile', icon: <svg />, active: false, onClick },
  ];

  it('bar: a 64 surface navigation with four equal tabs, the active one on a raised pill', () => {
    const onClick = vi.fn();
    render(<NavBar items={tabs(onClick)} label="Sections" />);
    const nav = el('nav[aria-label="Sections"]');
    expect(nav.getAttribute('data-kit')).toBe('nav-bar');
    has(nav, 'h-16', 'bg-surface', 'border-t', 'border-line');
    const buttons = [...nav.querySelectorAll('button')];
    expect(buttons.map((b) => b.getAttribute('aria-label'))).toEqual(['Chats', 'Map', 'Wallet', 'Profile']);
    for (const b of buttons) has(b, 'flex-1', 'min-w-0');
    expect(buttons[0].getAttribute('aria-current')).toBe('page');
    expect(buttons[1].hasAttribute('aria-current')).toBe(false);
    has(el('[data-kit-nav-pill]', buttons[0]), 'w-16', 'h-8', 'rounded-full', 'bg-raised', 'text-ink');
    has(el('[data-kit-nav-pill]', buttons[1]), 'text-ink-2');
    hasNot(el('[data-kit-nav-pill]', buttons[1]), 'bg-raised');
    has(el('[data-kit-nav-label]', buttons[0]), 'text-caption');
    const badge = el('[data-kit-nav-badge]', buttons[0]);
    has(badge, 'rounded-full', 'bg-accent', 'text-on-accent', 'text-caption');
    expect(badge.textContent).toBe('3');
    fireEvent.click(buttons[2]);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('island: the desk dock pill; active slot on raised, colours from tokens, host places it', () => {
    render(<NavBar layout="island" testId="dock" label="Sections" className="absolute bottom-3" items={tabs()} />);
    const dock = el('[data-testid="dock"]');
    expect(dock.tagName).toBe('NAV');
    expect(dock.getAttribute('data-kit')).toBe('nav-island');
    expect(dock.getAttribute('aria-label')).toBe('Sections');
    has(dock, 'flex', 'gap-0.5', 'p-1', 'rounded-full', 'bg-surface', 'border', 'border-line', 'absolute', 'bottom-3');
    const [active, idle] = [...dock.querySelectorAll('button')];
    has(active, 'w-[88px]', 'h-[52px]', 'rounded-full', 'bg-raised', 'text-ink', 'duration-instant', 'ease-standard');
    has(idle, 'text-ink-2', 'hover:text-ink', 'hover:bg-raised/60');
    expect(active.getAttribute('aria-current')).toBe('page');
    expect(active.getAttribute('title')).toBe('Chats');
    has(el('[data-kit-nav-badge]', active), 'rounded-full', 'bg-accent', 'text-on-accent');
    const all = [...dock.querySelectorAll('[class]'), dock].flatMap((e) => [...classesOf(e)]);
    expect(all.filter((c) => /(^|:)(bg|text|border)-f-/.test(c) || /(duration|ease)-f-/.test(c))).toEqual([]);
  });

  it('island type sizes can be set by the host; short labels win in the label slot', () => {
    render(
      <NavBar
        layout="island"
        items={[{ key: 's', label: 'Settings', shortLabel: 'Prefs', icon: <svg />, badge: 2, active: false, onClick: () => undefined }]}
        typeScale={{ label: 'text-footnote', badge: 'text-footnote leading-[18px]' }}
      />,
    );
    const label = el('[data-kit-nav-label]');
    expect(label.textContent).toBe('Prefs');
    has(el('button'), 'text-footnote');
    has(el('[data-kit-nav-badge]'), 'text-footnote', 'leading-[18px]');
    expect(el('button').getAttribute('aria-label')).toBe('Settings');
  });

  it('is reached from the desk island (pc-warm dock)', () => {
    const island = readFileSync(join(__dirname, '..', 'components', 'shell', 'DeskIsland.tsx'), 'utf8');
    expect(island).toMatch(/import \{ NavBar[^}]*\} from '\.\.\/messenger\/kit\/NavBar'/);
    expect(island).toMatch(/<NavBar\s[^>]*layout="island"/);
    expect(island).not.toMatch(/<nav[\s>]/);
  });
});

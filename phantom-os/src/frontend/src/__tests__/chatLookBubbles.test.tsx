/**
 * Conversation bubbles, mockup 04-conversation: own = `self` + `on-self`,
 * incoming = `surface`; radius 16 with an 8 tail corner on the last bubble of
 * a run, no drawn tail; time + ticks 12 tnum inside; ticks never amber; the
 * group sender name sits inside the text bubble in the person tone (D); 2 within a run,
 * 8 between runs; the day separator is a quiet 13/500 text-2 line.
 */
import { beforeAll, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ChatArea } from '../components/messenger/ChatArea';
import type { Chat, Message } from '../types/messenger';

const STYLES = join(__dirname, '..', 'styles');
const strip = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '');
const FORM = strip(readFileSync(join(STYLES, 'form.css'), 'utf8'));
const IDENTITY = strip(readFileSync(join(STYLES, 'identity.css'), 'utf8'));
const ruleOf = (css: string, selector: string) => {
  const at = css.indexOf(selector);
  expect(at, selector).toBeGreaterThanOrEqual(0);
  const rest = css.slice(at);
  return rest.slice(0, rest.indexOf('}'));
};

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

describe('bubble shape', () => {
  it('radius 16; the 8 tail corner only on the last (or single) bubble of a run', () => {
    const r = (c: string, v: string) => expect(FORM).toContain(`.bubble-contour-${c}.bubble-contour-${c} { border-radius: ${v}; }`);
    r('self-single', '16px 16px 8px 16px');
    r('self-last', '16px 16px 8px 16px');
    r('self-first', '16px');
    r('self-middle', '16px');
    r('other-single', '16px 16px 16px 8px');
    r('other-last', '16px 16px 16px 8px');
    r('other-first', '16px');
    r('other-middle', '16px');
  });

  it('no drawn tail, no 4 corner, no ember tint on my bubble', () => {
    expect(IDENTITY).not.toMatch(/bubble-contour-[a-z-]+::after/);
    expect(IDENTITY).not.toMatch(/border-bottom-(right|left)-radius: 4px/);
    expect(IDENTITY).not.toMatch(/#E3833A/i);
    expect(ruleOf(IDENTITY, 'html:root .msg-bubble-self,')).not.toMatch(/background-color/);
    expect(ruleOf(FORM, 'html:root .msg-bubble-self,')).toContain('background-color: var(--ph-self)');
  });

  it('padding 8 12 on text bubbles', () => {
    expect(ruleOf(IDENTITY, 'html:root .msg-density-short,')).toContain('padding: 8px 12px;');
  });

  it('assistant answer is a plain surface bubble: no stroke, no grain, radius 16', () => {
    const body = ruleOf(IDENTITY, 'html:root .msg-bubble-other.msg-bubble-phantom {');
    expect(body).not.toMatch(/grain|inset|box-shadow: [^n]/);
    expect(body).toContain('border-radius: 16px');
  });

  it('the stage under the feed is plain bg, no grain or sun tint', () => {
    const body = ruleOf(IDENTITY, '.msg-stage {');
    expect(body).toContain('background-color: var(--ph-canvas)');
    expect(body).not.toMatch(/grain|sun-tint|background-image/);
  });
});

describe('meta and separators', () => {
  it('read ticks are text-2 on both themes, never amber or green', () => {
    const all = FORM + IDENTITY;
    const reads = [...all.matchAll(/\.msg-tick-read \{([^}]*)\}/g)].map((m) => m[1]);
    expect(reads.length).toBeGreaterThan(0);
    for (const r of reads) {
      expect(r).toContain('var(--ph-text-2)');
      expect(r).not.toMatch(/accent|success/);
    }
  });

  it('day separator is a quiet 13/500 text-2 line without a pill fill', () => {
    const body = ruleOf(IDENTITY, '.ph-day-pill {');
    expect(body).not.toMatch(/background-color|border-radius/);
    expect(body).toContain('color: var(--ph-text-2)');
    expect(body).toContain('font: 500 13px/18px');
  });
});

const noop = () => {};
const group = {
  id: 'g1',
  title: 'Friday pho',
  avatar: '',
  type: 'group',
  circle: 'friends',
  unreadCount: 0,
  messages: [],
} as unknown as Chat;
const from = (id: string, who: string, text: string): Message =>
  ({ id, senderId: who.toLowerCase(), senderName: who, senderAvatar: '', timestamp: '16:40', type: 'text', text, isSelf: false, status: 'sent' }) as unknown as Message;

const feed = (messages: Message[]) =>
  render(
    <MemoryRouter>
      <ChatArea
        currentChat={group}
        messages={messages}
        currentUserId="me"
        onOpenLocation={noop}
        onVotePoll={noop}
        onPayBillShare={noop}
        onAddReaction={noop}
        onReplyMessage={noop}
        selectedMessageIds={[]}
        onToggleSelectMessage={noop}
        isSelectionMode={false}
        isSearching={false}
        onCloseSearch={noop}
      />
    </MemoryRouter>,
  );

describe('runs in a group', () => {
  const msgs = [from('a1', 'Olena', 'who is in'), from('a2', 'Olena', 'for pho'), from('b1', 'Petro', 'me')];

  it('2 within a run, 8 between runs', () => {
    const { container } = feed(msgs);
    const row = (id: string) => container.querySelector(`#message-${id}`) as HTMLElement;
    expect(row('a2').className).toMatch(/(^|\s)mt-0\.5(\s|$)/);
    expect(row('b1').className).toMatch(/(^|\s)mt-2(\s|$)/);
    expect(row('b1').className).not.toMatch(/(^|\s)mt-3(\s|$)/);
  });

  it('sender name 13/600 in the person tone, inside the text bubble, once per run (D)', () => {
    const { container } = feed(msgs);
    const names = [...container.querySelectorAll('[data-msg-sender]')] as HTMLElement[];
    expect(names.map((n) => n.textContent)).toEqual(['Olena', 'Petro']);
    for (const n of names) {
      expect(n.className).toContain('text-footnote');
      expect(n.className).toContain('font-semibold');
      expect(n.style.color).not.toBe('');
      expect(n.closest('.msg-bubble-fluid')).not.toBeNull();
    }
  });

  it('time sits inside the bubble', () => {
    const { container } = feed(msgs);
    const meta = container.querySelector('#message-b1 .msg-meta-row') as HTMLElement;
    expect(meta.closest('.msg-bubble-fluid')).not.toBeNull();
    expect(meta.textContent).toContain('16:40');
  });
});

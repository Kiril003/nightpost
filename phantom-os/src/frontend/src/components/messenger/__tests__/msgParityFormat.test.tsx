import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { FormattedMessageText } from '../FormattedMessageText';
import { MessageComposer } from '../MessageComposer';
import { plainSnippet } from '../chatListFormat';

afterEach(() => {
  cleanup();
});

const composerProps = (overrides: Partial<React.ComponentProps<typeof MessageComposer>> = {}) =>
  ({
    onSendMessage: vi.fn(),
    onSendVoiceMessage: vi.fn(),
    replyingTo: null,
    onCancelReply: vi.fn(),
    editingMessage: null,
    onCancelEdit: vi.fn(),
    onSaveEdit: vi.fn(),
    selectedMessagesForQuote: [],
    onSynthesizeMultiQuote: vi.fn(),
    onClearSelectedQuotes: vi.fn(),
    chatId: 'chat_fmt',
    ...overrides,
  }) satisfies React.ComponentProps<typeof MessageComposer>;

describe('MSG-PARITY step 1 — FormattedMessageText renderer', () => {
  it('renders bold, italic (* and _), underline, strike (~~ and ~), spoiler, and inline code', () => {
    const { container } = render(
      <FormattedMessageText
        text="**bold** *ital1* _ital2_ __under__ ~~strike2~~ ~strike1~ ||secret|| `inline_code`"
      />,
    );
    expect(container.querySelector('strong')?.textContent).toBe('bold');
    const ems = [...container.querySelectorAll('em')].map((el) => el.textContent);
    expect(ems).toEqual(['ital1', 'ital2']);
    expect(container.querySelector('u')?.textContent).toBe('under');
    const dels = [...container.querySelectorAll('del, s')].map((el) => el.textContent);
    expect(dels).toEqual(['strike2', 'strike1']);
    expect(container.textContent).not.toContain('~');
    expect(container.querySelector('code')?.textContent).toBe('inline_code');
    expect(container.textContent).toContain('secret');
  });

  it('parses _ital_ at word boundaries while leaving snake_case_name untouched', () => {
    const { container } = render(
      <FormattedMessageText text="Use _ital_ with snake_case_name and another_var_1 here" />,
    );
    const ems = [...container.querySelectorAll('em')].map((el) => el.textContent);
    expect(ems).toEqual(['ital']);
    expect(container.textContent).toBe('Use ital with snake_case_name and another_var_1 here');
  });

  it('renders ~~s~~ as <del>/<s> with no leftover tildes', () => {
    const { container } = render(<FormattedMessageText text="before ~~s~~ after" />);
    const del = container.querySelector('del, s');
    expect(del).toBeTruthy();
    expect(del?.textContent).toBe('s');
    expect(container.textContent).toBe('before s after');
    expect(container.textContent).not.toContain('~');
  });

  it('renders [Nightpost](https://phantom-os.dev) as anchor and [x](javascript:alert(1)) as plain text', () => {
    const safe = render(
      <FormattedMessageText text="Visit [Nightpost](https://phantom-os.dev) or [mail](mailto:hi@phantom-os.dev)" />,
    );
    const links = [...safe.container.querySelectorAll('a')];
    expect(links).toHaveLength(2);
    expect(links[0].getAttribute('href')).toBe('https://phantom-os.dev');
    expect(links[0].textContent).toBe('Nightpost');
    expect(links[1].getAttribute('href')).toBe('mailto:hi@phantom-os.dev');
    expect(links[1].textContent).toBe('mail');
    cleanup();

    const unsafe = render(
      <FormattedMessageText text="Bad [x](javascript:alert(1)) and [y](data:text/html,hi)" />,
    );
    expect(unsafe.container.querySelector('a')).toBeNull();
    expect(unsafe.container.textContent).toContain('[x](javascript:alert(1))');
  });

  it('renders fenced code blocks keeping newlines, with a 44px copy button, and keeps markers inside code literal', () => {
    const text = '```ts\nconst a = "**not bold**";\nconst b = "_not_ ~~strike~~";\n```';
    const { container } = render(<FormattedMessageText text={text} />);
    const pre = container.querySelector('pre');
    expect(pre).toBeTruthy();
    expect(pre?.className).toContain('overflow-x-auto');
    const code = pre?.querySelector('code');
    expect(code?.textContent).toBe('const a = "**not bold**";\nconst b = "_not_ ~~strike~~";');
    expect(container.querySelector('strong')).toBeNull();
    expect(container.querySelector('em')).toBeNull();
    expect(container.querySelector('del, s')).toBeNull();

    const copyBtn = container.querySelector('button');
    expect(copyBtn).toBeTruthy();
    expect(copyBtn?.className).toMatch(/w-11|min-w-\[44px\]/);
    expect(copyBtn?.className).toMatch(/h-11|min-h-\[44px\]/);
  });

  it('keeps markers inside inline code literal', () => {
    const { container } = render(
      <FormattedMessageText text="Run `**bold** _ital_ ~~strike~~ [x](https://a.b)` now" />,
    );
    const code = container.querySelector('code');
    expect(code?.textContent).toBe('**bold** _ital_ ~~strike~~ [x](https://a.b)');
    expect(container.querySelector('strong')).toBeNull();
    expect(container.querySelector('em')).toBeNull();
    expect(container.querySelector('del, s')).toBeNull();
    expect(container.querySelector('a')).toBeNull();
  });

  it('groups consecutive > quote lines into one blockquote and keeps line breaks', () => {
    const { container } = render(
      <FormattedMessageText text={'> first quote\n> second **bold** quote\nNormal line 1\n\nNormal line 3'} />,
    );
    const quotes = container.querySelectorAll('blockquote');
    expect(quotes).toHaveLength(1);
    expect(quotes[0].textContent).toBe('first quote\nsecond bold quote');
    expect(quotes[0].querySelector('strong')?.textContent).toBe('bold');
    const p = container.querySelector('p');
    expect(p?.textContent).toBe('Normal line 1\n\nNormal line 3');
  });

  it('matches @First Last when it matches a known member name passed in, as well as @handle', () => {
    const onMention = vi.fn();
    render(
      <FormattedMessageText
        text="Hello @First Last and @olena_k!"
        memberNames={['First Last']}
        onMentionClick={onMention}
      />,
    );
    const fullBtn = screen.getByRole('button', { name: '@First Last' });
    const handleBtn = screen.getByRole('button', { name: '@olena_k' });
    fireEvent.click(fullBtn);
    expect(onMention).toHaveBeenCalledWith('First Last');
    fireEvent.click(handleBtn);
    expect(onMention).toHaveBeenCalledWith('olena_k');
  });

  it('supports nested formatting where sane', () => {
    const { container } = render(
      <FormattedMessageText text="**bold _and italic_ with ~~strike~~**" />,
    );
    const strong = container.querySelector('strong');
    expect(strong).toBeTruthy();
    expect(strong?.querySelector('em')?.textContent).toBe('and italic');
    expect(strong?.querySelector('del, s')?.textContent).toBe('strike');
  });

  it('plainSnippet and composer reply banner strip all formatting markers', () => {
    expect(
      plainSnippet('> **bold** _ital_ *ital2* __under__ ~~strike~~ ~s1~ `code` [Nightpost](https://phantom-os.dev)'),
    ).toBe('bold ital ital2 under strike s1 code Nightpost');
    expect(plainSnippet('snake_case_name stays')).toBe('snake_case_name stays');
    expect(plainSnippet('```ts\nconst x = 1;\n```')).toBe('const x = 1;');

    const peer = 'Olena';
    render(
      <MessageComposer
        {...composerProps({
          replyingTo: {
            id: 'm1',
            senderName: peer,
            text: '**Важливо** _дуже_ ~~старе~~ [сайт](https://phantom-os.dev)',
          },
        })}
      />,
    );
    const reply = screen.getByTestId('composer-reply');
    expect(reply.textContent).toContain('Важливо дуже старе сайт');
    expect(reply.textContent).not.toContain('**');
    expect(reply.textContent).not.toContain('~~');
  });
});

describe('MSG-PARITY step 1 — MessageComposer formatting bar and shortcuts', () => {
  const selectRange = (textarea: HTMLTextAreaElement, start: number, end: number) => {
    textarea.focus();
    textarea.setSelectionRange(start, end);
    fireEvent.select(textarea, { target: { selectionStart: start, selectionEnd: end } });
  };

  it('shows format bar on non-empty selection, wraps "word" in **word** keeping selection on "word", and unwraps on second click', () => {
    render(<MessageComposer {...composerProps({ initialDraft: 'hello word here' })} />);
    const textarea = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;

    expect(screen.queryByTestId('composer-format-bar')).toBeNull();

    // Select "word" (index 6..10)
    selectRange(textarea, 6, 10);
    expect(screen.getByTestId('composer-format-bar')).toBeTruthy();

    const boldBtn = screen.getByLabelText('Жирний');
    fireEvent.click(boldBtn);

    expect(textarea.value).toBe('hello **word** here');
    expect(textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)).toBe('word');
    expect(document.activeElement).toBe(textarea);

    // Click Bold again -> unwrapped
    fireEvent.click(boldBtn);
    expect(textarea.value).toBe('hello word here');
    expect(textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)).toBe('word');
  });

  it('wraps with _ on Ctrl+I and toggles off on second Ctrl+I, plus supports Ctrl+B, U, Shift+X, Shift+P, E', () => {
    render(<MessageComposer {...composerProps({ initialDraft: 'word' })} />);
    const textarea = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;

    selectRange(textarea, 0, 4);
    fireEvent.keyDown(textarea, { key: 'i', ctrlKey: true });
    expect(textarea.value).toBe('_word_');
    expect(textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)).toBe('word');

    fireEvent.keyDown(textarea, { key: 'i', ctrlKey: true });
    expect(textarea.value).toBe('word');
    expect(textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)).toBe('word');

    fireEvent.keyDown(textarea, { key: 'u', ctrlKey: true });
    expect(textarea.value).toBe('__word__');
    fireEvent.keyDown(textarea, { key: 'u', ctrlKey: true });
    expect(textarea.value).toBe('word');

    fireEvent.keyDown(textarea, { key: 'X', ctrlKey: true, shiftKey: true });
    expect(textarea.value).toBe('~~word~~');
    fireEvent.keyDown(textarea, { key: 'X', ctrlKey: true, shiftKey: true });
    expect(textarea.value).toBe('word');

    fireEvent.keyDown(textarea, { key: 'P', ctrlKey: true, shiftKey: true });
    expect(textarea.value).toBe('||word||');
    fireEvent.keyDown(textarea, { key: 'P', ctrlKey: true, shiftKey: true });
    expect(textarea.value).toBe('word');

    fireEvent.keyDown(textarea, { key: 'e', ctrlKey: true });
    expect(textarea.value).toBe('`word`');
    fireEvent.keyDown(textarea, { key: 'e', ctrlKey: true });
    expect(textarea.value).toBe('word');

    const quoteBtn = screen.getByLabelText('Цитата');
    fireEvent.click(quoteBtn);
    expect(textarea.value).toBe('> word');
    expect(textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)).toBe('word');
    fireEvent.click(quoteBtn);
    expect(textarea.value).toBe('word');
    expect(textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)).toBe('word');
  });

  it('wraps selection as [word](https://x.y) via inline URL field without window.prompt', () => {
    const promptSpy = vi.spyOn(window, 'prompt');
    render(<MessageComposer {...composerProps({ initialDraft: 'see word now' })} />);
    const textarea = screen.getByTestId('composer-textarea') as HTMLTextAreaElement;

    selectRange(textarea, 4, 8);
    const linkBtn = screen.getByLabelText('Посилання');
    fireEvent.click(linkBtn);

    expect(promptSpy).not.toHaveBeenCalled();

    const urlInput = screen.getByLabelText('Адреса посилання') as HTMLInputElement;
    fireEvent.change(urlInput, { target: { value: 'https://x.y' } });

    const applyBtn = screen.getByLabelText('Застосувати');
    fireEvent.click(applyBtn);

    expect(textarea.value).toBe('see [word](https://x.y) now');
    expect(textarea.value.slice(textarea.selectionStart, textarea.selectionEnd)).toBe('word');
    promptSpy.mockRestore();
  });
});

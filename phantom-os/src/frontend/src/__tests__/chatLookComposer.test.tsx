/**
 * Composer, sheet D: [+ ghost] [surface field pill with a hairline, radius 24,
 * camera inside] [mic ghost]. No shadow, no raised sheet. Send is the only
 * amber thing, and only when there is text.
 */
import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

vi.mock('../services/messengerApi', async (orig) => {
  const real = await orig<typeof import('../services/messengerApi')>();
  return {
    ...real,
    messengerApi: {
      ...real.messengerApi,
      pulse: vi.fn(() => new Promise(() => {})),
      peerProfiles: vi.fn(() => new Promise(() => {})),
    },
  };
});

import { MessageComposer } from '../components/messenger/MessageComposer';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const CHAT = { id: 'chat_o', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'c1' } as unknown as Chat;
const props = {
  onSendMessage: () => {},
  onSendVoiceMessage: () => {},
  onOpenActions: () => {},
  onOpenScheduler: () => {},
  replyingTo: null,
  onCancelReply: () => {},
  editingMessage: null,
  onCancelEdit: () => {},
  onSaveEdit: () => {},
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: () => {},
  onClearSelectedQuotes: () => {},
  chatId: 'chat_o',
} as unknown as React.ComponentProps<typeof MessageComposer>;

const draw = () => {
  useMessengerStore.setState({ chats: [CHAT], activeChatId: 'chat_o', storageFailure: null } as never);
  return render(<MessageComposer {...props} />);
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('composer (mockup 04)', () => {
  it('the row itself is flat: no raised sheet, no shadow', () => {
    draw();
    const row = document.querySelector('[data-composer-pill]') as HTMLElement;
    expect(row.className).not.toMatch(/bg-raised|shadow-/);
  });

  it('the field is the pill: radius 24 on surface, attach sits outside it', () => {
    draw();
    const field = screen.getByTestId('composer-field');
    expect(field.className).toContain('rounded-composer');
    expect(field.className).toContain('bg-surface');
    expect(field.className).toContain('border-line');
    expect(field.className).not.toMatch(/shadow-/);
    expect(field.contains(screen.getByTestId('composer-attach'))).toBe(false);
  });

  it('T22: no camera button inside the field; camera is an attach tile', () => {
    draw();
    expect(screen.queryByTestId('composer-camera')).toBeNull();
    fireEvent.click(screen.getByTestId('composer-attach'));
    expect(screen.getByTestId('send-camera')).toBeTruthy();
  });

  it('T40: all attach tiles at once, no More tile', () => {
    draw();
    fireEvent.click(screen.getByTestId('composer-attach'));
    expect(screen.queryByTestId('send-more')).toBeNull();
    expect(screen.getByTestId('send-music')).toBeTruthy();
    expect(screen.getByTestId('send-video-note')).toBeTruthy();
  });

  it('mic is a ghost in text-1, never amber at rest (D)', () => {
    draw();
    const mic = screen.getByTestId('composer-media-button');
    expect(mic.className).not.toMatch(/(^|\s)bg-(ink|raised)/);
    expect(mic.className).toMatch(/(^|\s)text-ink(\s|$)/);
    expect(mic.className).not.toMatch(/bg-accent/);
  });

  it('with text: send is the only amber, camera steps aside', () => {
    const { container } = draw();
    fireEvent.change(screen.getByTestId('composer-textarea'), { target: { value: 'Буду о сьомій' } });
    const amber = [...container.querySelectorAll('[class*="bg-accent"]')].filter((el) => /(^|\s)bg-accent(\s|$)/.test(el.className));
    expect(amber).toEqual([screen.getByTestId('composer-send')]);
  });

  it('voice states sit on the same surface pill, not raised', () => {
    const src = readFileSync(join(__dirname, '..', 'components', 'messenger', 'MessageComposer.tsx'), 'utf8');
    expect(src).not.toMatch(/bg-raised rounded-composer/);
  });
});

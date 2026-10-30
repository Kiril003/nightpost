/**
 * A52 10.10: «Ще» різало підписи — «Video mes…», «Live locati…». Плитка
 * ширша за слово не стане, тож підпис — до двох рядків, а слова короткі.
 */
import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { tNow } from '../components/chat/tNow';

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
  chatId: 'grp',
};

const LONGEST_WORD = 10;
const setLang = (lang: 'en' | 'uk') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: lang } }));

afterEach(() => {
  cleanup();
  setLang('uk');
});

describe('«+ → Ще»: підписи плиток цілі', () => {
  for (const lang of ['en', 'uk'] as const) {
    it(`${lang}: жоден підпис не обрізано трьома крапками, слово влазить у плитку`, async () => {
      setLang(lang);
      render(<MessageComposer {...props} wherePeer={null} />);
      await act(async () => {});
      fireEvent.click(screen.getByTestId('composer-attach'));
      const sheet = screen.getByTestId('send-what-sheet');
      const labels = within(sheet).getAllByRole('button').map((b) => b.querySelector('span:last-child') as HTMLElement);
      expect(labels.length).toBeGreaterThan(3);
      expect(screen.getByTestId('send-video-note').textContent).toBe(lang === 'en' ? 'Video note' : 'Кружечок');
      const words = [...labels.map((l) => l.textContent ?? ''), tNow('messageComposer.liveStop')];
      for (const label of labels) {
        expect(label.className).not.toMatch(/\btruncate\b/);
        expect(label.className).toMatch(/\bline-clamp-2\b/);
      }
      for (const text of words) {
        const long = text.split(/\s+/).filter((w) => w.length > LONGEST_WORD);
        expect(long, `«${text}» не влазить у плитку`).toEqual([]);
        expect(text.split(/\s+/).length, `«${text}» довше двох рядків`).toBeLessThanOrEqual(2);
      }
    });
  }
});

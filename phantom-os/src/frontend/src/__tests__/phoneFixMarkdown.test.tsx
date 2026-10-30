/** A56 10.10: «Drafted: **Olena · 0.05 SOL**» — розмітка в превʼю рядка. */
import { describe, expect, it } from 'vitest';
import { plainSnippet } from '../components/messenger/chatListFormat';
import { conv, mountRoot, previewLine, useRootHooks } from './phoneFixRoot';

useRootHooks();

describe('3. превʼю без розмітки', () => {
  it('рядок Nightpost — без зірочок', async () => {
    await mountRoot([
      conv('chat_phantom', 'Nightpost', 'phantom', {
        last_kind: 'text', last_snippet: 'Drafted: **Olena · 0.05 SOL**. Confirm with yes', last_at: '2026-10-10T07:00:00',
      }),
    ]);
    expect(previewLine('chat_phantom')?.textContent).toBe('Drafted: Olena · 0.05 SOL. Confirm with yes');
  });

  it('спільний помічник: жирне, курсив, код, закреслене, посилання, цитата; спойлер не розкривається', () => {
    expect(plainSnippet('> **a** *c* `d` ~e~ [f](https://x.y)')).toBe('a c d e f');
    expect(plainSnippet('file_name_x')).toBe('file_name_x');
    expect(plainSnippet('ціна ||сюрприз|| тут')).not.toContain('сюрприз');
    expect(plainSnippet('2 * 3 * 4')).toBe('2 * 3 * 4');
  });
});

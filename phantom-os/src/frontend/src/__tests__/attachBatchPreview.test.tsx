/**
 * Групове надсилання вкладень.
 *
 * Власник: «файли і фото не по одному, а групуванно можна було надсилати».
 * Раніше `handlePickedFile` слав файли послідовно, кожен одразу в стрічку,
 * з окремою смугою прогресу на файл. Тепер кілька файлів ВІДКРИВАЮТЬ спільний
 * передогляд (мініатюри, можна прибрати зайве, один підпис) — і йдуть на
 * вузол лише після «Надіслати N», з ОДНІЄЮ прогресією на всю пачку. Один
 * не-фото файл летить напряму; одне фото відкриває передогляд (P04: обрізати,
 * якість).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import '@testing-library/jest-dom/vitest';

import { useMessengerStore } from '../stores/messengerStore';
import { MessageComposer } from '../components/messenger/MessageComposer';
import type { Chat } from '../types/messenger';
import { mediaLimitLabel, MEDIA_PLAIN_LIMIT_BYTES } from '../services/messengerMedia';

const chat = (): Chat => ({
  id: 'chat_batch',
  title: 'Марта',
  avatar: '',
  type: 'dm',
  circle: 'work',
  unreadCount: 0,
  messages: [],
});

const composerProps = {
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
  chatId: 'chat_batch',
};

const pngFile = (name: string) => new File([new Uint8Array([1, 2, 3])], name, { type: 'image/png' });

/** Понад межу вузла рівно на байт більше — розмір підроблено, байтів не тримаємо. */
const hugeFile = (name: string) => {
  const f = new File([new Uint8Array([1])], name, { type: 'video/mp4' });
  Object.defineProperty(f, 'size', { value: MEDIA_PLAIN_LIMIT_BYTES + 1_000_000 });
  return f;
};

const setFiles = (input: HTMLElement, files: File[]) => {
  Object.defineProperty(input, 'files', { value: files, configurable: true });
  fireEvent.change(input);
};

describe('передогляд пачки вкладень перед відправкою', () => {
  let sendAttachment: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    sendAttachment = vi.fn().mockResolvedValue(undefined);
    useMessengerStore.setState({
      chats: [chat()],
      activeChatId: 'chat_batch',
      storageFailure: null,
      replyingTo: null,
      editingMessage: null,
      sendAttachment: sendAttachment as never,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('кілька файлів відкривають передогляд і НЕ шлють нічого одразу', () => {
    render(<MessageComposer {...composerProps} />);
    const input = screen.getByTestId('composer-file-input');
    setFiles(input, [pngFile('a.png'), pngFile('b.png'), pngFile('c.png')]);

    expect(screen.getByTestId('attach-batch-preview')).toBeInTheDocument();
    expect(screen.getAllByTestId('attach-batch-remove')).toHaveLength(3);
    expect(sendAttachment).not.toHaveBeenCalled();
  });

  it('один не-фото файл летить напряму — без передогляду', async () => {
    render(<MessageComposer {...composerProps} />);
    const input = screen.getByTestId('composer-file-input');
    setFiles(input, [new File([new Uint8Array([1, 2, 3])], 'solo.pdf', { type: 'application/pdf' })]);

    await waitFor(() => expect(sendAttachment).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('attach-batch-preview')).not.toBeInTheDocument();
  });

  it('«Прибрати» зменшує пачку, не займаючи вузол', () => {
    render(<MessageComposer {...composerProps} />);
    const input = screen.getByTestId('composer-file-input');
    setFiles(input, [pngFile('a.png'), pngFile('b.png')]);

    fireEvent.click(screen.getAllByTestId('attach-batch-remove')[0]);

    expect(screen.getAllByTestId('attach-batch-remove')).toHaveLength(1);
    expect(sendAttachment).not.toHaveBeenCalled();
  });

  it('скасування пачки не викликає вузол узагалі', () => {
    render(<MessageComposer {...composerProps} />);
    const input = screen.getByTestId('composer-file-input');
    setFiles(input, [pngFile('a.png'), pngFile('b.png')]);

    fireEvent.click(screen.getByLabelText('Скасувати пачку вкладень'));

    expect(screen.queryByTestId('attach-batch-preview')).not.toBeInTheDocument();
    expect(sendAttachment).not.toHaveBeenCalled();
  });

  it('«Надіслати N» шле кожен файл окремо, з тим самим batch_id і однією прогресією', async () => {
    render(<MessageComposer {...composerProps} />);
    const input = screen.getByTestId('composer-file-input');
    setFiles(input, [pngFile('a.png'), pngFile('b.png')]);

    fireEvent.click(screen.getByTestId('attach-batch-send'));

    await waitFor(() => expect(sendAttachment).toHaveBeenCalledTimes(2));

    // Обидва виклики — та сама пачка (id, total), різний index.
    const [file1, , caption1, batch1] = sendAttachment.mock.calls[0];
    const [file2, , caption2, batch2] = sendAttachment.mock.calls[1];
    expect(file1.name).toBe('a.png');
    expect(file2.name).toBe('b.png');
    expect(batch1).toEqual({ id: batch1.id, index: 0, total: 2 });
    expect(batch2).toEqual({ id: batch1.id, index: 1, total: 2 });
    // Підпис — лише з першим вкладенням.
    expect(caption2).toBeUndefined();
    void caption1;

    // Передогляд зникає одразу — людина бачить ОДНУ прогресію, не сітку, що завмерла.
    expect(screen.queryByTestId('attach-batch-preview')).not.toBeInTheDocument();
  });
});

/**
 * Межа — ДО спроби, не після.
 *
 * `sendAttachment` перевіряв розмір усередині себе, тобто вже ПІСЛЯ того, як
 * людина натиснула «Надіслати N»: завеликий файл спокійно лежав у сітці
 * передогляду поруч зі здоровими, з мініатюрою й розміром, і ставав червоним
 * рядком лише в кінці — коли решта вже поїхала. Тепер він не потрапляє в
 * пачку взагалі, а людина читає ЧОМУ і яка межа.
 */
describe('завелике вкладення відмовляється на виборі', () => {
  let sendAttachment: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    sendAttachment = vi.fn().mockResolvedValue(undefined);
    useMessengerStore.setState({
      chats: [chat()],
      activeChatId: 'chat_batch',
      storageFailure: null,
      replyingTo: null,
      editingMessage: null,
      sendAttachment: sendAttachment as never,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('один завеликий файл не доходить до вузла, і причина названа з межею', async () => {
    render(<MessageComposer {...composerProps} />);
    setFiles(screen.getByTestId('composer-file-input'), [hugeFile('відео.mp4')]);

    await waitFor(() => expect(screen.getByText(/важить/)).toBeInTheDocument());
    expect(screen.getByText(new RegExp(mediaLimitLabel()))).toBeInTheDocument();
    expect(sendAttachment).not.toHaveBeenCalled();
  });

  it('у пачці завеликий відсівається, здорові лишаються', () => {
    render(<MessageComposer {...composerProps} />);
    setFiles(screen.getByTestId('composer-file-input'), [
      pngFile('a.png'),
      hugeFile('відео.mp4'),
      pngFile('b.png'),
    ]);

    // Дві мініатюри, не три: завеликий не вдає, що чекає своєї черги.
    expect(screen.getAllByTestId('attach-batch-remove')).toHaveLength(2);
    expect(screen.getByText(/відео\.mp4/)).toBeInTheDocument();
    expect(sendAttachment).not.toHaveBeenCalled();
  });

  it('порожній файл теж названий, а не проковтнутий', async () => {
    render(<MessageComposer {...composerProps} />);
    setFiles(screen.getByTestId('composer-file-input'), [
      new File([], 'нуль.bin', { type: 'application/octet-stream' }),
    ]);

    await waitFor(() => expect(screen.getByText(/порожній/)).toBeInTheDocument());
    expect(sendAttachment).not.toHaveBeenCalled();
  });
});

/**
 * Пачку можна спинити — і те, що вже поїхало, про це чесно сказано.
 *
 * Скасування спиняє МІЖ файлами: вкладення, яке вже в людини, назад не
 * забирається, і «скасовано» без числа було б неправдою саме про нього.
 */
describe('скасування пачки під час надсилання', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('пачка йде одним викликом стору (по три одночасно); «спинити» доходить до черги, а її слова — до людини', async () => {
    // Тип оголошено явно: TypeScript не відстежує присвоєння всередині
    // виконавця Promise і звузив би `release` до null назавжди.
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    let sawCancel = false;
    const sendAttachments = vi.fn().mockImplementation(async (files: File[], opts: { cancelled?: () => boolean; onProgress?: (p: number) => void }) => {
      opts.onProgress?.(30);
      await gate;
      sawCancel = Boolean(opts.cancelled?.());
      return sawCancel ? [`Скасовано: ${files.length - 1} з ${files.length} не почались`] : [];
    });
    useMessengerStore.setState({
      chats: [chat()],
      activeChatId: 'chat_batch',
      storageFailure: null,
      replyingTo: null,
      editingMessage: null,
      sendAttachments: sendAttachments as never,
    });

    render(<MessageComposer {...composerProps} />);
    setFiles(screen.getByTestId('composer-file-input'), [
      pngFile('a.png'), pngFile('b.png'), pngFile('c.png'),
    ]);
    fireEvent.click(screen.getByTestId('attach-batch-send'));

    await waitFor(() => expect(screen.getByTestId('attach-upload-cancel')).toBeInTheDocument());
    expect(sendAttachments).toHaveBeenCalledTimes(1);
    expect((sendAttachments.mock.calls[0][0] as File[]).map((f) => f.name)).toEqual(['a.png', 'b.png', 'c.png']);
    fireEvent.click(screen.getByTestId('attach-upload-cancel'));
    release();

    await waitFor(() => expect(screen.getByText(/Скасовано: 2 з 3 не почались/)).toBeInTheDocument());
    expect(sawCancel).toBe(true);
  });
});

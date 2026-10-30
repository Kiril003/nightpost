/**
 * Ліміт вкладення — від ВУЗЛА, а не вигаданий у вебі.
 *
 * `MEDIA_LIMIT_BYTES` була константою: `4 МіБ` на телефоні, `25 МіБ` на ПК,
 * ніяк не звʼязаною з тим, що реально прийме `blobs.py`/`PeerFile.MAX_BYTES`.
 * Підніми стелю в одному місці — і числа розійдуться мовчки: веб або
 * відмовляє файлам, які вузол узяв би, або шифрує мегабайти заради 413,
 * дізнаного вже ПІСЛЯ роботи. `applyMediaLimitFromNode` — та функція, яку
 * `hydrateFromNode` викликає з відповіддю `/identity`; тести нижче стережуть
 * її контракт: відсутнє чи бите число НЕ рухає межу («вузол ще не каже» —
 * не помилка, і тому немає шляху назад до «fallback», щойно вузол хоч раз
 * підтвердив число), а справжнє — рухає ВСІ похідні числа разом.
 *
 * Стан модуля — спільний на файл, тож тести читають одне одного: перші два
 * стережуть інваріант «немає нового числа → старе не змінилось», наступні —
 * що ПІДТВЕРДЖЕНЕ число справді застосувалось. Порядок навмисний, не
 * випадковий: побите/відсутнє значення перевіряємо ДО того, як інший тест
 * підтвердить справжнє (інакше «не змінилось» звірялося б уже з підтвердженим).
 *
 * Контракт хендшейку — docs/MEDIA_LIMIT_HANDSHAKE.md.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { applyMediaLimitFromNode, mediaLimitSource } from '../services/messengerMedia';
import * as messengerMedia from '../services/messengerMedia';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';

describe('межа вкладення приходить з /identity, а не вигадана у вебі', () => {
  it('без поля з вузла — межа НЕ рухається (вузол ще не сказав нічого)', () => {
    const before = messengerMedia.MEDIA_LIMIT_BYTES;
    const sourceBefore = mediaLimitSource();
    applyMediaLimitFromNode(undefined);
    expect(messengerMedia.MEDIA_LIMIT_BYTES).toBe(before);
    expect(mediaLimitSource()).toBe(sourceBefore);
  });

  it('биті значення (0, відʼємне, не число, нескінченність) теж не рухають межу', () => {
    const before = messengerMedia.MEDIA_LIMIT_BYTES;
    applyMediaLimitFromNode(0);
    applyMediaLimitFromNode(-100);
    applyMediaLimitFromNode(Number.NaN);
    applyMediaLimitFromNode(Number.POSITIVE_INFINITY);
    expect(messengerMedia.MEDIA_LIMIT_BYTES).toBe(before);
  });

  it('справжнє число з вузла рухає І межу шифротексту, І межу відкритого файла, І напис', () => {
    const nodeBytes = 10 * 1024 * 1024; // 10 МіБ — навмисне не 4 і не 25, щоб не збігтися з ГІДом випадково
    applyMediaLimitFromNode(nodeBytes);

    expect(messengerMedia.MEDIA_LIMIT_BYTES).toBe(nodeBytes);
    expect(messengerMedia.MEDIA_PLAIN_LIMIT_BYTES).toBe(nodeBytes - 64);
    expect(messengerMedia.mediaLimitLabel()).toBe('до 10 МБ');
    expect(mediaLimitSource()).toBe('node');
  });

  it('після підтвердження вузлом наступний виклик без поля НЕ повертає стан до fallback', () => {
    // Продовження попереднього тесту: вузол уже сказав 10 МіБ. Мовчання при
    // наступному опиті — не привід вдавати, що підтвердження й не було.
    const before = messengerMedia.MEDIA_LIMIT_BYTES;
    applyMediaLimitFromNode(undefined);
    expect(messengerMedia.MEDIA_LIMIT_BYTES).toBe(before);
    expect(mediaLimitSource()).toBe('node');
  });

  it('дробові мегабайти пишуться однією десятковою, не вдають точність, якої нема', () => {
    applyMediaLimitFromNode(4.5 * 1024 * 1024);
    expect(messengerMedia.mediaLimitLabel()).toBe('до 4.5 МБ');
  });

  it('ціле число мегабайтів пишеться без зайвого «.0»', () => {
    applyMediaLimitFromNode(7 * 1024 * 1024);
    expect(messengerMedia.mediaLimitLabel()).toBe('до 7 МБ');
  });
});

describe('hydrateFromNode питає /identity і застосовує межу вузла', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('гідратація застосовує max_attachment_bytes з відповіді вузла', async () => {
    const nodeBytes = 12 * 1024 * 1024;
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({
      node_id: 'n1',
      bundle: {},
      compact: 'x',
      max_attachment_bytes: nodeBytes,
    } as never);
    vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([] as never);

    await useMessengerStore.getState().hydrateFromNode();
    // Виклик до /identity — void-проміс (не блокує решту гідратації);
    // чекаємо один тік, щоб .then() встиг застосувати межу.
    await Promise.resolve();
    await Promise.resolve();

    expect(messengerMedia.MEDIA_LIMIT_BYTES).toBe(nodeBytes);
    expect(mediaLimitSource()).toBe('node');
  });
});

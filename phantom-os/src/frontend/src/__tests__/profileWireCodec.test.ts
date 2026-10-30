/**
 * `profileWireCodec` проти ДРОТУ, а не проти себе.
 *
 * До 16.09 цей файл перевіряв кодек сам проти себе — кругова подорож,
 * стелі, три стани — і був повністю зелений, доки кодек розходився з
 * `core-net/.../peer/PeerProfile.kt` у трьох місцях одночасно (номери тегів
 * зсунуті на одиницю, `rev` варінтом замість чотирьох байтів, кадр без
 * конверта `PH-PRF:`+base64). Кругова подорож не вміє цього спіймати: вона
 * питає «чи читаю я те, що сам написав», а не «чи прочитає це той, кому я
 * пишу».
 *
 * Тому тепер першим стоїть GOLDEN — той самий рядок, що в
 * `PeerProfileTest.kt` («золотий вектор»). Він не знятий із виходу жодної зі
 * сторін: порахований третьою рукою за описом формату з
 * `docs/PROFILE_WIRE_CONTRACT.md`. Збіг доводить, що обидва кодувальники
 * читають ОДИН опис, а не однаково помиляються.
 */
import { describe, it, expect } from 'vitest';
import {
  decodeBannerPhotoFrame,
  PROFILE_WIRE_LIMITS,
  PROFILE_WIRE_TAG,
  byteLength,
  decodeProfileFrame,
  encodeProfileFrame,
  isNewerRev,
  isProfileFrame,
  truncateToByteLimit,
} from '../services/profileWireCodec';

/**
 * rev=7, avatar=[1,2,3], emoji="🇺🇦", text="привіт", bio="біо".
 *
 * Байти: 01 04 00000007 | 02 03 010203 | 03 08 <прапор> | 04 0C <привіт> |
 * 05 06 <біо> — тег, варінт-довжина, значення; `rev` твердими чотирма
 * байтами big-endian. Далі base64 без доповнення під префіксом `PH-PRF:`.
 */
const GOLDEN = 'PH-PRF:AQQAAAAHAgMBAgMDCPCfh7rwn4emBAzQv9GA0LjQstGW0YIFBtCx0ZbQvg';

const GOLDEN_INPUT = {
  rev: 7,
  avatarThumb: new Uint8Array([1, 2, 3]),
  statusEmoji: '🇺🇦',
  statusText: 'привіт',
  bio: 'біо',
};

/** Сирі байти з кадру — щоб тести могли зібрати навмисно кривий. */
function rawOf(frame: string): number[] {
  const body = frame.slice(PROFILE_WIRE_TAG.length);
  const padded = body + '='.repeat((4 - (body.length % 4)) % 4);
  return [...atob(padded)].map((c) => c.charCodeAt(0));
}

function frameOf(bytes: number[]): string {
  return PROFILE_WIRE_TAG + btoa(String.fromCharCode(...bytes)).replace(/=+$/, '');
}

const GOLDEN_FACE = 'PH-PRF:AQQAAAAHAgMBAgMDCPCfh7rwn4emBAzQv9GA0LjQstGW0YIFBtCx0ZbQvgYI0KHQvtC90Y8HBXNvbmlhCAIJCQ';

describe('теги 6–8: імʼя, заява, фото', () => {
  const input = { ...GOLDEN_INPUT, name: 'Соня', handle: 'sonia', photo: new Uint8Array([9, 9]) };

  it('складаємо рівно той рядок, що PeerProfile.encode (порахований за описом)', () => {
    expect(encodeProfileFrame(input)).toBe(GOLDEN_FACE);
  });

  it('читаємо назад імʼя, заяву й фото', () => {
    const d = decodeProfileFrame(GOLDEN_FACE)!;
    expect(d.name).toEqual({ state: 'value', value: 'Соня' });
    expect(d.handle).toEqual({ state: 'value', value: 'sonia' });
    expect(d.photo).toEqual({ state: 'value', value: new Uint8Array([9, 9]) });
  });

  it('старий кадр без них: absent; порожнє імʼя — не очищення; handle null — очищення', () => {
    expect(decodeProfileFrame(GOLDEN)!.name).toEqual({ state: 'absent' });
    expect(decodeProfileFrame(encodeProfileFrame({ rev: 1, name: '' }))!.name).toEqual({ state: 'absent' });
    expect(decodeProfileFrame(encodeProfileFrame({ rev: 1, handle: null }))!.handle).toEqual({ state: 'empty' });
  });

  it('фото понад стелю — відмова, кадр з фото понад стелю відкидається', () => {
    expect(() => encodeProfileFrame({ rev: 1, photo: new Uint8Array(PROFILE_WIRE_LIMITS.photoBytes + 1) })).toThrow(RangeError);
  });
});

describe('золотий вектор — дзеркало Kotlin', () => {
  it('складаємо рівно той рядок, що складає PeerProfile.encode', () => {
    expect(encodeProfileFrame(GOLDEN_INPUT)).toBe(GOLDEN);
  });

  it('читаємо рівно те, що поклав туди Kotlin', () => {
    const decoded = decodeProfileFrame(GOLDEN);
    expect(decoded).not.toBeNull();
    expect(decoded!.rev).toBe(7);
    expect(decoded!.avatarThumb).toEqual({ state: 'value', value: new Uint8Array([1, 2, 3]) });
    expect(decoded!.statusEmoji).toEqual({ state: 'value', value: '🇺🇦' });
    expect(decoded!.statusText).toEqual({ state: 'value', value: 'привіт' });
    expect(decoded!.bio).toEqual({ state: 'value', value: 'біо' });
  });

  it('кадр — це рядок із родовим префіксом, а не голі байти', () => {
    expect(GOLDEN.startsWith(PROFILE_WIRE_TAG)).toBe(true);
    expect(isProfileFrame(GOLDEN)).toBe(true);
    expect(isProfileFrame('PH-RTE:щось інше')).toBe(false);
    expect(isProfileFrame('просто слово')).toBe(false);
  });
});

describe('encodeProfileFrame / decodeProfileFrame', () => {
  it('кругова подорож: те, що закодували, те й прочитали', () => {
    const avatar = new Uint8Array([1, 2, 3, 4, 5]);
    const frame = encodeProfileFrame({
      rev: 7,
      avatarThumb: avatar,
      statusEmoji: '⚡',
      statusText: 'У фокусі',
      bio: 'Коротко про себе',
    });
    const decoded = decodeProfileFrame(frame);
    expect(decoded).not.toBeNull();
    expect(decoded!.rev).toBe(7);
    expect(decoded!.avatarThumb).toEqual({ state: 'value', value: avatar });
    expect(decoded!.statusEmoji).toEqual({ state: 'value', value: '⚡' });
    expect(decoded!.statusText).toEqual({ state: 'value', value: 'У фокусі' });
    expect(decoded!.bio).toEqual({ state: 'value', value: 'Коротко про себе' });
  });

  /**
   * `rev` більший за 2³¹ ламав би знакову арифметику зсувів у JS
   * (`payload[0] << 24` дає відʼємне для байта ≥ 0x80). На дроті це `u32`.
   */
  it('великий rev читається без знакового переповнення', () => {
    const frame = encodeProfileFrame({ rev: 0xf0000001, bio: 'х' });
    expect(decodeProfileFrame(frame)!.rev).toBe(0xf0000001);
  });

  it('поле, якого не передали (undefined) — «ще не приїхало», не порожньо', () => {
    const frame = encodeProfileFrame({ rev: 1 });
    const decoded = decodeProfileFrame(frame)!;
    expect(decoded.avatarThumb).toEqual({ state: 'absent' });
    expect(decoded.bio).toEqual({ state: 'absent' });
  });

  it('явне очищення (null) — це не те саме, що «не приїхало»', () => {
    const frame = encodeProfileFrame({ rev: 2, bio: null, avatarThumb: null });
    const decoded = decodeProfileFrame(frame)!;
    expect(decoded.bio).toEqual({ state: 'empty' });
    expect(decoded.avatarThumb).toEqual({ state: 'empty' });
    // Те, чого явно не чіпали в цьому виклику, лишається «не приїхало».
    expect(decoded.statusText).toEqual({ state: 'absent' });
  });

  it('стеля — тверда: кадр не збирається, якщо поле більше за межу', () => {
    const tooLong = 'а'.repeat(PROFILE_WIRE_LIMITS.bioBytes); // кирилиця — 2 байти на символ
    expect(() => encodeProfileFrame({ rev: 1, bio: tooLong })).toThrow(RangeError);

    const oversizedAvatar = new Uint8Array(PROFILE_WIRE_LIMITS.avatarThumbBytes + 1);
    expect(() => encodeProfileFrame({ rev: 1, avatarThumb: oversizedAvatar })).toThrow(RangeError);
  });

  it('рівно на межі — проходить', () => {
    const exact = new Uint8Array(PROFILE_WIRE_LIMITS.avatarThumbBytes);
    expect(() => encodeProfileFrame({ rev: 1, avatarThumb: exact })).not.toThrow();
  });

  it('обірваний кадр — ціла відмова, не часткове застосування', () => {
    const frame = encodeProfileFrame({ rev: 5, bio: 'Повний текст, який не мав доїхати' });
    // Знімаємо хвіст base64 — рівно те, що зробив би обірваний переказ.
    const truncated = frame.slice(0, frame.length - 6);
    expect(decodeProfileFrame(truncated)).toBeNull();
  });

  it('кадр без rev — відмова: rev обовʼязковий контрактом', () => {
    // Зібрано вручну: сам лише тег bio (5), без tag=REV.
    const bogus = frameOf([5, 2, 104, 105]);
    expect(decodeProfileFrame(bogus)).toBeNull();
  });

  /** `rev` не тієї довжини — це чужий кадр, а не «інший варіант rev». */
  it('rev не з чотирьох байтів — кадр відкидається цілком', () => {
    expect(decodeProfileFrame(frameOf([1, 1, 7]))).toBeNull();
  });

  it('невідомий тег пропускається, решта кадру парситься', () => {
    const known = rawOf(encodeProfileFrame({ rev: 3, statusText: 'Кава' }));
    // Дописуємо невідомий тег 99 з payload довжиною 4 в кінець кадру.
    const decoded = decodeProfileFrame(frameOf([...known, 99, 4, 9, 9, 9, 9]));
    expect(decoded).not.toBeNull();
    expect(decoded!.statusText).toEqual({ state: 'value', value: 'Кава' });
  });

  it('банер довшої майбутньої версії — не повідомлено, імʼя лишається', () => {
    const d = decodeProfileFrame('PH-PRF:AQQAAAAHBgJJYQsQAgcAAAAAAAAAAAAAAAAAAA');
    expect(d).not.toBeNull();
    expect(d!.name).toEqual({ state: 'value', value: 'Ia' });
    expect(d!.banner).toEqual({ state: 'absent' });
  });

  it('останнє повторення тега виграє (пересланий двічі кадр)', () => {
    const first = rawOf(encodeProfileFrame({ rev: 1, statusText: 'Старе' }));
    const second = rawOf(encodeProfileFrame({ rev: 1, statusText: 'Нове' }));
    const decoded = decodeProfileFrame(frameOf([...first, ...second]))!;
    expect(decoded.statusText).toEqual({ state: 'value', value: 'Нове' });
  });

  it('не наш рід не читається як профіль', () => {
    expect(decodeProfileFrame('PH-RTE:1788517702898;337;258;FOOT')).toBeNull();
    expect(decodeProfileFrame('привіт, як справи')).toBeNull();
  });
});

describe('isNewerRev — дзеркало PeerProfile.isNewer', () => {
  it('нічого не знаємо — беремо', () => {
    expect(isNewerRev(1, null)).toBe(true);
    expect(isNewerRev(1, undefined)).toBe(true);
  });

  it('рівний і менший — повтор переказу, мовчки повз', () => {
    expect(isNewerRev(4, 4)).toBe(false);
    expect(isNewerRev(3, 4)).toBe(false);
    expect(isNewerRev(5, 4)).toBe(true);
  });
});

describe('truncateToByteLimit / byteLength', () => {
  it('не ріже багатобайтовий символ навпіл', () => {
    const text = 'привіт'; // кирилиця, 2 байти на символ у UTF-8
    const limit = byteLength('прив') + 1; // межа впаде всередину наступного символу
    const cut = truncateToByteLimit(text, limit);
    expect(byteLength(cut)).toBeLessThanOrEqual(limit);
    // Результат — валідний рядок без replacement-символу.
    expect(cut).not.toContain('�');
    expect(text.startsWith(cut)).toBe(true);
  });

  it('коротший за межу рядок лишається як є', () => {
    expect(truncateToByteLimit('ок', 100)).toBe('ок');
  });
});

describe('теги 11–12 і PH-PBN: банер', () => {
  // Ті самі рядки, що PeerBannerTest.kt і test_peer_frames_vectors.py.
  const GOLDEN_BANNER = 'PH-PRF:AQQAAAAHCwcBAgGATQD/DCYAESIzRFVmd4iZqrvM3e7/AAPo/+7dzLuqmYh3ZlVEMyIRAABgAA';
  const banner = {
    items: [
      { hash: '00112233445566778899aabbccddeeff', size: 1000, fx: 128, fy: 77 },
      { hash: 'ffeeddccbbaa99887766554433221100', size: 24576, fx: 0, fy: 255 },
    ],
    videoStill: true,
  };

  it('складаємо рівно той рядок, що PeerProfile.encode', () => {
    expect(encodeProfileFrame({ rev: 7, banner })).toBe(GOLDEN_BANNER);
  });

  it('читаємо назад; стирання — empty; без тегів — absent', () => {
    expect(decodeProfileFrame(GOLDEN_BANNER)!.banner).toEqual({ state: 'value', value: banner });
    expect(encodeProfileFrame({ rev: 8, banner: null })).toBe('PH-PRF:AQQAAAAICwA');
    expect(decodeProfileFrame('PH-PRF:AQQAAAAICwA')!.banner).toEqual({ state: 'empty' });
    expect(decodeProfileFrame(GOLDEN)!.banner).toEqual({ state: 'absent' });
  });

  it('сім фото або фото понад 24 КіБ — кидає', () => {
    const item = banner.items[0];
    expect(() => encodeProfileFrame({ rev: 1, banner: { items: Array(7).fill(item), videoStill: false } })).toThrow(RangeError);
    expect(() => encodeProfileFrame({ rev: 1, banner: { items: [{ ...item, size: 24577 }], videoStill: false } })).toThrow(RangeError);
  });

  it('кадр фото банера читається з хешем', () => {
    const p = decodeBannerPhotoFrame('PH-PBN:AfwW19zunK6D7zkjIiqBzNj/2P/gABA')!;
    expect(p.hash).toBe('fc16d7dcee9cae83ef3923222a81ccd8');
    expect(Array.from(p.bytes)).toEqual([0xff, 0xd8, 0xff, 0xe0, 0, 0x10]);
    expect(decodeBannerPhotoFrame('PH-PRF:AQQAAAAICwA')).toBeNull();
  });
});

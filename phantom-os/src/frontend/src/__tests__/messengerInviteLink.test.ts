/**
 * Запрошення їде https-посиланням, а код телефона — запасом поруч (D6, 08.10).
 *
 * Досі людина отримувала `phantom://invite/PH2…` — месенджери такого не
 * підсвічують, тож для отримувача це був шматок тексту, а не двері. Тепер
 * текст «Поділитися» несе https://phantom-os.dev/i/#<код> (сторінка сайту
 * відкриває застосунок) і сам код окремим рядком — на випадок, коли
 * посилання не відкривається.
 *
 * `findInText` — веб-двійник `PeerInvite.findInText`: приймає все, що людина
 * могла вставити, і повертає лише ЦІЛИЙ код. Червоніти вміє: заберіть
 * розкодування `%3A` — падає «месенджер закодував двокрапки»; заберіть
 * другий рядок із shareText — падає «код і запасом».
 */
import { describe, it, expect, afterEach } from 'vitest';
import { messengerApi } from '../services/messengerApi';
import { resetPhonePortForTests } from '../services/phonePort';
import {
  INVITE_WEB_BASE,
  addInvitedContact,
  findInText,
  inviteShareText,
  inviteWebLink,
  isOwnInvite,
  isWaiting,
  parseInvite,
  phoneInvitePeer,
  speaksReach,
} from '../utils/messengerInvite';

const CODE = 'PH2:cGVlcnxNYXJ0YXwxLjIuMy40fDQ0Mw:48377a';

describe('findInText — код зі всього, що людина могла вставити', () => {
  it('https-посилання сайту', () => {
    expect(findInText(`https://phantom-os.dev/i/#${CODE}`)).toBe(CODE);
  });

  it('месенджер закодував двокрапки в посиланні (%3A) — код однаково знаходиться', () => {
    const mangled = `https://phantom-os.dev/i/#${CODE.replace(/:/g, '%3A')}`;
    expect(findInText(mangled)).toBe(CODE);
    expect(parseInvite(mangled)).toEqual({ kind: 'compact', compact: CODE });
  });

  it('phantom://invite/… і голий код — теж', () => {
    expect(findInText(`phantom://invite/${CODE}`)).toBe(CODE);
    expect(findInText(CODE)).toBe(CODE);
  });

  it('увесь текст «Поділитися» з привітанням — один і той самий код', () => {
    expect(findInText(inviteShareText(CODE, 'uk'))).toBe(CODE);
    expect(findInText(inviteShareText(CODE, 'en'))).toBe(CODE);
  });

  it('обрізаний код — не код: краще чесне «не схоже», ніж зламаний контакт', () => {
    expect(findInText('https://phantom-os.dev/i/#PH2:cGVlcnxN')).toBeNull();
    expect(findInText('привіт, як справи')).toBeNull();
    expect(findInText('')).toBeNull();
    expect(findInText(null)).toBeNull();
  });
});

describe('текст «Поділитися» — https-посилання і код запасом', () => {
  it('посилання веде на сторінку сайту з кодом після #', () => {
    expect(inviteWebLink(CODE)).toBe(`https://phantom-os.dev/i/#${CODE}`);
    expect(INVITE_WEB_BASE).toBe('https://phantom-os.dev/i/#');
  });

  it('код і в посиланні, і окремим рядком запасом', () => {
    for (const locale of ['uk', 'en'] as const) {
      const text = inviteShareText(CODE, locale);
      expect(text).toContain(inviteWebLink(CODE));
      expect(text.split(CODE).length - 1).toBe(2);
      // Код запасом — останній рядок, цілий, без слів навколо: його копіюють.
      expect(text.trim().split('\n').at(-1)?.trim()).toBe(CODE);
      expect(text).not.toContain('phantom://');
    }
  });

  it('англійський і український текст називають Nightpost, а не безіменний «месенджер»', () => {
    expect(inviteShareText(CODE, 'en')).toContain(`Hi! Add me on Nightpost: https://phantom-os.dev/i/#${CODE}`);
    expect(inviteShareText(CODE, 'en')).not.toMatch(/on the messenger/i);
    expect(inviteShareText(CODE, 'uk')).toContain(`Привіт! Додай мене в Nightpost: https://phantom-os.dev/i/#${CODE}`);
    expect(inviteShareText(CODE, 'uk')).not.toMatch(/в месенджері/i);
  });
});

describe('«чекаємо» — лише зі слів моста, що каже `reach` (B0)', () => {
  it('reach: waiting — чекаємо; mutual — ні', () => {
    expect(isWaiting({ session_ready: false, reach: 'waiting' })).toBe(true);
    expect(isWaiting({ session_ready: true, reach: 'mutual' })).toBe(false);
  });

  it('без `reach` — не чекаємо ні від session_ready=true (телефон до B0), ні від false (ПК на код телефона)', () => {
    expect(isWaiting({ session_ready: true })).toBe(false);
    expect(isWaiting({ session_ready: false })).toBe(false);
  });

  it('міст «каже reach» — коли поле є в /identity або хоч в одному рядку контактів', () => {
    expect(speaksReach(null, [])).toBe(false);
    expect(speaksReach({ compact: 'x' }, [{ session_ready: true }])).toBe(false);
    expect(speaksReach({ compact: 'x', reach: 'door' }, [])).toBe(true);
    expect(speaksReach(null, [{ session_ready: true }, { session_ready: false, reach: 'waiting' }])).toBe(true);
  });
});

describe('моє ж запрошення в буфері', () => {
  const MINE = 'PH2:bWV8S3lyeWxvfHww:123abc'; // me|Kyrylo||0

  it('peer коду — перше поле тіла', () => {
    expect(phoneInvitePeer(MINE)).toBe('me');
    expect(phoneInvitePeer(CODE)).toBe('peer');
    expect(phoneInvitePeer('не код')).toBe('');
  });

  it('свій код — і голий, і в тексті «Поділитися»; чужий — ні', () => {
    expect(isOwnInvite(MINE, ['me'])).toBe(true);
    expect(isOwnInvite(inviteShareText(MINE, 'en'), [null, 'me'])).toBe(true);
    expect(isOwnInvite(inviteShareText(CODE, 'uk'), ['me'])).toBe(false);
    expect(isOwnInvite(MINE, [null, undefined, ''])).toBe(false);
    expect(isOwnInvite(null, ['me'])).toBe(false);
  });
});

describe('addInvitedContact — те саме тіло, що й messengerApi.addContact (W2), плюс source', () => {
  // Рецензія WC (08.10): `addInvitedContact` — копія `messengerApi.addContact`
  // (файл W2) з одним полем зверху. Змінить W2 тіло — копія мовчки відстане.
  // Цей сторож червоніє саме тоді: тіла мусять збігатися до поля `source`.
  afterEach(() => {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    resetPhonePortForTests();
  });

  const capture = () => {
    resetPhonePortForTests();
    const sent: Array<{ method: string; path: string; body: Record<string, unknown> }> = [];
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
      call: (method: string, path: string, body: string | null) => {
        sent.push({ method, path, body: body ? (JSON.parse(body) as Record<string, unknown>) : {} });
        return JSON.stringify({ ok: true, status: 200, data: { id: 'ct_x' } });
      },
    };
    return sent;
  };

  it.each([
    ['код телефона з адресою', 'Марта', { compact: CODE }, '1.2.3.4:443'],
    ['конверт ПК без адреси', '', { bundle: { identity_ed: 'k' } }, null],
  ] as const)('%s', async (_label, name, key, address) => {
    const sent = capture();
    await messengerApi.addContact(name, { ...key }, address);
    await addInvitedContact(name, { ...key }, address, 'camera');
    await addInvitedContact(name, { ...key }, address);
    const [theirs, camera, plain] = sent;
    expect(camera.method).toBe(theirs.method);
    expect(camera.path).toBe(theirs.path);
    const { source, ...rest } = camera.body;
    expect(source).toBe('camera');
    expect(rest).toEqual(theirs.body);
    expect(plain.body).toEqual(theirs.body);
  });
});

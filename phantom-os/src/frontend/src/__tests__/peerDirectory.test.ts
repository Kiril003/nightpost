/**
 * Довідник у вебі: підроблений запис не стає людиною на екрані.
 *
 * Три речі, які тут доводяться прогоном, а не абзацом:
 *
 *  1. ВЕБ ГОВОРИТЬ ТІЄЮ САМОЮ МОВОЮ, ЩО Й ТЕЛЕФОН. Вектор
 *     `directory_v2_vector.json` — спільний: його читає Kotlin
 *     (PeerDirectoryVectorTest) і pytest (test_directory.py). Підпис у ньому
 *     зроблено Kotlin'ом над ключем RFC 8032 §7.1 TEST 1. Ed25519
 *     детермінований, тож якщо цей модуль складе речення інакше хоч на
 *     пробіл — підпис не звіриться. Криптографія тут і є дзеркалом контракту:
 *     звірити нíчим підробити не можна, і пропуску тесту (assumeTrue на чуже
 *     дерево) тут немає.
 *
 *  2. ПІДРОБКА ВІДХИЛЯЄТЬСЯ. Чотири різні підробки, і кожна — окремий спосіб,
 *     яким сервер міг би підсунути чужий ключ під потрібним іменем. Жодна з
 *     них не дає стану, який можна показати.
 *
 *  3. БЕЗ ДОВІДНИКА ВСЕ ПРАЦЮЄ. Зачинений, відсутній, недосяжний — три різні
 *     стани, і жоден не вдає, що людини не існує.
 *
 * Червоніти вміє: приберіть звірку підпису у `verifyEntry` — падають усі
 * чотири підробки; змініть PUBLISH_PREFIX на v1 — падає вектор.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import vector from './directory_v2_vector.json';
import {
  checksum,
  directoryOrigin,
  fingerprint,
  handleHash,
  lookup,
  normalise,
  normaliseForPublish,
  parsePh2,
  publishMessage,
  shortId,
  spokenFingerprint,
  verifyEntry,
  verifySignature,
} from '../services/peerDirectory';

const HANDLE = vector.handle; // "marta_k"
const CODE = vector.invite.code;
const SIG = vector.expected.publish_sig_b64url;
const EXP = vector.exp;

/** Справжній запис, рівно такий, яким його віддає сервер. */
const genuine = () => ({ invite: CODE, sig: SIG, exp: EXP });

const decoder = new TextDecoder();

/**
 * Перескладає PH2 з іншими полями — з ПРАВИЛЬНОЮ контрольною сумою.
 *
 * Без цього підробка не була б підробкою: перший запис цього тесту робив
 * `CODE.replace(<ключ>)` по зовнішньому рядку, але ключ лежить УСЕРЕДИНІ
 * base64-тіла, тож заміна не знаходила нічого, запис їхав недоторканим і
 * тест зеленів на «підробці», якої не було. Саме тому конверт тут
 * перескладається чесно: сума сходиться, розбір проходить, і відхилити запис
 * може ЛИШЕ звірка підпису — те, що й перевіряється.
 */
const reencode = (fields: string[]): string => {
  // Через UTF-8, а не btoa(рядок): `btoa` бачить лише latin-1 і на «Марта»
  // кидає InvalidCharacterError — а телефон кодує саме UTF-8.
  const raw = new TextEncoder().encode(fields.join('|'));
  let bin = '';
  for (let i = 0; i < raw.length; i += 1) bin += String.fromCharCode(raw[i]);
  const body = btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `PH2:${body}:${checksum(body)}`;
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('спільний вектор: веб рахує те саме, що телефон і сервер', () => {
  it('адреса запису — sha256 імені, і саме та, що у векторі', async () => {
    expect(await handleHash(HANDLE)).toBe(vector.expected.handle_hash);
    // Регістр і «@» не роблять іншої людини.
    expect(normalise('@Marta_K')).toBe(HANDLE);
    expect(await handleHash(normalise('@Marta_K')!)).toBe(vector.expected.handle_hash);
  });

  it('PH2 розбирається на ті самі шість полів', () => {
    const invite = parsePh2(CODE);
    expect(invite).not.toBeNull();
    expect([
      invite!.peerId,
      invite!.name,
      invite!.pubKey,
      invite!.dhPubKey,
      invite!.host,
      invite!.port,
    ]).toEqual(vector.expected.invite_fields);
    // Показного імені в записі довідника немає — телефон зрізає його в
    // addressless(). Якби воно там було, сервер тримав би перелік людських
    // імен із ключами, тобто рівно той список, якого «не існує».
    expect(invite!.name).toBe('');
    expect(invite!.host).toBe('');
  });

  it('контрольна сума рахується як у Kotlin (FNV-1a по одиницях UTF-16)', () => {
    const body = CODE.split(':')[1];
    expect(checksum(body)).toBe(CODE.split(':')[2]);
  });

  it('підписане речення збігається байт у байт', async () => {
    const message = publishMessage(vector.expected.handle_hash, CODE, EXP);
    expect(decoder.decode(message)).toBe(vector.expected.publish_message);
    // І підпис, зроблений Kotlin'ом, звіряється тут.
    expect(await verifySignature(vector.ed25519.pub_b64, message, SIG)).toBe(true);
  });

  it('відбиток ключа — ті самі 24 цифри, що показує телефон', async () => {
    const digits = await fingerprint(vector.ed25519.pub_b64, vector.invite.dh_pub_b64);
    // Пораховано незалежно, за формулою PeerInvite.fingerprint:
    // sha256("<pub>|<dh>")[:12] як ціле, mod 10^24, до 24 знаків.
    expect(digits).toBe('323462827190606113012239');
    expect(spokenFingerprint(digits)).toBe('3234 6282 7190 6061 1301 2239');
  });
});

describe('справжній запис проходить і несе все, що треба показати', () => {
  it('стає found із видимим відбитком і ключем', async () => {
    const result = await verifyEntry(HANDLE, genuine());
    expect(result.kind).toBe('found');
    if (result.kind !== 'found') return;
    expect(result.record.handle).toBe(HANDLE);
    expect(result.record.compact).toBe(CODE);
    expect(result.record.fingerprint).toBe('323462827190606113012239');
    expect(result.record.invite.pubKey).toBe(vector.ed25519.pub_b64);
    expect(shortId(result.record.invite.peerId)).toBe('d1d2·7b5f');
  });
});

describe('підроблений підпис ВІДХИЛЯЄТЬСЯ', () => {
  it('зіпсований підпис → forged, а не «знайшли»', async () => {
    // Один символ. Ed25519 не має «майже дійсного» підпису.
    const broken = (SIG[0] === 'A' ? 'B' : 'A') + SIG.slice(1);
    const result = await verifyEntry(HANDLE, { ...genuine(), sig: broken });
    expect(result.kind).toBe('forged');
  });

  it('чужий ключ підпису в конверті → forged', async () => {
    // Сервер підміняє ключ підпису, лишаючи підпис. Конверт перескладено
    // чесно — сума сходиться, розбір проходить, — тож відхилити це може лише
    // звірка підпису.
    const f = [...vector.expected.invite_fields];
    f[2] = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA';
    const result = await verifyEntry(HANDLE, { ...genuine(), invite: reencode(f) });
    expect(result.kind).toBe('forged');
  });

  it('підмінений ключ ШИФРУВАННЯ → forged', async () => {
    // Посередник, який пропускає ключ підпису незайманим і міняє лише
    // X25519, читав би кожен лист. Підпис накриває рядок конверта ЦІЛКОМ,
    // тож і ця підміна ламає його.
    const f = [...vector.expected.invite_fields];
    f[3] = 'BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB';
    const result = await verifyEntry(HANDLE, { ...genuine(), invite: reencode(f) });
    expect(result.kind).toBe('forged');
  });

  it('той самий запис, підставлений під ЧУЖЕ імʼя → forged', async () => {
    // Підпис накриває sha256 імені. Тому запис Марти не можна повернути тому,
    // хто спитав «oleksiy1» — перехоплення чужого ніка неможливе за побудовою.
    const result = await verifyEntry('oleksiy1', genuine());
    expect(result.kind).toBe('forged');
  });

  it('запис із мережевою адресою → forged', async () => {
    // У довіднику адреси бути не може: вона розповіла б серверу, у якій мережі
    // стоїть апарат. Запис із нею — не той, за кого себе видає.
    const f = [...vector.expected.invite_fields];
    f[4] = '192.168.1.5';
    f[5] = '45871';
    const result = await verifyEntry(HANDLE, { ...genuine(), invite: reencode(f) });
    expect(result.kind).toBe('forged');
  });

  it('показне імʼя, підсунуте в конверт, теж ламає підпис', async () => {
    // Доки імʼя їхало в конверті, сервер тримав перелік людських імен із
    // ключами. Тепер його там немає — і дописати його сервер не може.
    const f = [...vector.expected.invite_fields];
    f[1] = 'Марта';
    const result = await verifyEntry(HANDLE, { ...genuine(), invite: reencode(f) });
    expect(result.kind).toBe('forged');
  });

  it('порожній або битий запис → forged, ніколи не found', async () => {
    for (const bad of [
      { invite: '', sig: SIG, exp: EXP },
      { invite: CODE, sig: '', exp: EXP },
      { invite: 'не запрошення', sig: SIG, exp: EXP },
      { invite: CODE, sig: SIG, exp: Number.NaN },
    ]) {
      expect((await verifyEntry(HANDLE, bad)).kind).toBe('forged');
    }
  });
});

describe('без довідника все працює як працювало', () => {
  const stubFetch = (impl: () => Promise<Response> | never) =>
    vi.stubGlobal('fetch', vi.fn(impl));

  it('довідник зачинено на сервері → closed, а не «нікого немає»', async () => {
    stubFetch(async () => new Response('', { status: 503 }));
    expect((await lookup(HANDLE)).kind).toBe('closed');
  });

  it('сервер недосяжний → unreachable, і це не відповідь про людину', async () => {
    stubFetch(() => {
      throw new TypeError('Failed to fetch');
    });
    const result = await lookup(HANDLE);
    expect(result.kind).toBe('unreachable');
  });

  it('нікого немає → absent, і нікого схожого не показано', async () => {
    stubFetch(async () => new Response(JSON.stringify({ detail: 'no such entry' }), { status: 404 }));
    const result = await lookup('nemaie_takogo');
    expect(result.kind).toBe('absent');
    // Порожній результат — це порожній результат. Поля record тут немає
    // взагалі, тож «показати схожого» нема з чого.
    expect('record' in result).toBe(false);
  });

  it('імʼя не тієї форми не йде в мережу взагалі', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    for (const bad of ['', '@', 'ко', 'Марта', 'a'.repeat(33), 'has space']) {
      expect((await lookup(bad)).kind).toBe('absent');
    }
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('публікувати коротше за вісім знаків не можна, шукати — можна', () => {
    // Захід проти словника: адреса запису — голий sha256, і кожен знак множить
    // простір на 37. Шукати старі короткі імена треба вміти, інакше правило,
    // введене сьогодні, замкнуло б імʼя, видане вчора.
    expect(normalise('marta_k')).toBe('marta_k');
    expect(normaliseForPublish('marta_k')).toBeNull();
    expect(normaliseForPublish('marta_kovalenko')).toBe('marta_kovalenko');
  });
});

describe('directory host (Fly → Oracle, vc30)', () => {
  it('asks Oracle, not Fly, when the build names no other host', () => {
    expect(directoryOrigin()).toBe('https://api.phantom-os.dev');
  });
});

/**
 * Глобальний пошук на СКЛІ: що людина бачить і чого не бачить ніколи.
 *
 * Модуль `peerDirectory` вже доведено окремо (peerDirectory.test.ts) — там
 * криптографія. Тут доводиться інше: що рішення модуля справді доїжджають до
 * екрана, і що екран не домальовує того, чого модуль не сказав. Зелений модуль
 * під мертвим або брехливим склом — це те саме «написане, але не викликане».
 *
 * Чотири речі, і кожна — окремий спосіб зробити продукт гіршим каталогом:
 *   1. підроблений запис НЕ стає карткою людини (звірка ДО показу);
 *   2. відбиток ключа для звірки голосом — за «Перевірити наживо», не на видноті;
 *   3. порожній результат каже правду й не показує «схожого»;
 *   4. смуга взагалі є на екрані — інакше все вище стосується коду, якого
 *      ніхто не бачить.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import vector from './directory_v2_vector.json';
import { DirectorySearchPanel } from '../components/messenger/DirectorySearchPanel';
import { CreateChatModal } from '../components/messenger/CreateChatModal';

const HANDLE = vector.handle;
const CODE = vector.invite.code;
const SIG = vector.expected.publish_sig_b64url;
const EXP = vector.exp;

/** Сервер, який відповідає рівно тим, що йому сказали. */
const serve = (status: number, body: unknown) =>
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response(JSON.stringify(body), { status })),
  );

/**
 * Як цей вимір дістає поле й кнопку — і чому не за написом.
 *
 * До 18.09 обидва бралися за ДОСЛІВНИМ текстом: `getByLabelText('Імʼя для
 * пошуку в глобальному довіднику')` і `getByText('Знайти в довіднику')`.
 * Коміт d67e1482 переписав ті написи («Імʼя точно так, як його вам
 * назвали», «Звірити імʼя») — свідома правка змісту: панель тепер каже, що
 * це ЗВІРКА, а не вгадування. Екран став кращим, а пʼять тестів червоними,
 * бо стерегли правопис, а не поведінку.
 *
 * Тому поле й кнопка беруться за РОЛЛЮ — тим самим, чим їх дістає людина з
 * читачем екрана. А дві властивості, які дослівний селектор стеріг лише
 * ВИПАДКОВО, тепер стережуться навмисне й не залежать від слів:
 *
 *   * поле має непорожнє озвучене імʼя — інакше в нього не влучити читачем
 *     екрана (перевіряється тут, на кожному пошуку);
 *   * на екрані немає двох кнопок з однаковим імʼям — саме про це коментар
 *     у самому компоненті: «сусідня смуга вже має кнопку з таким написом»
 *     (перевіряється в «смуга справді є на екрані», де обидві смуги разом).
 */
const nameField = (): HTMLElement => {
  const field = screen.getByRole('textbox');
  const by = field.getAttribute('aria-labelledby');
  const spoken =
    field.getAttribute('aria-label') ??
    (by ? (document.getElementById(by)?.textContent ?? '') : '');
  expect(
    spoken.trim().length,
    'поле пошуку без озвученого імені: з читачем екрана в нього не влучити',
  ).toBeGreaterThan(0);
  return field;
};

const searchFor = async (name: string) => {
  fireEvent.change(nameField(), { target: { value: name } });
  // Єдина кнопка смуги, доки немає результату. Зникне — тест почервоніє.
  fireEvent.click(screen.getByRole('button'));
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('картка знайденого', () => {
  beforeEach(() => serve(200, { invite: CODE, sig: SIG, exp: EXP }));

  it('показує ВИДИМИЙ відбиток ключа для звірки голосом', async () => {
    render(<DirectorySearchPanel onUseInvite={() => {}} />);
    await searchFor(`@${HANDLE}`);

    const card = await screen.findByTestId('directory-found');
    expect(card).toBeTruthy();

    // Звірка — необовʼязкова деталь: відбиток за «Перевірити наживо».
    expect(screen.queryByTestId('directory-fingerprint')).toBeNull();
    fireEvent.click(screen.getByText('Перевірити наживо'));
    // Ті самі 24 цифри, що показує телефон, і згруповані для читання вголос.
    const fp = screen.getByTestId('directory-fingerprint');
    expect(fp.textContent).toBe('3234 6282 7190 6061 1301 2239');

    // Показного імені сервер не називає — і екран не підставляє код ключа.
    expect(screen.queryByText('d1d2·7b5f')).toBeNull();
    expect(screen.getByText('Не перевірено')).toBeTruthy();
  });

  it('«Почати розмову» віддає рядок PH2 у вже доведену дорогу', async () => {
    const taken: string[] = [];
    render(<DirectorySearchPanel onUseInvite={(c) => taken.push(c)} />);
    await searchFor(HANDLE);

    fireEvent.click(await screen.findByText('Почати розмову'));
    // Саме запрошення, байт у байт, а не імʼя й не вигаданий ідентифікатор.
    expect(taken).toEqual([CODE]);
  });
});

describe('підроблений запис не стає людиною на екрані', () => {
  it('зіпсований підпис → гучне попередження, і ЖОДНОЇ картки', async () => {
    const broken = (SIG[0] === 'A' ? 'B' : 'A') + SIG.slice(1);
    serve(200, { invite: CODE, sig: broken, exp: EXP });

    render(<DirectorySearchPanel onUseInvite={() => {}} />);
    await searchFor(HANDLE);

    await waitFor(() => expect(screen.getByText(/недійсним підписом/)).toBeTruthy());
    // Головне — НЕ те, що є попередження, а те, що людини немає.
    expect(screen.queryByTestId('directory-found')).toBeNull();
    expect(screen.queryByTestId('directory-fingerprint')).toBeNull();
    expect(screen.queryByText('Почати розмову')).toBeNull();
    expect(screen.queryByText(`@${HANDLE}`)).toBeNull();
  });

  it('запис, підставлений під чуже імʼя, теж не стає карткою', async () => {
    // Сервер віддає запис Марти тому, хто спитав інше імʼя. Підпис накриває
    // sha256 імені, тож підміна видна — і на екран не потрапляє.
    serve(200, { invite: CODE, sig: SIG, exp: EXP });

    render(<DirectorySearchPanel onUseInvite={() => {}} />);
    await searchFor('oleksiy1');

    await waitFor(() => expect(screen.getByText(/недійсним підписом/)).toBeTruthy());
    expect(screen.queryByTestId('directory-found')).toBeNull();
  });
});

describe('порожній результат каже правду', () => {
  it('нікого немає — і нікого схожого не намальовано', async () => {
    serve(404, { detail: 'no such entry' });

    render(<DirectorySearchPanel onUseInvite={() => {}} />);
    await searchFor('nemaie_takogo');

    await waitFor(() => expect(screen.getByText(/Такого імені в довіднику немає/)).toBeTruthy());
    expect(screen.queryByTestId('directory-found')).toBeNull();
    // І екран каже, ЧОМУ порожньо: типово в довіднику немає нікого.
    expect(screen.getByText(/поки він сам себе туди не поклав/)).toBeTruthy();
  });

  it('довідник зачинено — це стан сервера, а не відповідь про людину', async () => {
    serve(503, { detail: 'directory is not open' });

    render(<DirectorySearchPanel onUseInvite={() => {}} />);
    await searchFor(HANDLE);

    await waitFor(() => expect(screen.getByText(/не відкрито/)).toBeTruthy());
    expect(screen.queryByTestId('directory-found')).toBeNull();
  });

  it('довідник не відповів — «не спитали», а не «нікого нема»', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => {
        throw new TypeError('Failed to fetch');
      }),
    );

    render(<DirectorySearchPanel onUseInvite={() => {}} />);
    await searchFor(HANDLE);

    await waitFor(() => expect(screen.getByText(/не «нікого немає», а «не спитали»/)).toBeTruthy());
    expect(screen.queryByTestId('directory-found')).toBeNull();
  });
});

describe('смуга справді є на екрані', () => {
  it('на ПК — рядок «Глобальний довідник» у «Новій розмові», смуга названа вголос (на телефоні — загальний пошук)', async () => {
    // Без цього все попереднє стосувалося б коду, якого ніхто не викликає.
    // 03.10: вкладки «Група/Діалог/Запрошення» прибрано — ПК відкриває той
    // самий аркуш, що й телефон, а довідник має власний тихий рядок.
    try {
      render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={() => {}} />);
      fireEvent.click(screen.getByRole('button', { name: 'Глобальний довідник' }));
      await waitFor(() => expect(screen.getByTestId('directory-search')).toBeTruthy());
      // Названа — інакше два поля «знайти людину» на одному екрані давали б
      // дві різні відповіді без жодного натяку, чому вони різні.
      expect(within(screen.getByTestId('directory-search')).getByText('Глобальний довідник')).toBeTruthy();

      // І два різні пошуки не носять однакового напису. Це та сама вимога,
      // що стоїть коментарем у DirectorySearchPanel; доти вона трималась
      // лише на тому, що тест шукав кнопку за дослівним текстом, тобто
      // трималась випадково. Тепер це твердження про ВЛАСТИВІСТЬ, і воно
      // переживе будь-яке наступне переписування слів.
      const spoken = screen
        .getAllByRole('button')
        .map((b) => (b.getAttribute('aria-label') ?? b.textContent ?? '').trim())
        .filter((s) => s.length > 0);
      const twice = spoken.filter((s, i) => spoken.indexOf(s) !== i);
      expect(
        [...new Set(twice)],
        'дві кнопки з однаковим написом на одному екрані: людині не дали способу ' +
          'відрізнити, який із двох пошуків вона натискає',
      ).toEqual([]);
    } finally {
      delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    }
  });
});

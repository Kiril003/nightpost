/**
 * Згода бути в довіднику: типово НЕМАЄ, ціна названа, зняття справді знімає.
 *
 * Чотири речі, і кожна — окремий спосіб зробити згоду несправжньою:
 *   1. типово людини в довіднику немає, і екран каже це станом, а не порожнім
 *      полем;
 *   2. перелік того, що побачить сервер, стоїть НА ЕКРАНІ — включно з
 *      незручним (відбиток без солі, видно кількість опублікованих). «Безпечно
 *      й приватно» — це не згода, а вмовляння;
 *   3. «зняти себе» знімає по-справжньому: при відмові апарата екран каже, що
 *      запис ЖИВИЙ, і НЕ показує «знято»;
 *   4. там, де ключа немає (браузер на столі), кнопки немає — замість неї
 *      речення. Мертвої кнопки на цьому екрані бути не може.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { DirectoryConsentPanel } from '../components/messenger/DirectoryConsentPanel';
import { messengerApi } from '../services/messengerApi';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('типово людини в довіднику немає', () => {
  it('стан «вас немає» плюс чесний перелік того, що побачить сервер', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({
      handle: null,
      published: false,
    });

    render(<DirectoryConsentPanel />);
    await waitFor(() => expect(screen.getByTestId('directory-consent-off')).toBeTruthy());

    expect(screen.getByText('Вас у довіднику немає.')).toBeTruthy();

    // Ціна названа вголос, і саме незручна її половина.
    expect(screen.getByText('Що побачить сервер')).toBeTruthy();
    expect(screen.getByText(/без солі й без розтягування/)).toBeTruthy();
    expect(screen.getByText(/скільки людей узагалі опублікувалось/)).toBeTruthy();
    expect(screen.getByText(/час ваших звернень/)).toBeTruthy();

    // І друга половина угоди — чого сервер не побачить.
    expect(screen.getByText('Чого сервер не побачить')).toBeTruthy();
    expect(screen.getByText(/саме імʼя — на сервер їде вже відбиток/)).toBeTruthy();
  });

  it('коротке імʼя не публікується, і сказано яке можна', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({ handle: null, published: false });
    const publish = vi.spyOn(messengerApi, 'directoryPublish');

    render(<DirectoryConsentPanel />);
    await waitFor(() => expect(screen.getByTestId('directory-consent-off')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('Імʼя, під яким мене можна знайти'), {
      target: { value: 'marta' },
    });
    fireEvent.click(screen.getByText('Покласти себе в довідник'));

    await waitFor(() => expect(screen.getByText(/від 8 до 32 знаків/)).toBeTruthy());
    // Головне: апарат навіть не спитали.
    expect(publish).not.toHaveBeenCalled();
  });

  it('добре імʼя йде на апарат у канонічній формі', async () => {
    vi.spyOn(messengerApi, 'directoryMe')
      .mockResolvedValueOnce({ handle: null, published: false })
      .mockResolvedValueOnce({ handle: 'marta_kovalenko', published: true });
    const publish = vi
      .spyOn(messengerApi, 'directoryPublish')
      .mockResolvedValue({ handle: 'marta_kovalenko' });

    render(<DirectoryConsentPanel />);
    await waitFor(() => expect(screen.getByTestId('directory-consent-off')).toBeTruthy());

    // Людина набрала з «@» і з великої — це те саме імʼя, не інше.
    fireEvent.change(screen.getByLabelText('Імʼя, під яким мене можна знайти'), {
      target: { value: '@Marta_Kovalenko' },
    });
    fireEvent.click(screen.getByText('Покласти себе в довідник'));

    await waitFor(() => expect(publish).toHaveBeenCalledWith('marta_kovalenko'));
    // Стан перечитано з апарата, а не вигаданий з відповіді.
    await waitFor(() => expect(screen.getByTestId('directory-consent-on')).toBeTruthy());
  });
});

describe('зняти себе — справді знімає', () => {
  it('після підтвердження апарата екран повертається в «вас немає»', async () => {
    vi.spyOn(messengerApi, 'directoryMe')
      .mockResolvedValueOnce({ handle: 'marta_kovalenko', published: true })
      .mockResolvedValueOnce({ handle: null, published: false });
    const withdraw = vi.spyOn(messengerApi, 'directoryWithdraw').mockResolvedValue({});

    render(<DirectoryConsentPanel />);
    await waitFor(() => expect(screen.getByTestId('directory-consent-on')).toBeTruthy());

    fireEvent.click(screen.getByText('Зняти себе з довідника'));
    await waitFor(() => expect(withdraw).toHaveBeenCalled());
    await waitFor(() => expect(screen.getByTestId('directory-consent-off')).toBeTruthy());
  });

  it('відмова — і сказано, що запис ЖИВИЙ, а не «знято»', async () => {
    // Найважливіша відмова на цьому екрані. Показати «знято», поки запис
    // живий, було б рівно тією брехнею, від якої ця панель і мусить боронити.
    vi.spyOn(messengerApi, 'directoryMe').mockResolvedValue({
      handle: 'marta_kovalenko',
      published: true,
    });
    vi.spyOn(messengerApi, 'directoryWithdraw').mockRejectedValue(new Error('немає мережі'));

    render(<DirectoryConsentPanel />);
    await waitFor(() => expect(screen.getByTestId('directory-consent-on')).toBeTruthy());

    fireEvent.click(screen.getByText('Зняти себе з довідника'));

    await waitFor(() => expect(screen.getByText(/ще живий/)).toBeTruthy());
    // Людина все ще в довіднику — і екран це показує.
    expect(screen.getByTestId('directory-consent-on')).toBeTruthy();
    expect(screen.queryByTestId('directory-consent-off')).toBeNull();
  });
});

describe('де ключа немає — там немає й кнопки', () => {
  it('без моста екран каже правду й не малює публікації', async () => {
    vi.spyOn(messengerApi, 'directoryMe').mockRejectedValue(new Error('404 not_found'));

    render(<DirectoryConsentPanel />);
    await waitFor(() =>
      expect(screen.getByText(/лише там, де лежить ваш ключ/)).toBeTruthy(),
    );

    // Жодної дії, яка нічого не зробить.
    expect(screen.queryByText('Покласти себе в довідник')).toBeNull();
    expect(screen.queryByText('Зняти себе з довідника')).toBeNull();
    expect(screen.queryByLabelText('Імʼя, під яким мене можна знайти')).toBeNull();
  });
});

describe('вкладка справді досяжна', () => {
  it('id стоїть І у списку категорій, І у гілці рендеру', () => {
    // Сторож проти рівно тієї вади, яку цей файл уже ловив на панелі пари з
    // телефоном: рендер чекав на категорію `mobile`, а в списку її не було, і
    // єдиний шлях до екрана існував лише в коді. Панель, яку нікуди не
    // натиснути, — це мертвий інтерфейс, хай і зелений у власних тестах.
    const source = readFileSync(
      join(__dirname, '..', 'components', 'settings', 'SettingsPanel.tsx'),
      'utf-8',
    );
    expect(source).toContain("id: 'directory'");
    // У зведеному списку, з якого малюються вкладки.
    expect(source).toMatch(/merged = \[[^\]]*\bdirectory\b/);
    // І в гілці, яка малює саму панель.
    expect(source).toContain("activeCategory.id === 'directory'");
    expect(source).toContain('<DirectoryConsentPanel />');
  });
});

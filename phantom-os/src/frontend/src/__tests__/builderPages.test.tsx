import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { MeScreen } from '../components/messenger/MeScreen';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { createPage, getPage, listPages, restoreVersion, savePage, MAX_PAGES } from '../services/builder/pages';
import { publishFailures } from '../services/builder/checks';

beforeEach(() => localStorage.clear());
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});

const openHub = () => {
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);
  fireEvent.click(screen.getByTestId('me-pages'));
};

describe('Builder на телефоні', () => {
  it('Я › Мої сторінки: порожній стан, шаблон, редактор, перевірка, видалення', async () => {
    openHub();
    const hub = await screen.findByTestId('builder-hub');
    expect(within(hub).getByTestId('builder-empty')).toBeTruthy();

    fireEvent.click(within(hub).getByText('Створити сторінку'));
    const create = await screen.findByTestId('builder-create');
    fireEvent.change(within(create).getByTestId('builder-name'), { target: { value: 'Кав’ярня Лампа' } });
    fireEvent.click(within(create).getByTestId('builder-tpl-cafe'));
    fireEvent.click(within(create).getAllByText('Створити сторінку').pop()!);

    const editor = await screen.findByTestId('builder-editor');
    expect(within(editor).getAllByTestId('builder-block').map((b) => b.getAttribute('data-kind'))).toEqual(['title', 'about', 'address', 'hours', 'menu']);

    // Публікація: порожні блоки дають чітку відмову, а не «готово».
    fireEvent.click(within(editor).getByTestId('builder-tab-publish'));
    expect(within(editor).getByTestId('builder-state').textContent).toMatch(/Не готово/);
    expect(within(editor).getAllByTestId('builder-failure').length).toBeGreaterThanOrEqual(3);

    // Додати блок: «Подія» з'являється в списку, «Назва» вже є — її немає.
    fireEvent.click(within(editor).getByTestId('builder-tab-edit'));
    fireEvent.click(within(editor).getByText('Додати блок'));
    const add = await screen.findByTestId('builder-add');
    expect(within(add).queryByTestId('builder-add-title')).toBeNull();
    fireEvent.click(within(add).getByTestId('builder-add-event'));
    expect(within(editor).getAllByTestId('builder-block').length).toBe(6);

    // Видалення з підтвердженням.
    fireEvent.click(within(editor).getByTestId('builder-delete'));
    fireEvent.click(await screen.findByTestId('builder-delete-yes'));
    expect(listPages().length).toBe(0);
  });
});

describe('Сховище сторінок і перевірки', () => {
  it('версії: знімок лише при зміні, відновлення повертає старий зміст і саме стає версією', () => {
    const p = createPage('A', 'cafe', 1000)!;
    const edited = p.blocks.map((b) => (b.type === 'about' ? { ...b, text: 'нове' } : b));
    savePage(p.id, p.blocks, { snapshot: true, now: 2000 });
    expect(getPage(p.id)!.versions.length).toBe(0);
    savePage(p.id, edited, { snapshot: true, now: 3000 });
    expect(getPage(p.id)!.versions.map((v) => v.at)).toEqual([3000]);
    const back = restoreVersion(p.id, 3000, 4000)!;
    expect(back.blocks.find((b) => b.type === 'about')).toMatchObject({ text: '' });
    expect(back.versions.length).toBe(2);
  });

  it('ліміт сторінок', () => {
    for (let i = 0; i < MAX_PAGES; i++) expect(createPage(`p${i}`, 'blank')).not.toBeNull();
    expect(createPage('ще', 'blank')).toBeNull();
  });

  it('перевірка називає всі проблеми, а точка (0,0) не вважається адресою', () => {
    const p = createPage('Клуб', 'club')!;
    const f = publishFailures(p.blocks);
    const kinds = new Set(f.map((x) => x.problem));
    expect(kinds.has('bad_text')).toBe(true);
    expect(kinds.has('bad_wallet')).toBe(true);
    const ok = p.blocks.map((b) =>
      b.type === 'address' ? { ...b, text: 'вул. Вигадана 1', lat: 0, lon: 0 } : b,
    );
    expect(publishFailures(ok).some((x) => x.problem === 'no_point')).toBe(true);
    expect(publishFailures([]).map((x) => x.problem)).toEqual(['missing_title', 'needs_address']);
  });

  it('словник збігається з сайтом байт у байт', () => {
    const site = readFileSync(resolve(__dirname, '../../../../../platform-site/src/lib/venueBlocks.ts'), 'utf8');
    const here = readFileSync(resolve(__dirname, '../services/builder/venueBlocks.ts'), 'utf8');
    expect(here).toBe(site);
  });
});

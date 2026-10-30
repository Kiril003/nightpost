/**
 * Віднімання театру з ПК-месенджера (24.09) — сторож, щоб він не повернувся.
 *
 * Перепис кожного елемента шапки й бічної панелі — `docs/pc-msgr-census.md`:
 * що обіцяв, що робив насправді, файл:рядок. Коротко: 56 модалок за
 * `ModalHost` (Agentic Runtime & Нейроергономіка, P2P Swarm, Blueprint із
 * вигаданим «DLP», скарбниця, IoT, Time Machine…) не робили ЖОДНОГО виклику
 * до вузла — засіяні масиви, `setTimeout` замість роботи, `Math.random()`
 * замість виміру. AI-студія відповідала регуляркою через 600 мс. Меню папки
 * казало «позначено», «вимкнено», «в архіві» тостами, за якими не стояло
 * жодного обробника. Усе це ВИДАЛЕНО, не сховано прапорцем.
 *
 * Сторож міряє три речі, і кожна вміє червоніти:
 *   1. продукт, змонтований так само, як його монтує застосунок
 *      (`MessengerRoot`), не малює дверей у театр — і справжні дії
 *      досяжні дотиком (поверніть будь-який пункт у меню шапки — падає
 *      перший тест; приберіть «Мережевий звʼязок» — падає теж);
 *   2. меню папки в `Sidebar` не обіцяє дій без обробника (поверніть
 *      «Позначити як прочитані» чи «В архів» — падає другий);
 *   3. самих модулів театру в дереві немає, і шина `phantom:open-*` не
 *      слухається (поверніть файл чи слухача — падає третій).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import type { NodeConversation } from '../services/messengerApi';

const CONVERSATION: NodeConversation = {
  id: 'conv_marta',
  title: 'Марта',
  kind: 'dm',
  circle: 'friends',
  handle: null,
  avatar: null,
  pinned: false,
  archived: false,
  created_at: '2026-09-04T10:00:00',
  updated_at: '2026-09-04T10:00:00',
  peer_node_id: 'a1b2c3d4e5f60718',
};

/** Підписи дверей, що вели в декорацію. Жодного з них не має бути на екрані. */
const THEATRE_LABELS = [
  'Agentic Runtime & Нейроергономіка',
  'Physical Computing, GIS & WebGPU',
  'P2P Swarm роздача',
  'Сховище Workspace Drive',
  'Shared Terminal & Runner',
  'Вхідні Webhook-хаби (CI/CD)',
  'Project Memory Graph',
  'Скарбниця (Multi-sig Vault)',
  'Моніторинг вузла',
  'Автоматизації (IFTTT & Cron)',
  'Реляційна база (Data Grid)',
  'CRDT Time Machine (Снапшоти)',
  'IoT Сенсори & YubiKey',
  'Контекстні ролі (Scopes)',
  'Git-Native DevOps & PR',
  'Side-by-Side Diff & Hex',
  'AI Studio',
  'Навчальні картки (Flashcards)',
];

const openProduct = async () => {
  render(
    <MemoryRouter>
      <MessengerRoot />
    </MemoryRouter>,
  );
  await screen.findByTitle('Профіль');
};

const openMoreMenu = () => {
  fireEvent.click(screen.getByTitle('Ще'));
  return screen.getByRole('menu');
};

beforeEach(() => {
  useMessengerStore.setState({
    chats: [],
    activeChatId: '',
    hydrated: false,
  });
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([CONVERSATION]);
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('ПК-месенджер без театру', () => {
  it('шапка змонтованого продукту: дверей у декорацію немає, справжні дії досяжні', async () => {
    await openProduct();

    // Права група шапки: ні «AI Studio», ні «Omni-search», ні фокус-режиму.
    expect(screen.queryByText('AI Studio')).toBeNull();
    expect(screen.queryByTitle('Відкрити когнітивний полігон Nightpost AI Studio')).toBeNull();
    expect(screen.queryByTitle('Семантичний пошук рішень та документів (Omni-search)')).toBeNull();
    expect(screen.queryByText('На звʼязку')).toBeNull();
    // Рейка й картка себе в Sidebar: ні студії, ні перемикача «сфер».
    expect(screen.queryByTitle('Nightpost AI Synthesis Lab (Когнітивний полігон)')).toBeNull();
    expect(screen.queryByTitle('Швидка зміна сфери')).toBeNull();
    // «E2EE» на кожному рядку — про кожного однаково, тобто ні про кого.
    expect(screen.queryByText('E2EE')).toBeNull();

    const menu = openMoreMenu();
    const text = menu.textContent ?? '';
    const present = THEATRE_LABELS.filter((label) => text.includes(label));
    expect(present, 'у меню шапки повернулись двері в театр').toEqual([]);

    // Те, за чим стоїть справжній виклик, — на місці й натискається (§7.1:
    // пошук живе в «Ще»). Механіки на кшталт «Мережевий звʼязок» тут немає.
    for (const real of ['Профіль', 'Пошук у розмові', 'Відкладені повідомлення', 'Налаштування']) {
      expect(text, `зникла справжня дія «${real}»`).toContain(real);
    }
    for (const mech of ["Мережевий звʼязок", 'Студія карток', 'Список покупок', 'Навчальні картки']) {
      expect(text, `у меню повернулась механіка чи декорація «${mech}»`).not.toContain(mech);
    }
    fireEvent.click(screen.getByText('Налаштування'));
    expect(useMessengerStore.getState().isSettingsModalOpen).toBe(true);
    act(() => useMessengerStore.getState().setSettingsModalOpen(false));
  });

  it('шина вікон і клавіші більше не відкривають показових модалок', async () => {
    await openProduct();
    const before = document.body.innerHTML;

    act(() => {
      for (const name of ['phantom:open-agentic', 'phantom:open-blueprint', 'phantom:open-neuro']) {
        window.dispatchEvent(new CustomEvent(name));
      }
      window.dispatchEvent(
        new CustomEvent('phantom:open-modal', { detail: { type: 'p2pSwarm' } }),
      );
      window.dispatchEvent(new KeyboardEvent('keydown', { key: '`', ctrlKey: true }));
    });
    // Модалки ModalHost були лінивими: дати час шматку довантажитись, інакше
    // «нічого не зʼявилось» могло б означати «ще не встигло».
    await act(async () => {
      await new Promise((r) => setTimeout(r, 800));
    });

    expect(document.body.innerHTML).toBe(before);
    expect(document.body.textContent).not.toContain('Agentic Runtime');
    expect(document.body.textContent).not.toContain('DLP');
  });

  it('Ctrl+K відкриває лише рядок пошуку в списку розмов, а не модалку', async () => {
    await openProduct();
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }));
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 800));
    });
    expect(screen.getByRole('searchbox')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.body.textContent).not.toContain('Agentic Runtime');
  });

  it('модулів театру в дереві немає, і ніхто не кличе їх подіями', () => {
    const SRC = join(process.cwd(), 'src');
    for (const gone of [
      'components/messenger/modals/ModalHost.tsx',
      'components/messenger/modals/ModalHost.phone.tsx',
      'components/messenger/CommandPaletteModal.tsx',
      'components/messenger/PhantomArchitectureBlueprintModal.tsx',
      'components/messenger/AgenticWorkspaceVirtualizationModal.tsx',
      'components/messenger/NeuroErgonomicsModal.tsx',
      'components/messenger/P2PFileSwarmModal.tsx',
      'components/messenger/KnowledgeSearchModal.tsx',
      'components/messenger/FocusModeSelector.tsx',
      'components/messenger/ShareFolderModal.tsx',
      'components/messenger/FolderIconPickerModal.tsx',
      'components/ai-studio',
      'stores/modalStore.ts',
      'stores/aiSynthesisStore.ts',
      'stores/meshStore.ts',
      'stores/workOsStore.ts',
      'stores/agenticStore.ts',
      'stores/canvasStore.ts',
    ]) {
      expect(existsSync(join(SRC, gone)), `повернувся ${gone}`).toBe(false);
    }

    const offenders: string[] = [];
    const walk = (dir: string) => {
      for (const name of readdirSync(dir)) {
        const p = join(dir, name);
        if (statSync(p).isDirectory()) {
          if (name !== '__tests__') walk(p);
        } else if (/\.tsx?$/.test(name)) {
          const body = readFileSync(p, 'utf8');
          if (/['"`]phantom:open-[a-z-]+['"`]|useModalStore|useAISynthesisStore/.test(body)) {
            offenders.push(p.slice(SRC.length + 1));
          }
        }
      }
    };
    walk(SRC);
    expect(offenders).toEqual([]);
  });
});

/**
 * Теки й простори списку розмов (власник 02.10: «де папки, простори,
 * розподілення, групування, хештеги?»).
 *
 * Дві речі однієї природи — людина сама розкладає розмови:
 *  - **тека** — вкладка смуги над списком: вибрані розмови і/або #теги
 *    (розмова, де звучав тег, сама потрапляє в теку);
 *  - **простір** — рядок-групування в самому списку: розмови простору
 *    згортаються під один рядок, що розгортається на місці (як «Мої
 *    пристрої»). Простір — твоє впорядкування на цьому телефоні; спільні
 *    простори з темами (06_communities §Простори) — окрема робота вузла,
 *    і цей рядок не вдає, що вони вже є.
 *
 * Живуть на пристрої (`localStorage`, ключ нижче) — старий `smartFolders`
 * у messengerStore не зберігався ніде і на телефоні не показувався.
 * Сторож — src/__tests__/listFolders.test.tsx.
 */
import { create } from 'zustand';
import { stashForBackup } from '../services/backupStash';
import { tNow } from '../components/chat/tNow';

export type FolderKind = 'folder' | 'space';

export interface ListFolder {
  id: string;
  name: string;
  kind: FolderKind;
  /** Розмови, покладені руками. */
  chatIds: string[];
  /** Теги без «#», малими: розмова з таким тегом теж у теці. */
  tags: string[];
}

const KEY = 'phantom.listFolders.v1';

const load = (): ListFolder[] => {
  try {
    const raw = typeof localStorage !== 'undefined' ? localStorage.getItem(KEY) : null;
    const arr = raw ? (JSON.parse(raw) as unknown) : [];
    if (!Array.isArray(arr)) return [];
    return arr
      .filter((f): f is ListFolder => !!f && typeof f.id === 'string' && typeof f.name === 'string')
      .map((f) => ({
        id: f.id,
        name: f.name.slice(0, 40),
        kind: f.kind === 'space' ? 'space' : 'folder',
        chatIds: Array.isArray(f.chatIds) ? f.chatIds.filter((x) => typeof x === 'string') : [],
        tags: Array.isArray(f.tags) ? f.tags.filter((x) => typeof x === 'string') : [],
      }));
  } catch {
    return [];
  }
};

const save = (folders: ListFolder[]) => {
  try {
    const raw = JSON.stringify(folders);
    localStorage.setItem(KEY, raw);
    stashForBackup(KEY, raw);
  } catch {
    /* без сховища — до перезапуску */
  }
};

export const normTag = (t: string): string => t.trim().replace(/^#+/, '').toLocaleLowerCase('uk-UA');

interface FolderState {
  folders: ListFolder[];
  /** Розгорнуті простори в «Усіх». */
  openSpaces: Record<string, boolean>;
  upsert: (f: Omit<ListFolder, 'id'> & { id?: string }) => string;
  remove: (id: string) => void;
  /** Перенести теку з позиції `from` на `to` (серед тек свого роду). */
  move: (id: string, to: number) => void;
  toggleSpace: (id: string) => void;
  reload: () => void;
}

export const useFolders = create<FolderState>((set, get) => ({
  folders: load(),
  openSpaces: {},
  upsert: (f) => {
    const id = f.id || `${f.kind}_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
    const clean: ListFolder = {
      id,
      name: f.name.trim().slice(0, 40) || tNow(f.kind === 'space' ? 'listFolders.space' : 'listFolders.folder'),
      kind: f.kind,
      chatIds: [...new Set(f.chatIds)],
      tags: [...new Set(f.tags.map(normTag).filter(Boolean))],
    };
    const has = get().folders.some((x) => x.id === id);
    const folders = has ? get().folders.map((x) => (x.id === id ? clean : x)) : [...get().folders, clean];
    save(folders);
    set({ folders });
    return id;
  },
  remove: (id) => {
    const folders = get().folders.filter((x) => x.id !== id);
    save(folders);
    set({ folders });
  },
  move: (id, to) => {
    const list = [...get().folders];
    const from = list.findIndex((x) => x.id === id);
    if (from < 0) return;
    const [it] = list.splice(from, 1);
    list.splice(Math.max(0, Math.min(list.length, to)), 0, it);
    save(list);
    set({ folders: list });
  },
  toggleSpace: (id) => set((s) => ({ openSpaces: { ...s.openSpaces, [id]: !s.openSpaces[id] } })),
  reload: () => set({ folders: load() }),
}));

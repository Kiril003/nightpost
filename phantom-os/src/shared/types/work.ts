/**
 * Схема `work.*` (reports/product-v2/11-work.md §2): задача, обмін змінами,
 * відмітка входу й решта робочих обʼєктів. Живуть у розмові чи просторі,
 * їдуть наскрізно зашифрованими, сервер їх не бачить.
 *
 * Заморожена до R1 (рішення лідера 22.10): нове поле — `WORK_SCHEMA + 1` і
 * розбір старого. `WORK_FIELDS` + тест на знімок не дадуть змінити мовчки.
 */
export const WORK_SCHEMA = 1 as const;

/** Лемпорт + автор: хто пізніше, той і правий; рівність розводить автор. */
export interface Stamp {
  l: number;
  by: string;
}

export const TASK_STATUSES = ['TODO', 'DOING', 'REVIEW', 'DONE', 'CANCELLED'] as const;
export type TaskStatus = (typeof TASK_STATUSES)[number];

export interface SignoffDecision {
  by: string;
  verdict: 'approve' | 'changes';
  at: number;
}

export interface ChecklistItem {
  text: string;
  done: boolean;
  order: number;
  /** Видалений пункт лишається надгробком, щоб злиття не воскресило його. */
  gone: boolean;
  s: Stamp;
}

export interface WorkTask {
  /** UUIDv7. */
  id: string;
  spaceId: string | null;
  chatId: string;
  /** Лист, з якого народилась задача; null — створена з нуля. */
  sourceMsg: string | null;
  title: string;
  assignee: string | null;
  watchers: Record<string, { on: boolean; s: Stamp }>;
  due: number | null;
  /** «з тексту» чи обрано руками — людина бачить, звідки строк. */
  dueFrom: 'text' | 'picked' | null;
  status: TaskStatus;
  /** Прапорець із причиною, не стан: «чекає #479». */
  blocked: string | null;
  checklist: Record<string, ChecklistItem>;
  signoff: { approvers: string[]; quorum: number } | null;
  decisions: SignoffDecision[];
  /** Зовнішні звʼязки: `gh:pr:482`, бронювання, замовлення. */
  links: Record<string, { on: boolean; s: Stamp }>;
  /** Дробовий ключ порядку на дошці. */
  boardOrder: string | null;
  createdBy: string;
  createdAt: number;
  /** Мітка останнього запису для кожного скалярного поля. */
  clock: Partial<Record<TaskScalar, Stamp>>;
}

export const TASK_SCALARS = [
  'title',
  'assignee',
  'due',
  'dueFrom',
  'status',
  'blocked',
  'signoff',
  'boardOrder',
] as const;
export type TaskScalar = (typeof TASK_SCALARS)[number];

export const SWAP_STATES = [
  'OFFERED',
  'ACCEPTED',
  'APPROVED',
  'APPLIED',
  'DECLINED',
  'WITHDRAWN',
  'EXPIRED',
] as const;
export type SwapState = (typeof SWAP_STATES)[number];

export interface ShiftSwap {
  id: string;
  shiftA: string;
  /** null — віддати зміну, нічого не беручи. */
  shiftB: string | null;
  from: string;
  to: string;
  state: SwapState;
  /** Вимкнено в команді — APPROVED пропускається. */
  leadApproves: boolean;
  /** За 2 год до ранішої з двох змін. */
  expiresAt: number;
  log: Array<{ state: SwapState; by: string; at: number }>;
}

export interface WorkShift {
  id: string;
  teamId: string;
  placeId: string | null;
  role: string;
  start: number;
  end: number;
  /** null — відкрита зміна, «Взяти». */
  assignee: string | null;
  requiredSkill: string | null;
}

/**
 * Відмітка входу. Координат тут немає і не буде: «місце один раз» пише лише
 * результат `inside`, сама точка не покидає телефона.
 */
export interface ClockEvent {
  shiftId: string;
  member: string;
  at: number;
  method: 'door' | 'location-once' | 'lead';
  result: 'inside' | 'outside' | 'confirmed';
}

export interface WorkBoard {
  id: string;
  spaceId: string;
  /** Колонки = стани; власні — у W2. */
  columns: TaskStatus[];
}

export interface ProcessStep {
  title: string;
  role: string | null;
  /** Зсув у днях від старту прогону. */
  offsetDays: number;
  signoff: boolean;
}

export interface WorkProcess {
  id: string;
  spaceId: string;
  title: string;
  steps: ProcessStep[];
}

export interface RepoLink {
  channelId: string;
  provider: 'github' | 'gitlab' | 'gitea';
  repo: string;
  branches: string[];
  /** Компʼютери учасників, що тримають токен. Токен ніколи не тут. */
  bridgeDevices: string[];
}

export interface LiveCard {
  /** `gh:pr:482` — одна картка на подію, оновлюється на місці. */
  extId: string;
  channelId: string;
  kind: 'pr' | 'build' | 'deploy' | 'push';
  fields: Record<string, string>;
  foldedCount: number;
  updatedAt: number;
}

export interface ReviewThread {
  id: string;
  channelId: string;
  anchor: { repo: string; pr: number; sha: string; path: string; side: 'old' | 'new'; line: number };
  state: 'open' | 'resolved' | 'outdated';
  mirrored: boolean;
}

/** Запуск фрагмента на власному ПК. Не покидає моїх пристроїв, доки не «Поділитись». */
export interface RunRequest {
  codeHash: string;
  author: string;
  device: string;
  limits: { seconds: number; memoryMb: number; network: false };
  output: string | null;
}

export const ORDER_STATES = [
  'SENT',
  'ACCEPTED',
  'MAKING',
  'READY',
  'PICKED_UP',
  'DECLINED',
  'CANCELLED',
  'NO_SHOW',
] as const;
export type OrderState = (typeof ORDER_STATES)[number];

export interface WorkOrder {
  id: string;
  chatId: string;
  items: Array<{ sku: string; title: string; qty: number; priceMinor: number }>;
  currency: string;
  state: OrderState;
  pickupCode: string;
  pay: 'at-pickup' | 'solana-devnet';
}

export const WORK_FIELDS = {
  task: [
    'id',
    'spaceId',
    'chatId',
    'sourceMsg',
    'title',
    'assignee',
    'watchers',
    'due',
    'dueFrom',
    'status',
    'blocked',
    'checklist',
    'signoff',
    'decisions',
    'links',
    'boardOrder',
    'createdBy',
    'createdAt',
    'clock',
  ],
  swap: ['id', 'shiftA', 'shiftB', 'from', 'to', 'state', 'leadApproves', 'expiresAt', 'log'],
  clock: ['shiftId', 'member', 'at', 'method', 'result'],
} as const satisfies {
  task: readonly (keyof WorkTask)[];
  swap: readonly (keyof ShiftSwap)[];
  clock: readonly (keyof ClockEvent)[];
};

/** `never`, коли `WORK_FIELDS` перелічує всі поля типу (перевіряє tsc). */
export type MissingTaskFields = Exclude<keyof WorkTask, (typeof WORK_FIELDS.task)[number]>;
export type MissingSwapFields = Exclude<keyof ShiftSwap, (typeof WORK_FIELDS.swap)[number]>;
export type MissingClockFields = Exclude<keyof ClockEvent, (typeof WORK_FIELDS.clock)[number]>;

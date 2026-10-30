import { create } from 'zustand';
import { translate, type Locale, type MessageKey } from '../i18n';
import { resolveLocale } from '../i18n/useTranslation';
import {
  supportApi,
  type AppealReason,
  type AppealWire,
  type SupportNotice,
  type SupportTicketResponse,
  type SupportTicketType,
} from '../services/supportApi';

export const SUPPORT_STORAGE_KEY = 'np.support.reports.v1';
export const UNDO_WINDOW_MS = 5000;

export const REPORT_REASONS = [
  'spam',
  'scam',
  'harassment',
  'violence',
  'sexual',
  'minor_safety',
  'impersonation',
  'other',
] as const;

export type ReportReason = (typeof REPORT_REASONS)[number];
export type ReportTargetKind = 'message' | 'person' | 'group';
export type ReportSurface = 'private' | 'public';
export type ReportStatus = 'queued' | 'sent' | 'reviewing' | 'action' | 'dismissed' | 'failed';

export interface ReportAppeal {
  reason: AppealReason;
  text: string;
  at: number;
  status: AppealWire['status'];
}

export interface SupportReport {
  id: string;
  targetKind: ReportTargetKind;
  targetId: string;
  targetName?: string;
  chatId?: string;
  messageIds?: string[];
  messageText?: string;
  senderKey?: string;
  surface?: ReportSurface;
  reason: ReportReason;
  note?: string;
  createdAt: number;
  status: ReportStatus;
  appeal?: ReportAppeal;
  sentAt?: number;
  failReason?: string;
}

export interface ReportTarget {
  targetKind: ReportTargetKind;
  targetId: string;
  targetName?: string;
  chatId?: string;
  messageIds?: string[];
  messageText?: string;
  senderKey?: string;
  surface?: ReportSurface;
  peerId?: string;
}

export interface SubmitReportInput {
  id?: string;
  targetKind: ReportTargetKind;
  targetId: string;
  targetName?: string;
  chatId?: string;
  messageIds?: string[];
  messageText?: string;
  senderKey?: string;
  surface?: ReportSurface;
  reason: ReportReason;
  note?: string;
  deviceSig?: string;
}

const STATUS_KEY: Record<ReportStatus, MessageKey> = {
  queued: 'support.status.queued',
  sent: 'support.status.sent',
  reviewing: 'support.status.reviewing',
  action: 'support.status.action',
  dismissed: 'support.status.dismissed',
  failed: 'support.status.failed',
};

export function normalizeReportStatus(raw: unknown): ReportStatus {
  if (raw === 'queued') return 'queued';
  if (raw === 'failed') return 'failed';
  if (raw === 'reviewing' || raw === 'in_review') return 'reviewing';
  if (raw === 'action' || raw === 'action_taken' || raw === 'resolved') return 'action';
  if (raw === 'dismissed' || raw === 'no_changes' || raw === 'no_action' || raw === 'rejected') return 'dismissed';
  return 'sent';
}

export function reportStatusLabel(status: ReportStatus, locale: Locale = resolveLocale(undefined)): string {
  const normalized = normalizeReportStatus(status);
  return translate(STATUS_KEY[normalized], locale);
}

export function canAppealReport(report: Pick<SupportReport, 'status' | 'appeal'>): boolean {
  if (report.appeal) return false;
  const status = normalizeReportStatus(report.status);
  return status === 'action' || status === 'dismissed';
}

function appealFromWire(a: AppealWire | null | undefined): ReportAppeal | undefined {
  if (!a) return undefined;
  return { reason: a.reason, text: a.text, at: Date.parse(a.at) || Date.now(), status: a.status };
}

function normalizeStoredAppeal(raw: unknown): ReportAppeal | undefined {
  if (!isPlainObject(raw) || typeof raw.reason !== 'string' || typeof raw.text !== 'string') return undefined;
  const st = raw.status === 'upheld' || raw.status === 'overturned' ? raw.status : 'submitted';
  return {
    reason: raw.reason as AppealReason,
    text: raw.text,
    at: typeof raw.at === 'number' && Number.isFinite(raw.at) ? raw.at : Date.now(),
    status: st,
  };
}

export function nextClientUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `rep-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function isTargetKind(v: unknown): v is ReportTargetKind {
  return v === 'message' || v === 'person' || v === 'group';
}

function isReason(v: unknown): v is ReportReason {
  return typeof v === 'string' && (REPORT_REASONS as readonly string[]).includes(v);
}

function normalizeStoredReport(raw: unknown): SupportReport | null {
  if (!isPlainObject(raw)) return null;
  if (typeof raw.id !== 'string' || !raw.id) return null;
  if (!isTargetKind(raw.targetKind)) return null;
  if (typeof raw.targetId !== 'string' || !raw.targetId) return null;
  if (!isReason(raw.reason)) return null;
  const createdAt = typeof raw.createdAt === 'number' && Number.isFinite(raw.createdAt) ? raw.createdAt : Date.now();
  const status = normalizeReportStatus(raw.status);
  return {
    id: raw.id,
    targetKind: raw.targetKind,
    targetId: raw.targetId,
    targetName: typeof raw.targetName === 'string' ? raw.targetName : undefined,
    chatId: typeof raw.chatId === 'string' ? raw.chatId : undefined,
    messageIds: Array.isArray(raw.messageIds)
      ? raw.messageIds.filter((x): x is string => typeof x === 'string')
      : undefined,
    messageText: typeof raw.messageText === 'string' ? raw.messageText : undefined,
    senderKey: typeof raw.senderKey === 'string' ? raw.senderKey : undefined,
    surface: raw.surface === 'public' || raw.surface === 'private' ? raw.surface : undefined,
    reason: raw.reason,
    note: typeof raw.note === 'string' ? raw.note : undefined,
    createdAt,
    status,
    appeal: normalizeStoredAppeal(raw.appeal),
    sentAt: typeof raw.sentAt === 'number' && Number.isFinite(raw.sentAt) ? raw.sentAt : undefined,
  };
}

function loadFromStorage(): { reports: SupportReport[]; outbox: SupportReport[] } {
  try {
    if (typeof localStorage === 'undefined') return { reports: [], outbox: [] };
    const raw = localStorage.getItem(SUPPORT_STORAGE_KEY);
    if (!raw) return { reports: [], outbox: [] };
    const parsed: unknown = JSON.parse(raw);
    if (!isPlainObject(parsed)) return { reports: [], outbox: [] };
    const reports = Array.isArray(parsed.reports)
      ? parsed.reports.map(normalizeStoredReport).filter((r): r is SupportReport => r !== null)
      : [];
    const outbox = Array.isArray(parsed.outbox)
      ? parsed.outbox.map(normalizeStoredReport).filter((r): r is SupportReport => r !== null)
      : reports.filter((r) => r.status === 'queued');
    return { reports, outbox };
  } catch {
    return { reports: [], outbox: [] };
  }
}

function saveToStorage(reports: SupportReport[], outbox: SupportReport[]): void {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(SUPPORT_STORAGE_KEY, JSON.stringify({ v: 1, reports, outbox }));
    }
  } catch {
    // Ignore storage quota / security errors.
  }
}

function isBrowserOffline(): boolean {
  return typeof navigator !== 'undefined' && navigator.onLine === false;
}

export const TICKETS_STORAGE_KEY = 'np.support.tickets.v1';

export type TicketType = SupportTicketType;
export type TicketStatus = 'queued' | 'open' | 'answered' | 'closed' | 'failed';

export interface TicketMessage {
  id: string;
  from: 'you' | 'support';
  text: string;
  at: number;
  queued?: boolean;
  failed?: boolean;
}

export interface TicketAttachment {
  mime: string;
  size: number;
  /** Лише поки звернення в черзі: після відправки зображення живе на вузлі. */
  dataB64?: string;
}

export interface SupportTicket {
  id: string;
  type: TicketType;
  subject: string;
  text: string;
  diagnostics?: Record<string, unknown>;
  attachment?: TicketAttachment;
  status: TicketStatus;
  unread: boolean;
  createdAt: number;
  thread: TicketMessage[];
  failReason?: string;
}

export interface QueuedReply {
  ticketId: string;
  id: string;
  text: string;
}

export interface SubmitTicketInput {
  id?: string;
  type: TicketType;
  subject: string;
  text: string;
  diagnostics?: Record<string, unknown>;
  attachment?: TicketAttachment;
  deviceSig?: string;
}

function loadTickets(): { tickets: SupportTicket[]; replyOutbox: QueuedReply[] } {
  try {
    if (typeof localStorage === 'undefined') return { tickets: [], replyOutbox: [] };
    const parsed: unknown = JSON.parse(localStorage.getItem(TICKETS_STORAGE_KEY) || 'null');
    if (!isPlainObject(parsed)) return { tickets: [], replyOutbox: [] };
    return {
      tickets: Array.isArray(parsed.tickets) ? (parsed.tickets as SupportTicket[]).filter((t) => t && typeof t.id === 'string' && Array.isArray(t.thread)) : [],
      replyOutbox: Array.isArray(parsed.replyOutbox) ? (parsed.replyOutbox as QueuedReply[]).filter((r) => r && typeof r.id === 'string') : [],
    };
  } catch {
    return { tickets: [], replyOutbox: [] };
  }
}

function saveTickets(tickets: SupportTicket[], replyOutbox: QueuedReply[]): void {
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(TICKETS_STORAGE_KEY, JSON.stringify({ v: 1, tickets, replyOutbox }));
    }
  } catch {
    // Ignore storage quota / security errors.
  }
}

function fromWire(res: SupportTicketResponse, prev?: SupportTicket): SupportTicket {
  const serverThread: TicketMessage[] = res.thread.map((m) => ({
    id: m.id,
    from: m.from,
    text: m.text,
    at: Date.parse(m.at) || Date.now(),
  }));
  const ids = new Set(serverThread.map((m) => m.id));
  const pendingLocal = (prev?.thread ?? []).filter((m) => m.queued && !ids.has(m.id));
  const knownRemote = (prev?.thread ?? []).filter((m) => !m.queued).length;
  return {
    id: res.id,
    type: res.type,
    subject: res.subject,
    text: res.text,
    diagnostics: res.diagnostics ?? prev?.diagnostics,
    attachment: res.attachment ? { mime: res.attachment.mime, size: res.attachment.size } : prev?.attachment && !prev.attachment.dataB64 ? prev.attachment : undefined,
    status: res.status,
    unread: serverThread.length > knownRemote ? res.unread : (prev?.unread ?? res.unread),
    createdAt: Date.parse(res.created_at) || prev?.createdAt || Date.now(),
    thread: [...serverThread, ...pendingLocal],
  };
}

function wireAttachment(a: TicketAttachment | undefined): { attachment?: { mime: string; data_b64: string } } {
  return a?.dataB64 ? { attachment: { mime: a.mime, data_b64: a.dataB64 } } : {};
}

let flushing: Promise<void> | null = null;

const flushedIds = new Set<string>();

export type HoldKind = 'server' | 'clock';

// `not_found` — міст APK без реле /support: звернення чекає оновлення, а не гине.
// 401 тримають лише годинник і повтор заяви; ключ чи підпис, що не зійшовся, — відмова.
const HOLD_CODES = new Set(['support_pending', 'support_busy', 'replay', 'not_found']);

/** Чому сервер не прийняв: `device` — ключ пристрою, інакше його власні слова. */
export const DEVICE_REFUSED = 'device';

export function refusalReason(e: unknown): string | undefined {
  const err = e as { code?: unknown; message?: unknown } | null;
  if (err?.code === 'support_unauthorized') return DEVICE_REFUSED;
  const text = typeof err?.message === 'string' ? err.message.trim() : '';
  return text && text !== err?.code && text !== 'Unknown error' ? text.slice(0, 200) : undefined;
}

export function holdKind(e: unknown): HoldKind | null {
  const err = e as { status?: unknown; code?: unknown } | null;
  if (!err) return null;
  if (err.code === 'clock_skew') return 'clock';
  if (typeof err.code === 'string' && HOLD_CODES.has(err.code)) return 'server';
  const status = typeof err.status === 'number' ? err.status : 0;
  return status === 408 || status === 429 || status >= 500 ? 'server' : null;
}

export function isServerPending(e: unknown): boolean {
  return holdKind(e) !== null;
}

export function isRefused(e: unknown): boolean {
  const status = (e as { status?: unknown } | null)?.status;
  return typeof status === 'number' && status >= 400 && status < 500 && holdKind(e) === null;
}

export function heldLabelKey(clockSkew: boolean): MessageKey {
  return clockSkew ? 'support.status.clockSkew' : 'support.status.serverPending';
}

export function queuedLabelKey(s: { serverPending: boolean; clockSkew: boolean }, fallback: MessageKey): MessageKey {
  return !isBrowserOffline() && s.serverPending ? heldLabelKey(s.clockSkew) : fallback;
}

export interface SupportState {
  serverPending: boolean;
  clockSkew: boolean;
  reports: SupportReport[];
  outbox: SupportReport[];
  tickets: SupportTicket[];
  replyOutbox: QueuedReply[];
  submitTicket: (input: SubmitTicketInput) => Promise<SupportTicket>;
  replyTicket: (ticketId: string, text: string) => Promise<void>;
  refreshTickets: () => Promise<void>;
  markTicketRead: (id: string) => void;
  activeTarget: ReportTarget | null;
  myReportsOpen: boolean;
  submitReport: (input: SubmitReportInput) => Promise<SupportReport>;
  undoReport: (id: string) => boolean;
  appealReport: (id: string, reason: AppealReason, text: string) => Promise<void>;
  flush: () => Promise<void>;
  flushTickets: () => Promise<void>;
  flushReports: () => Promise<void>;
  refreshStatuses: () => Promise<void>;
  openReport: (target: ReportTarget) => void;
  closeReport: () => void;
  openMyReports: () => void;
  closeMyReports: () => void;
  notices: SupportNotice[];
  loadNotices: (ids: string[]) => Promise<void>;
  appealNotice: (id: number, target: string, reason: AppealReason, text: string) => Promise<void>;
  reset: () => void;
}

const initial = loadFromStorage();
const initialTickets = loadTickets();

export const useSupportStore = create<SupportState>((set, get) => {
  const reached = () => {
    if (get().serverPending || get().clockSkew) set({ serverPending: false, clockSkew: false });
  };
  const hold = (e: unknown): boolean => {
    const kind = holdKind(e);
    if (kind) set({ serverPending: true, clockSkew: kind === 'clock' });
    return kind !== null;
  };
  const pendingOr = (e: unknown) => {
    if (!hold(e)) throw e;
  };
  const flushIfQueued = () => {
    const s = get();
    if (s.outbox.length || s.replyOutbox.length || s.tickets.some((x) => x.status === 'queued')) void s.flush();
  };
  return {
  serverPending: false,
  clockSkew: false,
  reports: initial.reports,
  outbox: initial.outbox,
  tickets: initialTickets.tickets,
  replyOutbox: initialTickets.replyOutbox,
  activeTarget: null,
  myReportsOpen: false,
  notices: [],

  submitTicket: async (input) => {
    const id = input.id || nextClientUuid();
    const now = Date.now();
    const text = input.text.trim();
    const local: SupportTicket = {
      id,
      type: input.type,
      subject: input.subject.trim(),
      text,
      diagnostics: input.diagnostics,
      attachment: input.attachment,
      status: 'queued',
      unread: false,
      createdAt: now,
      thread: [{ id: `${id}-0`, from: 'you', text, at: now }],
    };
    set((s) => {
      const tickets = [local, ...s.tickets.filter((t) => t.id !== id)];
      saveTickets(tickets, s.replyOutbox);
      return { tickets };
    });
    if (isBrowserOffline()) return local;
    let res: SupportTicketResponse;
    try {
      res = await supportApi.submitTicket({
        id,
        type: local.type,
        subject: local.subject,
        text,
        ...(input.diagnostics ? { diagnostics: input.diagnostics } : {}),
        ...(wireAttachment(input.attachment)),
        ...(input.deviceSig ? { device_sig: input.deviceSig } : {}),
      });
    } catch (e) {
      pendingOr(e);
      return local;
    }
    reached();
    const sent = fromWire(res, local);
    set((s) => {
      const tickets = s.tickets.map((t) => (t.id === id ? sent : t));
      saveTickets(tickets, s.replyOutbox);
      return { tickets };
    });
    return sent;
  },

  replyTicket: async (ticketId, text) => {
    const body = text.trim();
    if (!body) return;
    const msg: TicketMessage = { id: nextClientUuid(), from: 'you', text: body, at: Date.now(), queued: true };
    set((s) => {
      const tickets = s.tickets.map((t) => (t.id === ticketId ? { ...t, thread: [...t.thread, msg] } : t));
      const replyOutbox = [...s.replyOutbox, { ticketId, id: msg.id, text: body }];
      saveTickets(tickets, replyOutbox);
      return { tickets, replyOutbox };
    });
    await get().flush();
  },

  refreshTickets: async () => {
    if (isBrowserOffline()) return;
    let remote: SupportTicketResponse[];
    try {
      remote = await supportApi.listTickets();
    } catch (e) {
      pendingOr(e);
      return;
    }
    reached();
    flushIfQueued();
    if (!Array.isArray(remote)) return;
    set((s) => {
      const byId = new Map(s.tickets.map((t) => [t.id, t]));
      const merged = remote.map((r) => fromWire(r, byId.get(r.id)));
      const mergedIds = new Set(merged.map((t) => t.id));
      const tickets = [...merged, ...s.tickets.filter((t) => !mergedIds.has(t.id))].sort((a, b) => b.createdAt - a.createdAt);
      saveTickets(tickets, s.replyOutbox);
      return { tickets };
    });
  },

  markTicketRead: (id) =>
    set((s) => {
      if (!s.tickets.some((t) => t.id === id && t.unread)) return {};
      const tickets = s.tickets.map((t) => (t.id === id ? { ...t, unread: false } : t));
      saveTickets(tickets, s.replyOutbox);
      return { tickets };
    }),

  submitReport: async (input) => {
    const id = input.id || nextClientUuid();
    const now = Date.now();
    const note = input.note?.trim() ? input.note.trim().slice(0, 500) : undefined;
    const baseReport: SupportReport = {
      id,
      targetKind: input.targetKind,
      targetId: input.targetId,
      targetName: input.targetName,
      chatId: input.chatId,
      messageIds: input.messageIds,
      messageText: input.messageText,
      senderKey: input.senderKey,
      surface: input.surface,
      reason: input.reason,
      note,
      createdAt: now,
      status: 'queued',
    };

    const keepQueued = () => {
      set((s) => {
        const filtered = s.reports.filter((r) => r.id !== id);
        const reports = [baseReport, ...filtered];
        const outbox = [baseReport, ...s.outbox.filter((r) => r.id !== id)];
        saveToStorage(reports, outbox);
        return { reports, outbox };
      });
      return baseReport;
    };
    if (isBrowserOffline()) return keepQueued();

    let res: Awaited<ReturnType<typeof supportApi.submitReport>>;
    try {
      res = await supportApi.submitReport({
        id,
        target_kind: input.targetKind,
        target_id: input.targetId,
        chat_id: input.chatId,
        message_ids: input.messageIds,
        message_text: input.messageText,
        sender_key: input.senderKey,
        surface: input.surface,
        reason: input.reason,
        note,
        device_sig: input.deviceSig,
        created_at: now,
      });
    } catch (e) {
      pendingOr(e);
      return keepQueued();
    }
    reached();

    const sentAt = Date.now();
    const sentReport: SupportReport = {
      ...baseReport,
      status: normalizeReportStatus(res?.status ?? 'sent'),
      sentAt,
    };

    set((s) => {
      const reports = [sentReport, ...s.reports.filter((r) => r.id !== id)];
      const outbox = s.outbox.filter((r) => r.id !== id);
      saveToStorage(reports, outbox);
      return { reports, outbox };
    });

    return sentReport;
  },

  undoReport: (id) => {
    const state = get();
    const target = state.reports.find((r) => r.id === id) ?? state.outbox.find((r) => r.id === id);
    if (!target) return false;
    if (flushedIds.has(id)) return false;
    if (target.status !== 'queued') return false;
    if (Date.now() - target.createdAt > UNDO_WINDOW_MS) return false;

    const reports = state.reports.filter((r) => r.id !== id);
    const outbox = state.outbox.filter((r) => r.id !== id);
    set({ reports, outbox });
    saveToStorage(reports, outbox);
    return true;
  },

  appealReport: async (id, reason, text) => {
    const report = get().reports.find((r) => r.id === id);
    if (!report || !canAppealReport(report)) throw new Error('not appealable');
    const res = await supportApi.appealReport(id, { reason, text: text.trim() });
    const appeal = appealFromWire(res.appeal) ?? { reason, text: text.trim(), at: Date.now(), status: 'submitted' as const };
    set((s) => {
      const reports = s.reports.map((r) => (r.id === id ? { ...r, status: normalizeReportStatus(res.status), appeal } : r));
      saveToStorage(reports, s.outbox);
      return { reports };
    });
  },

  flush: () => {
    if (isBrowserOffline()) return Promise.resolve();
    if (!flushing) {
      flushing = (async () => {
        try {
          await get().flushReports();
          await get().flushTickets();
        } finally {
          flushing = null;
        }
      })();
    }
    return flushing;
  },

  flushTickets: async () => {
    for (const t of get().tickets.filter((x) => x.status === 'queued')) {
      try {
        const res = await supportApi.submitTicket({
          id: t.id,
          type: t.type,
          subject: t.subject,
          text: t.text,
          ...(t.diagnostics ? { diagnostics: t.diagnostics } : {}),
          ...wireAttachment(t.attachment),
        });
        reached();
        set((s) => {
          const tickets = s.tickets.map((x) => (x.id === t.id ? fromWire(res, x) : x));
          saveTickets(tickets, s.replyOutbox);
          return { tickets };
        });
      } catch (e) {
        if (hold(e)) return;
        if (isRefused(e)) {
          set((s) => {
            const tickets = s.tickets.map((x) => (x.id === t.id ? { ...x, status: 'failed' as const, failReason: refusalReason(e) } : x));
            const replyOutbox = s.replyOutbox.filter((q) => q.ticketId !== t.id);
            saveTickets(tickets, replyOutbox);
            return { tickets, replyOutbox };
          });
        }
      }
    }
    for (const r of [...get().replyOutbox]) {
      const owner = get().tickets.find((x) => x.id === r.ticketId);
      if (!owner || owner.status === 'queued') continue;
      try {
        const res = await supportApi.appendTicketMessage(r.ticketId, { id: r.id, from: 'you', text: r.text });
        set((s) => {
          const replyOutbox = s.replyOutbox.filter((x) => x.id !== r.id);
          const tickets = s.tickets.map((x) => {
            if (x.id !== r.ticketId) return x;
            const next = fromWire(res, x);
            return { ...next, thread: next.thread.filter((m, i, arr) => arr.findIndex((y) => y.id === m.id) === i) };
          });
          saveTickets(tickets, replyOutbox);
          return { tickets, replyOutbox };
        });
      } catch (e) {
        if (hold(e)) return;
        if (isRefused(e)) {
          set((s) => {
            const replyOutbox = s.replyOutbox.filter((x) => x.id !== r.id);
            const tickets = s.tickets.map((x) =>
              x.id === r.ticketId
                ? { ...x, thread: x.thread.map((m) => (m.id === r.id ? { ...m, queued: false, failed: true } : m)) }
                : x,
            );
            saveTickets(tickets, replyOutbox);
            return { tickets, replyOutbox };
          });
        }
      }
    }
  },

  flushReports: async () => {
    const queue = [...get().outbox];
    if (queue.length === 0) return;

    for (const item of queue) {
      try {
        const res = await supportApi.submitReport({
          id: item.id,
          target_kind: item.targetKind,
          target_id: item.targetId,
          chat_id: item.chatId,
          message_ids: item.messageIds,
          message_text: item.messageText,
          sender_key: item.senderKey,
          surface: item.surface,
          reason: item.reason,
          note: item.note,
          created_at: item.createdAt,
        });
        reached();
        const sentAt = Date.now();
        flushedIds.add(item.id);
        set((s) => {
          const reports = s.reports.map((r) =>
            r.id === item.id
              ? { ...r, status: normalizeReportStatus(res?.status ?? 'sent'), sentAt }
              : r,
          );
          const outbox = s.outbox.filter((r) => r.id !== item.id);
          saveToStorage(reports, outbox);
          return { reports, outbox };
        });
      } catch (e) {
        if (hold(e)) return;
        if (isRefused(e)) {
          set((s) => {
            const reports = s.reports.map((r) => (r.id === item.id ? { ...r, status: 'failed' as const, failReason: refusalReason(e) } : r));
            const outbox = s.outbox.filter((r) => r.id !== item.id);
            saveToStorage(reports, outbox);
            return { reports, outbox };
          });
        }
      }
    }
  },

  refreshStatuses: async () => {
    if (isBrowserOffline()) return;
    try {
      const remote = await supportApi.listReports();
      reached();
      flushIfQueued();
      if (!Array.isArray(remote)) return;
      const byId = new Map(remote.map((r) => [r.id, r]));
      set((s) => {
        const reports = s.reports.map((r) => {
          const found = byId.get(r.id);
          if (!found) return r;
          return {
            ...r,
            status: normalizeReportStatus(found.status),
            appeal: appealFromWire(found.appeal) ?? r.appeal,
            sentAt: r.sentAt ?? (found.sent_at ? Date.parse(found.sent_at) || r.createdAt : r.createdAt),
          };
        });
        saveToStorage(reports, s.outbox);
        return { reports };
      });
    } catch (e) {
      hold(e);
    }
  },

  openReport: (target) => set({ activeTarget: target }),
  closeReport: () => set({ activeTarget: null }),
  openMyReports: () => set({ myReportsOpen: true }),
  closeMyReports: () => set({ myReportsOpen: false }),
  loadNotices: async (ids) => {
    const own = [...new Set(ids.filter((i) => typeof i === 'string' && i.length > 0))].slice(0, 16);
    if (own.length === 0) return;
    try {
      const notices = await supportApi.listNotices(own);
      set({ notices: Array.isArray(notices) ? notices : [] });
    } catch {
      set({ notices: [] });
    }
  },
  appealNotice: async (id, target, reason, text) => {
    const res = await supportApi.appealNotice(id, { target, reason, text: text.trim() });
    set((s) => ({ notices: s.notices.map((n) => (n.id === id ? { ...n, appeal: res.appeal } : n)) }));
  },
  reset: () => {
    flushedIds.clear();
    const loaded = loadFromStorage();
    const loadedTickets = loadTickets();
    set({
      serverPending: false,
      clockSkew: false,
      reports: loaded.reports,
      outbox: loaded.outbox,
      tickets: loadedTickets.tickets,
      replyOutbox: loadedTickets.replyOutbox,
      activeTarget: null,
      myReportsOpen: false,
      notices: [],
    });
  },
  };
});

export const openMyReports = (): void => {
  useSupportStore.getState().openMyReports();
};

if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
  window.addEventListener('online', () => {
    void useSupportStore.getState().flush();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void useSupportStore.getState().flush();
  });
}

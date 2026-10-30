import { request } from './api';
import type { ReportReason, ReportStatus, ReportSurface, ReportTargetKind } from '../stores/supportStore';

export interface SupportReportPayload {
  id: string;
  target_kind: ReportTargetKind;
  target_id: string;
  chat_id?: string;
  message_ids?: string[];
  message_text?: string;
  sender_key?: string;
  surface?: ReportSurface;
  reason: ReportReason;
  note?: string;
  device_sig?: string;
  created_at?: number;
}

export type AppealReason = 'wrong_decision' | 'missing_context' | 'mistake' | 'other';

export interface AppealWire {
  reason: AppealReason;
  text: string;
  at: string;
  status: 'submitted' | 'upheld' | 'overturned';
}

export interface SupportReportResponse {
  id: string;
  target_kind: ReportTargetKind;
  target_id: string;
  chat_id?: string | null;
  message_ids?: string[] | null;
  reason: string;
  note?: string | null;
  device_sig?: string | null;
  status: ReportStatus;
  appeal?: AppealWire | null;
  created_at: string;
  sent_at?: string | null;
  expires_at?: string | null;
}

export type SupportTicketType = 'calls' | 'messages' | 'money' | 'account' | 'map' | 'security' | 'other';

export interface SupportTicketPayload {
  id: string;
  type: SupportTicketType;
  subject: string;
  text: string;
  diagnostics?: Record<string, unknown>;
  attachment?: { mime: string; data_b64: string };
  device_sig?: string;
}

export interface SupportTicketMessageWire {
  id: string;
  from: 'you' | 'support';
  text: string;
  at: string;
}

export interface SupportTicketResponse {
  id: string;
  type: SupportTicketType;
  subject: string;
  text: string;
  diagnostics?: Record<string, unknown> | null;
  diag_expires_at?: string | null;
  attachment?: { mime: string; size: number } | null;
  device_sig?: string | null;
  status: 'open' | 'answered' | 'closed';
  unread: boolean;
  thread: SupportTicketMessageWire[];
  created_at: string;
  updated_at: string;
}

export interface CrashPayload {
  id: string;
  name: string;
  stack_hash: string;
  app_version: string;
  at?: number;
}

export type NoticeScope = 'key' | 'group' | 'channel' | 'space' | 'place';

export interface SupportNotice {
  id: number;
  scope: NoticeScope;
  target: string;
  reason: ReportReason;
  decided_at: string;
  appeal: { status: AppealWire['status']; at: string } | null;
}

export const supportApi = {
  listNotices: (ids: string[]) => request<SupportNotice[]>('POST', '/support/notices', { ids }),
  appealNotice: (id: number, payload: { target: string; reason: AppealReason; text: string }) =>
    request<SupportNotice>('POST', `/support/notices/${id}/appeal`, payload),
  appealReport: (id: string, payload: { reason: AppealReason; text: string }) =>
    request<SupportReportResponse>('POST', `/support/reports/${encodeURIComponent(id)}/appeal`, payload),
  sendCrash: (payload: CrashPayload) => request<{ id: string; stored: boolean }>('POST', '/support/crash', payload),
  submitReport: (payload: SupportReportPayload) =>
    request<SupportReportResponse>('POST', '/support/reports', payload),
  listReports: () =>
    request<SupportReportResponse[]>('GET', '/support/reports'),
  submitTicket: (payload: SupportTicketPayload) =>
    request<SupportTicketResponse>('POST', '/support/tickets', payload),
  listTickets: () => request<SupportTicketResponse[]>('GET', '/support/tickets'),
  appendTicketMessage: (ticketId: string, payload: { id?: string; from: 'you'; text: string }) =>
    request<SupportTicketResponse>('POST', `/support/tickets/${encodeURIComponent(ticketId)}/messages`, payload),
};

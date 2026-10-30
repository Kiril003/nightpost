export type ReportStatus = 'queued' | 'sent' | 'reviewing' | 'action' | 'dismissed';

export interface StaffSupportReport {
  ref: number;
  reporter: string;
  target_kind: 'message' | 'person' | 'group';
  target_id: string;
  chat_id: string | null;
  message_ids: string[] | null;
  sender_key: string | null;
  reason: string;
  note: string | null;
  message_text: string | null;
  status: ReportStatus;
  appeal: { reason: string; text: string; at: string; status: 'submitted' | 'upheld' | 'overturned' } | null;
  decision_reason: string | null;
  created_at: string;
}

export interface StaffReportPage {
  rows: StaffSupportReport[];
  total: number;
  offset: number;
  page: number;
}

export type ReportAction = 'uphold' | 'dismiss' | 'keep' | 'overturn';

export function whoLine(r: Pick<StaffSupportReport, 'target_kind' | 'target_id' | 'sender_key' | 'chat_id'>): string {
  const who = r.target_kind === 'message' ? r.sender_key || r.target_id : r.target_id;
  return [r.target_kind, who, r.chat_id].filter(Boolean).join(' · ');
}

export function nextOffset(p: Pick<StaffReportPage, 'rows' | 'total' | 'offset'>): number | null {
  const next = p.offset + p.rows.length;
  return p.rows.length > 0 && next < p.total ? next : null;
}

export const REASON_MAX = 300;

export function actionsFor(r: Pick<StaffSupportReport, 'status' | 'appeal'>): ReportAction[] {
  if (r.appeal) return r.appeal.status === 'submitted' ? ['keep', 'overturn'] : [];
  return r.status === 'action' || r.status === 'dismissed' ? [] : ['uphold', 'dismiss'];
}

export function decisionBody(
  action: ReportAction,
  reason: string,
): { status?: 'action' | 'dismissed'; appeal_status?: 'upheld' | 'overturned'; reason: string } | null {
  const why = reason.trim().slice(0, REASON_MAX);
  if (!why) return null;
  switch (action) {
    case 'uphold':
      return { status: 'action', reason: why };
    case 'dismiss':
      return { status: 'dismissed', reason: why };
    case 'keep':
      return { appeal_status: 'upheld', reason: why };
    case 'overturn':
      return { appeal_status: 'overturned', reason: why };
  }
}

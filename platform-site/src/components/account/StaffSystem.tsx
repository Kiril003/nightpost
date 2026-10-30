import { useEffect, useState, type ReactNode } from 'react';
import { api, post, type Role, type StaffAssistant, type StaffErasures, type StaffModeration, type StaffMoney, type StaffRoles, type StaffSystem } from '../../lib/account-api';
import type { AccountCopy } from '../../i18n/account';
import type { Lang } from '../../i18n/types';
import { Btn, Card, controlSkin, Empty, fmtDate, PanelHead, Stat, StatGrid, Status, SubHead, Tag } from './Panels';
import { actionsFor, decisionBody, nextOffset, REASON_MAX, whoLine, type ReportAction, type StaffReportPage } from '../../lib/support/reportQueue';

const ROLES: Role[] = ['owner', 'admin', 'moderator', 'user'];

function useStaff<T>(path: string, version = 0): T | null | false {
  const [data, setData] = useState<T | null | false>(null);
  useEffect(() => {
    api<T>(path).then(setData).catch(() => setData(false));
  }, [path, version]);
  return data;
}

function fmtStamp(ms: number, lang: Lang): string {
  return new Date(ms).toLocaleString(lang === 'uk' ? 'uk-UA' : 'en-GB', {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4 py-3">
      <dt className="text-callout text-ink-2">{label}</dt>
      <dd className="tnum min-w-0 break-all text-right text-callout text-ink">{children}</dd>
    </div>
  );
}

function Rows({ children }: { children: ReactNode }) {
  return <dl className="mt-2 divide-y divide-line">{children}</dl>;
}

function NotTracked({ c }: { c: AccountCopy }) {
  return <span className="text-ink-3">{c.ops.notTracked}</span>;
}

function OnOff({ on, c }: { on: boolean; c: AccountCopy }) {
  return <Status tone={on ? 'success' : 'neutral'}>{on ? c.ops.on : c.ops.off}</Status>;
}

function Failed({ c }: { c: AccountCopy }) {
  return <Empty title={c.staff.denied} />;
}

const ACTION_LABEL: Record<ReportAction, keyof AccountCopy['ops']> = {
  uphold: 'uphold',
  dismiss: 'dismiss',
  keep: 'keepDecision',
  overturn: 'overturn',
};

function SupportReports({ c, lang }: { c: AccountCopy; lang: Lang }) {
  const [version, setVersion] = useState(0);
  const [busy, setBusy] = useState('');
  const [failed, setFailed] = useState(false);
  const [why, setWhy] = useState<Record<number, string>>({});
  const [offset, setOffset] = useState(0);
  const data = useStaff<StaffReportPage>(`/api/staff/support/reports?offset=${offset}`, version);
  const decide = async (ref: number, action: ReportAction) => {
    const body = decisionBody(action, why[ref] ?? '');
    if (!body) return;
    setBusy(`${action}${ref}`);
    setFailed(false);
    try {
      await post(`/api/staff/support/reports/${ref}/decision`, body);
      setWhy((w) => ({ ...w, [ref]: '' }));
    } catch {
      setFailed(true);
    }
    setBusy('');
    setVersion((v) => v + 1);
  };
  if (data === false) return null;
  const rows = data ? data.rows : [];
  const next = data ? nextOffset(data) : null;
  return (
    <section data-support-reports>
      <SubHead aside={<Tag>{data ? data.total : 0}</Tag>}>{c.ops.reports}</SubHead>
      {failed && (
        <p role="alert" className="mt-2 text-callout text-danger">
          {c.ops.actionFailed}
        </p>
      )}
      {data && rows.length === 0 ? (
        <div className="mt-3">
          <Empty title={c.ops.reportsEmpty} />
        </div>
      ) : (
        <ul className="card mt-3 divide-y divide-line overflow-hidden">
          {rows.map((r) => {
            const actions = actionsFor(r);
            return (
              <li key={r.ref} data-report-ref={r.ref} className="p-4 sm:px-5">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <span className="flex flex-wrap items-center gap-2 text-callout font-semibold text-ink">
                    <Tag>{r.reason}</Tag>
                    <span className="break-all">{whoLine(r)}</span>
                  </span>
                  <span className="tnum text-footnote text-ink-2">{fmtStamp(Date.parse(r.created_at), lang)}</span>
                </div>
                {r.message_text && <p className="mt-2 break-words text-callout text-ink">{r.message_text}</p>}
                {r.note && <p className="mt-1 break-words text-footnote text-ink-2">{r.note}</p>}
                <p className="tnum mt-1 flex flex-wrap items-center gap-2 text-footnote text-ink-2">
                  <Status tone={r.status === 'action' ? 'success' : 'neutral'}>{r.status}</Status>
                  {r.reporter}
                  {r.decision_reason && <span className="break-words">· {r.decision_reason}</span>}
                </p>
                {r.appeal && (
                  <p className="mt-2 break-words text-footnote text-ink">
                    <span className="text-ink-2">{c.ops.appeal} ({r.appeal.status}): </span>
                    {r.appeal.text}
                  </p>
                )}
                {actions.length > 0 && (
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <input
                      aria-label={c.ops.decisionReason}
                      placeholder={c.ops.decisionReason}
                      maxLength={REASON_MAX}
                      value={why[r.ref] ?? ''}
                      onChange={(e) => setWhy((w) => ({ ...w, [r.ref]: e.target.value }))}
                      className={`${controlSkin} h-11 min-w-0 flex-1 basis-48 px-3`}
                    />
                    {actions.map((a, i) => (
                      <Btn
                        key={a}
                        tone={i === 0 ? 'secondary' : 'ghost'}
                        busy={busy === `${a}${r.ref}`}
                        disabled={busy !== '' || !(why[r.ref] ?? '').trim()}
                        onClick={() => decide(r.ref, a)}
                      >
                        {c.ops[ACTION_LABEL[a]] as string}
                      </Btn>
                    ))}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {(offset > 0 || next !== null) && (
        <div className="mt-3 flex gap-2">
          {offset > 0 && (
            <Btn tone="ghost" onClick={() => setOffset(Math.max(0, offset - (data ? data.page : 0)))}>
              {c.ops.prevPage}
            </Btn>
          )}
          {next !== null && (
            <Btn tone="ghost" onClick={() => setOffset(next)}>
              {c.ops.nextPage}
            </Btn>
          )}
        </div>
      )}
    </section>
  );
}

export function Moderation({ c, lang }: { c: AccountCopy; lang: Lang }) {
  const [version, setVersion] = useState(0);
  const [busy, setBusy] = useState('');
  const [failed, setFailed] = useState(false);
  const data = useStaff<StaffModeration>('/api/staff/moderation', version);
  const act = async (key: string, path: string, body?: unknown) => {
    setBusy(key);
    setFailed(false);
    try {
      await post(path, body);
    } catch {
      setFailed(true);
    }
    setBusy('');
    setVersion((v) => v + 1);
  };
  return (
    <div>
      <PanelHead title={c.nav.moderation} />
      {data === false && <Failed c={c} />}
      {failed && (
        <p role="alert" data-moderation-failed className="mb-4 text-callout text-danger">
          {c.ops.actionFailed}
        </p>
      )}
      {data && (
        <div className="space-y-6">
          <SupportReports c={c} lang={lang} />
          <section>
            <SubHead aside={<Tag>{data.claims.length}</Tag>}>{c.ops.claims}</SubHead>
            {data.claims.length === 0 ? (
              <div className="mt-3">
                <Empty title={c.ops.claimsEmpty} />
              </div>
            ) : (
              <ul className="card mt-3 divide-y divide-line overflow-hidden">
                {data.claims.map((claim) => (
                  <li key={claim.id} className="p-4 sm:px-5">
                    <div className="flex flex-wrap items-baseline justify-between gap-2">
                      <span className="text-callout font-semibold text-ink">{claim.osm_name}</span>
                      <span className="tnum text-footnote text-ink-2">{fmtStamp(claim.created_ms, lang)}</span>
                    </div>
                    <p className="tnum mt-1 flex flex-wrap items-center gap-2 text-footnote text-ink-2">
                      <Tag>{claim.venue_name || claim.venue_id}</Tag>
                      {c.ops.distance} {claim.distance_m} m · {claim.owner}
                    </p>
                    {claim.evidence && (
                      <p className="mt-2 break-words text-footnote text-ink">
                        <span className="text-ink-2">{c.ops.evidence}: </span>
                        {claim.evidence}
                      </p>
                    )}
                    <div className="mt-3 flex flex-wrap gap-2">
                      <Btn
                        tone="secondary"
                        busy={busy === `a${claim.id}`}
                        disabled={busy !== ''}
                        onClick={() => act(`a${claim.id}`, `/claims/${claim.id}/decision`, { approve: true })}
                      >
                        {c.ops.approve}
                      </Btn>
                      <Btn
                        tone="ghost"
                        busy={busy === `r${claim.id}`}
                        disabled={busy !== ''}
                        onClick={() => act(`r${claim.id}`, `/claims/${claim.id}/decision`, { approve: false })}
                      >
                        {c.ops.reject}
                      </Btn>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section>
            <SubHead aside={<Tag>{data.map_notes.length}</Tag>}>{c.ops.mapNotes}</SubHead>
            {data.map_notes.length === 0 ? (
              <div className="mt-3">
                <Empty title={c.ops.mapNotesEmpty} />
              </div>
            ) : (
              <ul className="card mt-3 divide-y divide-line overflow-hidden">
                {data.map_notes.map((note) => {
                  const hidden = note.hidden;
                  return (
                    <li key={note.id} data-note-hidden={hidden || undefined} className="flex flex-wrap items-center gap-x-3 gap-y-1 p-4 sm:px-5">
                      <span className={`rounded-chip bg-overlay px-2 py-0.5 font-mono text-caption ${hidden ? 'text-ink-3' : 'text-ink'}`}>{note.kind}</span>
                      <span className={`min-w-0 basis-full break-words text-callout sm:basis-0 sm:flex-1 ${hidden ? 'text-ink-3 line-through' : 'text-ink'}`}>{note.note}</span>
                      <span className="tnum text-caption text-ink-3">{fmtStamp(note.hour_ms, lang)}</span>
                      <Btn
                        tone="ghost"
                        busy={busy === `n${note.id}`}
                        disabled={busy !== ''}
                        onClick={() => act(`n${note.id}`, `/api/staff/moderation/notes/${note.id}/${hidden ? 'unhide' : 'hide'}`)}
                      >
                        {hidden ? c.ops.restore : c.ops.hide}
                      </Btn>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
          <section>
            <SubHead aside={<Tag>{(data.venues ?? []).length}</Tag>}>{c.ops.venues}</SubHead>
            {(data.venues ?? []).length === 0 ? (
              <div className="mt-3">
                <Empty title={c.ops.venuesEmpty} />
              </div>
            ) : (
              <ul className="card mt-3 divide-y divide-line overflow-hidden">
                {(data.venues ?? []).map((venue) => {
                  const hidden = venue.hidden;
                  return (
                    <li key={venue.id} data-venue-hidden={hidden || undefined} className="flex flex-wrap items-center gap-x-3 gap-y-1 p-4 sm:px-5">
                      {venue.area && (
                        <span className={`rounded-chip bg-overlay px-2 py-0.5 font-mono text-caption ${hidden ? 'text-ink-3' : 'text-ink'}`}>{venue.area}</span>
                      )}
                      <span className={`min-w-0 basis-full break-words text-callout sm:basis-0 sm:flex-1 ${hidden ? 'text-ink-3 line-through' : 'text-ink'}`}>{venue.name}</span>
                      {venue.rating && (
                        <span className="tnum text-caption text-ink-3">
                          {venue.rating.mean.toFixed(2)} ({venue.rating.count})
                        </span>
                      )}
                      <Btn
                        tone="ghost"
                        busy={busy === `v${venue.id}`}
                        disabled={busy !== ''}
                        onClick={() => act(`v${venue.id}`, `/api/staff/venues/${encodeURIComponent(venue.id)}/${hidden ? 'unhide' : 'hide'}`)}
                      >
                        {hidden ? c.ops.restore : c.ops.hide}
                      </Btn>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
        </div>
      )}
    </div>
  );
}

export function Erasures({ c, lang }: { c: AccountCopy; lang: Lang }) {
  const [version, setVersion] = useState(0);
  const [busy, setBusy] = useState('');
  const [failed, setFailed] = useState(false);
  const data = useStaff<StaffErasures>('/api/staff/erasures', version);
  const cancel = async (subject: string) => {
    setBusy(subject);
    setFailed(false);
    try {
      await post(`/api/staff/erasures/${encodeURIComponent(subject)}/cancel`);
    } catch {
      setFailed(true);
    }
    setBusy('');
    setVersion((v) => v + 1);
  };
  return (
    <div>
      <PanelHead title={c.nav.erasures} lead={data ? c.ops.grace(data.grace_days) : undefined} />
      {data === false && <Failed c={c} />}
      {failed && (
        <p role="alert" className="mb-4 text-callout text-danger">
          {c.ops.actionFailed}
        </p>
      )}
      {data && data.rows.length === 0 && <Empty title={c.ops.erasuresEmpty} />}
      {data && data.rows.length > 0 && (
        <ul className="card divide-y divide-line overflow-hidden">
          {data.rows.map((row) => (
            <li key={`${row.subject}-${row.requested_at}`} className="flex flex-wrap items-center gap-3 p-4 sm:px-5">
              <span className="font-mono text-callout text-ink">{row.subject}</span>
              <Tag>{c.ops.kind[row.kind]}</Tag>
              <span className="tnum min-w-0 flex-1 text-footnote text-ink-2">
                {c.ops.due} {fmtStamp(row.due_at * 1000, lang)}
              </span>
              <Status tone={row.status === 'overdue' ? 'danger' : row.status === 'waiting' ? 'success' : 'neutral'}>
                {c.ops.status[row.status]}
              </Status>
              {(row.status === 'waiting' || row.status === 'overdue') && (
                <Btn
                  tone="ghost"
                  busy={busy === row.subject}
                  disabled={busy !== ''}
                  onClick={() => cancel(row.subject)}
                >
                  {c.ops.cancel}
                </Btn>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function Money({ c }: { c: AccountCopy }) {
  const data = useStaff<StaffMoney>('/api/staff/money');
  return (
    <div>
      <PanelHead title={c.nav.money} />
      {data === false && <Failed c={c} />}
      {data && (
        <div className="space-y-6">
          <StatGrid>
            <Stat label={c.ops.cluster} value={data.cluster} text />
            <Stat label={c.ops.escrow} value={data.escrow ? c.ops.yes : c.ops.no} text />
            <Stat label={c.ops.proofsDevnet} value={data.payment_proofs.devnet} />
            <Stat label={c.ops.proofsMainnet} value={data.payment_proofs.mainnet} />
          </StatGrid>
          <Card>
            <SubHead aside={<span className="text-footnote text-ink-3">{c.ops.sinceRestart}</span>}>{c.ops.hook}</SubHead>
            <Rows>
              <Row label={c.ops.lastEvent}>
                {data.hook.last_event_age_s === null ? c.ops.noEvent : c.ops.ago(data.hook.last_event_age_s)}
              </Row>
              <Row label={c.ops.accepted}>{data.hook.hook_ok}</Row>
              <Row label={c.ops.hook503}>{data.hook.hook_503}</Row>
              <Row label={c.ops.watch503}>{data.hook.watch_503}</Row>
              <Row label={c.ops.watched}>
                {data.hook.watched.devnet} · {data.hook.watched.mainnet}
              </Row>
              <Row label={c.ops.webhookDevnet}>
                <OnOff on={data.hook.webhook.devnet} c={c} />
              </Row>
              <Row label={c.ops.webhookMainnet}>
                <OnOff on={data.hook.webhook.mainnet} c={c} />
              </Row>
              <Row label={c.ops.hookAuth}>
                <OnOff on={data.hook.auth} c={c} />
              </Row>
            </Rows>
          </Card>
          <Card>
            <Rows>
              <Row label={c.ops.bills}>
                <NotTracked c={c} />
              </Row>
            </Rows>
          </Card>
        </div>
      )}
    </div>
  );
}

const todayUtc = () => new Date().toISOString().slice(0, 10);

export function Assistant({ c }: { c: AccountCopy }) {
  const data = useStaff<StaffAssistant>('/api/staff/assistant');
  const fallback: StaffAssistant['fallback'] = (data && data.fallback) || [];
  return (
    <div>
      <PanelHead title={c.nav.assistant} />
      {data === false && <Failed c={c} />}
      {data && (
        <div className="space-y-6">
          <StatGrid>
            <Stat label={c.ops.upstream} value={data.upstream} text />
            <Stat label={c.ops.devicesToday} value={data.today.devices} />
            <Stat label={c.ops.asksToday} value={data.today.asks} />
            {data.upstream === 'vertex' && (
              <Stat label={c.ops.fallback} value={fallback.find((r) => r.day === todayUtc())?.vertex_failed ?? 0} />
            )}
          </StatGrid>
          <section>
            <SubHead aside={<span className="text-footnote text-ink-3">{c.ops.sinceRestart}</span>}>{c.ops.byDay}</SubHead>
            {data.days.length === 0 ? (
              <div className="mt-3">
                <Empty title={c.ops.asksEmpty} />
              </div>
            ) : (
              <div className="card mt-3 overflow-x-auto">
                <table className="tnum w-full min-w-[560px] text-left text-footnote">
                  <thead className="text-ink-2">
                    <tr className="border-b border-line">
                      {[c.ops.day, c.ops.profile, c.ops.model, c.ops.asks, c.ops.refused, c.ops.failed, c.ops.errorRate].map((h, i) => (
                        <th key={h} className={`px-4 py-3 font-semibold ${i >= 3 ? 'text-right' : ''}`}>
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line text-ink">
                    {data.days.map((r) => (
                      <tr key={`${r.day}-${r.profile}-${r.model}-${r.upstream}`}>
                        <td className="px-4 py-3">{r.day}</td>
                        <td className="px-4 py-3">{r.profile}</td>
                        <td className="px-4 py-3 font-mono text-caption">{r.model}</td>
                        <td className="px-4 py-3 text-right">{r.asks}</td>
                        <td className="px-4 py-3 text-right">{r.refused}</td>
                        <td className="px-4 py-3 text-right">{r.failed}</td>
                        <td className="px-4 py-3 text-right">{r.asks ? `${Math.round((r.failed / r.asks) * 100)}%` : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}

function fmtUptime(s: number): string {
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m`;
}

export function SystemPanel({ c, lang }: { c: AccountCopy; lang: Lang }) {
  const sys = useStaff<StaffSystem>('/api/staff/system');
  const roles = useStaff<StaffRoles>('/api/staff/roles');
  return (
    <div>
      <PanelHead title={c.nav.system} />
      {sys === false && <Failed c={c} />}
      <div className="space-y-6">
        {sys && (
          <Card>
            <SubHead>{c.ops.health}</SubHead>
            <Rows>
              <Row label={c.ops.health}>
                <Status tone={sys.ok ? 'success' : 'danger'}>{c.ops.healthy}</Status>
              </Row>
              <Row label={c.ops.relay}>
                <OnOff on={sys.relay} c={c} />
              </Row>
              <Row label={c.ops.started}>{fmtDate(sys.started_at, lang)}</Row>
              <Row label={c.ops.uptime}>{fmtUptime(sys.uptime_s)}</Row>
              <Row label={c.ops.image}>{sys.image ?? <NotTracked c={c} />}</Row>
              <Row label={c.ops.region}>{sys.region ?? <NotTracked c={c} />}</Row>
              <Row label={c.ops.sha}>{sys.sha ?? <NotTracked c={c} />}</Row>
              <Row label={c.ops.siteBuild}>
                <NotTracked c={c} />
              </Row>
            </Rows>
          </Card>
        )}
        {roles && (
          <section>
            <SubHead>{c.ops.roles}</SubHead>
            <div className="mt-3">
              <StatGrid>
                {ROLES.map((role) => (
                  <Stat key={role} label={c.roles[role]} value={roles.counts[role] ?? 0} />
                ))}
              </StatGrid>
            </div>
            {roles.staff.length === 0 ? (
              <div className="mt-3">
                <Empty title={c.ops.staffEmpty} />
              </div>
            ) : (
              <ul className="card mt-3 divide-y divide-line overflow-hidden">
                {roles.staff.map((row) => (
                  <li key={`${row.email}-${row.role}`} className="flex flex-wrap items-center gap-3 p-4 sm:px-5">
                    <span className="min-w-0 flex-1 break-all text-callout text-ink">{row.email}</span>
                    {row.disabled && <Status tone="danger">{c.staff.disabled}</Status>}
                    <Tag>{c.roles[row.role]}</Tag>
                    <span className="tnum text-footnote text-ink-2">
                      {c.staff.lastSeen} {row.last_seen_at ? fmtDate(row.last_seen_at, lang) : c.staff.never}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
      </div>
    </div>
  );
}

import { useCallback, useEffect, useId, useState } from 'react';
import { Check, Copy, KeyRound, Search, ShieldAlert } from 'lucide-react';
import {
  api,
  atLeast,
  post,
  type AuditEvent,
  type License,
  type Role,
  type StaffAccount,
} from '../../lib/account-api';
import type { AccountCopy } from '../../i18n/account';
import { pricingCopy } from '../../i18n/commerce';
import type { Lang } from '../../i18n/types';
import type { PlanId } from '../../lib/plans';
import {
  Btn,
  Card,
  controlSkin,
  Empty,
  Field,
  fmtDate,
  labelSkin,
  PanelHead,
  Stat,
  StatGrid,
  Status,
  SubHead,
  Tag,
} from './Panels';

const ROLES: Role[] = ['user', 'moderator', 'admin', 'owner'];
const ISSUABLE: PlanId[] = ['personal', 'crew', 'unit'];
const ICON = { strokeWidth: 1.75 } as const;

interface Overview {
  accounts: number;
  licenses: number;
  revoked: number;
  devices: number;
  waitlist: number;
  threads_open: number;
  unread: number;
}

export function StaffOverview({ c }: { c: AccountCopy }) {
  const [data, setData] = useState<Overview | null>(null);
  useEffect(() => {
    api<Overview>('/api/staff/overview').then(setData).catch(() => setData(null));
  }, []);
  if (!data) return <PanelHead title={c.staff.title} lead={c.staff.lead} />;
  return (
    <div>
      <PanelHead title={c.staff.title} lead={c.staff.lead} />
      <StatGrid>
        <Stat label={c.staff.accounts} value={data.accounts} />
        <Stat label={c.staff.licenses} value={data.licenses} />
        <Stat label={c.staff.devices} value={data.devices} />
        <Stat label={c.staff.waitlist} value={data.waitlist} />
        <Stat label={c.staff.threadsOpen} value={data.threads_open} />
        <Stat label={c.staff.unread} value={data.unread} />
        <Stat label={c.staff.revoked} value={data.revoked} />
        {/* Восьма клітинка закриває сітку 2×4 і 4×2 без дірки. */}
        <div className="bg-surface dark:bg-raised" aria-hidden />
      </StatGrid>
    </div>
  );
}

export function People({ c, lang, myRole, myId }: { c: AccountCopy; lang: Lang; myRole: Role; myId: string }) {
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<StaffAccount[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    setRows(await api<StaffAccount[]>(`/api/staff/accounts?q=${encodeURIComponent(query)}`));
  }, [query]);

  useEffect(() => {
    load().catch(() => setRows([]));
  }, [load]);

  const setRole = async (account: StaffAccount, role: Role) => {
    setBusy(account.id);
    try {
      await post('/api/staff/role', { account_id: account.id, role });
      await load();
    } finally {
      setBusy(null);
    }
  };

  const toggle = async (account: StaffAccount) => {
    setBusy(account.id);
    try {
      await post('/api/staff/account/disable', {
        account_id: account.id,
        disabled: !account.disabled,
      });
      await load();
    } finally {
      setBusy(null);
    }
  };

  const canAssignRoles = myRole === 'owner';

  return (
    <div>
      <PanelHead title={c.nav.people} lead={canAssignRoles ? undefined : c.staff.ownerOnly} />
      <form
        className="mb-4 flex items-end gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          void load();
        }}
      >
        <div className="min-w-0 flex-1">
          <Field label={c.staff.search} value={query} onChange={setQuery} type="search" />
        </div>
        <Btn type="submit" tone="secondary" label={c.staff.search}>
          <Search className="h-4 w-4" {...ICON} aria-hidden />
        </Btn>
      </form>
      {rows && rows.length > 0 && (
        <ul className="card divide-y divide-line overflow-hidden">
          {rows.map((row) => (
            <li key={row.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:px-5">
              <span className="min-w-0 flex-1">
                <span className="flex flex-wrap items-center gap-2">
                  <span className="break-all text-callout text-ink">{row.email}</span>
                  {row.disabled && <Status tone="danger">{c.staff.disabled}</Status>}
                </span>
                <span className="block text-footnote text-ink-2">
                  {row.display_name || '—'} · {c.staff.lastSeen}{' '}
                  {row.last_seen_at ? fmtDate(row.last_seen_at, lang) : c.staff.never}
                </span>
              </span>
              <div className="flex flex-wrap items-center gap-3">
                <select
                  value={row.role}
                  aria-label={`${c.staff.role}: ${row.email}`}
                  disabled={!canAssignRoles || busy === row.id || row.id === myId}
                  onChange={(e) => setRole(row, e.target.value as Role)}
                  className={`${controlSkin} h-11 !w-auto px-3 text-callout`}
                >
                  {ROLES.map((role) => (
                    <option key={role} value={role}>
                      {c.roles[role]}
                    </option>
                  ))}
                </select>
                {atLeast(myRole, 'admin') && row.id !== myId && (
                  <Btn tone={row.disabled ? 'secondary' : 'danger'} busy={busy === row.id} onClick={() => toggle(row)}>
                    {row.disabled ? c.staff.enable : c.staff.disable}
                  </Btn>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
      {rows && rows.length === 0 && <Empty title={c.staff.nothing} />}
    </div>
  );
}

export function AllLicenses({ c, lang }: { c: AccountCopy; lang: Lang }) {
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<License[] | null>(null);
  const [email, setEmail] = useState('');
  const [tier, setTier] = useState<PlanId>('personal');
  const [issued, setIssued] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const tierId = useId();
  const plans = pricingCopy[lang].plans;

  const load = useCallback(async () => {
    setRows(await api<License[]>(`/api/staff/licenses?q=${encodeURIComponent(query)}`));
  }, [query]);

  useEffect(() => {
    load().catch(() => setRows([]));
  }, [load]);

  const issue = async () => {
    setBusy(true);
    try {
      const res = await post<{ license_key: string }>('/api/staff/license/issue', { email, tier });
      setIssued(res.license_key);
      setCopied(false);
      setEmail('');
      await load();
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (license: License) => {
    await post('/api/staff/license/revoke', { license_id: license.id, reason: 'staff' });
    await load();
  };

  return (
    <div>
      <PanelHead title={c.nav.allLicenses} />
      <Card className="mb-6">
        <SubHead>{c.staff.issueTitle}</SubHead>
        <form
          className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-end"
          onSubmit={(e) => {
            e.preventDefault();
            if (!busy && email.includes('@')) void issue();
          }}
        >
          <div className="min-w-0 flex-1">
            <Field label={c.staff.issueEmail} value={email} onChange={setEmail} type="email" autoComplete="off" />
          </div>
          <div>
            <label htmlFor={tierId} className={labelSkin}>
              {c.licenses.tier}
            </label>
            <select
              id={tierId}
              value={tier}
              onChange={(e) => setTier(e.target.value as PlanId)}
              className={`${controlSkin} h-12 px-4 sm:w-48`}
            >
              {ISSUABLE.map((id) => (
                <option key={id} value={id}>
                  {plans[id]?.name ?? id}
                </option>
              ))}
            </select>
          </div>
          <Btn type="submit" busy={busy} disabled={!email.includes('@')}>
            <KeyRound className="h-4 w-4" {...ICON} aria-hidden />
            {c.staff.issue}
          </Btn>
        </form>
        {issued && (
          <div className="mt-5 rounded-control bg-accent-soft p-4">
            <p className="text-footnote font-semibold text-ink">{c.staff.issued}</p>
            <code className="mt-2 block break-all font-mono text-callout text-ink">{issued}</code>
            <div className="mt-2 flex flex-wrap items-center gap-3">
              <button
                type="button"
                onClick={() => {
                  void navigator.clipboard?.writeText(issued);
                  setCopied(true);
                }}
                className="btn-ghost -ml-3 !min-h-[44px]"
              >
                {copied ? <Check className="h-4 w-4" {...ICON} aria-hidden /> : <Copy className="h-4 w-4" {...ICON} aria-hidden />}
                {copied ? c.staff.copied : c.staff.copy}
              </button>
              <p className="text-footnote text-ink-2">{c.staff.issuedNote}</p>
            </div>
          </div>
        )}
      </Card>

      <div className="mb-4">
        <Field label={c.staff.search} value={query} onChange={setQuery} type="search" />
      </div>
      {rows && rows.length > 0 && (
        <ul className="card divide-y divide-line overflow-hidden">
          {rows.map((row) => (
            <li key={row.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center sm:px-5">
              <span className="min-w-0 flex-1">
                <span className="block break-all text-callout text-ink">{row.email}</span>
                <span className="tnum mt-0.5 flex flex-wrap items-center gap-2 text-footnote text-ink-2">
                  <Tag>{plans[row.tier as PlanId]?.name ?? row.tier}</Tag>
                  {row.devices.length}/{row.max_activations} · {fmtDate(row.created_at, lang)}
                </span>
              </span>
              {row.revoked ? (
                <Status tone="danger">{c.licenses.revoked}</Status>
              ) : (
                <Btn tone="danger" onClick={() => revoke(row)}>
                  {c.staff.revoke}
                </Btn>
              )}
            </li>
          ))}
        </ul>
      )}
      {rows && rows.length === 0 && <Empty title={c.staff.nothing} />}
    </div>
  );
}

export function Journal({ c, lang }: { c: AccountCopy; lang: Lang }) {
  const [rows, setRows] = useState<AuditEvent[] | null>(null);
  useEffect(() => {
    api<AuditEvent[]>('/api/staff/events').then(setRows).catch(() => setRows([]));
  }, []);
  return (
    <div>
      <PanelHead title={c.nav.journal} lead={c.staff.journalLead} />
      {rows && rows.length > 0 && (
        <ul className="card divide-y divide-line overflow-hidden">
          {rows.map((row, i) => (
            <li key={`${row.ts}-${i}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 p-4 sm:px-5">
              <span className="tnum text-footnote text-ink-2">{fmtDate(row.ts, lang)}</span>
              {/* Назва події — машинне слово сервера, тому моноширинним. */}
              <span className="rounded-chip bg-overlay px-2 py-0.5 font-mono text-caption text-ink">{row.kind}</span>
              <span className="min-w-0 basis-full break-words text-footnote text-ink-2 sm:basis-0 sm:flex-1">
                {row.detail}
              </span>
              <span className="tnum text-caption text-ink-3">{row.ip ?? '—'}</span>
            </li>
          ))}
        </ul>
      )}
      {rows && rows.length === 0 && <Empty title={c.staff.journalEmpty} />}
    </div>
  );
}

export function Denied({ c }: { c: AccountCopy }) {
  return <Empty title={c.staff.denied} icon={<ShieldAlert className="h-6 w-6" {...ICON} />} />;
}

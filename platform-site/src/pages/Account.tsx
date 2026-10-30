import { useCallback, useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';
import { Section } from '../components/ui';
import { Tag, Downloads, Licenses, Security, Settings, Threads } from '../components/account/Panels';
import { AllLicenses, Denied, Journal, People, StaffOverview } from '../components/account/Staff';
import { Assistant, Erasures, Moderation, Money, SystemPanel } from '../components/account/StaffSystem';
import SignedOut from '../components/account/SignedOut';
import { tabShown } from '../components/account/tabs';
import AccountHome, { AccountDevices } from '../components/account/AccountHome';
import { displayName, licenceSectionVisible, memberSince } from '../components/account/homeModel';
import { Purchases, Stored } from '../components/account/Overview';
import { cabinetCopy } from '../i18n/cabinet';
import { homeCopy } from '../i18n/accountHome';
import { useLanguage } from '../i18n';
import { accountCopy, type AccountCopy } from '../i18n/account';
import type { Lang } from '../i18n/types';
import { usePageMeta } from '../lib/meta';
import { signInLeadsTo } from '../lib/accountFlow';
import { safeNext } from '../lib/nextPath';
import { api, ApiError, atLeast, post, readToken, writeToken, type Me, type Role } from '../lib/account-api';

async function fetchMeWithRetry(token: string): Promise<Me> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await api<Me>('/api/me', {
        headers: { Authorization: `Bearer ${token}` },
      });
    } catch (err) {
      if (err instanceof ApiError && err.status === 429 && attempt < 3) {
        await new Promise((resolve) => setTimeout(resolve, 1000 * (attempt + 1)));
        continue;
      }
      throw err;
    }
  }
}

type TabId =
  | 'overview'
  | 'devices'
  | 'purchases'
  | 'licenses'
  | 'downloads'
  | 'threads'
  | 'settings'
  | 'security'
  | 'staff'
  | 'people'
  | 'allLicenses'
  | 'queue'
  | 'journal'
  | 'moderation'
  | 'erasures'
  | 'money'
  | 'assistant'
  | 'system';

interface Tab {
  id: TabId | 'places';
  to?: string;
  needs?: Role;
}

const TABS: Tab[] = [
  { id: 'overview' },
  { id: 'devices' },
  { id: 'licenses' },
  { id: 'purchases' },
  { id: 'places', to: '/build/place' },
  { id: 'threads' },
  { id: 'security' },
  { id: 'staff', needs: 'moderator' },
  { id: 'queue', needs: 'moderator' },
  { id: 'people', needs: 'admin' },
  { id: 'allLicenses', needs: 'admin' },
  { id: 'journal', needs: 'admin' },
  { id: 'moderation', needs: 'moderator' },
  { id: 'erasures', needs: 'admin' },
  { id: 'money', needs: 'admin' },
  { id: 'assistant', needs: 'admin' },
  { id: 'system', needs: 'admin' },
];

const TAB_IDS = new Set<string>(TABS.filter((t) => !t.to).map((t) => t.id));

function initialTab(): TabId {
  const hash = typeof window === 'undefined' ? '' : window.location.hash.slice(1);
  if (hash === 'downloads') return 'overview';
  if (hash === 'settings') return 'security';
  return TAB_IDS.has(hash) ? (hash as TabId) : 'overview';
}

export default function Account() {
  const { lang } = useLanguage();
  const c = accountCopy[lang];
  const h = homeCopy[lang];
  usePageMeta(c.meta);

  const [me, setMe] = useState<Me | null>(null);
  const [booting, setBooting] = useState(true);
  const [tab, setTabState] = useState<TabId>(initialTab);
  const setTab = useCallback((next: TabId) => {
    const resolved: TabId = next === 'downloads' ? 'overview' : next === 'settings' ? 'security' : next;
    setTabState(resolved);
    try {
      window.history.replaceState(null, '', `#${resolved}`);
    } catch {
      /* без history — просто без адреси розділу */
    }
  }, []);

  const navigate = useNavigate();
  const load = useCallback(async (newToken?: string, verified?: { scope?: string; due_at?: number }) => {
    if (newToken && signInLeadsTo(verified) === 'pendingErase') {
      writeToken(newToken);
      navigate('/delete-account', { state: { pendingDueAt: verified?.due_at ?? null } });
      return;
    }
    const token = newToken ?? readToken();
    if (!token) {
      setMe(null);
      setBooting(false);
      return;
    }
    try {
      const next = await fetchMeWithRetry(token);
      if (newToken) writeToken(newToken);
      setMe(next);
    } catch (err) {
      setMe(null);
      if (!newToken && err instanceof ApiError && err.status === 403) writeToken(null);
      if (newToken) throw err;
    } finally {
      setBooting(false);
    }
  }, [navigate]);

  useEffect(() => {
    load();
  }, [load]);

  const signedIn = useCallback(
    async (newToken?: string, verified?: { scope?: string; due_at?: number }) => {
      await load(newToken, verified);
      if (newToken && signInLeadsTo(verified) === 'pendingErase') return;
      const next = safeNext(new URLSearchParams(window.location.search).get('next'));
      if (next && readToken()) navigate(next, { replace: true });
    },
    [load, navigate],
  );

  const leave = async () => {
    try {
      await post('/api/auth/logout');
    } catch {
      /* сервер міг і не відповісти — локально виходимо однаково */
    }
    writeToken(null);
    setMe(null);
    setTab('overview');
  };

  if (booting) {
    return (
      <Section>
        <div className="flex min-h-[40vh] items-center justify-center gap-3 text-subhead text-ink-2" role="status">
          <Loader2 className="h-5 w-5 animate-spin" strokeWidth={1.75} aria-hidden />
          {c.loading}
        </div>
      </Section>
    );
  }

  if (!me) return <SignedOut onDone={signedIn} />;

  const role = me.account.role;
  const name = displayName(me.account);
  const since = memberSince(me.account.created_at, lang);
  const hasLicence = licenceSectionVisible(me);
  const visible = TABS.filter(
    (item) =>
      (!item.needs || atLeast(role, item.needs)) && tabShown(item.id) &&
      ((item.id !== 'licenses' && item.id !== 'purchases') || hasLicence),
  );
  const staffTabs = visible.filter((item) => item.needs);
  const staffBadge = atLeast(role, 'moderator') ? me.staff_unread : 0;

  return (
    <div className="mx-auto w-full max-w-shell px-4 pb-16 pt-8 sm:px-6 lg:px-14">
      <div className="mb-8">
        <p className="eyebrow">{c.hero.eyebrow}</p>
        <div className="mt-2 flex flex-wrap items-center gap-3">
          <h1 className="min-w-0 break-words text-page-h1 text-ink">{name || h.account}</h1>
          {role !== 'user' && <Tag>{c.roles[role]}</Tag>}
        </div>
        <p className="mt-2 break-words text-body text-ink-2">
          {me.account.display_name && me.account.email
            ? `${me.account.email} · ${h.since(since)}`
            : h.since(since)}
        </p>
      </div>

      <div className="flex flex-col gap-10">
        <nav aria-label={c.navLabel} className="flex flex-wrap items-center gap-6 border-b border-line">
          <NavList
            items={visible.filter((item) => !item.needs)}
            tab={tab}
            onPick={setTab}
            c={c}
            me={me}
            staffBadge={staffBadge}
          />
          {staffTabs.length > 0 && (
            <>
              <span aria-hidden className="h-4 w-px bg-line" />
              <NavList items={staffTabs} tab={tab} onPick={setTab} c={c} me={me} staffBadge={staffBadge} />
            </>
          )}
        </nav>

        <div className="min-w-0">
          <Panel tab={tab} me={me} role={role} c={c} lang={lang} reload={load} onLoggedOut={leave} onTab={setTab} />
        </div>
      </div>
    </div>
  );
}

function NavList({
  items,
  tab,
  onPick,
  c,
  me,
  staffBadge,
  className = '',
}: {
  items: Tab[];
  tab: TabId;
  onPick: (id: TabId) => void;
  c: AccountCopy;
  me: Me;
  staffBadge: number;
  className?: string;
}) {
  const { lang } = useLanguage();
  const extra = cabinetCopy[lang].nav;
  const label = (id: Tab['id']) =>
    id === 'overview' || id === 'devices' || id === 'purchases' ? extra[id] : c.nav[id];
  return (
    <ul className={`flex flex-wrap items-center gap-6 ${className}`}>
      {items.map((item) => {
        const on = tab === item.id;
        const badge =
          item.id === 'threads' ? me.account.unread : item.id === 'queue' ? staffBadge : 0;
        const cls = `inline-flex h-11 items-center gap-2 text-subhead transition-colors duration-quick ${
          on ? '-mb-px border-b-2 border-accent text-ink' : 'text-ink-2 hover:text-ink'
        }`;
        const inner = (
          <>
            <span>{label(item.id)}</span>
            {badge > 0 && (
              <span className="tnum rounded-chip bg-accent px-1.5 text-caption text-on-accent">{badge}</span>
            )}
          </>
        );
        return (
          <li key={item.id}>
            {item.to ? (
              <Link to={item.to} className={cls}>
                {inner}
              </Link>
            ) : (
              <button
                type="button"
                onClick={() => onPick(item.id as TabId)}
                aria-current={on ? 'page' : undefined}
                className={cls}
              >
                {inner}
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function Panel({
  tab,
  me,
  role,
  c,
  lang,
  reload,
  onLoggedOut,
  onTab,
}: {
  tab: TabId;
  me: Me;
  role: Role;
  c: AccountCopy;
  lang: Lang;
  reload: () => Promise<void>;
  onLoggedOut: () => void;
  onTab: (id: TabId) => void;
}) {
  switch (tab) {
    case 'overview':
      return <AccountHome me={me} lang={lang} onTab={onTab} onSignOut={onLoggedOut} />;
    case 'devices':
      return <AccountDevices lang={lang} onSignOut={onLoggedOut} />;
    case 'purchases':
      return licenceSectionVisible(me) ? <Purchases me={me} lang={lang} /> : null;
    case 'licenses':
      return licenceSectionVisible(me) ? (
        <Licenses
          me={me}
          c={c}
          lang={lang}
          reload={reload}
          onThreads={() => onTab('threads')}
          onDevices={() => onTab('devices')}
        />
      ) : null;
    case 'downloads':
      return <Downloads me={me} c={c} lang={lang} />;
    case 'threads':
      return <Threads c={c} lang={lang} reload={reload} />;
    case 'settings':
    case 'security':
      return (
        <div className="space-y-12">
          <Settings me={me} c={c} reload={reload} />
          <Security c={c} lang={lang} onLoggedOut={onLoggedOut} />
          <Stored lang={lang} me={me} />
        </div>
      );
    case 'staff':
      return atLeast(role, 'moderator') ? <StaffOverview c={c} /> : <Denied c={c} />;
    case 'queue':
      return atLeast(role, 'moderator') ? (
        <Threads c={c} lang={lang} reload={reload} staffView />
      ) : (
        <Denied c={c} />
      );
    case 'people':
      return atLeast(role, 'admin') ? (
        <People c={c} lang={lang} myRole={role} myId={me.account.id} />
      ) : (
        <Denied c={c} />
      );
    case 'allLicenses':
      return atLeast(role, 'admin') ? <AllLicenses c={c} lang={lang} /> : <Denied c={c} />;
    case 'journal':
      return atLeast(role, 'admin') ? <Journal c={c} lang={lang} /> : <Denied c={c} />;
    case 'moderation':
      return atLeast(role, 'moderator') ? <Moderation c={c} lang={lang} /> : <Denied c={c} />;
    case 'erasures':
      return atLeast(role, 'admin') ? <Erasures c={c} lang={lang} /> : <Denied c={c} />;
    case 'money':
      return atLeast(role, 'admin') ? <Money c={c} /> : <Denied c={c} />;
    case 'assistant':
      return atLeast(role, 'admin') ? <Assistant c={c} /> : <Denied c={c} />;
    case 'system':
      return atLeast(role, 'admin') ? <SystemPanel c={c} lang={lang} /> : <Denied c={c} />;
    default:
      return null;
  }
}

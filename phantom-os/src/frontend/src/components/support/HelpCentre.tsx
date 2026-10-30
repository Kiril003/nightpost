import React, { useEffect, useMemo, useState } from 'react';
import { AlertCircle, ChevronRight, Clock, EyeOff, Flag, LifeBuoy, Search, WifiOff } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { FormSheet, PrimaryButton } from '../messenger/FormSheet';
import { ListRow } from '../messenger/kit/ListRow';
import { HELP_ARTICLES, HELP_TOPICS, helpArticleKeys, type HelpArticle, type HelpTopic } from '../../data/helpArticles';
import { DEVICE_REFUSED, heldLabelKey, useSupportStore, type SupportTicket, type TicketStatus } from '../../stores/supportStore';
import { isPhoneShell } from '../messenger/phoneShell';
import { formatReportTimeAgo, MyReports, NoticesSheet } from './MyReports';
import { useMessengerStore } from '../../stores/messengerStore';
import { ProblemForm } from './ProblemForm';

export interface HelpCentreProps {
  open: boolean;
  onClose: () => void;
  inline?: boolean;
}

const TOPIC_KEY: Record<HelpTopic | 'security', MessageKey> = {
  calls: 'help.topic.calls',
  messages: 'help.topic.messages',
  money: 'help.topic.money',
  account: 'help.topic.account',
  map: 'help.topic.map',
  security: 'help.topic.security',
  other: 'help.topic.other',
};

const STATUS_KEY: Record<TicketStatus, MessageKey> = {
  queued: 'tickets.status.queued',
  open: 'tickets.status.open',
  answered: 'tickets.status.answered',
  closed: 'tickets.status.closed',
  failed: 'tickets.status.failed',
};

function useOnline(): boolean {
  const [online, setOnline] = useState(() => typeof navigator === 'undefined' || navigator.onLine !== false);
  useEffect(() => {
    const up = () => setOnline(true);
    const down = () => setOnline(false);
    window.addEventListener('online', up);
    window.addEventListener('offline', down);
    return () => {
      window.removeEventListener('online', up);
      window.removeEventListener('offline', down);
    };
  }, []);
  return online;
}


const Row: React.FC<{ testId?: string; icon: React.ReactNode; label: string; onClick: () => void; badge?: React.ReactNode }> = ({
  testId,
  icon,
  label,
  onClick,
  badge,
}) => (
  <button
    type="button"
    data-testid={testId}
    onClick={onClick}
    className="w-full min-h-14 px-4 flex items-center gap-3 text-left hover:bg-overlay/60 active:bg-overlay"
  >
    <span className="shrink-0 text-ink-2">{icon}</span>
    <span className="flex-1 text-body text-ink">{label}</span>
    {badge}
    <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
  </button>
);

const Empty: React.FC<{ icon: React.ReactNode; label: string; testId: string; children?: React.ReactNode }> = ({
  icon,
  label,
  testId,
  children,
}) => (
  <div data-testid={testId} className="px-4 py-12 flex flex-col items-center text-center gap-3">
    <span className="w-12 h-12 rounded-full bg-surface flex items-center justify-center text-ink-3">{icon}</span>
    <p className="text-callout text-ink-2">{label}</p>
    {children}
  </div>
);

export const TicketList: React.FC<{ open: boolean; onClose: () => void; inline?: boolean }> = ({ open, onClose, inline }) => {
  const { t, locale } = useTranslation();
  const tickets = useSupportStore((s) => s.tickets);
  const online = useOnline();
  const serverPending = useSupportStore((s) => s.serverPending);
  const clockSkew = useSupportStore((s) => s.clockSkew);
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading');
  const [openId, setOpenId] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!open) return;
    if (!online) {
      setPhase('ready');
      return;
    }
    let live = true;
    setPhase('loading');
    useSupportStore
      .getState()
      .refreshTickets()
      .then(() => live && setPhase('ready'))
      .catch(() => live && setPhase('error'));
    return () => {
      live = false;
    };
  }, [open, online, attempt]);

  if (!open) return null;
  if (openId) return <TicketThread ticketId={openId} onClose={() => setOpenId(null)} inline={inline} />;

  return (
    <FormSheet open onClose={onClose} inline={inline} tall title={t('help.tickets')} testId="ticket-list-sheet">
      {!online && (
        <div data-testid="ticket-offline-banner" className="mx-4 mb-2 px-3 h-10 rounded-control bg-surface flex items-center gap-2 text-footnote text-ink-2">
          <WifiOff className="w-4 h-4" strokeWidth={1.75} aria-hidden />
          {t('tickets.offline')}
        </div>
      )}
      {phase === 'loading' && tickets.length === 0 ? (
        <Empty testId="ticket-loading" icon={<Clock className="w-6 h-6" strokeWidth={1.75} aria-hidden />} label={t('tickets.loading')} />
      ) : phase === 'error' && tickets.length === 0 ? (
        <Empty testId="ticket-error" icon={<AlertCircle className="w-6 h-6" strokeWidth={1.75} aria-hidden />} label={t('tickets.error')}>
          <div className="w-full max-w-[240px]">
            <PrimaryButton onClick={() => setAttempt((n) => n + 1)}>{t('support.failed.retry')}</PrimaryButton>
          </div>
        </Empty>
      ) : tickets.length === 0 ? (
        <Empty testId="ticket-empty" icon={<LifeBuoy className="w-6 h-6" strokeWidth={1.75} aria-hidden />} label={t('tickets.empty')} />
      ) : (
        <div className="py-2">
          {tickets.map((tk) => (
            <ListRow
              key={tk.id}
              data-testid={`ticket-row-${tk.id}`}
              onClick={() => setOpenId(tk.id)}
              leading={
                <span className="w-10 h-10 shrink-0 rounded-full bg-surface flex items-center justify-center text-ink-2">
                  <LifeBuoy className="w-5 h-5" strokeWidth={1.75} aria-hidden />
                </span>
              }
              title={tk.subject}
              subtitle={
                tk.status === 'failed' && tk.failReason
                  ? tk.failReason === DEVICE_REFUSED
                    ? t('support.refused.device')
                    : tk.failReason
                  : t(TOPIC_KEY[tk.type])
              }
              time={formatReportTimeAgo(tk.createdAt, locale)}
              badge={
                <span className={`shrink-0 h-6 px-2.5 rounded-full text-caption font-medium flex items-center ${tk.unread ? 'bg-accent text-on-accent' : 'bg-surface text-ink-2'}`}>
                  {serverPending && tk.status === 'queued' ? t(heldLabelKey(clockSkew)) : t(STATUS_KEY[tk.status])}
                </span>
              }
            />
          ))}
        </div>
      )}
    </FormSheet>
  );
};

export const TicketThread: React.FC<{ ticketId: string; onClose: () => void; inline?: boolean }> = ({ ticketId, onClose, inline }) => {
  const { t, locale } = useTranslation();
  const ticket: SupportTicket | undefined = useSupportStore((s) => s.tickets.find((x) => x.id === ticketId));
  const serverPending = useSupportStore((s) => s.serverPending);
  const clockSkew = useSupportStore((s) => s.clockSkew);
  const online = useOnline();
  const [draft, setDraft] = useState('');

  useEffect(() => {
    useSupportStore.getState().markTicketRead(ticketId);
  }, [ticketId, ticket?.thread.length]);

  const send = () => {
    const body = draft.trim();
    if (!body) return;
    setDraft('');
    void useSupportStore.getState().replyTicket(ticketId, body);
  };

  return (
    <FormSheet
      open
      onClose={onClose}
      inline={inline}
      tall
      title={ticket?.subject ?? t('help.tickets')}
      testId="ticket-thread-sheet"
      footer={ticket && ticket.status !== 'closed' ? (
            <div className="flex items-end gap-2">
              <textarea
                data-testid="ticket-reply-input"
                aria-label={t('tickets.reply')}
                rows={2}
                maxLength={2000}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                placeholder={t('tickets.reply')}
                className="flex-1 p-3 rounded-control bg-surface border border-line text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line resize-none"
              />
              <button
                type="button"
                data-testid="ticket-reply-send"
                onClick={send}
                disabled={!draft.trim()}
                aria-label={t('tickets.replySend')}
                className="h-12 px-4 rounded-control bg-accent text-on-accent text-headline disabled:opacity-40"
              >
                {t('problem.send')}
              </button>
            </div>
      ) : undefined}
    >
      {!online && (
        <div data-testid="ticket-offline-banner" className="mx-4 mb-2 px-3 h-10 rounded-control bg-surface flex items-center gap-2 text-footnote text-ink-2">
          <WifiOff className="w-4 h-4" strokeWidth={1.75} aria-hidden />
          {t('tickets.offline')}
        </div>
      )}
      {!ticket ? (
        <Empty testId="ticket-missing" icon={<LifeBuoy className="w-6 h-6" strokeWidth={1.75} aria-hidden />} label={t('tickets.notFound')} />
      ) : (
        <>
          <div className="px-4 pb-3 space-y-2">
            {ticket.thread.map((m) => (
              <div key={m.id} className={`flex ${m.from === 'you' ? 'justify-end' : 'justify-start'}`}>
                <div
                  data-testid={`ticket-msg-${m.from}`}
                  className={`max-w-[85%] px-3.5 py-2 rounded-card text-callout break-words whitespace-pre-wrap ${m.from === 'you' ? 'bg-accent-soft text-ink' : 'bg-surface text-ink'}`}
                >
                  {m.from === 'support' && <span className="block text-caption font-semibold text-ink-2">{t('tickets.support')}</span>}
                  {m.text}
                  <span className="mt-1 flex items-center gap-1.5 text-caption text-ink-3">
                    {m.queued ? (
                      <span data-testid="ticket-msg-queued" className="flex items-center gap-1">
                        <Clock className="w-3 h-3" strokeWidth={1.75} aria-hidden />
                        {serverPending ? t(heldLabelKey(clockSkew)) : t('tickets.queued')}
                      </span>
                    ) : m.failed ? (
                      <span data-testid="ticket-msg-failed">{t('tickets.status.failed')}</span>
                    ) : (
                      formatReportTimeAgo(m.at, locale)
                    )}
                  </span>
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </FormSheet>
  );
};

const ArticleView: React.FC<{ article: HelpArticle }> = ({ article }) => {
  const { t } = useTranslation();
  const [title, ...steps] = helpArticleKeys(article).map((k) => t(k as MessageKey));
  return (
    <div data-testid="help-article-view" className="px-4 pb-4">
      <h3 className="text-title-1 text-ink pb-3">{title}</h3>
      <ol className="space-y-3 list-decimal pl-5 text-body text-ink">
        {steps.map((s, i) => (
          <li key={i}>{s}</li>
        ))}
      </ol>
    </div>
  );
};

export const HelpCentre: React.FC<HelpCentreProps> = ({ open, onClose, inline = false }) => {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [article, setArticle] = useState<HelpArticle | null>(null);
  const [problemOpen, setProblemOpen] = useState(false);
  const [ticketsOpen, setTicketsOpen] = useState(false);
  const [reportsOpen, setReportsOpen] = useState(false);
  const [noticesOpen, setNoticesOpen] = useState(false);
  const hasNotices = useSupportStore((s) => s.notices.length > 0);
  const meId = useMessengerStore((s) => s.currentUser.id);
  const ownPublic = useMessengerStore((s) =>
    s.chats
      .filter((c) => c.isPublic && (c.myRole === 'owner' || (!!c.ownerId && c.ownerId === s.currentUser.id)))
      .map((c) => c.id)
      .join('|'),
  );

  useEffect(() => {
    if (!open) return;
    void useSupportStore.getState().loadNotices([meId, ...(ownPublic ? ownPublic.split('|') : [])]);
  }, [open, meId, ownPublic]);
  const unread = useSupportStore((s) => s.tickets.filter((x) => x.unread).length);
  const hasTickets = useSupportStore((s) => s.tickets.length > 0);
  // Персонал поки не бачить звернень із сервера (лише скарги), тож телефон нових не створює.
  const canWrite = !(__PHONE_BUILD__ || isPhoneShell());

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return HELP_ARTICLES;
    return HELP_ARTICLES.filter((a) =>
      helpArticleKeys(a)
        .map((k) => t(k as MessageKey))
        .join(' ')
        .toLowerCase()
        .includes(q),
    );
  }, [query, t]);

  if (!open) return null;

  return (
    <>
      <FormSheet
        open
        onClose={onClose}
        onBack={article ? () => setArticle(null) : undefined}
        inline={inline}
        tall
        title={t('help.title')}
        testId="help-centre-sheet"
      >
        {article ? (
          <ArticleView article={article} />
        ) : (
          <div className="pb-4">
            <div className="px-4 pb-3">
              <label className="h-12 px-3 rounded-control bg-surface border border-line flex items-center gap-2 focus-within:outline focus-within:outline-2 focus-within:outline-accent-line">
                <Search className="w-4 h-4 shrink-0 text-ink-3" strokeWidth={1.75} aria-hidden />
                <input
                  data-testid="help-search-input"
                  aria-label={t('help.search')}
                  placeholder={t('help.search')}
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  className="flex-1 min-w-0 bg-transparent text-body text-ink placeholder:text-ink-3 outline-none"
                />
              </label>
            </div>

            {matches.length === 0 ? (
              <Empty testId="help-search-empty" icon={<Search className="w-6 h-6" strokeWidth={1.75} aria-hidden />} label={t('help.searchEmpty')}>
                {canWrite && (
                  <div className="w-full max-w-[280px]">
                    <PrimaryButton onClick={() => setProblemOpen(true)}>{t('help.reportProblem')}</PrimaryButton>
                  </div>
                )}
              </Empty>
            ) : (
              HELP_TOPICS.map((topic) => {
                const rows = matches.filter((a) => a.topic === topic);
                if (rows.length === 0) return null;
                return (
                  <div key={topic} className="pb-2">
                    <p className="px-5 pt-2 pb-1 text-footnote font-semibold text-ink-2">{t(TOPIC_KEY[topic])}</p>
                    <div className="mx-4 rounded-card bg-surface overflow-hidden">
                      {rows.map((a, i) => (
                        <button
                          key={a.id}
                          type="button"
                          data-testid={`help-article-row-${a.id}`}
                          onClick={() => setArticle(a)}
                          className={`w-full min-h-12 px-4 flex items-center gap-3 text-left text-callout text-ink hover:bg-overlay/60 active:bg-overlay ${i > 0 ? 'border-t border-line' : ''}`}
                        >
                          <span className="flex-1">{t(`help.${a.id}.title` as MessageKey)}</span>
                          <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden="true" />
                        </button>
                      ))}
                    </div>
                  </div>
                );
              })
            )}

            <div className="mt-2 mx-4 rounded-card bg-surface overflow-hidden">
              {canWrite && (
                <>
                  <Row testId="help-report" icon={<AlertCircle className="w-5 h-5" strokeWidth={1.75} aria-hidden />} label={t('help.reportProblem')} onClick={() => setProblemOpen(true)} />
                  <div aria-hidden className="ml-12 h-px bg-line" />
                </>
              )}
              {(canWrite || hasTickets) && (
                <>
                  <Row
                    testId="help-tickets"
                    icon={<LifeBuoy className="w-5 h-5" strokeWidth={1.75} aria-hidden />}
                    label={t('help.tickets')}
                    onClick={() => setTicketsOpen(true)}
                    badge={unread > 0 ? <span className="h-5 min-w-5 px-1.5 rounded-full bg-accent text-on-accent text-caption font-semibold tnum flex items-center justify-center">{unread}</span> : undefined}
                  />
                  <div aria-hidden className="ml-12 h-px bg-line" />
                </>
              )}
              <Row testId="help-reports" icon={<Flag className="w-5 h-5" strokeWidth={1.75} aria-hidden />} label={t('support.myReports.title')} onClick={() => setReportsOpen(true)} />
              {hasNotices && (
                <>
                  <div aria-hidden className="ml-12 h-px bg-line" />
                  <Row testId="help-notices" icon={<EyeOff className="w-5 h-5" strokeWidth={1.75} aria-hidden />} label={t('support.notices.title')} onClick={() => setNoticesOpen(true)} />
                </>
              )}
            </div>
          </div>
        )}
      </FormSheet>
      {canWrite && <ProblemForm open={problemOpen} onClose={() => setProblemOpen(false)} />}
      <TicketList open={ticketsOpen} onClose={() => setTicketsOpen(false)} />
      {reportsOpen && <MyReports open onClose={() => setReportsOpen(false)} />}
      {noticesOpen && <NoticesSheet onClose={() => setNoticesOpen(false)} />}
    </>
  );
};

export default HelpCentre;

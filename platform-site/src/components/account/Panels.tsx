import { useCallback, useEffect, useId, useState, type ReactNode } from 'react';
import {
  AlertCircle,
  ArrowLeft,
  Check,
  ChevronDown,
  CircleDashed,
  Copy,
  Download,
  Loader2,
  Plus,
  RotateCcw,
  ShieldCheck,
} from 'lucide-react';
import { Link } from 'react-router-dom';
import {
  api,
  ApiError,
  atLeast,
  patch,
  post,
  revokeSession,
  type License,
  type Me,
  type Message,
  type SessionItem,
  type Thread,
} from '../../lib/account-api';
import type { AccountCopy } from '../../i18n/account';
import { revokeAllSignsOut, statusOf } from '../../lib/accountFlow';
import { pricingCopy } from '../../i18n/commerce';
import type { Lang } from '../../i18n/types';
import { megabytes } from '../../lib/downloads';
import { FEATURE_ORDER, PLANS, TRIAL_DAYS, planById, surfaceOf, type FeatureKey, type PlanId } from '../../lib/plans';

/*
 * Будівельні деталі кабінету в мові «теплої ночі».
 *
 * Форма одна на весь кабінет: поле 48px, тло на крок вище за картку, межа
 * `line-strong`, фокус — суцільна бурштинова лінія у 2px без сяйва. Підпис
 * стоїть НАД полем, помилка — під ним, і вона каже, що сталося і що робити.
 */

const ICON = { strokeWidth: 1.75 } as const;

/** Шкіра поля вводу, списку й багаторядкового поля — одна на всіх. */
export const controlSkin =
  'w-full rounded-control border border-line-strong bg-raised text-body text-ink placeholder:text-ink-3 outline-none transition-colors duration-quick ease-standard focus:border-accent-line focus:ring-1 focus:ring-accent-line focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60 aria-[invalid=true]:border-danger';

export const labelSkin = 'mb-2 block text-footnote font-semibold text-ink-2';

export function fmtDate(iso: string | null, lang: Lang): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleDateString(lang === 'uk' ? 'uk-UA' : 'en-GB', {
        day: 'numeric',
        month: 'short',
        year: 'numeric',
      });
}

/** Дата З ЧАСОМ. У листуванні дві репліки часто в один день, і сама лише дата
 *  не давала зрозуміти ні порядку, ні свіжості — обидві виглядали однаково. */
export function fmtWhen(iso: string | null, lang: Lang): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const locale = lang === 'uk' ? 'uk-UA' : 'en-GB';
  const today = new Date().toDateString() === date.toDateString();
  const time = date.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
  return today ? time : `${date.toLocaleDateString(locale, { day: 'numeric', month: 'short' })}, ${time}`;
}

export function PanelHead({ title, lead }: { title: string; lead?: string }) {
  return (
    <div className="mb-6">
      <h2 className="text-h3 text-ink">{title}</h2>
      {lead && <p className="mt-2 max-w-prose text-callout text-ink-2">{lead}</p>}
    </div>
  );
}

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`card p-5 sm:p-6 ${className}`}>{children}</div>;
}

/** Підзаголовок усередині картки. */
export function SubHead({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <h3 className="text-headline text-ink">{children}</h3>
      {aside}
    </div>
  );
}

export function Stat({
  label,
  value,
  text = false,
}: {
  label: string;
  value: ReactNode;
  /** Дата — не число: тим самим кеглем вона на телефоні ламається на два рядки. */
  text?: boolean;
}) {
  return (
    <div className="bg-surface p-5 dark:bg-raised">
      <p className={text ? 'text-title-2 text-ink' : 'tnum text-h2 text-ink'}>{value}</p>
      <p className="mt-2 text-footnote text-ink-2">{label}</p>
    </div>
  );
}

export function StatGrid({ children }: { children: ReactNode }) {
  return (
    <div className="grid grid-cols-2 gap-px overflow-hidden rounded-card border border-line bg-line md:grid-cols-4">
      {children}
    </div>
  );
}

/** Повідомлення під формою: помилка — червоним і з поясненням, успіх — зеленим. */
export function Notice({ tone, children, className = '' }: { tone: 'error' | 'success'; children: ReactNode; className?: string }) {
  const Icon = tone === 'error' ? AlertCircle : Check;
  return (
    <p
      role={tone === 'error' ? 'alert' : 'status'}
      className={`flex items-start gap-2 text-callout ${tone === 'error' ? 'text-danger' : 'text-success'} ${className}`}
    >
      <Icon className="mt-[3px] h-4 w-4 shrink-0" {...ICON} aria-hidden />
      <span>{children}</span>
    </p>
  );
}

/** Стан ліцензії чи звернення: слово і крапка, без кольорової пігулки. */
export function Status({ tone, children }: { tone: 'success' | 'danger' | 'neutral'; children: ReactNode }) {
  const color = tone === 'success' ? 'text-success' : tone === 'danger' ? 'text-danger' : 'text-ink-2';
  return (
    <span className={`inline-flex shrink-0 items-center gap-1.5 text-footnote font-semibold ${color}`}>
      <span className="h-2 w-2 rounded-full bg-current" aria-hidden />
      {children}
    </span>
  );
}

/** Нейтральна позначка: роль, платформа, «лише в телефоні». */
export function Tag({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex items-center whitespace-nowrap rounded-chip bg-overlay px-2 py-0.5 text-caption text-ink-2">
      {children}
    </span>
  );
}

export function Field({
  label,
  value,
  onChange,
  placeholder,
  type = 'text',
  mono = false,
  hint,
  error,
  ...rest
}: {
  label: string;
  value: string;
  onChange: (next: string) => void;
  placeholder?: string;
  type?: string;
  /** Лише для ключа ліцензії й коду — це рядки, які звіряють посимвольно. */
  mono?: boolean;
  hint?: string;
  error?: string | null;
} & Omit<React.InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'type'>) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  return (
    <div>
      <label htmlFor={id} className={labelSkin}>
        {label}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error ? true : undefined}
        aria-describedby={[hintId, errorId].filter(Boolean).join(' ') || undefined}
        className={`${controlSkin} h-12 px-4 ${mono ? 'font-mono' : ''}`}
        {...rest}
      />
      {hint && (
        <p id={hintId} className="mt-2 text-footnote text-ink-3">
          {hint}
        </p>
      )}
      {error && (
        <div id={errorId} className="mt-2">
          <Notice tone="error">{error}</Notice>
        </div>
      )}
    </div>
  );
}

export function Btn({
  children,
  onClick,
  busy = false,
  disabled = false,
  tone = 'primary',
  type = 'button',
  full = false,
  label,
}: {
  children: ReactNode;
  onClick?: () => void;
  busy?: boolean;
  disabled?: boolean;
  /** Бурштинова заливка — одна на ділянку. Решта дій — `secondary` або `ghost`. */
  tone?: 'primary' | 'secondary' | 'ghost' | 'danger';
  type?: 'button' | 'submit';
  /** На вузькому екрані кнопка стає окремою смугою на всю ширину. */
  full?: boolean;
  /** Підпис для кнопки, де лише іконка. */
  label?: string;
}) {
  const skin =
    tone === 'primary'
      ? 'btn-primary'
      : tone === 'danger'
        ? 'btn-secondary !text-danger'
        : tone === 'ghost'
          ? 'btn-ghost'
          : 'btn-secondary';
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled || busy}
      aria-label={label}
      aria-busy={busy || undefined}
      className={`${skin} !min-h-[44px] disabled:cursor-not-allowed ${full ? 'w-full' : ''}`}
    >
      {busy && <Loader2 className="h-4 w-4 animate-spin" {...ICON} aria-hidden />}
      {children}
    </button>
  );
}

export function Empty({
  title,
  hint,
  icon,
  action,
}: {
  title: string;
  hint?: string;
  icon?: ReactNode;
  /** Порожній екран, який НАЗИВАЄ потрібну вкладку замість того, щоб туди
   *  вести, змушує людину шукати руками те, що вже знає сама сторінка. */
  action?: ReactNode;
}) {
  return (
    <div className="card flex flex-col items-center gap-2 px-6 py-12 text-center">
      {icon && <span className="mb-1 text-ink-2">{icon}</span>}
      <p className="text-headline text-ink">{title}</p>
      {hint && <p className="max-w-md text-callout text-ink-2">{hint}</p>}
      {action && <div className="mt-3">{action}</div>}
    </div>
  );
}

function daysUntil(iso: string | null): number | null {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  return Number.isNaN(then) ? null : Math.ceil((then - Date.now()) / 86_400_000);
}

/**
 * Смуга строку оновлень. Початок беремо з дати покупки, а не рахуємо як
 * «кінець мінус роки рівня»: у продовженого вручну строку (і в Unit, де років
 * не задано зовсім) вирахувана позначка стояла б не там, де насправді.
 */
function WindowRail({ from, to }: { from: string; to: string }) {
  const start = new Date(from).getTime();
  const end = new Date(to).getTime();
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return null;
  const gone = Math.max(0, Math.min(100, ((Date.now() - start) / (end - start)) * 100));
  return (
    <div className="mt-3 h-1.5 w-full overflow-hidden rounded-full bg-overlay" aria-hidden>
      <span className="block h-full rounded-full bg-accent-line" style={{ width: `${gone}%` }} />
    </div>
  );
}

const FREE_KEYS = new Set(planById('free').features.map((f) => f.key));

/** Найвищий чинний рівень: два ключі різних рівнів не мусять малювати два
 *  майже однакові переліки прав один під одним. */
function bestTier(me: Me): PlanId | null {
  let rank = -1;
  for (const lic of me.licenses) {
    if (lic.revoked) continue;
    const found = PLANS.findIndex((p) => p.id === lic.tier);
    if (found > rank) rank = found;
  }
  return rank >= 0 ? PLANS[rank].id : null;
}

/** Що ключ ДОДАВ понад вільний рівень: повний перелік тут був би десятьма
 *  рядками, з яких шість вільні в кожній копії. */
function TierRights({ tier, c, lang }: { tier: PlanId; c: AccountCopy; lang: Lang }) {
  const pricing = pricingCopy[lang];
  const plan = planById(tier);
  const rows = FEATURE_ORDER.filter(
    (key) => !FREE_KEYS.has(key) && plan.features.some((f) => f.key === key),
  );
  if (rows.length === 0) return null;
  return (
    <Card className="mt-4">
      <SubHead>{c.licenses.rightsTitle}</SubHead>
      <ul className="mt-4 grid gap-2.5 sm:grid-cols-2">
        {rows.map((key: FeatureKey) => {
          const live = plan.features.find((f) => f.key === key)?.live ?? false;
          const surface = pricing.surfaces[surfaceOf(key)];
          return (
            <li key={key} className="flex items-start gap-2.5 text-callout">
              {live ? (
                <Check className="mt-[3px] h-4 w-4 shrink-0 text-ink-2" {...ICON} aria-hidden />
              ) : (
                <CircleDashed className="mt-[3px] h-4 w-4 shrink-0 text-ink-3" {...ICON} aria-hidden />
              )}
              <span className={`flex flex-wrap items-center gap-x-2 gap-y-1 ${live ? 'text-ink' : 'text-ink-2'}`}>
                {pricing.features[key]}
                {surface && <Tag>{surface}</Tag>}
                {!live && <span className="text-footnote text-ink-3">{c.licenses.roadmapTag}</span>}
              </span>
            </li>
          );
        })}
      </ul>
      <div className="mt-5 space-y-2 border-t border-line pt-4 text-footnote text-ink-2">
        <p>{c.licenses.rightsFree}</p>
        <p>{c.licenses.rightsTrial(TRIAL_DAYS)}</p>
      </div>
    </Card>
  );
}

export function Licenses({
  me,
  c,
  lang,
  onThreads,
  onDevices,
}: {
  me: Me;
  c: AccountCopy;
  lang: Lang;
  reload?: () => Promise<void>;
  /** Відмова без виходу — це глухий кут. Кожне «не можемо» веде в листування. */
  onThreads: () => void;
  /** Пристрої живуть в окремому розділі — тут лише їхні імена й двері туди. */
  onDevices?: () => void;
}) {
  const manageDevices = lang === 'uk' ? 'Керувати пристроями' : 'Manage devices';
  const [confirming, setConfirming] = useState<string | null>(null);
  const [reissuing, setReissuing] = useState<string | null>(null);
  const [reissued, setReissued] = useState<string | null>(null);
  const [reissueError, setReissueError] = useState<'failed' | 'tooMany' | null>(null);

  if (me.licenses.length === 0) return null;

  const reissue = async (license: License) => {
    setReissuing(license.id);
    setReissueError(null);
    try {
      await post(`/api/me/licenses/${license.id}/reissue`);
      setReissued(license.id);
      setConfirming(null);
    } catch (error) {
      setReissueError(error instanceof ApiError && error.status === 429 ? 'tooMany' : 'failed');
    } finally {
      setReissuing(null);
    }
  };

  const tier = bestTier(me);
  const pricing = pricingCopy[lang];
  const licenses = [...me.licenses].sort((a, b) => Number(a.revoked) - Number(b.revoked));

  return (
    <div>
      <PanelHead title={c.licenses.title} lead={c.licenses.lead} />

      <div className="space-y-4">
        {licenses.map((lic) => {
          const left = daysUntil(lic.updates_until);
          const closed = left !== null && left <= 0;
          const windowLine = !lic.updates_until
            ? c.licenses.updatesNone
            : closed
              ? `${c.licenses.updatesEnded} · ${fmtDate(lic.updates_until, lang)}`
              : `${c.licenses.updates} ${fmtDate(lic.updates_until, lang)}${
                  left === null ? '' : ` · ${c.licenses.updatesLeft(left)}`
                }`;
          return (
            <Card key={lic.id}>
              <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
                <div className="min-w-0">
                  <h3 className="text-title-2 text-ink">{pricing.plans[lic.tier as PlanId]?.name ?? lic.tier}</h3>
                  {/* Дата покупки — єдине, чим два однакові ключі відрізняються
                      на екрані: самого ключа кабінет не має й мати не може. */}
                  <p className="mt-0.5 text-footnote text-ink-2">
                    {c.licenses.bought} {fmtDate(lic.created_at, lang)}
                  </p>
                </div>
                <Status tone={lic.revoked ? 'danger' : 'success'}>
                  {lic.revoked ? c.licenses.revoked : c.licenses.active}
                </Status>
              </div>

              {lic.revoked ? (
                <div className="mt-4 border-t border-line pt-4">
                  <p className="max-w-prose text-callout text-ink-2">{c.licenses.revokedLead}</p>
                  <div className="mt-3">
                    <Btn tone="secondary" onClick={onThreads}>
                      {c.licenses.writeUs}
                    </Btn>
                  </div>
                </div>
              ) : (
                <>
                  <div className="mt-4 border-t border-line pt-4">
                    <p className="text-callout text-ink">{windowLine}</p>
                    {lic.updates_until && !closed && <WindowRail from={lic.created_at} to={lic.updates_until} />}
                    {closed && <p className="mt-2 text-footnote text-ink-2">{c.licenses.windowEnded}</p>}
                  </div>

                  <div className="mt-5 border-t border-line pt-4">
                    <SubHead
                      aside={
                        <span className="tnum text-footnote text-ink-2">
                          {lic.devices.length}/{lic.max_activations}
                        </span>
                      }
                    >
                      {c.licenses.devices}
                    </SubHead>
                    {lic.devices.length === 0 ? (
                      <p className="mt-3 text-callout text-ink-2">{c.licenses.none}</p>
                    ) : (
                      <p className="mt-2 text-callout text-ink-2">
                        {lic.devices.map((d) => d.name || `…${d.fingerprint.slice(-8)}`).join(', ')}
                      </p>
                    )}
                    {onDevices && lic.devices.length > 0 && (
                      <div className="mt-3">
                        <Btn tone="ghost" onClick={onDevices}>
                          {manageDevices}
                        </Btn>
                      </div>
                    )}
                  </div>

                  {lic.can_reissue !== undefined && (
                    <div className="mt-4 border-t border-line pt-4">
                      {reissued === lic.id ? (
                        <Notice tone="success">{c.licenses.reissueDone(me.account.email)}</Notice>
                      ) : !lic.can_reissue ? (
                        <>
                          <p className="max-w-prose text-footnote text-ink-2">{c.licenses.notMine}</p>
                          <div className="mt-3">
                            <Btn tone="secondary" onClick={onThreads}>
                              {c.licenses.writeUs}
                            </Btn>
                          </div>
                        </>
                      ) : (
                        <>
                          <SubHead>{c.licenses.lostTitle}</SubHead>
                          <p className="mt-1.5 max-w-prose text-footnote text-ink-2">
                            {c.licenses.lostLead(me.account.email)}
                          </p>
                          {confirming === lic.id ? (
                            <>
                              <p className="mt-3 rounded-control bg-accent-soft px-4 py-3 text-footnote text-ink">
                                {c.licenses.reissueWarn}
                              </p>
                              <div className="mt-3 flex flex-wrap gap-3">
                                <Btn tone="danger" busy={reissuing === lic.id} onClick={() => reissue(lic)}>
                                  {reissuing === lic.id ? c.licenses.reissuing : c.licenses.reissueConfirm}
                                </Btn>
                                <Btn
                                  tone="ghost"
                                  onClick={() => {
                                    setConfirming(null);
                                    setReissueError(null);
                                  }}
                                >
                                  {c.licenses.reissueCancel}
                                </Btn>
                              </div>
                              {reissueError && (
                                <Notice tone="error" className="mt-3">
                                  {reissueError === 'tooMany' ? c.licenses.reissueTooMany : c.licenses.reissueFailed}
                                </Notice>
                              )}
                            </>
                          ) : (
                            <div className="mt-3">
                              <Btn
                                tone="secondary"
                                onClick={() => {
                                  setConfirming(lic.id);
                                  setReissueError(null);
                                }}
                              >
                                <RotateCcw className="h-4 w-4" {...ICON} aria-hidden />
                                {c.licenses.reissue}
                              </Btn>
                            </div>
                          )}
                        </>
                      )}
                    </div>
                  )}
                </>
              )}
            </Card>
          );
        })}
      </div>

      {tier && <TierRights tier={tier} c={c} lang={lang} />}
    </div>
  );
}

/** Та сама двійкова мегабайтність, що й на сторінці завантажень: кабінет казав
 *  «56 МБ» там, де сайт і завантажувач телефона кажуть «53». */
function fmtSize(bytes: number | null, lang: Lang): string | null {
  const mb = megabytes(bytes);
  return mb ? `${mb} ${lang === 'uk' ? 'МБ' : 'MB'}` : null;
}

/** Сума файла поруч із файлом. Сторінка /download учить її звіряти, а кабінет
 *  досі не давав що звіряти — тобто вчив дії, яку сам робив неможливою. */
function Digest({ value, c }: { value: string; c: AccountCopy }) {
  const [copied, setCopied] = useState(false);
  return (
    <details className="group rounded-control bg-raised px-4 py-1">
      <summary className="flex min-h-[44px] cursor-pointer list-none items-center justify-between gap-2 text-footnote font-semibold text-ink-2 hover:text-ink">
        {c.downloads.sha256}
        <ChevronDown className="h-4 w-4 shrink-0 text-ink-2 transition-transform duration-quick group-open:rotate-180" {...ICON} aria-hidden />
      </summary>
      <p className="text-footnote text-ink-2">{c.downloads.sha256Hint}</p>
      <p className="mt-2 break-all font-mono text-footnote text-ink">{value}</p>
      <div className="mb-2 mt-1">
        <button
          type="button"
          onClick={() => {
            void navigator.clipboard?.writeText(value);
            setCopied(true);
            window.setTimeout(() => setCopied(false), 1600);
          }}
          className="btn-ghost -ml-3 !min-h-[44px]"
        >
          {copied ? <Check className="h-4 w-4" {...ICON} aria-hidden /> : <Copy className="h-4 w-4" {...ICON} aria-hidden />}
          {copied ? c.downloads.copied : c.downloads.copy}
        </button>
      </div>
    </details>
  );
}

export function Downloads({
  me,
  c,
  lang,
}: {
  me: Me;
  c: AccountCopy;
  lang: Lang;
  onLicenses?: () => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  const grab = async (platform: string) => {
    setBusy(platform);
    setFailed(false);
    try {
      const { url } = await post<{ url: string }>('/api/download/link', { platform });
      window.location.href = url;
    } catch {
      setFailed(true);
    } finally {
      setBusy(null);
    }
  };

  if (me.downloads.length === 0) {
    return (
      <div>
        <PanelHead title={c.downloads.title} lead={c.downloads.lead} />
        <Empty title={c.downloads.none} hint={c.downloads.noneHint} icon={<Download className="h-6 w-6" {...ICON} />} />
      </div>
    );
  }

  const licensed =
    me.download_policy === 'open' ||
    me.download_policy === 'account' ||
    me.licenses.some((license) => !license.revoked) ||
    atLeast(me.account.role, 'moderator');

  return (
    <div>
      <PanelHead title={c.downloads.title} lead={c.downloads.lead} />
      <div className="grid gap-4 md:grid-cols-2">
        {me.downloads.map((build, index) => {
          const size = fmtSize(build.size, lang);
          return (
            <Card key={build.platform} className="flex flex-col gap-4">
              <div>
                <h3 className="text-title-2 text-ink">{c.downloads.platform[build.platform] ?? build.platform}</h3>
                {/* Версія стоїть біля назви платформи: єдине питання перед
                    кнопкою — чи це новіше за те, що вже стоїть на пристрої. */}
                {build.version && (
                  <p className="tnum mt-0.5 text-footnote text-ink-2">
                    {build.version}
                    {build.build ? ` · ${c.downloads.buildTag(build.build)}` : ''}
                    {size && ` · ${size}`}
                  </p>
                )}
                {/* Файл названо тут, бо саме він приїде в теку «Завантаження». */}
                <p className="mt-1 break-all text-footnote text-ink-3">
                  {build.filename}
                  {!build.version && size && ` · ${size}`}
                </p>
              </div>
              {/* Пояснення СТОЇТЬ ПЕРЕД кнопкою: мобільний трафік читають до
                  натискання, а не після. */}
              {c.downloads.install[build.platform] && (
                <p className="text-footnote text-ink-2">{c.downloads.install[build.platform]}</p>
              )}
              {build.sha256 && <Digest value={build.sha256} c={c} />}
              <div className="mt-auto">
                {licensed ? (
                  <Btn
                    full
                    tone={index === 0 ? 'primary' : 'secondary'}
                    busy={busy === build.platform}
                    onClick={() => grab(build.platform)}
                  >
                    {busy !== build.platform && <Download className="h-4 w-4" {...ICON} aria-hidden />}
                    {busy === build.platform ? c.downloads.preparing : c.downloads.get}
                  </Btn>
                ) : (
                  <p className="text-subhead text-ink-2">{c.downloads.needLicense}</p>
                )}
              </div>
            </Card>
          );
        })}
      </div>
      {failed && (
        <Notice tone="error" className="mt-4">
          {c.downloads.failed}
        </Notice>
      )}
    </div>
  );
}

export function Threads({
  c,
  lang,
  reload,
  staffView = false,
}: {
  c: AccountCopy;
  lang: Lang;
  reload: () => Promise<void>;
  staffView?: boolean;
}) {
  const [list, setList] = useState<Thread[] | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [thread, setThread] = useState<{ messages: Message[]; author?: { email: string } } | null>(null);
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const [composing, setComposing] = useState(false);
  const bodyId = useId();

  const path = staffView ? '/api/staff/threads' : '/api/me/threads';

  const loadList = useCallback(async () => {
    setList(await api<Thread[]>(path));
  }, [path]);

  useEffect(() => {
    loadList();
  }, [loadList]);

  const openThread = async (id: string) => {
    setOpenId(id);
    setThread(await api(`/api/threads/${id}`));
    await Promise.all([loadList(), reload()]);
  };

  const start = async () => {
    setBusy(true);
    setFailed(false);
    try {
      const created = await post<Thread>('/api/me/threads', { subject, body });
      setSubject('');
      setBody('');
      setComposing(false);
      await loadList();
      await openThread(created.id);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  const reply = async () => {
    if (!openId) return;
    setBusy(true);
    setFailed(false);
    try {
      await post(`/api/threads/${openId}/reply`, { body });
      setBody('');
      setThread(await api(`/api/threads/${openId}`));
      await Promise.all([loadList(), reload()]);
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  const close = async () => {
    if (!openId) return;
    await post(`/api/staff/threads/${openId}/close`);
    await loadList();
    setOpenId(null);
    setThread(null);
  };

  if (openId && thread) {
    return (
      <div>
        <button
          type="button"
          onClick={() => {
            setOpenId(null);
            setThread(null);
            setBody('');
            setFailed(false);
          }}
          className="btn-ghost -ml-3 mb-4 !min-h-[44px]"
        >
          <ArrowLeft className="h-4 w-4" {...ICON} aria-hidden /> {c.threads.back}
        </button>
        {staffView && thread.author && <p className="mb-3 break-all text-footnote text-ink-2">{thread.author.email}</p>}
        {/* Хто говорить, видно перш ніж читати підпис: бік команди тримає
            лівий бурштиновий кант, свій — відступ і тло на крок вище. */}
        <ol className="space-y-3">
          {thread.messages.map((message) => (
            <li
              key={message.id}
              className={
                message.staff
                  ? 'card border-l-2 border-l-accent-line p-4 sm:mr-12'
                  : 'ml-6 rounded-card bg-raised p-4 sm:ml-12'
              }
            >
              <div className="flex items-baseline justify-between gap-3">
                <span className={`text-footnote font-semibold ${message.staff ? 'text-accent-text' : 'text-ink-2'}`}>
                  {message.staff ? c.threads.team : c.threads.you}
                </span>
                <span className="tnum shrink-0 text-caption text-ink-3">{fmtWhen(message.created_at, lang)}</span>
              </div>
              <p className="mt-2 whitespace-pre-wrap break-words text-callout text-ink">{message.body}</p>
            </li>
          ))}
        </ol>
        <form
          className="mt-6"
          onSubmit={(e) => {
            e.preventDefault();
            if (!busy && body.trim()) void reply();
          }}
        >
          <label htmlFor={bodyId} className={labelSkin}>
            {c.threads.replyLabel}
          </label>
          <textarea
            id={bodyId}
            value={body}
            onChange={(e) => setBody(e.target.value)}
            rows={4}
            placeholder={c.threads.replyHint}
            className={`${controlSkin} resize-y px-4 py-3`}
          />
          {failed && (
            <Notice tone="error" className="mt-2">
              {c.threads.failed}
            </Notice>
          )}
          <div className="mt-3 flex flex-wrap gap-3">
            <Btn type="submit" busy={busy} disabled={!body.trim()}>
              {busy ? c.threads.replying : c.threads.reply}
            </Btn>
            {staffView && (
              <Btn tone="secondary" onClick={close}>
                {c.staff.close}
              </Btn>
            )}
          </div>
        </form>
      </div>
    );
  }

  return (
    <div>
      <PanelHead title={staffView ? c.nav.queue : c.threads.title} lead={staffView ? undefined : c.threads.lead} />
      {!staffView && !composing && (
        <div className="mb-4">
          <Btn tone={list && list.length === 0 ? 'primary' : 'secondary'} onClick={() => setComposing(true)}>
            <Plus className="h-4 w-4" {...ICON} aria-hidden /> {c.threads.newTitle}
          </Btn>
        </div>
      )}
      {composing && (
        <Card className="mb-4">
          <form
            className="space-y-4"
            onSubmit={(e) => {
              e.preventDefault();
              if (!busy && subject.trim().length >= 2 && body.trim()) void start();
            }}
          >
            <SubHead>{c.threads.newTitle}</SubHead>
            <Field label={c.threads.subject} value={subject} onChange={setSubject} />
            <div>
              <label htmlFor={bodyId} className={labelSkin}>
                {c.threads.body}
              </label>
              <textarea
                id={bodyId}
                value={body}
                onChange={(e) => setBody(e.target.value)}
                rows={5}
                className={`${controlSkin} resize-y px-4 py-3`}
              />
            </div>
            {failed && <Notice tone="error">{c.threads.failed}</Notice>}
            <div className="flex flex-wrap gap-3">
              <Btn type="submit" busy={busy} disabled={subject.trim().length < 2 || !body.trim()}>
                {busy ? c.threads.sending : c.threads.send}
              </Btn>
              <Btn
                tone="ghost"
                onClick={() => {
                  setComposing(false);
                  setFailed(false);
                }}
              >
                {c.threads.cancel}
              </Btn>
            </div>
          </form>
        </Card>
      )}
      {list && list.length === 0 && !composing && (
        <Empty title={c.threads.empty} hint={staffView ? undefined : c.threads.emptyHint} />
      )}
      {list && list.length > 0 && (
        <ul className="card divide-y divide-line overflow-hidden">
          {list.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                onClick={() => openThread(item.id)}
                className="flex w-full flex-col gap-1.5 p-4 text-left transition-colors duration-quick hover:bg-raised sm:px-5"
              >
                {/* Тема — єдине, чим гілки відрізняються, тож вона не обрізається
                    заради позначки стану. */}
                <span className="flex items-start justify-between gap-3">
                  <span className="min-w-0 flex-1 break-words text-callout font-semibold text-ink">{item.subject}</span>
                  <Status tone={item.status === 'open' ? 'success' : 'neutral'}>
                    {item.status === 'open' ? c.threads.open : c.threads.closed}
                  </Status>
                </span>
                {item.preview && (
                  <span className="line-clamp-2 text-footnote text-ink-2">
                    <span className={item.last_staff ? 'font-semibold text-accent-text' : 'font-semibold'}>
                      {item.last_staff ? c.threads.team : c.threads.you}:{' '}
                    </span>
                    {item.preview}
                  </span>
                )}
                <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-caption text-ink-3">
                  {item.author && <span className="break-all">{item.author}</span>}
                  <span className="tnum">{fmtWhen(item.updated_at, lang)}</span>
                  {item.unanswered && <Tag>{c.staff.unanswered}</Tag>}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function Settings({ me, c, reload }: { me: Me; c: AccountCopy; reload: () => Promise<void> }) {
  const [name, setName] = useState(me.account.display_name);
  const [locale, setLocale] = useState(me.account.locale);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);
  const [failed, setFailed] = useState(false);
  const localeId = useId();

  const save = async () => {
    setBusy(true);
    setSaved(false);
    setFailed(false);
    try {
      await patch('/api/me', { display_name: name, locale });
      setSaved(true);
      await reload();
    } catch {
      setFailed(true);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <PanelHead title={c.settings.title} lead={c.settings.lead} />
      <Card>
        <form
          className="max-w-md space-y-5"
          onSubmit={(e) => {
            e.preventDefault();
            if (!busy) void save();
          }}
        >
          <div>
            <p className={labelSkin}>{c.settings.email}</p>
            <p className="break-all text-body text-ink">{me.account.email || '—'}</p>
            {me.account.pub_key && (
              <p className="mt-1.5 break-all font-mono text-footnote text-ink-3">{me.account.pub_key}</p>
            )}
            {me.account.email && <p className="mt-1.5 text-footnote text-ink-3">{c.settings.emailNote}</p>}
          </div>
          <Field
            label={c.settings.name}
            value={name}
            onChange={setName}
            placeholder={c.settings.namePlaceholder}
            autoComplete="nickname"
          />
          <div>
            <label htmlFor={localeId} className={labelSkin}>
              {c.settings.locale}
            </label>
            <select
              id={localeId}
              value={locale}
              onChange={(e) => setLocale(e.target.value)}
              className={`${controlSkin} h-12 px-4`}
            >
              <option value="uk">Українська</option>
              <option value="en">English</option>
            </select>
          </div>
          {failed && <Notice tone="error">{c.settings.failed}</Notice>}
          <div className="flex flex-wrap items-center gap-4">
            <Btn type="submit" busy={busy}>
              {busy ? c.settings.saving : c.settings.save}
            </Btn>
            {saved && <Notice tone="success">{c.settings.saved}</Notice>}
          </div>
        </form>
      </Card>
    </div>
  );
}

export function Security({
  c,
  lang,
  onLoggedOut,
}: {
  c: AccountCopy;
  lang: Lang;
  onLoggedOut: () => void;
}) {
  const [sessions, setSessions] = useState<SessionItem[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [revokingId, setRevokingId] = useState<string | null>(null);
  const [revokeFailed, setRevokeFailed] = useState(false);

  const loadSessions = useCallback(() => {
    api<SessionItem[]>('/api/me/sessions')
      .then(setSessions)
      .catch(() => setSessions([]));
  }, []);

  useEffect(() => {
    loadSessions();
  }, [loadSessions]);

  const revokeOne = async (session: SessionItem) => {
    setRevokingId(session.id);
    try {
      await revokeSession(session.id);
      if (session.current) {
        onLoggedOut();
        return;
      }
      loadSessions();
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        onLoggedOut();
        return;
      }
    } finally {
      setRevokingId(null);
    }
  };

  const revokeAll = async () => {
    setBusy(true);
    setRevokeFailed(false);
    let status: number | null = null;
    try {
      await post('/api/me/sessions/revoke');
    } catch (error) {
      status = statusOf(error);
    }
    setBusy(false);
    // 401 — очікуваний бік справи: ми щойно погасили і власну сесію.
    if (revokeAllSignsOut(status)) onLoggedOut();
    else setRevokeFailed(true);
  };

  return (
    <div>
      <PanelHead title={c.security.title} lead={c.security.lead} />
      <Card>
        <SubHead>{c.security.sessions}</SubHead>
        <ul className="mt-2 divide-y divide-line">
          {sessions?.map((session) => (
            <li
              key={session.id || session.created_at}
              className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 py-3"
            >
              <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
                <ShieldCheck className="h-5 w-5 shrink-0 text-ink-2" {...ICON} aria-hidden />
                {session.label && <span className="text-callout font-semibold text-ink">{session.label}</span>}
                <span className="text-callout text-ink">
                  {c.security.started} {fmtDate(session.created_at, lang)}
                </span>
                <span className="tnum text-footnote text-ink-3">{session.ip ?? '—'}</span>
                {session.via && <Tag>{session.via}</Tag>}
                {session.current && <Tag>{c.security.current}</Tag>}
              </div>
              {session.id && (
                <Btn
                  tone="ghost"
                  busy={revokingId === session.id}
                  onClick={() => void revokeOne(session)}
                >
                  {revokingId === session.id ? c.security.revokingOne : c.security.revokeOne}
                </Btn>
              )}
            </li>
          ))}
        </ul>
        <div className="mt-4 border-t border-line pt-5">
          <p className="mb-3 max-w-prose text-footnote text-ink-2">{c.security.revokeAllHint}</p>
          {revokeFailed && (
            <div className="mb-3">
              <Notice tone="error">{c.security.revokeFailed}</Notice>
            </div>
          )}
          <Btn tone="danger" onClick={revokeAll} busy={busy}>
            {busy ? c.security.revoking : c.security.revokeAll}
          </Btn>
        </div>
        <div className="mt-4 border-t border-line pt-4">
          <Link to="/delete-account" className="btn-ghost -ml-3 inline-flex min-h-[44px] items-center">
            {c.security.deleteAccount}
          </Link>
        </div>
      </Card>
    </div>
  );
}

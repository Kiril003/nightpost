import { useCallback, useEffect, useState } from 'react';
import { Activity, ArrowRight, Download, KeyRound, LifeBuoy, MonitorSmartphone, Receipt, RotateCcw, ShieldCheck, Wallet } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { cabinetCopy } from '../../i18n/cabinet';
import { pricingCopy } from '../../i18n/commerce';
import type { Lang } from '../../i18n/types';
import { LICENSE_SERVER } from '../../lib/meta';
import { api, post, type Device, type License, type Me, type Session } from '../../lib/account-api';
import type { PlanId } from '../../lib/plans';
import { Btn, Card, Empty, fmtDate, fmtWhen, Notice, PanelHead, Status, Tag } from './Panels';
import { PAID_PLANS, tabShown } from './tabs';

const ICON = { strokeWidth: 1.75 } as const;

/** Пристрій разом із ліцензією, на якій він сидить. */
interface Seat extends Device {
  license: License;
}

function seatsOf(me: Me): Seat[] {
  return me.licenses
    .filter((lic) => !lic.revoked)
    .flatMap((license) => license.devices.map((device) => ({ ...device, license })));
}

/** Найсвіжіша підписана звірка серед усіх пристроїв, або null — якщо жодної. */
function lastCheckIn(seats: Seat[]): string | null {
  const times = seats.map((s) => s.last_seen_at).filter((t): t is string => Boolean(t));
  return times.length ? times.sort()[times.length - 1] : null;
}

function tierName(lic: License, lang: Lang): string {
  return pricingCopy[lang].plans[lic.tier as PlanId]?.name ?? lic.tier;
}

// ── Стан сервісів ──────────────────────────────────────────────────────────

export type Probe =
  | { state: 'checking' }
  | { state: 'ok'; ms: number }
  | { state: 'down'; code: number }
  | { state: 'unreachable' }
  /** Маршруту немає (404): сервер цієї редакції не повідомляє стан. */
  | { state: 'notExposed' };

/** Живий запит із браузера: 404 — «не повідомляє», а не вигадане зелене. */
export async function probe(path: string, fetcher: typeof fetch = fetch): Promise<Probe> {
  const started = performance.now();
  try {
    const res = await fetcher(`${LICENSE_SERVER}${path}`, { cache: 'no-store' });
    if (res.status === 404) return { state: 'notExposed' };
    if (!res.ok) return { state: 'down', code: res.status };
    return { state: 'ok', ms: Math.max(1, Math.round(performance.now() - started)) };
  } catch {
    return { state: 'unreachable' };
  }
}

function ServiceStatus({ lang }: { lang: Lang }) {
  const t = cabinetCopy[lang].status;
  const [server, setServer] = useState<Probe>({ state: 'checking' });
  const [relay, setRelay] = useState<Probe>({ state: 'checking' });

  const check = useCallback(() => {
    setServer({ state: 'checking' });
    setRelay({ state: 'checking' });
    probe('/healthz').then(setServer);
    probe('/relay/v2/health').then(setRelay);
  }, []);

  useEffect(check, [check]);

  const row = (label: string, p: Probe) => {
    const tone: 'success' | 'danger' | 'neutral' =
      p.state === 'ok' ? 'success' : p.state === 'down' || p.state === 'unreachable' ? 'danger' : 'neutral';
    const text =
      p.state === 'ok'
        ? t.ok(p.ms)
        : p.state === 'down'
          ? t.down(p.code)
          : p.state === 'unreachable'
            ? t.unreachable
            : p.state === 'notExposed'
              ? t.notExposed
              : t.checking;
    return (
      <li className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-3">
        <span className="text-callout text-ink">{label}</span>
        <span className="tnum" data-probe={p.state}>
          <Status tone={tone}>{text}</Status>
        </span>
      </li>
    );
  };

  return (
    <Card className="mt-6">
      <h3 className="flex items-center gap-2 text-headline text-ink">
        <Activity className="h-4 w-4 shrink-0 text-ink-2" {...ICON} aria-hidden />
        {t.title}
      </h3>
      <p className="mt-1 text-footnote text-ink-2">{t.lead}</p>
      <ul className="mt-2 divide-y divide-line">
        {row(t.server, server)}
        {row(t.relay, relay)}
      </ul>
      <div className="mt-3">
        <Btn tone="secondary" onClick={check}>
          <RotateCcw className="h-4 w-4" {...ICON} aria-hidden /> {t.recheck}
        </Btn>
      </div>
    </Card>
  );
}

/**
 * Огляд — перша сторінка кабінету.
 *
 * Шість плиток, і кожна відповідає на одне питання з одного поля сервера:
 * чи є ліцензія, скільки місць зайнято, коли пристрій востаннє звірявся,
 * чи відповіла підтримка, скільки входів відкрито, яку збірку роздаємо.
 * Плитка веде туди, де з цим можна щось зробити. Нижче — одне речення
 * «що далі», вибране з тих самих даних.
 */
export function Overview({
  me,
  lang,
  onTab,
}: {
  me: Me;
  lang: Lang;
  onTab: (id: 'licenses' | 'devices' | 'threads' | 'security' | 'downloads' | 'purchases') => void;
}) {
  const t = cabinetCopy[lang].overview;
  const [sessions, setSessions] = useState<Session[] | null | 'failed'>(null);

  useEffect(() => {
    api<Session[]>('/api/me/sessions')
      .then(setSessions)
      .catch(() => setSessions('failed'));
  }, []);

  const live = me.licenses.filter((lic) => !lic.revoked);
  const seats = seatsOf(me);
  const maxSeats = live.reduce((sum, lic) => sum + lic.max_activations, 0);
  const seen = lastCheckIn(seats);
  const android = me.downloads.find((b) => b.platform === 'android') ?? me.downloads[0];

  const licenceValue = live.length
    ? live.map((lic) => tierName(lic, lang)).join(', ')
    : me.licenses.length
      ? t.licenceRevokedOnly
      : t.licenceNone;

  const tiles: { icon: LucideIcon; label: string; value: string; hint?: string; tab: Parameters<typeof onTab>[0] }[] = [
    {
      icon: KeyRound,
      label: t.licence,
      value: licenceValue,
      hint: live.length ? undefined : t.licenceNoneHint,
      tab: 'licenses',
    },
    {
      icon: MonitorSmartphone,
      label: t.devices,
      value: live.length ? t.devicesOf(seats.length, maxSeats) : t.devicesNone,
      hint: seats.length ? `${t.lastSeen}: ${seen ? fmtWhen(seen, lang) : t.lastSeenNever}` : undefined,
      tab: 'devices',
    },
    {
      icon: LifeBuoy,
      label: t.support,
      value: me.account.unread > 0 ? t.supportUnread(me.account.unread) : t.supportQuiet,
      tab: 'threads',
    },
    {
      icon: ShieldCheck,
      label: t.sessions,
      value:
        sessions === null ? '…' : sessions === 'failed' ? t.sessionsUnknown : t.sessionsCount(sessions.length),
      tab: 'security',
    },
    {
      icon: Download,
      label: t.build,
      value: android?.version ? t.buildReady(android.version) : android ? android.filename : t.buildNone,
      tab: 'downloads',
    },
    {
      icon: Receipt,
      label: cabinetCopy[lang].nav.purchases,
      value: me.licenses.length ? t.purchasesCount(me.licenses.length) : cabinetCopy[lang].purchases.empty,
      tab: 'purchases',
    },
  ];

  const next = !live.length ? t.nextLicence : seats.length === 0 ? t.nextDevice : t.nextFine;
  const hasLicence = me.licenses.length > 0;
  const shown = tiles.filter((tile) => tabShown(tile.tab)).filter(
    (tile) => hasLicence || (tile.tab !== 'licenses' && tile.tab !== 'purchases'),
  );

  return (
    <div>
      <PanelHead title={t.title} lead={t.lead} />
      <ul
        className={`grid gap-px overflow-hidden rounded-card border border-line bg-line ${
          shown.length > 3 ? 'sm:grid-cols-2 xl:grid-cols-3' : 'sm:grid-cols-3'
        }`}
      >
        {shown.map((tile) => {
          const Icon = tile.icon;
          return (
            <li key={tile.label} className="bg-surface dark:bg-raised">
              <button
                type="button"
                onClick={() => onTab(tile.tab)}
                className="group flex h-full min-h-[120px] w-full flex-col items-start gap-2 p-5 text-left transition-colors duration-quick hover:bg-raised dark:hover:bg-overlay"
              >
                <span className="flex w-full items-center gap-2 text-footnote text-ink-2">
                  <Icon className="h-4 w-4 shrink-0" {...ICON} aria-hidden />
                  {tile.label}
                  <ArrowRight
                    className="ml-auto h-4 w-4 text-ink-3 transition-transform duration-quick group-hover:translate-x-0.5"
                    {...ICON}
                    aria-hidden
                  />
                </span>
                <span className="text-headline text-ink">{tile.value}</span>
                {tile.hint && <span className="text-footnote text-ink-2">{tile.hint}</span>}
              </button>
            </li>
          );
        })}
      </ul>

      {PAID_PLANS && (
        <Card className="mt-6">
          <h3 className="text-headline text-ink">{t.nextTitle}</h3>
          <p className="mt-2 max-w-prose text-callout text-ink-2">{next}</p>
        </Card>
      )}

      <ServiceStatus lang={lang} />
    </div>
  );
}

/**
 * Усі пристрої з усіх чинних ліцензій — одним списком.
 *
 * «Останній звʼязок» — це час останньої УСПІШНОЇ підписаної звірки
 * (`activations.last_seen_at`), а не будь-якого запиту з адреси пристрою.
 * До першої звірки поля немає, і рядок каже «ще не звірявся».
 */
export function Devices({ me, lang, reload }: { me: Me; lang: Lang; reload: () => Promise<void> }) {
  const t = cabinetCopy[lang].devices;
  const [releasing, setReleasing] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const seats = seatsOf(me);
  const live = me.licenses.filter((lic) => !lic.revoked);
  const free = live.reduce((sum, lic) => sum + Math.max(0, lic.max_activations - lic.devices.length), 0);

  const release = async (seat: Seat) => {
    setReleasing(seat.fingerprint);
    setFailed(false);
    try {
      await post('/api/me/device/release', { license_id: seat.license.id, device_fingerprint: seat.fingerprint });
      await reload();
    } catch {
      setFailed(true);
    } finally {
      setReleasing(null);
    }
  };

  return (
    <div>
      <PanelHead title={t.title} lead={me.licenses.length > 0 ? t.lead : undefined} />
      {seats.length === 0 ? (
        <Empty
          title={t.empty}
          hint={me.licenses.length > 0 ? t.emptyHint : undefined}
          icon={<MonitorSmartphone className="h-6 w-6" {...ICON} />}
        />
      ) : (
        <Card className="!p-0">
          <ul className="divide-y divide-line">
            {seats.map((seat) => (
              <li
                key={`${seat.license.id}:${seat.fingerprint}`}
                className="flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6"
              >
                <div className="flex min-w-0 items-start gap-3">
                  <MonitorSmartphone className="mt-0.5 h-5 w-5 shrink-0 text-ink-2" {...ICON} aria-hidden />
                  <div className="min-w-0">
                    <p className="break-words text-headline text-ink">
                      {seat.name || `…${seat.fingerprint.slice(-8)}`}
                    </p>
                    <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-4 gap-y-0.5 text-footnote text-ink-2">
                      <dt className="text-ink-3">{t.lastSeen}</dt>
                      <dd className="tnum">
                        {seat.last_seen_at ? fmtWhen(seat.last_seen_at, lang) : t.never}
                      </dd>
                      <dt className="text-ink-3">{t.activated}</dt>
                      <dd className="tnum">{fmtDate(seat.activated_at, lang)}</dd>
                      <dt className="text-ink-3">{t.licence}</dt>
                      <dd>{tierName(seat.license, lang)}</dd>
                    </dl>
                  </div>
                </div>
                <Btn tone="secondary" busy={releasing === seat.fingerprint} onClick={() => release(seat)}>
                  {releasing === seat.fingerprint ? t.releasing : t.release}
                </Btn>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {failed && (
        <Notice tone="error" className="mt-4">
          {t.releaseFailed}
        </Notice>
      )}
      {live.length > 0 && free > 0 && <p className="mt-4 text-footnote text-ink-2">{t.seatsFree(free)}</p>}
      {seats.some((s) => !s.last_seen_at) && <p className="mt-2 max-w-prose text-footnote text-ink-3">{t.neverHint}</p>}
      {seats.length > 0 && <p className="mt-2 max-w-prose text-footnote text-ink-3">{t.staleHint}</p>}

      {me.licenses.length > 0 && (
        <Card className="mt-6">
          <h3 className="text-headline text-ink">{t.addTitle}</h3>
          <ol className="mt-3 grid gap-2.5">
            {t.addSteps.map((step, i) => (
              <li key={step} className="flex gap-3 text-callout text-ink-2">
                <span className="tnum inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-overlay text-footnote font-semibold text-ink">
                  {i + 1}
                </span>
                {step}
              </li>
            ))}
          </ol>
          <p className="mt-3 text-footnote text-ink-3">{t.playNote}</p>
        </Card>
      )}
    </div>
  );
}

/**
 * Історія покупок — з самих ліцензій: дата появи, рівень, джерело
 * (`provider`) і стан із причиною відкликання. Квитанцій у нас немає:
 * їх тримає той, хто приймав оплату, і сторінка так і каже.
 */
export function Purchases({ me, lang }: { me: Me; lang: Lang }) {
  if (me.licenses.length === 0) return null;
  const t = cabinetCopy[lang].purchases;
  const rows = [...me.licenses].sort((a, b) => b.created_at.localeCompare(a.created_at));
  const source = (lic: License) => (lic.provider ? (t.source[lic.provider] ?? lic.provider) : t.sourceUnknown);
  const reason = (lic: License) =>
    lic.revoked_reason ? (t.reason[lic.revoked_reason] ?? t.reasonOther) : t.reasonOther;

  return (
    <div>
      <PanelHead title={t.title} lead={t.lead} />
      {rows.length === 0 ? (
        <Empty title={t.empty} hint={t.emptyHint} icon={<Receipt className="h-6 w-6" {...ICON} />} />
      ) : (
        <div className="overflow-hidden rounded-card border border-line">
          <table className="w-full border-collapse text-left">
            <thead className="hidden bg-raised text-footnote text-ink-2 sm:table-header-group">
              <tr>
                <th scope="col" className="px-5 py-3 font-semibold">{t.columns.date}</th>
                <th scope="col" className="px-5 py-3 font-semibold">{t.columns.what}</th>
                <th scope="col" className="px-5 py-3 font-semibold">{t.columns.source}</th>
                <th scope="col" className="px-5 py-3 font-semibold">{t.columns.status}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line bg-surface dark:bg-raised">
              {rows.map((lic) => (
                <tr key={lic.id} className="grid grid-cols-2 gap-x-4 gap-y-1 p-5 sm:table-row sm:p-0">
                  <td className="tnum text-footnote text-ink-2 sm:px-5 sm:py-4 sm:text-callout">
                    {fmtDate(lic.created_at, lang)}
                  </td>
                  <td className="text-right text-headline text-ink sm:px-5 sm:py-4 sm:text-left">{tierName(lic, lang)}</td>
                  <td className="text-callout text-ink-2 sm:px-5 sm:py-4">{source(lic)}</td>
                  <td className="text-right sm:px-5 sm:py-4 sm:text-left">
                    <Status tone={lic.revoked ? 'danger' : 'success'}>{lic.revoked ? t.revoked : t.active}</Status>
                    {lic.revoked && <span className="mt-0.5 block text-footnote text-ink-2">{reason(lic)}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <p className="mt-4 max-w-prose text-footnote text-ink-2">{t.receipts}</p>

      <div className="mt-8 flex flex-col gap-3 rounded-card border border-dashed border-line-strong p-5 sm:flex-row sm:items-start sm:p-6">
        <Wallet className="h-5 w-5 shrink-0 text-ink-2" {...ICON} aria-hidden />
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-headline text-ink">{t.solanaTitle}</h3>
            <Tag>{t.solanaTag}</Tag>
          </div>
          <p className="mt-1.5 max-w-prose text-callout text-ink-2">{t.solanaBody}</p>
        </div>
      </div>
    </div>
  );
}

/**
 * «Що сервер про вас зберігає» — два переліки, звірені зі схемою бази
 * (server/tests/test_cabinet_stored_is_the_schema.py).
 */
export function Stored({ lang, me }: { lang: Lang; me?: Me }) {
  const t = cabinetCopy[lang].stored;
  const hasLicence = Boolean(me && me.licenses.length > 0);
  const filterItems = (items: string[]) =>
    hasLicence ? items : items.filter((item) => !/licen|ліценз|PHTM/i.test(item));
  const list = (title: string, items: string[]) => (
    <div>
      <h3 className="text-headline text-ink">{title}</h3>
      <ul className="mt-2 grid gap-2">
        {filterItems(items).map((item) => (
          <li key={item} className="flex gap-2.5 text-callout text-ink-2">
            <span className="mt-[0.6em] h-1.5 w-1.5 shrink-0 rounded-full bg-ink-3" aria-hidden />
            {item}
          </li>
        ))}
      </ul>
    </div>
  );
  return (
    <section className="mt-10">
      <PanelHead title={t.title} />
      <Card className="grid gap-6 md:grid-cols-2">
        {list(t.keptTitle, t.kept)}
        {list(t.notTitle, t.not)}
      </Card>
    </section>
  );
}

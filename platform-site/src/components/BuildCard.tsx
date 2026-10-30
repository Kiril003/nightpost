import { useState } from 'react';
import { AlertTriangle, ArrowRight, Check, CloudOff, Copy, Download, Info, Loader2, LogIn } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useLanguage } from '../i18n';
import { downloadCopy } from '../i18n/download';
import {
  BUILD_ANCHOR,
  buildAgeDays,
  buildFor,
  isDebugBuild,
  megabytes,
  packsFor,
  publishedOn,
  requestDownloadLink,
  useDownloads,
  type Build,
  type Downloads,
} from '../lib/downloads';

/**
 * Картка збірки, яку сервер справді роздає.
 *
 * Жодного числа з цього файла: версія, номер збірки, вага й сума приходять з
 * `/api/downloads` і стоять на екрані лише тоді, коли сервер їх назвав. Стани
 * поза «є файл» навмисно різні: «питаємо», «збірки немає», «лише після входу»
 * і «сервер не відповів» — це різні правди, і остання не дає права казати другу.
 *
 * `summary` — для героя: усе те саме, але дія веде на картку завантаження,
 * де перед кліком написано вагу, джерело й строк посилання.
 */
export default function BuildCard({
  platform = 'android',
  summary = false,
  id,
}: {
  platform?: string;
  summary?: boolean;
  id?: string;
}) {
  const { lang } = useLanguage();
  const c = downloadCopy[lang].build;
  const downloads = useDownloads();
  const build = buildFor(downloads, platform);

  if (downloads.state === 'asking') {
    return (
      <Shell id={id} title={c.title}>
        <div aria-hidden className="mt-5 space-y-3">
          <div className="h-7 w-2/3 rounded-chip bg-raised motion-safe:animate-pulse" />
          <div className="h-4 w-full rounded-chip bg-raised motion-safe:animate-pulse" />
          <div className="h-4 w-4/5 rounded-chip bg-raised motion-safe:animate-pulse" />
          <div className="mt-5 h-12 w-full rounded-control bg-raised motion-safe:animate-pulse" />
        </div>
        <p role="status" className="mt-4 text-footnote text-ink-2">
          {c.asking}
        </p>
      </Shell>
    );
  }

  if (downloads.state !== 'ready' || !build) {
    // Три різні правди, і жодна не сміє вдавати іншу: «сервер не відповів»,
    // «сервер каже, що збірки немає» і «збірка є, але лише після входу».
    const said =
      downloads.state === 'unreachable' ? c.unreachable : downloads.state === 'gated' ? c.gated : c.none;
    const Icon = downloads.state === 'unreachable' ? CloudOff : downloads.state === 'gated' ? LogIn : Info;
    return (
      <Shell id={id} title={c.title}>
        <p role="status" className="mt-5 flex items-start gap-3 text-body text-ink">
          <Icon className="mt-0.5 h-5 w-5 shrink-0 text-ink-2" strokeWidth={1.75} aria-hidden />
          <span>{said}</span>
        </p>
        {downloads.state === 'gated' && (
          <Link to="/account#downloads" className="btn-primary btn-lg mt-6 w-full">
            {c.gatedCta}
          </Link>
        )}
      </Shell>
    );
  }

  // Зелений значок «Можна завантажити» над попередженням «це не реліз» — два
  // голоси про одну річ, і гучніший з них неправильний. Тому для тестової
  // збірки значка немає: першим словом картки має бути попередження.
  return (
    <Shell id={id} title={c.title} ready={isDebugBuild(build) ? undefined : c.ready}>
      <div className="mt-4 min-w-0">
        <p className="text-title-2 text-ink">
          {c.platform[build.platform] ?? build.platform}
          {build.version && <span className="tnum ml-2 text-body text-ink-2">{build.version}</span>}
        </p>
        <p className="mt-1 break-all text-footnote text-ink-3">{build.filename}</p>
      </div>

      {isDebugBuild(build) && <DebugNote />}

      <dl className="mt-5 grid grid-cols-2 gap-x-6 gap-y-4 border-t border-line pt-5">
        <Fact label={c.labels.build} value={build.build ? String(build.build) : '—'} />
        <Fact label={c.labels.size} value={megabytes(build.size) ? `${megabytes(build.size)} ${c.mb}` : '—'} />
        <Published build={build} />
      </dl>

      {build.sha256 ? (
        <Digest value={build.sha256} label={`${c.labels.checksum} · ${c.labels.sha}`} copy={c.copy} copied={c.copied} />
      ) : (
        <p className="mt-5 border-t border-line pt-4 text-footnote text-ink-2">{c.noSha}</p>
      )}

      {summary ? (
        <Link to={BUILD_ANCHOR} className="btn-secondary btn-lg mt-6 w-full">
          {c.toDownload}
          <ArrowRight className="h-5 w-5 shrink-0" strokeWidth={1.75} aria-hidden />
        </Link>
      ) : (
        <Action build={build} />
      )}

      <OfflineRow downloads={downloads} />
    </Shell>
  );
}

/**
 * Смуга правди під героєм головної.
 *
 * Єдиний живий об'єкт на головній — версія, вага й сума збірки, яку сервер
 * роздає ЗАРАЗ. Смуга на всю ширину, щоб на телефоні він не падав нижче
 * згину. Жодного числа звідси: усі приходять з `/api/downloads`. Коли сервер
 * мовчить, смуга каже саме це — і не показує ані кнопки, ані вигаданої суми.
 */
export function BuildStrip({ platform = 'android' }: { platform?: string }) {
  const { lang } = useLanguage();
  const c = downloadCopy[lang].build;
  const downloads = useDownloads();
  const build = buildFor(downloads, platform);
  const ready = downloads.state === 'ready' && build;

  // На головній смуга живе лише тоді, коли серверу є що показати: версію,
  // вагу й суму. «Сервер не відповів» чи «збірки немає» — правда для сторінки
  // завантаження (там вона й лишається, повністю), а не перше, що людина
  // читає про продукт. Смуга стоїть нижче першого екрана, тож її поява
  // пізніше не зсуває видимий макет.
  if (!ready) return null;

  return (
    <section className="border-y border-line bg-surface px-4 py-8 sm:px-6 lg:px-14 md:py-10">
      <div className="mx-auto max-w-shell">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <p className="text-headline text-ink">{c.title}</p>
          {isDebugBuild(build) && (
            <span className="inline-flex items-center gap-2 text-subhead text-ink-2">
              <AlertTriangle className="h-4 w-4 shrink-0" strokeWidth={1.75} aria-hidden />
              {c.debug.label}
            </span>
          )}
          {!isDebugBuild(build) && <ReadyMark text={c.ready} />}
        </div>

        <div className="mt-6 grid gap-x-10 gap-y-6 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end">
          <div className="min-w-0">
            <dl className="flex flex-wrap gap-x-10 gap-y-5">
              <StripFact label={c.labels.build} value={build.version ?? String(build.build ?? '—')} />
              <StripFact
                label={c.labels.size}
                value={megabytes(build.size) ? `${megabytes(build.size)} ${c.mb}` : '—'}
              />
              <StripPublished build={build} />
            </dl>
            {build.sha256 && (
              <div className="mt-5 min-w-0">
                <p className="text-footnote text-ink-3">
                  {c.labels.checksum} · {c.labels.sha}
                </p>
                <p className="mt-1 break-all font-mono text-footnote text-ink-2">{build.sha256}</p>
              </div>
            )}
          </div>
          <Link to={BUILD_ANCHOR} className="btn-primary btn-lg max-lg:w-full">
            {c.toDownload}
            <ArrowRight className="h-5 w-5 shrink-0" strokeWidth={1.75} aria-hidden />
          </Link>
        </div>
      </div>
    </section>
  );
}

function ReadyMark({ text }: { text: string }) {
  return (
    <span className="inline-flex items-center gap-2 text-subhead text-success">
      <span className="h-2 w-2 shrink-0 rounded-full bg-success" aria-hidden />
      {text}
    </span>
  );
}

function StripFact({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-footnote text-ink-3">{label}</dt>
      <dd className="tnum mt-1 break-all text-title-2 text-ink">{value}</dd>
    </div>
  );
}

/** Дата стоїть у трьох станах, як і в картці: підставляти щось із голови не можна. */
function StripPublished({ build }: { build: Build }) {
  const { lang } = useLanguage();
  const c = downloadCopy[lang].build;
  const on = publishedOn(build.published_at, lang);
  const days = buildAgeDays(build.published_at);
  return (
    <div>
      <dt className="text-footnote text-ink-3">{c.labels.published}</dt>
      {on ? (
        <dd className="tnum mt-1 text-title-2 text-ink">
          {on}
          {days !== null && <span className="ml-2 text-footnote text-ink-2">{c.age(days)}</span>}
        </dd>
      ) : (
        <dd className="mt-1 max-w-xs text-footnote text-ink-2">{c.noPublished}</dd>
      )}
    </div>
  );
}

function Shell({
  id,
  title,
  ready,
  children,
}: {
  id?: string;
  title: string;
  ready?: string;
  children: React.ReactNode;
}) {
  return (
    <article id={id} aria-label={title} className="card scroll-mt-24 p-5 sm:p-6">
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <p className="text-subhead text-ink-2">{title}</p>
        {ready && <ReadyMark text={ready} />}
      </div>
      {children}
    </article>
  );
}

/**
 * Стоїть НАД фактами й кнопкою, бо це те, що міняє рішення, а не подробиця
 * після нього. Тонована підкладка, не червоне: файл робочий, просто не той,
 * що поїде людям.
 */
function DebugNote() {
  const { lang } = useLanguage();
  const c = downloadCopy[lang].build.debug;
  return (
    <div role="note" className="mt-5 rounded-control bg-accent-soft p-4">
      <p className="flex items-start gap-2.5 text-callout font-semibold text-ink">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-ink" strokeWidth={1.75} aria-hidden />
        {c.label}
      </p>
      <p className="mt-1.5 text-footnote text-ink-2">{c.text}</p>
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-footnote text-ink-3">{label}</dt>
      <dd className="tnum mt-1 text-body text-ink">{value}</dd>
    </div>
  );
}

/**
 * Скільки часу тому викладено те, на що дивиться відвідувач.
 *
 * Рядок стоїть у ТРЬОХ станах, а не в двох. Дати немає — так і написано:
 * сховати рядок означало б відповісти «збірки немає» на питання «яка вона
 * стара». Вік поруч із датою пропадає окремо: його рахує годинник читача.
 */
function Published({ build }: { build: Build }) {
  const { lang } = useLanguage();
  const c = downloadCopy[lang].build;
  const on = publishedOn(build.published_at, lang);
  const days = buildAgeDays(build.published_at);
  return (
    <div className="col-span-2">
      <dt className="text-footnote text-ink-3">{c.labels.published}</dt>
      {on ? (
        <dd className="tnum mt-1 text-body text-ink">
          {on}
          {days !== null && <span className="ml-2 text-footnote text-ink-2">{c.age(days)}</span>}
        </dd>
      ) : (
        <dd className="mt-1 text-footnote text-ink-2">{c.noPublished}</dd>
      )}
    </div>
  );
}

/**
 * Що людина зможе робити без мережі.
 *
 * Стан приходить ВІД СЕРВЕРА (`packsFor`), а не з тексту сайту й не з
 * маніфесту в дереві. Тому сторінка не має способу пообіцяти файл, якого
 * сервер не віддасть. Рядок — двері на сторінку пакетів, де видно, звідки
 * взялось число.
 */
function OfflineRow({ downloads }: { downloads: Downloads }) {
  const { lang } = useLanguage();
  const c = downloadCopy[lang].build.offline;
  const packs = packsFor(downloads);

  const smallest = packs.reduce<number | null>((min, p) => {
    const size = typeof p.size === 'number' ? p.size : null;
    if (size === null) return min;
    return min === null || size < min ? size : min;
  }, null);
  const mb = megabytes(smallest);

  // Кількість країн словами: «1 країна / 2 країни / 5 країн», з винятком на
  // 11–14 — той самий закон, що вже стоїть у `age`.
  const countries =
    lang === 'uk'
      ? `${packs.length} ${
          packs.length % 100 >= 11 && packs.length % 100 <= 14
            ? 'країн'
            : [null, 'країна', 'країни', 'країни', 'країни'][packs.length % 10] ?? 'країн'
        }`
      : `${packs.length} ${packs.length === 1 ? 'country' : 'countries'}`;

  return (
    <div className="mt-6 border-t border-line pt-4">
      <p className="text-callout font-semibold text-ink">{c.label}</p>
      <p className="mt-1 text-footnote text-ink-2">
        {packs.length && mb ? c.ready(countries, mb) : c.catalogued}
      </p>
    </div>
  );
}

function Digest({
  value,
  label,
  copy,
  copied,
}: {
  value: string;
  label: string;
  copy: string;
  copied: string;
}) {
  const [done, setDone] = useState(false);
  return (
    <div className="mt-5 border-t border-line pt-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-footnote text-ink-3">{label}</span>
        {/* 44px — це палець, а не смак: сума має 64 знаки, і промах означає
            виділяти її вручну на 390px. */}
        <button
          type="button"
          onClick={() => {
            void navigator.clipboard?.writeText(value);
            setDone(true);
            window.setTimeout(() => setDone(false), 1600);
          }}
          className="btn-ghost -mr-3 min-h-[44px]"
        >
          {done ? (
            <Check className="h-4 w-4 text-success" strokeWidth={1.75} aria-hidden />
          ) : (
            <Copy className="h-4 w-4" strokeWidth={1.75} aria-hidden />
          )}
          <span aria-live="polite">{done ? copied : copy}</span>
        </button>
      </div>
      <p className="mt-1 break-all font-mono text-footnote text-ink-2">{value}</p>
    </div>
  );
}

/** Сама дія. Вагу читають ДО кліку; що докачається потім — одразу під кнопкою. */
function Action({ build }: { build: Build }) {
  const { lang } = useLanguage();
  const c = downloadCopy[lang].build;
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const mb = megabytes(build.size);
  const android = build.platform === 'android';

  async function take() {
    setBusy(true);
    setFailed(false);
    try {
      // Присвоєння location, а не <a download>: файл лежить на чужому
      // походженні, і атрибут там мовчки ігнорується; ім'я й тип задає
      // Content-Disposition, який сервер уже підписав у посиланні.
      window.location.href = await requestDownloadLink(build.platform);
    } catch {
      setFailed(true);
      setBusy(false);
    }
  }

  return (
    <div className="mt-6">
      {android && mb && <p className="mb-3 text-footnote text-ink-2">{c.weight(mb)}</p>}
      <button type="button" onClick={take} disabled={busy} className="btn-primary btn-lg w-full">
        {busy ? (
          <Loader2 className="h-5 w-5 shrink-0 animate-spin" strokeWidth={1.75} aria-hidden />
        ) : (
          <Download className="h-5 w-5 shrink-0" strokeWidth={1.75} aria-hidden />
        )}
        {busy ? c.preparing : android ? c.action : c.actionFor(c.platform[build.platform] ?? build.platform)}
      </button>
      {failed && (
        <p role="alert" className="mt-3 flex items-start gap-2 text-callout text-danger">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" strokeWidth={1.75} aria-hidden />
          {c.failed}
        </p>
      )}
      {/* Найдорожчий рядок стоїть поруч із кнопкою: сотні мегабайтів
          приходять ПІСЛЯ встановлення. */}
      {android && <p className="mt-4 text-footnote text-ink-2">{c.models}</p>}
      <p className="mt-2 text-footnote text-ink-3">{c.linkTerms}</p>
    </div>
  );
}

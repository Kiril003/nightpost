/**
 * «Що я про тебе знаю» (Профіль › PHANTOM › Памʼять) — усе, що PHANTOM тримає про
 * людину, розкладене по розділах, з джерелом кожного рядка, і дві дії біля кожного:
 *  - «неправда» — запис хибний: зміст стирається й більше не запамʼятається;
 *  - «забудь» — стерти без сліду: скажеш знову — запамʼятаю наново.
 * Натиснуте зникає з памʼяті, а не лише з екрана: рядок прибирається тільки після
 * того, як вузол підтвердив. Угорі — чесний стан: скільки записів, чи є синк з ПК і
 * коли востаннє вдався. Без даних — чесний порожній стан, не заглушка.
 */
import React, { useEffect, useState } from 'react';
import {
  phantomSelfApi,
  type ForgetWhy,
  type KnownFact,
  type MemoryGroup,
  type MemoryState,
} from '../../services/phantomSelfApi';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import { translate, type MessageKey } from '../../i18n';
import type { Locale } from '../../i18n/locales';
import { dateTag } from '../../i18n/dateTag';

type T = Translation['t'];
const ukT: T = (key, vars) => translate(key, 'uk', vars);

export const when = (iso: string, t: T, locale: Locale): string => {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const today = new Date();
  if (d.toDateString() === today.toDateString()) return t('phantomKnows.today');
  return d.toLocaleDateString(dateTag(locale), { day: 'numeric', month: 'long' });
};

export const ago = (ms: number, t: T, locale: Locale): string => {
  const min = Math.round((Date.now() - ms) / 60000);
  if (min < 1) return t('phantomKnows.justNow');
  if (min < 60) return t('phantomKnows.minAgo', { min });
  const h = Math.round(min / 60);
  if (h < 24) return t('phantomKnows.hoursAgo', { h });
  return new Date(ms).toLocaleDateString(dateTag(locale), { day: 'numeric', month: 'long' });
};

const GROUPS: { id: MemoryGroup; title: MessageKey }[] = [
  { id: 'about', title: 'phantomKnows.groupAbout' },
  { id: 'people', title: 'phantomKnows.groupPeople' },
  { id: 'places', title: 'phantomKnows.groupPlaces' },
  { id: 'events', title: 'phantomKnows.groupEvents' },
  { id: 'skills', title: 'phantomKnows.groupSkills' },
  { id: 'pending', title: 'phantomKnows.groupPending' },
];

const groupOf = (f: KnownFact): MemoryGroup => f.group ?? (f.pending ? 'pending' : f.skill ? 'skills' : 'about');

/** Рядок стану: що памʼять є, скільки її, де вона живе і чи синхронізована. */
export const memoryStateLine = (s: MemoryState, t: T = ukT, locale: Locale = 'uk'): string => {
  const parts = [t('phantomKnows.items', { items: s.items })];
  if (s.pending > 0) parts.push(t('phantomKnows.pending', { pending: s.pending }));
  if (s.node === 'phone') {
    if (s.persistent === false) parts.push(t('phantomKnows.notPersistent'));
    if (!s.sync.paired) parts.push(t('phantomKnows.unpaired'));
    else if (s.sync.last_error) parts.push(t('phantomKnows.syncFailed', { last_error: s.sync.last_error }));
    else if (s.sync.last_ok_ms) parts.push(t('phantomKnows.syncedWithPc', { ago: ago(s.sync.last_ok_ms, t, locale) }));
    else parts.push(t('phantomKnows.neverSyncedPc'));
  } else if (s.sync.last_ok_ms) {
    parts.push(t('phantomKnows.pcPulled', { ago: ago(s.sync.last_ok_ms, t, locale) }));
  } else {
    parts.push(t('phantomKnows.phoneNeverSynced'));
  }
  return parts.join(' · ');
};

const download = (name: string, data: unknown) => {
  const blob = new Blob([JSON.stringify(data, null, 1)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
};

export const PhantomKnows: React.FC = () => {
  const [facts, setFacts] = useState<KnownFact[] | null>(null);
  const [state, setState] = useState<MemoryState | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [exportNote, setExportNote] = useState<string | null>(null);
  const { t, locale } = useTranslation();

  useEffect(() => {
    phantomSelfApi.facts().then(setFacts).catch(() => setFailed(true));
    // Старий вузол стану не віддає — тоді рядка стану просто немає.
    phantomSelfApi
      .memoryState()
      .then(setState)
      .catch(() => setState(null));
  }, []);

  const forget = async (id: string, why: ForgetWhy) => {
    setBusy(id);
    try {
      await phantomSelfApi.forget(id, why);
      setFacts((f) => (f ? f.filter((x) => x.id !== id) : f));
    } catch {
      setFailed(true);
    } finally {
      setBusy(null);
    }
  };

  const exportAll = async () => {
    setExportNote(null);
    try {
      const data = await phantomSelfApi.exportAll();
      download(`phantom-memory-${new Date().toISOString().slice(0, 10)}.json`, data);
      setExportNote(t('phantomKnows.exported', { count: data.count }));
    } catch {
      setExportNote(t('phantomKnows.exportFailed'));
    }
  };

  if (failed && facts === null) {
    return <p className="px-4 pb-4 text-callout text-danger">{t('phantomKnows.loadFailed')}</p>;
  }
  if (facts === null) return <div className="h-24" />;

  const header = state && (
    <p data-testid="phantom-knows-state" className="px-4 pb-3 text-caption text-ink-3">
      {memoryStateLine(state, t, locale)}
    </p>
  );

  if (facts.length === 0) {
    return (
      <div className="pb-6">
        {header}
        <p className="px-4 text-callout text-ink-2" data-testid="phantom-knows-empty">
          {t('phantomKnows.emptyState')}
        </p>
      </div>
    );
  }

  const fresh = facts.filter((f) => !f.old);
  const old = facts.filter((f) => f.old);
  const source = (f: KnownFact) =>
    [f.from_label, f.device ? (f.device === 'pc' ? t('phantomKnows.fromPc') : t('phantomKnows.fromPhone')) : '', when(f.at, t, locale)].filter(Boolean).join(' · ');

  const row = (f: KnownFact, quiet: boolean) => (
    <li key={f.id} className="px-4 py-3 flex items-start gap-2">
      <div className="flex-1 min-w-0">
        <p className={`text-callout break-words ${quiet ? 'text-ink-2' : 'text-ink'}`}>{f.text}</p>
        {!quiet && <p className="mt-0.5 text-caption text-ink-3">{source(f)}</p>}
      </div>
      <button
        type="button"
        disabled={busy === f.id}
        onClick={() => void forget(f.id, 'false')}
        className="shrink-0 h-11 px-3 rounded-control text-caption text-danger bg-raised active:opacity-90 disabled:opacity-50"
      >
        {t('phantomKnows.wrong')}
      </button>
      <button
        type="button"
        disabled={busy === f.id}
        onClick={() => void forget(f.id, 'forget')}
        className="shrink-0 h-11 px-3 rounded-control text-caption text-ink-2 bg-raised active:opacity-90 disabled:opacity-50"
      >
        {t('phantomKnows.forget')}
      </button>
    </li>
  );

  return (
    <div className="pb-2" data-testid="phantom-knows">
      {header}
      {GROUPS.map(({ id, title }) => {
        const rows = fresh.filter((f) => groupOf(f) === id);
        if (rows.length === 0) return null;
        return (
          <section key={id} data-testid={`phantom-knows-group-${id}`}>
            <h4 className="px-4 pt-3 pb-1 text-caption text-ink-3">
              {t(title)} · {rows.length}
            </h4>
            <ul>{rows.map((f) => row(f, false))}</ul>
          </section>
        );
      })}
      {old.length > 0 && (
        <section data-testid="phantom-knows-old">
          <p className="px-4 pt-4 pb-1 text-caption text-ink-3">{t('phantomKnows.oldNotes')}</p>
          <ul>{old.map((f) => row(f, true))}</ul>
        </section>
      )}
      {failed && <p className="px-4 pt-2 text-callout text-danger">{t('phantomKnows.failed')}</p>}
      <div className="px-4 pt-4 flex items-center gap-3">
        <button
          type="button"
          onClick={() => void exportAll()}
          className="h-11 px-4 rounded-control text-subhead text-accent-text bg-raised active:opacity-90"
        >
          {t('phantomKnows.export')}
        </button>
        {exportNote && <span className="text-caption text-ink-3">{exportNote}</span>}
      </div>
    </div>
  );
};

export default PhantomKnows;

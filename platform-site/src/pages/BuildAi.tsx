import { useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type DragEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Briefcase, CalendarCheck, Check, GraduationCap, ImagePlus, ShoppingBag, Table2, Truck, X } from 'lucide-react';
import '@fontsource-variable/inter-tight';
import '../styles/creator.css';
import CreatorPage from '../components/creator/CreatorPage';
import { EmailDoor, QrDoor } from '../components/account/SignedOut';
import { accountCopy } from '../i18n/account';
import { useLanguage } from '../i18n';
import { readToken, writeToken } from '../lib/account-api';
import { aiDraft, createVenue, saveDraft, uploadVenueImage, VenueRefusal, type V2Page, type Venue } from '../lib/venuesApi';
import { builderChrome, builderTheme } from '../lib/creator/chrome';
import { isLook } from '../lib/creator/looks';
import type { EditPage } from '../lib/creator/editor';
import {
  AI_KINDS, askBody, assistantNote, checklist, draftName, finalPage, initialPlacement, photoSlots, place, setName, setPrice, tray, unpriced,
  type AiKind, type AiResult, type Placed,
} from '../lib/creator/aiDraft';

const TEXT_MAX = 4000;
const TABLE_MAX = 512 * 1024;
const PHOTOS_MAX = 60;
const KIND_ICON = { book: CalendarCheck, shop: ShoppingBag, classes: GraduationCap, hiring: Briefcase, delivery: Truck } as const;

const COPY = {
  en: {
    title: 'New page', ask: 'Describe your business', placeholder: 'What you do, where, your hours, who is on the team, prices',
    chars: (n: number) => `${n} ch.`, materials: 'Materials', photos: (n: number) => `${n} ${n === 1 ? 'photo' : 'photos'}`, oneTable: '1 table',
    addPhotos: 'Add photos', addTable: 'Add price list', remove: 'Remove', kind: 'Kind of business',
    kinds: { book: 'Bookings', shop: 'Shop', classes: 'Classes', hiring: 'Hiring', delivery: 'Delivery' } as Record<AiKind, string>,
    create: 'Create page', creating: 'Making the draft…', signIn: 'Sign in to create', back: 'Back', close: 'Close',
    network: 'No connection. Try again.', tableBig: 'The price list is over 512 KB.', tableType: 'Use a CSV or XLSX file.',
    ready: 'Draft ready', status: 'Draft · not published', namePh: 'Page name', nameNeeded: 'Give the page a name',
    have: 'What the draft has', done: (a: number, b: number) => `${a} of ${b} ready`, set: 'Set',
    prices: 'Prices', pricePh: (token: string) => `price in ${token}`,
    photoHead: 'Photos', onBlocks: (a: number, b: number) => `${a} of ${b} on the page`, offPage: 'Not on the page', place: 'Place here',
    preview: 'Page preview', edit: 'Edit', publish: 'Publish', moving: 'Moving to the editor…',
  },
  uk: {
    title: 'Нова сторінка', ask: 'Опишіть свою справу', placeholder: 'Що робите, де, коли працюєте, хто в команді, ціни',
    chars: (n: number) => `${n} зн.`, materials: 'Матеріали', photos: (n: number) => `${n} фото`, oneTable: '1 таблиця',
    addPhotos: 'Додати фото', addTable: 'Додати прайс', remove: 'Прибрати', kind: 'Тип діяльності',
    kinds: { book: 'Запис', shop: 'Магазин', classes: 'Заняття', hiring: 'Найм', delivery: 'Доставка' } as Record<AiKind, string>,
    create: 'Створити сторінку', creating: 'Складаю чернетку…', signIn: 'Увійдіть, щоб створити', back: 'Назад', close: 'Закрити',
    network: 'Немає звʼязку. Спробуйте ще раз.', tableBig: 'Прайс більший за 512 КБ.', tableType: 'Підійде файл CSV або XLSX.',
    ready: 'Чернетка готова', status: 'Чернетка · ще не опубліковано', namePh: 'Назва сторінки', nameNeeded: 'Дайте сторінці назву',
    have: 'Що вже є', done: (a: number, b: number) => `${a} з ${b} готово`, set: 'Вказати',
    prices: 'Ціни', pricePh: (token: string) => `ціна в ${token}`,
    photoHead: 'Фото', onBlocks: (a: number, b: number) => `${a} з ${b} на сторінці`, offPage: 'Не на сторінці', place: 'Покласти сюди',
    preview: 'Попередній перегляд', edit: 'Редагувати', publish: 'Опублікувати', moving: 'Переношу в редактор…',
  },
};

type Ask = { k: 'idle' } | { k: 'busy' } | { k: 'signIn' } | { k: 'error'; text: string };

function Preview({ page, theme, lang }: { page: EditPage; theme: 'day' | 'night'; lang: 'uk' | 'en' }) {
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(0);
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setW(el.clientWidth));
    ro.observe(el);
    setW(el.clientWidth);
    return () => ro.disconnect();
  }, []);
  const inner = w >= 560 ? 1280 : 390;
  const scale = w ? w / inner : 0;
  const look = isLook(page.look) ? page.look : 'studio';
  return (
    <div ref={box} className="ai-preview" data-look={look}>
      {scale > 0 && (
        <div style={{ width: inner, transform: `scale(${scale})`, transformOrigin: '0 0' }} aria-hidden="true" {...{ inert: '' }}>
          <CreatorPage page={page} look={look} theme={theme} lang={lang} frame="preview" />
        </div>
      )}
    </div>
  );
}

function measure(file: File): Promise<{ w?: number; h?: number }> {
  return createImageBitmap(file).then((b) => ({ w: b.width, h: b.height })).catch(() => ({}));
}

export default function BuildAi() {
  const { lang } = useLanguage();
  const t = COPY[lang];
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const theme = builderTheme(params.get('theme'));
  const themeQ = params.get('theme') ? `theme=${params.get('theme')}` : '';
  const [text, setText] = useState('');
  const [kind, setKind] = useState<AiKind>('book');
  const [photos, setPhotos] = useState<File[]>([]);
  const [table, setTable] = useState<File | null>(null);
  const [ask, setAsk] = useState<Ask>({ k: 'idle' });
  const [email, setEmail] = useState(false);
  const [result, setResult] = useState<AiResult | null>(null);
  const [page, setPage] = useState<EditPage | null>(null);
  const [placed, setPlaced] = useState<Placed>({});
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [held, setHeld] = useState<string | null>(null);
  const [accept, setAccept] = useState<Ask>({ k: 'idle' });
  const made = useRef<Venue | null>(null);
  const photoInput = useRef<HTMLInputElement>(null);
  const tableInput = useRef<HTMLInputElement>(null);
  const nameInput = useRef<HTMLInputElement>(null);

  const urls = useMemo(() => Object.fromEntries(photos.map((f) => [f.name, URL.createObjectURL(f)])), [photos]);
  useEffect(() => () => Object.values(urls).forEach((u) => URL.revokeObjectURL(u)), [urls]);
  useEffect(() => {
    document.title = page ? t.ready : t.title;
  }, [page, t]);

  function addPhotos(list: FileList | null) {
    if (!list) return;
    const have = new Set(photos.map((f) => f.name));
    const next = [...photos, ...[...list].filter((f) => f.type.startsWith('image/') && !have.has(f.name) && have.add(f.name))];
    setPhotos(next.slice(0, PHOTOS_MAX));
  }

  function pickTable(f: File | undefined) {
    if (!f) return;
    if (!/\.(csv|tsv|txt|xlsx)$/i.test(f.name)) return setAsk({ k: 'error', text: t.tableType });
    if (f.size > TABLE_MAX) return setAsk({ k: 'error', text: t.tableBig });
    setAsk({ k: 'idle' });
    setTable(f);
  }

  async function create() {
    if (ask.k === 'busy') return;
    if (!readToken()) return setAsk({ k: 'signIn' });
    setAsk({ k: 'busy' });
    try {
      const bytes = table ? new Uint8Array(await table.arrayBuffer()) : null;
      const r = await aiDraft(askBody({ kind, lang, text, table: table && bytes ? { name: table.name, bytes } : null, photos: photos.map((f) => f.name) }));
      setResult(r);
      setPage(r.page);
      setPlaced(initialPlacement(r.page, photos.map((f) => f.name)));
      setTyped({});
      setAsk({ k: 'idle' });
      window.scrollTo(0, 0);
    } catch (e) {
      if (e instanceof VenueRefusal && e.status === 401) return setAsk({ k: 'signIn' });
      if (e instanceof VenueRefusal && (e.messageUk || e.messageEn)) return setAsk({ k: 'error', text: (lang === 'uk' ? e.messageUk : e.messageEn) ?? t.network });
      setAsk({ k: 'error', text: t.network });
    }
  }

  async function finish(intent: 'edit' | 'publish') {
    if (!page || accept.k === 'busy') return;
    const name = draftName(page);
    if (!name) {
      nameInput.current?.focus();
      return setAccept({ k: 'error', text: t.nameNeeded });
    }
    setAccept({ k: 'busy' });
    try {
      const venue = made.current ?? (await createVenue(name));
      made.current = venue;
      const uploaded: Record<string, { blob: string; w?: number; h?: number }> = {};
      for (const f of photos) {
        if (!placed[f.name]) continue;
        try {
          const [{ blob }, size] = await Promise.all([uploadVenueImage(venue.id, f), measure(f)]);
          uploaded[f.name] = { blob, ...size };
        } catch {
          /* the slot stays empty; the editor shows it as a picture still to add */
        }
      }
      await saveDraft(venue.id, finalPage(page, placed, uploaded) as V2Page, 0);
      const q = [intent === 'publish' ? 'publish=1' : '', themeQ].filter(Boolean).join('&');
      navigate(`/build/edit/${encodeURIComponent(venue.id)}${q ? `?${q}` : ''}`, { replace: true });
    } catch (e) {
      if (e instanceof VenueRefusal && (e.messageUk || e.messageEn)) return setAccept({ k: 'error', text: (lang === 'uk' ? e.messageUk : e.messageEn) ?? t.network });
      setAccept({ k: 'error', text: t.network });
    }
  }

  const chrome = builderChrome(theme) as CSSProperties;

  if (page && result) {
    const c = checklist(page, result, placed, lang);
    const asked = unpriced(result.page);
    const note = assistantNote(result, lang);
    const slots = photoSlots(page);
    const loose = tray(placed);
    const given = Object.keys(placed).length;
    const onSlot = Object.fromEntries(Object.entries(placed).filter(([, s]) => s).map(([p, s]) => [s as string, p]));
    const drop = (slot: string) => (e: DragEvent) => {
      e.preventDefault();
      const p = e.dataTransfer.getData('text/plain');
      if (p in placed) setPlaced(place(placed, p, slot));
    };
    return (
      <div className="cx ai" data-look="shop" data-theme={theme} style={chrome}>
        <div className="ai-wrap">
          <header className="ai-top">
            <button type="button" className="cx-btn ghost icon m bs-icon" aria-label={t.back} onClick={() => setPage(null)}><X size={20} aria-hidden="true" /></button>
            <h1 className="ai-title">{t.ready}</h1>
            <span className="bs-icon-space" />
          </header>
          <main className="ai-two">
            <div className="ai-side">
              <input
                ref={nameInput}
                className="ai-name"
                value={String(page.blocks.find((b) => b.kind === 'id.name')?.data.name ?? '')}
                placeholder={t.namePh}
                aria-label={t.namePh}
                maxLength={80}
                onChange={(e) => { setPage(setName(page, e.target.value)); if (accept.k === 'error') setAccept({ k: 'idle' }); }}
              />
              <p className="cx-muted ai-status">{t.status}</p>
              {note && <p className="ai-note" role="status">{note}</p>}

              <section className="ai-card" aria-label={t.have}>
                <div className="ai-card-head"><span>{t.have}</span><span>{t.done(c.done, c.total)}</span></div>
                <ul className="ai-checks">
                  {c.rows.map((r) => (
                    <li key={r.key} className={r.done ? 'on' : ''}>
                      <span className="ai-tick" aria-hidden="true">{r.done && <Check size={14} />}</span>
                      <span className="ai-check-text">{r.label} — {r.value}</span>
                      {r.key === 'place' && <button type="button" className="ai-set" onClick={() => void finish('edit')}>{t.set}</button>}
                    </li>
                  ))}
                </ul>
              </section>

              {asked.length > 0 && (
                <section className="ai-sec" aria-label={t.prices}>
                  <div className="ai-sec-head"><span>{t.prices}</span></div>
                  <ul className="ai-prices">
                    {asked.map((o) => {
                      const key = `${o.block}/${o.item}`;
                      return (
                        <li key={key}>
                          <span className="ai-price-name">{o.name}</span>
                          <input
                            className="ai-price"
                            inputMode="decimal"
                            value={typed[key] ?? ''}
                            placeholder={t.pricePh(o.token)}
                            aria-label={`${o.name}: ${t.pricePh(o.token)}`}
                            onChange={(e) => { setTyped({ ...typed, [key]: e.target.value }); setPage(setPrice(page, o.block, o.item, e.target.value)); }}
                          />
                          <span className="ai-was">{o.was}</span>
                        </li>
                      );
                    })}
                  </ul>
                </section>
              )}

              {given > 0 && (
                <section className="ai-sec" aria-label={t.photoHead}>
                  <div className="ai-sec-head"><span>{t.photoHead}</span><span>{t.onBlocks(given - loose.length, given)}</span></div>
                  <ul className="ai-slots">
                    {slots.map((s) => {
                      const p = onSlot[s.key];
                      return (
                        <li key={s.key} onDragOver={(e) => e.preventDefault()} onDrop={drop(s.key)}>
                          {p ? (
                            <span className="ai-slot full">
                              <img src={urls[p]} alt="" draggable onDragStart={(e) => e.dataTransfer.setData('text/plain', p)} />
                              <button type="button" className="ai-x" aria-label={`${t.remove}: ${p}`} onClick={() => setPlaced(place(placed, p, null))}><X size={12} aria-hidden="true" /></button>
                            </span>
                          ) : (
                            <button type="button" className={`ai-slot ${held ? 'ready' : ''}`} aria-label={`${t.place}: ${s.name}`} disabled={!held} onClick={() => { if (held) { setPlaced(place(placed, held, s.key)); setHeld(null); } }}>
                              <ImagePlus size={16} aria-hidden="true" />
                            </button>
                          )}
                          <span className="ai-slot-name">{s.name || '—'}</span>
                        </li>
                      );
                    })}
                  </ul>
                  {loose.length > 0 && (
                    <div className="ai-tray" onDragOver={(e) => e.preventDefault()} onDrop={(e) => { e.preventDefault(); const p = e.dataTransfer.getData('text/plain'); if (p in placed) setPlaced(place(placed, p, null)); }}>
                      <span className="ai-tray-head">{t.offPage}</span>
                      <div className="ai-thumbs">
                        {loose.map((p) => (
                          <button type="button" key={p} className={`ai-thumb ${held === p ? 'on' : ''}`} aria-pressed={held === p} aria-label={p} draggable onDragStart={(e) => e.dataTransfer.setData('text/plain', p)} onClick={() => setHeld(held === p ? null : p)}>
                            <img src={urls[p]} alt="" />
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                </section>
              )}
            </div>
            <section className="ai-look" aria-label={t.preview}>
              <span className="ai-label">{t.preview}</span>
              <Preview page={finalPage(page, {}, {})} theme={theme} lang={lang} />
            </section>
          </main>
          <div className="ai-bar">
            {accept.k === 'error' && <p className="ai-err" role="alert">{accept.text}</p>}
            <div className="ai-bar-row">
              <button type="button" className="cx-btn l ai-tonal" disabled={accept.k === 'busy'} onClick={() => void finish('edit')}>{accept.k === 'busy' ? t.moving : t.edit}</button>
              <button type="button" className="cx-btn primary l" disabled={accept.k === 'busy'} onClick={() => void finish('publish')}>{t.publish}</button>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const d = accountCopy[lang].doors;
  const signedIn = async (token?: string) => {
    if (token) writeToken(token);
    setAsk({ k: 'idle' });
    void create();
  };
  return (
    <div className="cx ai" data-look="shop" data-theme={theme} style={chrome}>
      <div className="ai-wrap ai-narrow">
        <header className="ai-top">
          <Link to={`/build/start${themeQ ? `?${themeQ}` : ''}`} className="cx-btn ghost icon m bs-icon" aria-label={t.back}><ArrowLeft size={20} aria-hidden="true" /></Link>
          <h1 className="ai-title">{t.title}</h1>
          <span className="bs-icon-space" />
        </header>
        <main className="ai-main">
          <label className="ai-h2" htmlFor="ai-text">{t.ask}</label>
          <div className="ai-box">
            <textarea id="ai-text" value={text} maxLength={TEXT_MAX} rows={5} placeholder={t.placeholder} onChange={(e) => setText(e.target.value)} />
            <span className="ai-count">{t.chars(text.length)}</span>
          </div>

          <div className="ai-sec-head"><span>{t.materials}</span><span>{[photos.length ? t.photos(photos.length) : '', table ? t.oneTable : ''].filter(Boolean).join(', ')}</span></div>
          {photos.length > 0 && (
            <div className="ai-thumbs">
              {photos.slice(0, 7).map((f) => (
                <span key={f.name} className="ai-thumb">
                  <img src={urls[f.name]} alt="" />
                  <button type="button" className="ai-x" aria-label={`${t.remove}: ${f.name}`} onClick={() => setPhotos(photos.filter((x) => x !== f))}><X size={12} aria-hidden="true" /></button>
                </span>
              ))}
              {photos.length > 7 && <span className="ai-thumb more">+{photos.length - 7}</span>}
            </div>
          )}
          {table && (
            <span className="ai-file">
              <Table2 size={18} aria-hidden="true" />
              <span>{table.name}</span>
              <button type="button" className="ai-x static" aria-label={`${t.remove}: ${table.name}`} onClick={() => setTable(null)}><X size={14} aria-hidden="true" /></button>
            </span>
          )}
          <div className="ai-adds">
            <button type="button" className="ai-chip" onClick={() => photoInput.current?.click()}><ImagePlus size={18} aria-hidden="true" />{t.addPhotos}</button>
            <button type="button" className="ai-chip" onClick={() => tableInput.current?.click()}><Table2 size={18} aria-hidden="true" />{t.addTable}</button>
            <input ref={photoInput} type="file" accept="image/*" multiple hidden onChange={(e) => { addPhotos(e.target.files); e.target.value = ''; }} />
            <input ref={tableInput} type="file" accept=".csv,.tsv,.txt,.xlsx" hidden onChange={(e) => { pickTable(e.target.files?.[0]); e.target.value = ''; }} />
          </div>

          <div className="ai-sec-head"><span>{t.kind}</span></div>
          <div className="ai-kinds" role="radiogroup" aria-label={t.kind}>
            {AI_KINDS.map((k) => {
              const Icon = KIND_ICON[k];
              return (
                <button type="button" role="radio" aria-checked={kind === k} key={k} className={`ai-chip ${kind === k ? 'on' : ''}`} onClick={() => setKind(k)}>
                  {kind === k && <Icon size={18} aria-hidden="true" />}{t.kinds[k]}
                </button>
              );
            })}
          </div>

          {ask.k === 'signIn' && (
            <section className="ai-card ai-door" aria-label={t.signIn}>
              <h2 className="ai-h2">{t.signIn}</h2>
              {email ? (
                <EmailDoor onDone={signedIn} onSwitchToQr={() => setEmail(false)} useQrLabel={d.qr.useQr} />
              ) : (
                <QrDoor onDone={signedIn} onSwitchToEmail={() => setEmail(true)} emailLabel={d.emailDoor} labels={d.qr} />
              )}
            </section>
          )}
        </main>
        <div className="ai-bar">
          {ask.k === 'error' && <p className="ai-err" role="alert">{ask.text}</p>}
          <button type="button" className="cx-btn primary l wide" disabled={ask.k === 'busy' || (!text.trim() && !table)} onClick={() => void create()}>
            {ask.k === 'busy' ? t.creating : t.create}
          </button>
        </div>
      </div>
    </div>
  );
}

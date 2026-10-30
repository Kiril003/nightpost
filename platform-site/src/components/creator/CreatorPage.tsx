import { createContext, useContext, useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { Ban, Check, Clock, Image as ImageIcon, MapPin, MessageSquare, Minus, Plus, Share, ShoppingBag, Star, User, Wallet, X } from 'lucide-react';
import { arrange, bookMode, initials, nameOf, onSite, toneOf, type Block, type Live, type Page, type Section } from '../../lib/creator/arrange.ts';
import { LOOK, lookVars, type Look, type Theme } from '../../lib/creator/looks.ts';
import {
  clock, creatorCopy, dayMonth, dayNum, dayRange, divide, duration, focusPosition, money, nowStamp, shortDate, stamp, stampMs, weekday, when,
  type CreatorCopy, type Lang,
} from '../../lib/creator/format.ts';
import { imageUrl } from '../../lib/creator/samples.ts';
import { encodeQrMatrix, encodeQrSvgPath } from '../../lib/qr.ts';
import { readToken } from '../../lib/account-api.ts';
import { followVenue, getFollow, sendPageRequest, stockLeft, takenTimes, VenueRefusal, type Follow, type PageRequest } from '../../lib/venuesApi.ts';
import { askWindow, slotDays, type Taken } from '../../lib/creator/slots.ts';
import { depositDue, pageStaff } from '../../lib/creator/booking.ts';
import { addTo, cartCount, fulfilOptions, leftOf, orderBody, orderSum, takeFrom, type Cart, type Product, type Zone } from '../../lib/creator/cart.ts';
import { MORE_RENDERERS } from './BlocksMore';
import '../../styles/creator.css';

type Ctx = {
  look: Look;
  lang: Lang;
  c: CreatorCopy;
  page: Page;
  live: Live;
  sample: boolean;
  name: string;
  byId: Map<string, Block>;
  token: string;
  test: boolean;
  primary: 'hero' | 'booking';
  act: () => void;
  ask: (a: Ask) => void;
  sent: number;
  venueId?: string;
  preview: boolean;
  cart: Basket;
  add: (block: Block, item: Product, left: number) => void;
  take: (block: Block, id: string) => void;
};

type Basket = { block: string; items: Cart; left: Record<string, number> } | null;


type Ask = {
  block: Block;
  service?: { id: string; name: string };
  at?: { at: number; local: string; date: string; t: string };
  preferred?: { min: string; max: string };
  staff?: { id: string; name: string };
  deposit?: { amount: string; token: string } | null;
  course?: { title: string; next: { date: string; time: string }; price: string | null; token: string };
  text?: string;
};

type ImgVal = { blob: string; alt: string; focus?: { x: number; y: number } };

const CxCtx = createContext<Ctx | null>(null);
export const useCx = () => useContext(CxCtx)!;

function pageToken(page: Page): string {
  for (const b of page.blocks) {
    const t = b.data?.token ?? b.data?.price?.token;
    if (typeof t === 'string') return t;
  }
  return 'USDC';
}

export function Img({ image, className, tone, eager, empty }: { image?: ImgVal; className?: string; tone?: number; eager?: boolean; empty?: string | true }) {
  const src = image ? imageUrl(image.blob) : null;
  if (!src && empty) {
    return (
      <div className={`cx-img cx-img-none cx-img-empty ${className ?? ''}`} aria-hidden="true">
        <ImageIcon size={20} strokeWidth={1.5} />
        {typeof empty === 'string' && <span>{empty}</span>}
      </div>
    );
  }
  if (!src) return <div className={`cx-img cx-img-none t${tone ?? 4} ${className ?? ''}`} aria-hidden="true" />;
  const pos = focusPosition(image!.focus);
  return (
    <img
      className={`cx-img ${className ?? ''}`}
      style={pos ? { objectPosition: pos } : undefined}
      src={src}
      alt={image!.alt}
      loading={eager ? 'eager' : 'lazy'}
      decoding="async"
    />
  );
}

export function Av({ name, size = '' }: { name: string; size?: string }) {
  return <span className={`cx-av a${toneOf(name)} ${size}`}>{initials(name)}</span>;
}

export function Price({ amount, token }: { amount: string; token: string }) {
  const lang = useContext(CxCtx)?.lang ?? 'en';
  return (
    <span className="cx-price">
      {money(lang, amount)} <span className="cx-tok">{token}</span>
    </span>
  );
}

export function Btn({ kind, children, size = '', wide, onClick, href }: {
  kind: 'primary' | 'ink' | 'ghost'; children: ReactNode; size?: string; wide?: boolean; onClick?: () => void; href?: string;
}) {
  const cls = `cx-btn ${kind} ${size} ${wide ? 'wide' : ''}`;
  if (href) return <a className={cls} href={href}>{children}</a>;
  return <button type="button" className={cls} onClick={onClick}>{children}</button>;
}

export function SecHead({ eyebrow, title, right }: { eyebrow?: string; title?: string; right?: ReactNode }) {
  if (!eyebrow && !title && !right) return null;
  return (
    <div className="cx-sec-h">
      <div>
        {eyebrow && <span className="cx-lbl">{eyebrow}</span>}
        {title && <h2 className="cx-h2">{title}</h2>}
      </div>
      {right}
    </div>
  );
}

function FollowBtn({ id }: { id: string }) {
  const { c } = useCx();
  const [signedIn, setSignedIn] = useState(false);
  const [state, setState] = useState<Follow | null>(null);
  const busy = useRef(false);
  useEffect(() => {
    const on = Boolean(readToken());
    setSignedIn(on);
    if (!on) return;
    let alive = true;
    getFollow(id).then((f) => alive && setState(f)).catch(() => {});
    return () => {
      alive = false;
    };
  }, [id]);
  if (!signedIn) {
    return (
      <Btn kind="ghost" size="m" href="/account">
        <Plus size={16} aria-hidden="true" />
        {c.follow}
      </Btn>
    );
  }
  const following = Boolean(state?.following);
  const toggle = () => {
    if (!state || busy.current) return;
    busy.current = true;
    const before = state;
    setState({ following: !following, followers: Math.max(0, (state?.followers ?? 0) + (following ? -1 : 1)) });
    followVenue(id, !following)
      .then(setState)
      .catch((e: { status?: number }) => {
        setState(before);
        if (e?.status === 401) window.location.assign('/account');
      })
      .finally(() => {
        busy.current = false;
      });
  };
  return (
    <button type="button" className={`cx-btn ghost m cx-follow ${following ? 'on' : ''}`} aria-pressed={following} aria-disabled={!state} onClick={toggle}>
      {following ? <Check size={16} aria-hidden="true" /> : <Plus size={16} aria-hidden="true" />}
      {following ? c.following : c.follow}
    </button>
  );
}

function Header() {
  const { page, name, c, byId, venueId } = useCx();
  const { tagline } = nameOf(page);
  const nav = page.blocks.filter(
    (b) => !b.hidden && onSite(b) && ['cat.products', 'cat.services', 'evt.single', 'org.members'].includes(b.kind) && b.data.title,
  );
  const msg = page.blocks.find((b) => b.kind === 'loc.message' && b.data.on && !b.hidden && onSite(b));
  return (
    <header className="cx-top">
      <div className="cx-row g12">
        <Av name={name} size="m" />
        <div className="cx-col">
          <b className="cx-name">{name}</b>
          {tagline && <span className="cx-faint cx-small">{tagline}</span>}
        </div>
      </div>
      <nav className="cx-nav" aria-label={name}>
        {nav.slice(0, 3).map((b) => (
          <a key={b.id} href={`#${b.id}`}>{b.data.title}</a>
        ))}
      </nav>
      <div className="cx-row g8">
        {venueId && <FollowBtn id={venueId} />}
        {msg && byId.has(msg.id) && (
          <Btn kind="ghost" size="m hide-s" href={`#${msg.id}`}>
            <MessageSquare size={16} aria-hidden="true" />
            {c.message}
          </Btn>
        )}
        <ShareBtn />
      </div>
    </header>
  );
}

function ShareBtn() {
  const { c, preview } = useCx();
  if (preview) return <span className="cx-btn ghost icon m" aria-hidden="true"><Share size={16} /></span>;
  const share = () => {
    const url = typeof window === 'undefined' ? '' : window.location.href;
    if (typeof navigator !== 'undefined' && navigator.share) navigator.share({ url }).catch(() => {});
    else navigator.clipboard?.writeText(url).catch(() => {});
  };
  return (
    <button type="button" className="cx-btn ghost icon m" onClick={share} aria-label={c.share}>
      <Share size={16} aria-hidden="true" />
    </button>
  );
}

function Hero({ block }: { block: Block }) {
  const { c, live, look, primary, page, byId } = useCx();
  const d = block.data;
  const proof = live[block.id]?.proof as { names: string[]; more: number } | undefined;
  const drop = page.blocks.find((b) => b.kind === 'deal.drop' && !b.hidden);
  const left = drop ? live[drop.id]?.left : undefined;
  return (
    <section className="cx-hero" data-block={block.id} data-kind={block.kind} data-tone={block.tone}>
      <div className="cx-hero-copy">
        {!!d.chips?.length && (
          <div className="cx-chips">
            {d.chips.map((ch: { id: string; label: string }) => (
              <span key={ch.id} className="cx-chip">{ch.label}</span>
            ))}
          </div>
        )}
        <h1 className="cx-h1">{d.headline}</h1>
        {d.lede && <p className="cx-lede">{d.lede}</p>}
        {(d.primary || d.secondary) && (
          <div className="cx-row g12 cx-ctas">
            {d.primary && byId.has(d.primary) && (
              <Btn kind={primary === 'hero' ? 'primary' : 'ink'} size="l" href={`#${d.primary}`}>
                {look === 'shop' && <ShoppingBag size={16} aria-hidden="true" />}
                {d.primary_label}
              </Btn>
            )}
            {d.secondary && byId.has(d.secondary) && (
              <Btn kind="ghost" size="l" href={`#${d.secondary}`}>{d.secondary_label}</Btn>
            )}
          </div>
        )}
        {proof && proof.names.length > 0 && (
          <div className="cx-row g12 cx-known">
            <span className="cx-stack">
              {proof.names.slice(0, 3).map((n) => <Av key={n} name={n} />)}
            </span>
            <span className="cx-muted cx-small">
              <b>{proof.names.join(', ')}</b> {c.knownProof(proof.names, proof.more)}
            </span>
          </div>
        )}
      </div>
      <div className="cx-hero-img">
        <Img image={d.image} eager empty />
        {drop && typeof left === 'number' && (
          <span className="cx-chip paper cx-tag">
            {drop.data.title} · {c.ofLeft(left, drop.data.total)}
          </span>
        )}
      </div>
    </section>
  );
}

function currentStage(stages: { opens: string }[], at: string): number {
  const now = stamp(at);
  let idx = -1;
  stages.forEach((s, i) => {
    const o = stamp(s.opens);
    if (now && o && stampMs(o) <= stampMs(now)) idx = i;
  });
  return idx;
}

type Stage = { id: string; circle: string; opens: string };

function useDrop(block: Block) {
  const { c, live, lang, byId, sample } = useCx();
  const d = block.data;
  const left: number | undefined = typeof live[block.id]?.left === 'number' ? live[block.id].left : undefined;
  const total: number = d.total;
  const stages: Stage[] = d.stages ?? [];
  const cur = currentStage(stages, live.at ?? (sample ? '' : nowStamp()));
  const shown = byId.get(d.products);
  const products = shown?.kind === 'cat.products' && (shown.audience ?? 'public') === 'public' ? shown : undefined;
  const cover: ImgVal | undefined = d.cover && imageUrl(d.cover.blob) ? d.cover : undefined;
  const image = cover ?? (products?.data.items as { image?: ImgVal }[] | undefined)?.find((p) => p.image && imageUrl(p.image.blob))?.image;
  const stage = cur >= 0
    ? `${c.circles[stages[cur].circle] ?? stages[cur].circle} · ${c.now}`
    : stages[0] ? `${c.circles[stages[0].circle] ?? stages[0].circle} · ${when(lang, stages[0].opens)}` : '';
  return {
    d, left, total, stages, cur, image, stage,
    big: left ?? total,
    unit: left === undefined ? c.piecesUnit(total) : c.ofTotalLeft(total),
    closes: d.closes ? `${c.closes} ${when(lang, d.closes)}` : '',
    soldPct: left === undefined ? undefined : ((total - left) / total) * 100,
  };
}

function Drop({ block, folded }: { block: Block; folded: Block[] }) {
  const { c, lang, look } = useCx();
  const { d, left, total, stages, cur, big, unit, closes } = useDrop(block);
  const sold = typeof left === 'number' ? total - left : undefined;
  const products = folded.find((b) => b.kind === 'cat.products');
  const segs = Math.min(total, 24);
  const soldSegs = typeof sold === 'number' ? Math.round((sold / total) * segs) : 0;
  return (
    <div className={`cx-drop ${look === 'studio' ? 'cx-band' : ''}`}>
      <div className="cx-drop-head">
        <div>
          {closes && <span className="cx-lbl">{closes}</span>}
          <h2 className="cx-h2">{d.title}</h2>
          {d.note && <p className="cx-muted cx-note">{d.note}</p>}
        </div>
        <div className="cx-drop-count">
          <b className="cx-big">{big}</b>
          <span className="cx-small">{unit}</span>
          {look === 'studio' && typeof left === 'number' && (
            <span className="cx-segs" aria-hidden="true">
              {Array.from({ length: segs }, (_, i) => <i key={i} className={i < soldSegs ? '' : 'on'} />)}
            </span>
          )}
        </div>
      </div>
      {stages.length > 0 && (
        <ol className="cx-stages">
          {stages.map((s, i) => (
            <li key={s.id} className={i === cur ? 'now' : i < cur ? 'past' : ''}>
              <b>{c.circles[s.circle] ?? s.circle}{i === cur ? ` · ${c.now}` : ''}</b>
              <span>{when(lang, s.opens)}</span>
            </li>
          ))}
        </ol>
      )}
      {products && <ProductGrid block={products} inBand />}
    </div>
  );
}

type Shape = '1x1' | '2x1' | '2x2';

function DropTile({ block, shape }: { block: Block; shape: Shape }) {
  const { d, big, unit, closes, stage, image, soldPct } = useDrop(block);
  return (
    <div className={`cx-dropcard s${shape}`}>
      <div className="cx-row sp g8 cx-dropcard-top">
        <span className="cx-lbl">{d.title}</span>
        {closes && <span className="cx-small cx-faint">{closes}</span>}
      </div>
      {shape !== '1x1' && (
        <div className="cx-drop-img">
          <Img image={image} empty />
        </div>
      )}
      <div className="cx-dropcard-num">
        <b className="cx-big">{big}</b>
        <span className="cx-small">{unit}</span>
        <div className="cx-dropcard-foot">
          {soldPct !== undefined && <div className="cx-bar"><b style={{ width: `${soldPct}%` }} /></div>}
          {stage && <span className="cx-small cx-faint cx-dropcard-stage">{stage}</span>}
        </div>
      </div>
    </div>
  );
}

function useStock(block: Block): Record<string, number> | undefined {
  const { venueId, sent } = useCx();
  const [left, setLeft] = useState<Record<string, number>>();
  useEffect(() => {
    if (!venueId) return;
    let on = true;
    stockLeft(venueId, block.id).then((l) => on && setLeft(l), () => {});
    return () => {
      on = false;
    };
  }, [venueId, block.id, sent]);
  return left;
}

function ProductGrid({ block, inBand }: { block: Block; inBand?: boolean }) {
  const { c, cart, add, take } = useCx();
  const d = block.data;
  const live = useStock(block);
  const groups = Array.from(new Set((d.items as { group?: string }[]).map((i) => i.group).filter(Boolean))) as string[];
  const [group, setGroup] = useState<string | null>(null);
  const items = (d.items as any[]).filter((i) => !group || i.group === group);
  const mine = cart?.block === block.id ? cart.items : {};
  return (
    <div className="cx-products-wrap" id={inBand ? block.id : undefined}>
      {!inBand && <SecHead eyebrow={d.eyebrow} title={d.title} />}
      {!inBand && groups.length > 1 && (
        <div className="cx-chips cx-groups" role="group">
          <button type="button" className={`cx-chip ${group === null ? 'on' : 'line'}`} aria-pressed={group === null} onClick={() => setGroup(null)}>{c.all}</button>
          {groups.map((g) => (
            <button type="button" key={g} className={`cx-chip ${group === g ? 'on' : 'line'}`} aria-pressed={group === g} onClick={() => setGroup(g)}>{g}</button>
          ))}
        </div>
      )}
      <div className="cx-products">
        {items.map((p, i) => {
          const left = leftOf(p, live);
          const n = mine[p.id] ?? 0;
          return (
            <article key={p.id} className={`cx-prod ${left === 0 ? 'sold' : ''}`}>
              <div className={`cx-ph ${p.image && imageUrl(p.image.blob) ? '' : 'empty'}`}>
                <Img image={p.image} tone={(i % 6) + 1} empty={p.name} />
              </div>
              <div className="cx-pbody">
                <div className="cx-pname">{p.name}</div>
                {p.detail && <div className="cx-faint cx-small">{p.detail}</div>}
                <div className="cx-muted cx-small">{left === 0 ? c.soldOut : c.nLeft(left)}</div>
              </div>
              <div className="cx-pfoot">
                <Price amount={p.price} token={d.token} />
                {left === 0 ? (
                  <span className="cx-btn ghost icon m cx-off" aria-label={c.soldOut}>
                    <Ban size={16} aria-hidden="true" />
                  </span>
                ) : n > 0 ? (
                  <span className="cx-step">
                    <button type="button" className="cx-btn ghost icon m" aria-label={`${c.less} · ${p.name}`} onClick={() => take(block, p.id)}>
                      <Minus size={16} aria-hidden="true" />
                    </button>
                    <b aria-live="polite">{n}</b>
                    <button type="button" className="cx-btn ghost icon m" aria-label={`${c.addOne} · ${p.name}`} disabled={n >= left} onClick={() => add(block, p, left)}>
                      <Plus size={16} aria-hidden="true" />
                    </button>
                  </span>
                ) : (
                  <button type="button" className="cx-btn ghost icon m" aria-label={`${c.addOne} · ${p.name}`} onClick={() => add(block, p, left)}>
                    <Plus size={16} aria-hidden="true" />
                  </button>
                )}
              </div>
            </article>
          );
        })}
      </div>
    </div>
  );
}

function CartBar({ onOpen }: { onOpen: () => void }) {
  const { c, lang, cart, byId } = useCx();
  const block = cart && byId.get(cart.block);
  if (!cart || !block || cartCount(cart.items) === 0) return null;
  const sum = orderSum(block, cart.items, null);
  return (
    <div className="cx-cartbar">
      <div className="cx-cartbar-in">
        <div className="cx-col">
          <span className="cx-muted cx-small">{c.cart}</span>
          <b>
            {c.items(cartCount(cart.items))} · {money(lang, sum.subtotal)} {block.data.token}
          </b>
        </div>
        <button type="button" className="cx-btn primary l" onClick={onOpen}>{c.checkout}</button>
      </div>
    </div>
  );
}

function CartSheet({ onClose, onSent }: { onClose: () => void; onSent: () => void }) {
  const { c, lang, cart, byId, page, venueId, add, take, name } = useCx();
  const block = cart ? byId.get(cart.block) : undefined;
  const ways = fulfilOptions(page);
  const [fulfil, setFulfil] = useState<'pickup' | 'delivery'>(ways.pickup ? 'pickup' : 'delivery');
  const [zoneId, setZoneId] = useState(ways.zones[0]?.id ?? '');
  const [address, setAddress] = useState('');
  const [who, setWho] = useState('');
  const [contact, setContact] = useState('');
  const [text, setText] = useState('');
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | { error: string }>('idle');
  useEffect(() => {
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', esc);
    return () => window.removeEventListener('keydown', esc);
  }, [onClose]);
  const empty = !block || !cart || cartCount(cart.items) === 0;
  useEffect(() => {
    if (empty && state !== 'sent') onClose();
  }, [empty, state, onClose]);
  if (state === 'sent') {
    return (
      <div className="cx-scrim" onClick={onClose}>
        <div className="cx-sheet" role="dialog" aria-modal="true" aria-labelledby="cx-cart-t" onClick={(e) => e.stopPropagation()}>
          <b className="cx-h3" id="cx-cart-t">{c.ordered}</b>
          <div className="cx-row g8">
            <Check size={18} aria-hidden="true" />
            <b>{c.sent(name.split(' ')[0])}</b>
          </div>
          <span className="cx-muted cx-small">{c.replyTo} · {c.orderPayNote}</span>
          <Btn kind="ink" size="l" wide onClick={onClose}>OK</Btn>
        </div>
      </div>
    );
  }
  if (!block || !cart) return null;
  const token: string = block.data.token;
  const zone: Zone | null = fulfil === 'delivery' ? ways.zones.find((z) => z.id === zoneId) ?? null : null;
  const sum = orderSum(block, cart.items, zone);
  const lines = (block.data.items as Product[]).filter((p) => cart.items[p.id] > 0);
  const ready = lines.length > 0 && who.trim() && contact.trim() && !sum.belowMin && (fulfil === 'pickup' || (zone && address.trim())) && state !== 'sending';
  const send = () => {
    if (!venueId || !ready) return;
    setState('sending');
    sendPageRequest(venueId, { ...orderBody(block, cart.items, { fulfil, zone: zone?.id, address }), name: who.trim(), contact: contact.trim(), text: text.trim() }).then(
      () => {
        setState('sent');
        onSent();
      },
      (e) => {
        const said = e instanceof VenueRefusal ? (lang === 'uk' ? e.messageUk : e.messageEn) : null;
        setState({ error: said ?? c.sendFailed });
      },
    );
  };
  const amount = (a: string) => `${money(lang, a)} ${token}`;
  return (
    <div className="cx-scrim" onClick={onClose}>
      <div className="cx-sheet" role="dialog" aria-modal="true" aria-labelledby="cx-cart-t" onClick={(e) => e.stopPropagation()}>
        <div className="cx-row sp">
          <b className="cx-h3" id="cx-cart-t">{c.cart}</b>
          <button type="button" className="cx-btn ghost icon m" onClick={onClose} aria-label={c.cancel}>
            <X size={16} aria-hidden="true" />
          </button>
        </div>
          <form
            className="cx-col g12"
            onSubmit={(e) => {
              e.preventDefault();
              send();
            }}
          >
            <div className="cx-lines">
              {lines.map((p) => (
                <div key={p.id} className="cx-line">
                  <span className="cx-grow">{p.name}</span>
                  <span className="cx-step">
                    <button type="button" className="cx-btn ghost icon m" aria-label={`${c.less} · ${p.name}`} onClick={() => take(block, p.id)}>
                      <Minus size={16} aria-hidden="true" />
                    </button>
                    <b>{cart.items[p.id]}</b>
                    <button type="button" className="cx-btn ghost icon m" aria-label={`${c.addOne} · ${p.name}`} onClick={() => add(block, p, cart.left[p.id] ?? leftOf(p, undefined))}>
                      <Plus size={16} aria-hidden="true" />
                    </button>
                  </span>
                </div>
              ))}
            </div>
            {(ways.pickup && ways.zones.length > 0) && (
              <div className="cx-chips" role="radiogroup" aria-label={c.howGet}>
                {(['pickup', 'delivery'] as const).map((f) => (
                  <button type="button" key={f} role="radio" aria-checked={fulfil === f} className={`cx-chip ${fulfil === f ? 'on' : 'line'}`} onClick={() => setFulfil(f)}>
                    {f === 'pickup' ? c.pickup : c.delivery}
                  </button>
                ))}
              </div>
            )}
            {fulfil === 'pickup' && !ways.zones.length && <span className="cx-muted cx-small">{c.pickupOnly}</span>}
            {fulfil === 'delivery' && (
              <>
                {ways.zones.length > 1 && (
                  <div className="cx-chips" role="radiogroup" aria-label={c.zone}>
                    {ways.zones.map((z) => (
                      <button type="button" key={z.id} role="radio" aria-checked={zoneId === z.id} className={`cx-chip ${zoneId === z.id ? 'on' : 'line'}`} onClick={() => setZoneId(z.id)}>
                        {z.area} · {amount(z.fee)}
                      </button>
                    ))}
                  </div>
                )}
                <label className="cx-field">
                  <span className="cx-lbl">{c.address}</span>
                  <input className="cx-in" value={address} maxLength={200} autoComplete="street-address" onChange={(e) => setAddress(e.target.value)} />
                </label>
              </>
            )}
            <div className="cx-sums">
              <div className="cx-row sp"><span className="cx-muted">{c.subtotal}</span><span>{amount(sum.subtotal)}</span></div>
              {zone && <div className="cx-row sp"><span className="cx-muted">{c.delivery} · {zone.area}</span><span>{amount(sum.fee)}</span></div>}
              <div className="cx-row sp"><b>{c.total}</b><b>{amount(sum.total)}</b></div>
              {sum.belowMin && zone?.min && <span className="cx-err">{c.minFor(amount(zone.min))}</span>}
            </div>
            <label className="cx-field">
              <span className="cx-lbl">{c.yourName}</span>
              <input className="cx-in" value={who} maxLength={60} autoComplete="name" onChange={(e) => setWho(e.target.value)} />
            </label>
            <label className="cx-field">
              <span className="cx-lbl">{c.contact}</span>
              <input className="cx-in" value={contact} maxLength={120} autoComplete="email" onChange={(e) => setContact(e.target.value)} />
            </label>
            <label className="cx-field">
              <span className="cx-lbl">{c.note}</span>
              <textarea className="cx-in cx-area" value={text} maxLength={500} rows={2} onChange={(e) => setText(e.target.value)} />
            </label>
            {typeof state === 'object' && <span className="cx-err" role="alert">{state.error}</span>}
            <span className="cx-muted cx-small">{c.orderPayNote}</span>
            <button type="submit" className="cx-btn primary l wide" disabled={!ready}>
              {state === 'sending' ? c.sending : `${c.order} · ${amount(sum.total)}`}
            </button>
          </form>
      </div>
    </div>
  );
}

function Services({ block, folded }: { block: Block; folded: Block[] }) {
  const { lang, c, preview } = useCx();
  const d = block.data;
  const slots = folded.find((b) => b.kind === 'book.slots');
  return (
    <div>
      <SecHead eyebrow={d.eyebrow} title={d.title} />
      <div className={slots ? 'cx-work' : ''}>
        <div className="cx-card cx-svcs">
          {(d.items as any[]).map((s, i) => (
            <div key={s.id} className={`cx-svc ${s.image || preview ? '' : 'noimg'}`}>
              {(s.image || preview) && <div className="cx-th"><Img image={s.image} tone={(i % 6) + 1} empty /></div>}
              <div className="cx-svc-body">
                <div className="cx-h3">{s.name}</div>
                <div className="cx-muted cx-small">
                  {[duration(lang, s.duration), s.capacity ? (s.capacity === 1 ? c.oneToOne : c.upTo(s.capacity)) : '', s.note]
                    .filter(Boolean)
                    .join(' · ')}
                </div>
              </div>
              <div className="cx-svc-price"><Price amount={s.price} token={d.token} /></div>
            </div>
          ))}
        </div>
        {slots && <Booking block={slots} services={block} />}
      </div>
    </div>
  );
}

type Day = { date: string; times: { t: string; left: number; at?: number; local?: string }[] };

function Booking({ block, services }: { block: Block; services: Block }) {
  const { live, lang, c, primary, name, ask, venueId, page, sent } = useCx();
  const l = live[block.id] as { service?: string; days?: Day[]; booked?: { name: string; when: string } } | undefined;
  const items = services.data.items as any[];
  const [sid, setSid] = useState<string | undefined>(l?.service);
  const svc = items.find((s) => s.id === sid) ?? items.find((s) => s.id === l?.service) ?? items[0];
  const mode = bookMode(block, 'everyone');
  const week = page.blocks.find((b) => b.kind === 'hrs.week' && !b.hidden);
  const [taken, setTaken] = useState<Taken[]>([]);
  const [now] = useState(() => new Date());
  const masters = useMemo(() => pageStaff(page), [page]);
  const [master, setMaster] = useState<string | undefined>();
  const who = masters.find((m) => m.id === master);
  const due = depositDue(page, svc, services.data.token);
  useEffect(() => {
    if (!venueId) return;
    let on = true;
    takenTimes(venueId, block.id).then((t) => on && setTaken(t), () => {});
    return () => {
      on = false;
    };
  }, [venueId, block.id, sent]);
  const days: Day[] = useMemo(
    () =>
      l?.days ??
      slotDays({ book: block.data, service: svc, rows: week?.data.rows, now, taken, enforce: mode === 'book', staff: masters.map((m) => m.id), master: who?.id }),
    [l?.days, block.data, svc, week, now, taken, mode, masters, who],
  );
  const firstDay = Math.max(0, days.findIndex((dd) => dd.times.some((t) => t.left > 0)));
  const [di, setDi] = useState(firstDay);
  const day = days[di] ?? days[firstDay];
  const [ti, setTi] = useState(-1);
  const at = day ? (day.times[ti]?.left ? ti : day.times.findIndex((t) => t.left > 0)) : -1;
  const time = at >= 0 ? day?.times[at] : undefined;
  const pick = (i: number) => {
    setDi(i);
    setTi(-1);
  };
  const verb = mode === 'book' ? c.book : c.request;
  const service = svc ? { id: svc.id, name: svc.name } : undefined;
  const book = () =>
    ask({ block, service, staff: who && { id: who.id, name: who.name }, deposit: due, at: time && day && time.at ? { at: time.at, local: time.local ?? '', date: day.date, t: time.t } : undefined });
  const askTime = () => ask({ block, service, staff: who && { id: who.id, name: who.name }, deposit: due, preferred: askWindow(block.data, new Date()) });
  return (
    <div className="cx-card cx-pad cx-col g16 cx-booking" id={block.id} data-block={block.id} data-kind={block.kind}>
      <div className="cx-row sp">
        <div className="cx-h3">{svc?.name}</div>
        {svc && <span className="cx-faint cx-small">{duration(lang, svc.duration)} · {money(lang, svc.price)} {services.data.token}</span>}
      </div>
      {items.length > 1 && (
        <div className="cx-chips" role="radiogroup" aria-label={c.service}>
          {items.map((s) => (
            <button
              type="button"
              key={s.id}
              role="radio"
              aria-checked={s.id === svc?.id}
              className={`cx-chip ${s.id === svc?.id ? 'on' : 'line'}`}
              onClick={() => {
                setSid(s.id);
                setTi(-1);
              }}
            >
              {s.name}
            </button>
          ))}
        </div>
      )}
      {masters.length > 0 && (
        <div className="cx-chips" role="radiogroup" aria-label={c.master}>
          {[{ id: undefined, name: c.anyone }, ...masters].map((m) => (
            <button
              type="button"
              key={m.id ?? ''}
              role="radio"
              aria-checked={m.id === who?.id}
              className={`cx-chip ${m.id === who?.id ? 'on' : 'line'}`}
              onClick={() => {
                setMaster(m.id);
                setTi(-1);
              }}
            >
              {m.name}
            </button>
          ))}
        </div>
      )}
      {days.length === 0 ? (
        <>
          <p className="cx-empty">{l?.days || week ? c.noTimes : c.noHours}</p>
          <Btn kind={primary === 'booking' ? 'primary' : 'ink'} size="l" wide onClick={askTime}>
            <Clock size={16} aria-hidden="true" />
            {c.askTime}
          </Btn>
        </>
      ) : (
        <>
          <div className="cx-days" role="listbox" aria-label={svc?.name}>
            {days.map((dd, i) => {
              const off = !dd.times.some((t) => t.left > 0);
              return (
                <button
                  type="button"
                  key={dd.date}
                  role="option"
                  aria-selected={dd === day}
                  disabled={off}
                  className={`cx-day ${dd === day ? 'on' : ''} ${off ? 'off' : ''}`}
                  onClick={() => pick(i)}
                >
                  <span className="cx-lbl">{weekday(lang, dd.date)}</span>
                  <b>{dayNum(dd.date)}</b>
                </button>
              );
            })}
          </div>
          {day && (
            <div className="cx-times">
              {day.times.map((t, i) => (
                <button
                  type="button"
                  key={t.t}
                  disabled={t.left === 0}
                  className={`cx-time ${i === at ? 'on' : ''} ${t.left === 0 ? 'gone' : ''}`}
                  onClick={() => setTi(i)}
                >
                  {t.t}
                </button>
              ))}
            </div>
          )}
          <div className="cx-row g8 cx-small">
            {l?.booked && (
              <>
                <Av name={l.booked.name} size="xs" />
                <span className="cx-muted">{c.booked(l.booked.name, l.booked.when)}</span>
              </>
            )}
            <span className="cx-grow" />
            {time && svc?.capacity && <span className="cx-faint">{c.seatsLeft(time.left, svc.capacity)}</span>}
          </div>
          <Btn kind={primary === 'booking' ? 'primary' : 'ink'} size="l" wide onClick={time ? book : askTime}>
            {verb}
            {due ? ` · ${c.deposit(money(lang, due.amount), due.token)}` : day && time ? ` ${weekday(lang, day.date)} ${dayNum(day.date)} · ${time.t}` : ''}
          </Btn>
          <div className="cx-row g8 cx-faint cx-xs cx-center">
            <MessageSquare size={14} aria-hidden="true" />
            {due ? c.depositNote : venueId ? c.replyTo : c.confirmedIn(name.split(' ')[0])}
          </div>
        </>
      )}
    </div>
  );
}

function ReviewsTile({ block }: { block: Block }) {
  const { c, live } = useCx();
  const r = live[block.id] as { rating?: number; count?: number; notes?: { text: string }[] } | undefined;
  return (
    <>
      <div className="cx-row sp">
        <span className="cx-lbl">{block.data.title ?? c.reviews}</span>
        <Star size={16} aria-hidden="true" />
      </div>
      {r?.rating ? (
        <>
          <div className="cx-big cx-auto">{r.rating.toFixed(1)}</div>
          <div className="cx-faint cx-small">{c.reviewCount(r.count ?? 0)}</div>
          {block.data.text && r.notes?.[0] && (
            <>
              <div className="cx-hair" />
              <div className="cx-small">“{r.notes[0].text}”</div>
            </>
          )}
        </>
      ) : (
        <p className="cx-empty cx-auto">{c.noReviews}</p>
      )}
    </>
  );
}

function Reviews({ block }: { block: Block }) {
  const { c, live } = useCx();
  const r = live[block.id] as { rating?: number; count?: number; notes?: { text: string; by: string; what?: string }[] } | undefined;
  const notes = block.data.text ? (r?.notes ?? []).slice(0, block.data.show ?? 3) : [];
  return (
    <div>
      <SecHead
        title={block.data.title ?? c.reviews}
        right={
          r?.rating ? (
            <div className="cx-row g8 cx-rating">
              <b className="cx-big">{r.rating.toFixed(1)}</b>
              <span className="cx-faint cx-small">{c.reviewCount(r.count ?? 0)}</span>
            </div>
          ) : undefined
        }
      />
      {!r?.rating && <p className="cx-empty">{c.noReviews}</p>}
      {notes.length > 0 && (
        <div className="cx-quotes">
          {notes.map((n) => (
            <figure key={n.by + n.text} className="cx-quote">
              <blockquote>“{n.text}”</blockquote>
              <figcaption className="cx-row g12">
                <Av name={n.by} />
                <span className="cx-col">
                  <b className="cx-small">{n.by}</b>
                  {n.what && <span className="cx-faint cx-xs">{n.what}</span>}
                </span>
              </figcaption>
            </figure>
          ))}
        </div>
      )}
    </div>
  );
}

function MapArt() {
  return (
    <svg viewBox="0 0 300 200" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <rect width="300" height="200" className="m-bg" />
      <path d="M0 170 Q80 150 150 190 T300 175 V200 H0Z" className="m-water" />
      <rect x="110" y="70" width="70" height="46" className="m-park" />
      <path d="M-10 60 L320 40 M60 -10 L90 210 M200 -10 L180 210 M-10 140 L320 120 M130 210 L310 20" className="m-road" />
    </svg>
  );
}

function MeetTile({ block }: { block: Block }) {
  return (
    <div className="cx-map">
      <MapArt />
      <span className="cx-pin" style={{ left: '56%', top: '50%' }} />
      <span className="cx-chip paper cx-tag">
        <MapPin size={14} aria-hidden="true" />
        {block.data.text}
      </span>
    </div>
  );
}

function MessageTile({ block }: { block: Block }) {
  const { c, ask, name } = useCx();
  const d = block.data;
  return (
    <>
      <div className="cx-row sp">
        <span className="cx-lbl">{d.title ?? name}</span>
        {d.hours && <span className="cx-faint cx-small">{c.replies} {d.hours.open}–{d.hours.close}</span>}
      </div>
      <div className="cx-col g8 cx-auto cx-bubbles">
        {(d.starters ?? []).map((s: { id: string; text: string }) => (
          <button type="button" key={s.id} className="cx-bubble" onClick={() => ask({ block, text: s.text })}>{s.text}</button>
        ))}
        {!d.starters?.length && (
          <Btn kind="ghost" size="m" onClick={() => ask({ block })}>
            <MessageSquare size={16} aria-hidden="true" />
            {c.message}
          </Btn>
        )}
      </div>
    </>
  );
}

function MessageSolo({ block }: { block: Block }) {
  const { c, ask, name, page } = useCx();
  const d = block.data;
  const addr = page.blocks.find((b) => b.kind === 'loc.address' && !b.hidden && onSite(b) && b.data.text);
  const starters: { id: string; text: string }[] = d.starters ?? [];
  return (
    <div className="cx-card cx-pad cx-col g12 cx-msg-solo">
      <div className="cx-row sp g12">
        <div className="cx-col cx-msg-meta">
          <b className="cx-pname">{d.title ?? name}</b>
          {addr && (
            <span className="cx-row cx-muted cx-small">
              <MapPin size={14} aria-hidden="true" />
              {addr.data.text}
            </span>
          )}
          {d.hours && (
            <span className="cx-row cx-muted cx-small cx-tnum">
              <Clock size={14} aria-hidden="true" />
              {c.replies} {d.hours.open}–{d.hours.close}
            </span>
          )}
        </div>
        {!starters.length && (
          <Btn kind="ghost" size="m" onClick={() => ask({ block })}>
            <MessageSquare size={16} aria-hidden="true" />
            {c.message}
          </Btn>
        )}
      </div>
      {starters.length > 0 && (
        <div className="cx-col g8 cx-bubbles">
          {starters.map((s) => (
            <button type="button" key={s.id} className="cx-bubble" onClick={() => ask({ block, text: s.text })}>{s.text}</button>
          ))}
        </div>
      )}
    </div>
  );
}

function solanaPayUrl(wallet: string, label: string): string {
  return `solana:${wallet}?label=${encodeURIComponent(label)}`;
}

function PayTile({ block }: { block: Block }) {
  const { c, token, test, page, sample } = useCx();
  const wallet = page.blocks.find((b) => b.kind === 'pay.wallet' && !b.hidden)?.data.address as string | undefined;
  const qr = useMemo(() => {
    if (sample || !wallet) return null;
    const m = encodeQrMatrix(solanaPayUrl(wallet, block.data.label));
    return { size: m.size, path: encodeQrSvgPath(m) };
  }, [sample, wallet, block.data.label]);
  return (
    <>
      <div className="cx-row sp">
        <span className="cx-lbl">{c.pay}</span>
        <Wallet size={16} aria-hidden="true" />
      </div>
      {qr && (
        <svg className="cx-qr" viewBox={`-2 -2 ${qr.size + 4} ${qr.size + 4}`} role="img" aria-label={block.data.label}>
          <path d={qr.path} />
        </svg>
      )}
      <div className="cx-auto">
        <div className="cx-h3">{block.data.label}</div>
        <div className="cx-small">{block.data.amount ? `${c.payNote(token)} · ${c.typeAmount}` : c.payNote(token)}</div>
        {test && <span className="cx-test">{c.testPayments}</span>}
      </div>
    </>
  );
}

function SplitTile({ block }: { block: Block }) {
  const { c, live, lang, act, test } = useCx();
  const d = block.data;
  const paid: number | undefined = live[block.id]?.paid;
  return (
    <>
      <div className="cx-row sp g8">
        <span className="cx-lbl">{d.title}</span>
        {d.closes && <span className="cx-faint cx-small">{c.closes} {shortDate(lang, d.closes)}</span>}
      </div>
      <div className="cx-auto">
        <div className="cx-h3">{c.perShare(divide(d.total, d.shares), d.token)}</div>
        <div className="cx-seats" aria-hidden="true">
          {Array.from({ length: Math.min(d.shares, 40) }, (_, i) => <i key={i} className={paid && i < paid ? 'on' : ''} />)}
        </div>
        <div className="cx-row sp cx-small">
          <span>{typeof paid === 'number' ? c.paidOf(paid, d.shares) : `${d.total} ${d.token}`}</span>
          {test && <span className="cx-test">{c.testPayments}</span>}
        </div>
      </div>
      <Btn kind="ghost" size="m" onClick={act}>{c.payShare}</Btn>
    </>
  );
}

function JoinTile({ block }: { block: Block }) {
  const { c, act } = useCx();
  const d = block.data;
  return (
    <>
      <span className="cx-lbl">{c.approve[d.approve] ?? ''}</span>
      <div className="cx-auto">
        <div className="cx-h3">{d.title}</div>
        {d.note && <div className="cx-small cx-muted">{d.note}</div>}
      </div>
      <Btn kind="ghost" size="m" onClick={act}>{c.askToJoin}</Btn>
    </>
  );
}

function AddressTile({ block }: { block: Block }) {
  return <MeetTile block={block} />;
}

function Event({ block, folded }: { block: Block; folded: Block[] }) {
  const { c, lang, live, act } = useCx();
  const d = block.data;
  const rsvp = folded.find((b) => b.kind === 'evt.rsvp');
  const going: number | undefined = rsvp ? live[rsvp.id]?.going : undefined;
  return (
    <div className="cx-card cx-pad cx-event">
      <div className="cx-event-date">
        <span className="cx-lbl">{weekday(lang, d.starts)}</span>
        <b className="cx-big">{dayNum(d.starts)}</b>
        <span className="cx-small">{clock(d.starts)}</span>
      </div>
      <div className="cx-event-body">
        <h2 className="cx-h2">{d.title}</h2>
        <div className="cx-muted cx-small">
          {[when(lang, d.starts), d.capacity ? c.places(d.capacity) : ''].filter(Boolean).join(' · ')}
        </div>
        {d.note && <p className="cx-note">{d.note}</p>}
      </div>
      <div className="cx-event-act">
        {d.price && <Price amount={d.price.amount} token={d.price.token} />}
        {rsvp && (
          <>
            {typeof going === 'number' && <span className="cx-small cx-muted">{going ? c.going(going) : c.beFirst}</span>}
            <Btn kind="ghost" size="m" onClick={act}>
              <Check size={16} aria-hidden="true" />
              {c.imGoing}
            </Btn>
          </>
        )}
      </div>
    </div>
  );
}

function Going({ block }: { block: Block }) {
  const { c, lang, live, act, byId } = useCx();
  const going: number | undefined = live[block.id]?.going;
  const shown = byId.get(block.data.event);
  const event = shown?.kind === 'evt.single' ? shown : undefined;
  const about = event ? [event.data.title, when(lang, event.data.starts)].filter(Boolean).join(' · ') : '';
  return (
    <div className="cx-card cx-pad cx-going">
      {typeof going === 'number' && (
        <span className="cx-stack" aria-hidden="true">
          {Array.from({ length: Math.min(Math.max(going, 1), 5) }, (_, i) => (
            <span key={i} className={`cx-av m cx-av-anon ${going ? '' : 'empty'}`}><User size={18} strokeWidth={1.75} /></span>
          ))}
        </span>
      )}
      <div className="cx-going-n">
        {typeof going === 'number' && <b className="cx-h2 cx-tnum">{going ? c.going(going) : c.beFirst}</b>}
        {about && <span className={typeof going === 'number' ? 'cx-small cx-muted' : 'cx-h3'}>{about}</span>}
      </div>
      <Btn kind="ghost" size="m" onClick={act}>
        <Check size={16} aria-hidden="true" />
        {c.imGoing}
      </Btn>
    </div>
  );
}

function Members({ block }: { block: Block }) {
  const d = block.data;
  return (
    <div>
      <SecHead title={d.title} />
      <div className="cx-members">
        {(d.members as { id: string; name: string; role?: string }[]).map((m) => (
          <div key={m.id} className="cx-member">
            <Av name={m.name} size="l" />
            <b>{m.name}</b>
            {m.role && <span className="cx-faint cx-small">{m.role}</span>}
          </div>
        ))}
      </div>
    </div>
  );
}

function About({ block }: { block: Block }) {
  return <p className="cx-about">{block.data.text}</p>;
}

const DAY_ORDER = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];

function Hours({ block }: { block: Block }) {
  const { lang } = useCx();
  const fmt = (code: string) => {
    const i = DAY_ORDER.indexOf(code);
    return weekday(lang, `2026-01-${String(5 + i).padStart(2, '0')}`);
  };
  return (
    <div className="cx-card cx-pad cx-rows">
      {(block.data.rows as { days: string[]; hours: { open: string; close: string } }[]).map((r) => (
        <div key={r.days.join('-') + r.hours.open} className="cx-row sp">
          <span>{dayRange(r.days, fmt)}</span>
          <span className="cx-tnum">{r.hours.open}–{r.hours.close}</span>
        </div>
      ))}
    </div>
  );
}

function Menu({ block }: { block: Block }) {
  const d = block.data;
  return (
    <div>
      <SecHead title={d.title} />
      <div className="cx-card cx-pad cx-rows">
        {(d.items as any[]).map((i) => (
          <div key={i.id} className="cx-row sp g16">
            <div>
              <div className="cx-pname">{i.name}</div>
              {i.note && <div className="cx-faint cx-small">{i.note}</div>}
            </div>
            <Price amount={i.price} token={d.token} />
          </div>
        ))}
      </div>
    </div>
  );
}

export type Render = (p: { block: Block; folded: Block[] }) => ReactNode;

export const RENDERERS: Record<string, Render> = {
  'id.hero': ({ block }) => <Hero block={block} />,
  'deal.drop': ({ block, folded }) => <Drop block={block} folded={folded} />,
  'cat.products': ({ block }) => <ProductGrid block={block} />,
  'cat.services': ({ block, folded }) => <Services block={block} folded={folded} />,
  'book.slots': ({ block }) => <Booking block={block} services={{ id: '', kind: 'cat.services', data: { items: [], token: '' } }} />,
  'com.reviews': ({ block }) => <Reviews block={block} />,
  'loc.message': ({ block }) => <MessageSolo block={block} />,
  'loc.meet_point': ({ block }) => <div className="cx-card cx-tile-solo cx-tile-map"><MeetTile block={block} /></div>,
  'loc.address': ({ block }) => <div className="cx-card cx-tile-solo cx-tile-map"><AddressTile block={block} /></div>,
  'pay.qr': ({ block }) => <div className="cx-card cx-pad cx-col g12 cx-tile-solo"><PayTile block={block} /></div>,
  'evt.single': ({ block, folded }) => <Event block={block} folded={folded} />,
  'evt.rsvp': ({ block }) => <Going block={block} />,
  'org.members': ({ block }) => <Members block={block} />,
  'pay.split_cost': ({ block }) => <div className="cx-card cx-pad cx-col g12 cx-tile-solo"><SplitTile block={block} /></div>,
  'org.join': ({ block }) => <div className="cx-card cx-pad cx-col g12 cx-tile-solo"><JoinTile block={block} /></div>,
  'id.about': ({ block }) => <About block={block} />,
  'hrs.week': ({ block }) => <Hours block={block} />,
  'cat.menu': ({ block }) => <Menu block={block} />,
  ...MORE_RENDERERS,
};

const TILE_BODY: Record<string, (p: { block: Block; shape: Shape }) => ReactNode> = {
  'deal.drop': DropTile,
  'com.reviews': ReviewsTile,
  'loc.message': MessageTile,
  'pay.qr': PayTile,
  'pay.split_cost': SplitTile,
  'org.join': JoinTile,
};

const TILE_TONE: Record<string, number> = { 'deal.drop': 4, 'pay.qr': 2, 'pay.split_cost': 3, 'org.join': 5, 'loc.message': 0, 'com.reviews': 0 };

function Tiles({ blocks }: { blocks: { block: Block; wide: boolean; tall?: boolean; full?: boolean }[] }) {
  return (
    <section className="cx-tiles">
      {blocks.map(({ block, wide, tall, full }) => {
        const Body = TILE_BODY[block.kind];
        const map = block.kind === 'loc.meet_point' || block.kind === 'loc.address';
        const tone = TILE_TONE[block.kind] ?? 0;
        return (
          <div
            key={block.id}
            id={block.id}
            data-block={block.id}
            data-kind={block.kind}
            data-tone={block.tone}
            className={`cx-tile ${wide ? 'w2' : ''} ${full ? 'w3' : ''} ${tall ? 'h2' : ''} ${map ? 'cx-tile-map' : 'cx-pad'} ${tone ? `t${tone}` : ''}`}
          >
            {map ? <MeetTile block={block} /> : Body ? <Body block={block} shape={tall ? '2x2' : wide ? '2x1' : '1x1'} /> : null}
          </div>
        );
      })}
    </section>
  );
}

function SectionView({ s }: { s: Section }) {
  if (s.type === 'hero') return <Hero block={s.block} />;
  if (s.type === 'tiles') return <Tiles blocks={s.blocks} />;
  const render = RENDERERS[s.block.kind];
  if (!render) return null;
  const ids = [s.block.id, ...s.with.map((b) => b.id)];
  return (
    <section
      className={`cx-sec cx-k-${s.block.kind.replace('.', '-')}`}
      id={s.block.id}
      data-block={ids.join(' ')}
      data-kind={s.block.kind}
      data-tone={s.block.tone}
    >
      {render({ block: s.block, folded: s.with })}
    </section>
  );
}

function AskSheet({ a, onClose, onSent }: { a: Ask; onClose: () => void; onSent: () => void }) {
  const { c, lang, name, venueId } = useCx();
  const [who, setWho] = useState('');
  const [contact, setContact] = useState('');
  const [text, setText] = useState(a.text ?? '');
  const [when, setWhen] = useState('');
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | { error: string }>('idle');
  const isMessage = a.block.kind === 'loc.message';
  const ready = who.trim() && contact.trim() && (!isMessage || text.trim()) && state !== 'sending';
  useEffect(() => {
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', esc);
    return () => window.removeEventListener('keydown', esc);
  }, [onClose]);
  const first = name.split(' ')[0];
  const title = isMessage ? (a.block.data.title ?? `${c.message} · ${name}`) : a.course?.title ?? a.service?.name ?? c.book;
  const send = () => {
    if (!venueId || !ready) return;
    const body: PageRequest = { block: a.block.id, name: who.trim(), contact: contact.trim(), text: text.trim() };
    if (a.service) body.service = a.service.id;
    if (a.staff) body.staff = a.staff.id;
    if (a.at) Object.assign(body, { at_ms: a.at.at, at_local: a.at.local });
    if (a.preferred) {
      body.preferred = true;
      if (when) Object.assign(body, { at_ms: new Date(when).getTime(), at_local: when });
    }
    setState('sending');
    sendPageRequest(venueId, body).then(
      () => {
        setState('sent');
        onSent();
      },
      (e) => {
        const said = e instanceof VenueRefusal ? (lang === 'uk' ? e.messageUk : e.messageEn) : null;
        setState({ error: said ?? c.sendFailed });
      },
    );
  };
  return (
    <div className="cx-scrim" onClick={onClose}>
      <div className="cx-sheet" role="dialog" aria-modal="true" aria-labelledby="cx-ask-t" onClick={(e) => e.stopPropagation()}>
        <div className="cx-row sp">
          <b className="cx-h3" id="cx-ask-t">{title}</b>
          <button type="button" className="cx-btn ghost icon m" onClick={onClose} aria-label={c.cancel}>
            <X size={16} aria-hidden="true" />
          </button>
        </div>
        {state === 'sent' ? (
          <div className="cx-col g12">
            <div className="cx-row g8">
              <Check size={18} aria-hidden="true" />
              <b>{c.sent(first)}</b>
            </div>
            <span className="cx-muted cx-small">{c.replyTo}</span>
            <Btn kind="ink" size="l" wide onClick={onClose}>OK</Btn>
          </div>
        ) : (
          <form
            className="cx-col g12"
            onSubmit={(e) => {
              e.preventDefault();
              send();
            }}
          >
            {a.at && (
              <span className="cx-chip on cx-self">
                <Clock size={14} aria-hidden="true" />
                {weekday(lang, a.at.date)} {dayNum(a.at.date)} · {a.at.t}
                {a.staff ? ` · ${a.staff.name}` : ''}
              </span>
            )}
            {a.course && (
              <span className="cx-chip on cx-self">
                <Clock size={14} aria-hidden="true" />
                {c.starts(`${dayMonth(lang, a.course.next.date)} · ${a.course.next.time}`)}
              </span>
            )}
            {a.course?.price && <span className="cx-muted cx-small">{money(lang, a.course.price)} {a.course.token} · {c.orderPayNote}</span>}
            {a.deposit && <span className="cx-muted cx-small">{c.deposit(money(lang, a.deposit.amount), a.deposit.token)} · {c.depositNote}</span>}
            {a.preferred && (
              <label className="cx-field">
                <span className="cx-lbl">{c.when}</span>
                <input className="cx-in" type="datetime-local" value={when} min={a.preferred.min} max={a.preferred.max} onChange={(e) => setWhen(e.target.value)} />
              </label>
            )}
            <label className="cx-field">
              <span className="cx-lbl">{c.yourName}</span>
              <input className="cx-in" value={who} maxLength={60} autoComplete="name" autoFocus onChange={(e) => setWho(e.target.value)} />
            </label>
            <label className="cx-field">
              <span className="cx-lbl">{c.contact}</span>
              <input className="cx-in" value={contact} maxLength={120} autoComplete="email" onChange={(e) => setContact(e.target.value)} />
            </label>
            <label className="cx-field">
              <span className="cx-lbl">{isMessage ? c.message : c.note}</span>
              <textarea className="cx-in cx-area" value={text} maxLength={500} rows={3} onChange={(e) => setText(e.target.value)} />
            </label>
            {typeof state === 'object' && <span className="cx-err" role="alert">{state.error}</span>}
            <button type="submit" className="cx-btn primary l wide" disabled={!ready}>
              {state === 'sending' ? c.sending : c.send}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}

export type CreatorPageProps = {
  page: Page;
  live?: Live;
  look: Look;
  theme: Theme;
  lang: Lang;
  sample?: boolean;
  frame?: 'page' | 'preview';
  venueId?: string;
};

export default function CreatorPage({ page, live = {}, look, theme, lang, sample = false, frame = 'page', venueId }: CreatorPageProps) {
  const [toast, setToast] = useState(false);
  const [asking, setAsking] = useState<Ask | null>(null);
  const [sent, setSent] = useState(0);
  const [cart, setCart] = useState<Basket>(null);
  const [checkout, setCheckout] = useState(false);
  const c = creatorCopy(lang);
  const real = frame === 'page' && !sample ? venueId : undefined;
  const { name } = nameOf(page);
  const ctx: Ctx = {
    look,
    lang,
    c,
    page,
    live,
    sample,
    name,
    byId: new Map(page.blocks.filter((b) => !b.hidden && onSite(b)).map((b) => [b.id, b])),
    token: pageToken(page),
    test: live.network !== 'mainnet',
    primary: LOOK[look].primary,
    act: () => setToast(true),
    ask: (a) => (real ? setAsking(a) : setToast(true)),
    sent,
    venueId: real,
    preview: frame === 'preview',
    cart,
    add: (block, item, left) =>
      real
        ? setCart((k) => {
            const same = k?.block === block.id ? k : null;
            return { block: block.id, items: addTo(same?.items ?? {}, item, left), left: { ...same?.left, [item.id]: left } };
          })
        : setToast(true),
    take: (block, id) => setCart((k) => (k?.block === block.id ? { ...k, items: takeFrom(k.items, id) } : k)),
  };
  const sections = arrange(page, look);
  const hero = page.blocks.find((b) => b.kind === 'id.hero' && !b.hidden && onSite(b));
  const meet = page.blocks.find((b) => (b.kind === 'loc.meet_point' || b.kind === 'loc.address') && !b.hidden && onSite(b));
  const msg = page.blocks.find((b) => b.kind === 'loc.message' && b.data.on && !b.hidden && onSite(b));
  const style = lookVars(look, theme) as CSSProperties;
  return (
    <CxCtx.Provider value={ctx}>
      <div className={`cx cx-${frame}`} data-look={look} data-theme={theme} style={style} lang={lang}>
        <div className="cx-wrap">
          <Header />
          {sections.length === 0 && <p className="cx-empty cx-sec">{c.notFoundBody}</p>}
          {sections.map((s, i) => (
            <SectionView key={s.type === 'tiles' ? `t${i}` : s.block.id} s={s} />
          ))}
          <footer className="cx-foot">
            <div className="cx-row g12">
              <Av name={name} size="s" />
              <span className="cx-muted cx-small">{[name, meet?.data.text].filter(Boolean).join(' · ')}</span>
            </div>
            <div className="cx-row g16 cx-faint cx-small">
              {sample && <span className="cx-test">{c.sample}</span>}
              <a href="/">{c.madeOn}</a>
            </div>
          </footer>
        </div>
        <CartBar onOpen={() => setCheckout(true)} />
        {look === 'shop' && hero?.data.primary && !(cart && cartCount(cart.items)) && (
          <div className="cx-sticky">
            {msg && (
              <a className="cx-btn ghost icon" href={`#${msg.id}`} aria-label={c.message}>
                <MessageSquare size={16} aria-hidden="true" />
              </a>
            )}
            <a className="cx-btn primary cx-grow" href={`#${hero.data.primary}`}>
              <ShoppingBag size={16} aria-hidden="true" />
              {hero.data.primary_label}
            </a>
          </div>
        )}
        {checkout && (
          <CartSheet
            onClose={() => setCheckout(false)}
            onSent={() => {
              setCart(null);
              setSent((n) => n + 1);
            }}
          />
        )}
        {asking && <AskSheet a={asking} onClose={() => setAsking(null)} onSent={() => setSent((n) => n + 1)} />}
        {toast && (
          <div className="cx-toast" role="status">
            <span>{sample ? c.sampleAct : c.appAct}</span>
            {!sample && <a href="/download">{c.getApp}</a>}
            <button type="button" onClick={() => setToast(false)} aria-label="OK">
              <Check size={16} aria-hidden="true" />
            </button>
          </div>
        )}
      </div>
    </CxCtx.Provider>
  );
}

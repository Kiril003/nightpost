/**
 * Діаграма в листі — власним SVG, без recharts.
 *
 * ── ЧОМУ НЕ БІБЛІОТЕКА ───────────────────────────────────────────────────
 *
 * Рішення власника 17.09: `vendor-charts` — найбільший шматок у теці
 * телефонної збірки (383,52 КБ, gzip 105,05) — не вартий діаграм у
 * кишеньковому месенджері. Переміряно, з чого він складався:
 *
 *   recharts/generateCategoricalChart  80,7 КБ
 *   decimal.js-light                   49,6 КБ
 *   recharts/ChartUtils                40,9 КБ
 *   recharts/Brush                     25,1 КБ   (тут не використовується)
 *   d3-time-format, d3-color, d3-scale, react-smooth, fast-equals …
 *
 * Шість модулів дерева імпортували recharts або d3. Пʼять із них
 * (`chat/ChartResponse`, `chat/DiagramResponse`, `map/panels/AnalysisPanel`,
 * `agent/workspace/WillPanel`, `pages/Dashboard/AnalyticsOverview`) недосяжні
 * з `main.tsx` цього дерева — див. стелю сиріт у `reachFromEntry.test.ts`.
 * Отже в телефонній збірці `vendor-charts` жив РІВНО заради цього файлу.
 *
 * ── ЧОМУ ФУНКЦІЮ НЕ ВИКИНУТО РАЗОМ ІЗ ВАГОЮ ──────────────────────────────
 *
 * Діаграма на телефоні ДОСЯЖНА: `ActionHubModal.handleInsertChart()` дає
 * оператору скласти її й надіслати в розмову, а `messengerApi` розкладає
 * кадр `kind: 'chart'` із дроту в `chartData`. Прибрати рендерер означало б
 * лишити двері в стіну: складач є, надсилання є, а в стрічці порожньо.
 * Тому вага йде, а функція лишається.
 *
 * ── ЩО ЗМІНИЛОСЬ У ПОВЕДІНЦІ, І ЧОМУ ЦЕ НЕ ВТРАТА ────────────────────────
 *
 * Замість підказки на наведення (`<Tooltip>`) значення читаються з рядка
 * під полотном, а активну точку вибирає ДОТИК. На склі наведення не буває
 * взагалі: recharts-підказка вимагала тримати палець, і на телефоні її
 * фактично не існувало. Тепер значення видно завжди, а смуга вибору —
 * суцільна, 48 px заввишки, тобто проходить сторожа `phone-fitness`.
 *
 * Сітка, обидві осі, легенда, кольори серій і «висновок» — на місці.
 * `type: 'area'` малюється як лінія із заливкою; `bar` і `line` — як були.
 */
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import { dateTag } from '../../i18n/dateTag';
import type { Locale } from '../../i18n/locales';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { BarChart3, TrendingUp } from 'lucide-react';
import { ChartData } from '../../types/messenger';

interface ChartEmbedProps {
  data: ChartData;
}

/**
 * Полотно міряється, а не масштабується.
 *
 * `preserveAspectRatio="none"` розтягнув би разом із геометрією й ЛІТЕРИ:
 * на телефоні колонка вузька (≈300 px), на ПК — до 688 px (`max-w-[720px]`),
 * тобто пропорція гуляє від 1,4 до 3,3, і підписи осей поїхали б удвічі
 * вшир. `meet` не спотворює, але лишає порожні поля. Тому одна одиниця
 * viewBox = один CSS-піксель: нуль спотворення й нуль полів.
 */
const H = 176;
const PAD = { top: 10, right: 8, bottom: 20, left: 32 };
const PLOT_H = H - PAD.top - PAD.bottom;

/**
 * Кольори — ЗМІННИМИ, не числами.
 *
 * SVG фарбується атрибутами `fill`/`stroke`, а не класами Tailwind, тож
 * перекриття утиліт під `:root[data-theme='day']` тут не діють — рівно та
 * пастка, яку стереже `dayTheme.test.ts` («інлайн-стилі не носять колір
 * числом»). Перша редакція цього файлу тримала тут `'#F4AF25'` і сторож
 * почервонів одразу: діаграма лишилась би нічною вдень.
 *
 * 03.10, v1.2: змінні — ролі теми `--ph-*` (обидві теми їх означують у
 * токенах), а не лісовий набір `--chart-*` з messenger.css.
 */
const ACCENT = 'var(--ph-accent)';
const INK_MUTED = 'var(--ph-text-3)';
const HAIRLINE = 'var(--ph-line)';
const AXIS = 'var(--ph-line-strong)';
const PAPER = 'var(--ph-surface)';

/**
 * «Круглі» поділки: крок 1/2/5 × 10^n, щоб підписи були читані числами, а не
 * 0.7333. Три-чотири лінії — стільке, скільке вміщує 168 px без тисняви.
 */
function ticks(max: number, min: number): number[] {
  const span = max - min || 1;
  const raw = span / 3;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((m) => m * pow).find((s) => s >= raw) ?? pow * 10;
  const out: number[] = [];
  for (let v = Math.ceil(min / step) * step; v <= max + step * 0.001; v += step) out.push(v);
  return out.length >= 2 ? out : [min, max];
}

/** Короткий підпис числа: 1200 → «1,2 тис.», 0.5 → «0,5». */
const short = (v: number, t: Translation['t'], locale: Locale): string => {
  const abs = Math.abs(v);
  const dec = (n: number) =>
    new Intl.NumberFormat(dateTag(locale), { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(n);
  if (abs >= 1_000_000) return t('chartEmbed.millions', { n: dec(v / 1_000_000) });
  if (abs >= 1_000) return t('chartEmbed.thousands', { n: dec(v / 1_000) });
  if (Number.isInteger(v)) return String(v);
  return dec(v);
};

export const ChartEmbed: React.FC<ChartEmbedProps> = ({ data }) => {
  const { t, locale } = useTranslation();
  const rows = data.data || [];
  const keys = (data.keys || []).length > 0 ? data.keys : [{ key: 'value', label: t('chartEmbed.value'), color: ACCENT }];
  const [active, setActive] = useState<number | null>(null);

  // Ширина полотна в CSS-пікселях. 320 — розумний перший кадр до першого
  // виміру; ResizeObserver далі тримає її свіжою при обертанні й розділенні.
  const shell = useRef<HTMLDivElement>(null);
  const [W, setW] = useState(320);
  useEffect(() => {
    const node = shell.current;
    if (!node || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(([entry]) => {
      const next = Math.round(entry.contentRect.width);
      if (next > 0) setW(next);
    });
    ro.observe(node);
    return () => ro.disconnect();
  }, []);
  const PLOT_W = Math.max(W - PAD.left - PAD.right, 1);

  const scale = useMemo(() => {
    const values = rows.flatMap((r) => keys.map((k) => Number(r[k.key])).filter(Number.isFinite));
    const hi = values.length ? Math.max(...values, 0) : 1;
    const lo = values.length ? Math.min(...values, 0) : 0;
    const marks = ticks(hi, lo);
    const top = Math.max(...marks, hi);
    const bottom = Math.min(...marks, lo);
    const range = top - bottom || 1;
    return {
      marks,
      y: (v: number) => PAD.top + PLOT_H - ((v - bottom) / range) * PLOT_H,
      zero: PAD.top + PLOT_H - ((0 - bottom) / range) * PLOT_H,
    };
  }, [rows, keys]);

  // Порожні дані — це стан, а не привід малювати осі ні для чого.
  if (rows.length === 0) {
    return (
      <div className="select-none bg-surface rounded-card p-3 text-ink-2 text-footnote">
        <p className="text-headline text-ink mb-1">{data.title}</p>
        <p>{t('chartEmbed.empty')}</p>
      </div>
    );
  }

  const band = PLOT_W / rows.length;
  const activeRow = active !== null ? rows[active] : rows[rows.length - 1];
  const activeIndex = active !== null ? active : rows.length - 1;
  const asLine = data.type === 'line' || data.type === 'area';

  return (
    <div className="space-y-3 select-none bg-surface rounded-card p-3 text-ink">
      {/* Шапка: значок ink-3 і назва — без «Інтерактивна візуалізація». */}
      <div className="flex items-center gap-2">
        <BarChart3 className="w-5 h-5 text-ink-3 shrink-0" strokeWidth={1.75} aria-hidden="true" />
        <h4 className="text-headline text-ink min-w-0">{data.title}</h4>
      </div>

      {/* Полотно */}
      <div ref={shell} className="w-full pt-2">
        <svg
          viewBox={`0 0 ${W} ${H}`}
          width="100%"
          height={H}
          className="block overflow-visible"
          role="img"
          aria-label={t(data.type === 'bar' ? 'chartEmbed.ariaBar' : 'chartEmbed.ariaLine', { title: data.title, n: rows.length })}
        >
          {/* Сітка й підписи значень */}
          {scale.marks.map((m) => (
            <g key={m}>
              <line
                x1={PAD.left}
                x2={W - PAD.right}
                y1={scale.y(m)}
                y2={scale.y(m)}
                stroke={HAIRLINE}
                strokeDasharray="3 3"
              />
              <text x={PAD.left - 4} y={scale.y(m) + 3} textAnchor="end" fontSize="8" fill={INK_MUTED}>
                {short(m, t, locale)}
              </text>
            </g>
          ))}

          {/* Осі */}
          <line x1={PAD.left} x2={PAD.left} y1={PAD.top} y2={PAD.top + PLOT_H} stroke={AXIS} />
          <line
            x1={PAD.left}
            x2={W - PAD.right}
            y1={PAD.top + PLOT_H}
            y2={PAD.top + PLOT_H}
            stroke={AXIS}
          />

          {/* Підписи точок: на тісноті — через одну, щоб не наїжджали */}
          {rows.map((r, i) => {
            const every = Math.ceil((rows.length * 26) / PLOT_W);
            if (i % every !== 0) return null;
            return (
              <text
                key={i}
                x={PAD.left + band * (i + 0.5)}
                y={H - 6}
                textAnchor="middle"
                fontSize="8"
                fill={i === activeIndex ? ACCENT : INK_MUTED}
                fontWeight={i === activeIndex ? 700 : 400}
              >
                {String(r.name ?? i + 1)}
              </text>
            );
          })}

          {/* Серії */}
          {asLine
            ? keys.map((k) => {
                const pts = rows
                  .map((r, i) => {
                    const v = Number(r[k.key]);
                    return Number.isFinite(v) ? `${PAD.left + band * (i + 0.5)},${scale.y(v)}` : null;
                  })
                  .filter(Boolean) as string[];
                if (pts.length === 0) return null;
                const colour = k.color || ACCENT;
                return (
                  <g key={k.key}>
                    {data.type === 'area' && (
                      <polygon
                        points={`${PAD.left + band * 0.5},${scale.zero} ${pts.join(' ')} ${
                          PAD.left + band * (rows.length - 0.5)
                        },${scale.zero}`}
                        fill={colour}
                        opacity="0.16"
                        stroke="none"
                      />
                    )}
                    <polyline points={pts.join(' ')} fill="none" stroke={colour} strokeWidth="2.5" />
                    {rows.map((r, i) => {
                      const v = Number(r[k.key]);
                      if (!Number.isFinite(v)) return null;
                      return (
                        <circle
                          key={i}
                          cx={PAD.left + band * (i + 0.5)}
                          cy={scale.y(v)}
                          r={i === activeIndex ? 5 : 3.5}
                          fill={colour}
                          stroke={PAPER}
                          strokeWidth={i === activeIndex ? 1.5 : 0}
                        />
                      );
                    })}
                  </g>
                );
              })
            : keys.map((k, ki) => {
                const slot = band / keys.length;
                const w = Math.max(slot * 0.62, 1.5);
                const colour = k.color || ACCENT;
                return (
                  <g key={k.key}>
                    {rows.map((r, i) => {
                      const v = Number(r[k.key]);
                      if (!Number.isFinite(v)) return null;
                      const y = Math.min(scale.y(v), scale.zero);
                      const h = Math.max(Math.abs(scale.zero - scale.y(v)), 1);
                      return (
                        <rect
                          key={i}
                          x={PAD.left + band * i + slot * ki + (slot - w) / 2}
                          y={y}
                          width={w}
                          height={h}
                          rx={Math.min(3, w / 2)}
                          fill={colour}
                          stroke={AXIS}
                          strokeWidth="0.75"
                          opacity={i === activeIndex ? 1 : 0.72}
                        />
                      );
                    })}
                  </g>
                );
              })}

          {/* Позначка активної точки */}
          <line
            x1={PAD.left + band * (activeIndex + 0.5)}
            x2={PAD.left + band * (activeIndex + 0.5)}
            y1={PAD.top}
            y2={PAD.top + PLOT_H}
            stroke={ACCENT}
            strokeOpacity="0.28"
            strokeWidth="1"
          />
        </svg>
      </div>

      {/*
        Вибір точки дотиком. Одна суцільна смуга 48 px — палець влучає в неї
        завжди, на відміну від окремого стовпчика в 6 px. Клавіатура веде
        стрілками; `role="slider"` бо це і є вибір індексу з діапазону.
      */}
      <div
        role="slider"
        tabIndex={0}
        aria-label={t('chartEmbed.point')}
        aria-valuemin={1}
        aria-valuemax={rows.length}
        aria-valuenow={activeIndex + 1}
        aria-valuetext={String(activeRow?.name ?? activeIndex + 1)}
        className="h-[48px] -mt-2 flex items-center gap-2 px-1 rounded-control cursor-pointer"
        onPointerDown={(e) => {
          const box = e.currentTarget.getBoundingClientRect();
          const ratio = (e.clientX - box.left) / Math.max(box.width, 1);
          setActive(Math.min(rows.length - 1, Math.max(0, Math.floor(ratio * rows.length))));
        }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowLeft') setActive(Math.max(0, activeIndex - 1));
          else if (e.key === 'ArrowRight') setActive(Math.min(rows.length - 1, activeIndex + 1));
          else return;
          e.preventDefault();
        }}
      >
        <span className="text-caption font-bold text-ink shrink-0">
          {String(activeRow?.name ?? '')}
        </span>
        <span className="flex-1 min-w-0 flex flex-wrap items-center gap-x-3 gap-y-0.5 justify-end">
          {keys.map((k) => {
            const v = Number(activeRow?.[k.key]);
            return (
              <span key={k.key} className="inline-flex items-center gap-1.5 text-caption whitespace-nowrap">
                <span
                  className="w-2 h-2 rounded-sm shrink-0"
                  style={{ backgroundColor: k.color || ACCENT }}
                  aria-hidden
                />
                <span className="text-ink-2">{k.label}</span>
                <span className="font-bold text-ink tnum">
                  {Number.isFinite(v) ? short(v, t, locale) : '—'}
                </span>
              </span>
            );
          })}
        </span>
      </div>

      {/* Висновок */}
      {data.takeaway && (
        <div className="flex items-center gap-2.5 p-3 bg-raised rounded-control text-footnote text-ink-2">
          <TrendingUp className="w-4 h-4 text-ink-3 shrink-0" strokeWidth={1.75} aria-hidden="true" />
          <p className="text-ink-2">{data.takeaway}</p>
        </div>
      )}
    </div>
  );
};

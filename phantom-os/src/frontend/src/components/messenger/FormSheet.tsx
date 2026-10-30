/**
 * Аркуш (вигляд D): папір `canvas` з верхньою лінією, кути 28, ручка 36×4,
 * рядок заголовка `title-1` з круглим «×», групи рядків на `surface`, одна
 * бурштинова дія внизу; `scrim` під ним. Заходить, виходить і відпускається
 * з-під пальця однією пружиною layer (useSheetDrag, LAYER_EXIT_MS).
 * Фокус лишається всередині; Esc, заслінка й BACK закривають.
 */
import { useTranslation } from '../../i18n/useTranslation';
import React, { useCallback, useEffect, useId, useLayoutEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { registerPhoneLayer } from '../../stores/phoneLayerStore';
import { useSheetDrag } from '../../hooks/useSheetDrag';
import { EXIT_MS, LAYER_EXIT_MS, useExitPresence } from '../../feel/motionKit';
import { playExitGhost } from '../../feel/exitGhost';
import { useEscapeClose } from '../../hooks/useEscapeClose';
import { ChevronLeft } from 'lucide-react';
import { SheetFrame } from './kit/SheetFrame';

const FOCUSABLE = 'a[href],button:not([disabled]),input:not([disabled]):not([type="hidden"]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

/** Tab ходить по колу всередині шару; закрили — фокус вертається туди, звідки відкрили. */
export function useFocusTrap(ref: React.RefObject<HTMLElement | null>, active: boolean): void {
  useEffect(() => {
    const root = ref.current;
    if (!active || !root) return;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const items = () => Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
    const dialog = root.matches('[role="dialog"]') ? root : root.querySelector<HTMLElement>('[role="dialog"]') ?? root;
    if (!root.contains(document.activeElement)) dialog.focus({ preventScroll: true });
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return;
      const list = items();
      if (list.length === 0) {
        e.preventDefault();
        return;
      }
      const i = list.indexOf(document.activeElement as HTMLElement);
      if (e.shiftKey ? i <= 0 : i === -1 || i === list.length - 1) {
        e.preventDefault();
        (e.shiftKey ? list[list.length - 1] : list[0]).focus();
      }
    };
    root.addEventListener('keydown', onKey);
    return () => {
      root.removeEventListener('keydown', onKey);
      if (opener?.isConnected) opener.focus({ preventScroll: true });
    };
  }, [ref, active]);
}

interface FormSheetProps {
  open: boolean;
  /** Без закривача аркуш не має «×»: його знімає власник. */
  onClose?: () => void;
  /** Корона аркуша (display) або звичайний заголовок (title). */
  title: string;
  crown?: boolean;
  children: React.ReactNode;
  /** Нижня смуга: одна головна дія. */
  footer?: React.ReactNode;
  /** Повна висота — для аркушів зі списками. */
  tall?: boolean;
  testId?: string;
  /** Крок назад усередині аркуша (двері → форма). */
  onBack?: () => void;
  /** Назва лише для читача екрана — коли корону несе сам вміст (профіль). */
  hideTitle?: boolean;
  /** Колонка ПК: аркуш лежить у потоці батька, без порталу, заслінки й свого «×». */
  inline?: boolean;
  /** Шар над дзвінком тощо: свій z-index замість `z-[1200]`. */
  layerClassName?: string;
  /** Префікс шару в стеку BACK. */
  layerKey?: string;
  /** Інфо-екран на весь екран: своя смуга «назад» у вмісті, без рамки аркуша й заслінки. */
  full?: boolean;
  /** Тихіша заслінка: аркуш-підказка поверх екрана, який людина ще читає. */
  softScrim?: boolean;
}

export const FormSheet: React.FC<FormSheetProps> = ({
  open,
  onClose,
  title,
  crown = false,
  children,
  footer,
  tall = false,
  testId,
  onBack,
  hideTitle = false,
  inline = false,
  layerClassName = 'z-[1200]',
  layerKey = 'sheet',
  full = false,
  softScrim = false,
}) => {
  const { t } = useTranslation();
  const id = useId();
  // Закривач — у ref: батьки передають нову стрілку щоразу, і реєстрація шару
  // в сторі на кожен рендер будила б їх знову (React #185, нескінченний цикл).
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  const backRef = useRef(onBack);
  backRef.current = onBack;
  // Системне «Назад» і тло на підсторінці знімають один крок, а не весь аркуш.
  const backOrClose = useCallback(() => (backRef.current ? backRef.current() : closeRef.current?.()), []);
  useEffect(() => registerPhoneLayer(`${layerKey}${id}`, open && !inline, backOrClose), [layerKey, id, open, inline, backOrClose]);
  useEscapeClose(open && !inline, backOrClose);
  // Закриття жестом уже доїхало до низу — другий вихід не потрібен.
  const draggedOut = useRef(false);
  // Вихід уже зіграно (жестом або класом f-sheet-out) — знімок при знятті не потрібен.
  const exitPlayed = useRef(false);
  const drag = useSheetDrag(() => {
    draggedOut.current = true;
    exitPlayed.current = true;
    closeRef.current?.();
  });
  // Аркуш виходить тією ж пружиною, що й заходить (TG-look 5): знімаємо, коли вона осіла.
  const presence = useExitPresence(open && !inline, draggedOut, full ? EXIT_MS : LAYER_EXIT_MS);
  // Колонка ПК: Escape знімає один крок усередині аркуша, а не всю колонку.
  const stepBack = useCallback(() => backRef.current?.(), []);
  useEscapeClose(open && inline && Boolean(onBack), stepBack);
  const layerRef = useRef<HTMLDivElement>(null);
  useFocusTrap(layerRef, open && !inline);
  if (presence.closing) exitPlayed.current = true;
  else if (open) exitPlayed.current = draggedOut.current;
  // Батько зняв відкритий аркуш з дерева: пружина layer доїжджає на знімку.
  const ghostable = !inline && !full;
  const lastLayer = useRef<HTMLDivElement | null>(null);
  useLayoutEffect(() => {
    if (layerRef.current) lastLayer.current = layerRef.current;
  });
  useLayoutEffect(() => {
    if (!ghostable) return undefined;
    return () => {
      if (!exitPlayed.current) playExitGhost(lastLayer.current);
    };
  }, [ghostable]);
  if (!open && !(presence.closing && !inline)) return null;

  if (inline) {
    return (
      <section aria-label={title} className={`flex-1 min-h-0 flex flex-col bg-surface text-ink font-sans ${onBack || !hideTitle ? '' : 'pt-3'}`} data-testid={testId}>
        {(onBack || !hideTitle) && (
          <div className={`shrink-0 min-h-12 pr-4 flex items-center gap-1 ${onBack ? 'pl-1' : 'pl-4'}`}>
            {onBack && (
              <button
                type="button"
                onClick={onBack}
                aria-label={t('formSheet.back')}
                className="w-11 h-11 min-h-0 min-w-0 shrink-0 flex items-center justify-center text-ink-2 rounded-full hover:bg-overlay active:bg-overlay"
              >
                <ChevronLeft className="w-5 h-5" strokeWidth={1.75} />
              </button>
            )}
            <h2 className={`flex-1 min-w-0 truncate text-ink ${crown ? 'text-title-1' : 'text-title-2'} ${hideTitle ? 'sr-only' : ''}`}>{title}</h2>
          </div>
        )}
        <div className="flex-1 min-h-0 overflow-y-auto no-scrollbar">{children}</div>
        {footer && <div className="shrink-0 px-4 pt-3 pb-4">{footer}</div>}
      </section>
    );
  }

  if (full) {
    return createPortal(
      <div
        ref={layerRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        className={`fixed inset-0 ${layerClassName} pt-[var(--sat,0px)] flex flex-col bg-canvas text-ink font-sans outline-none ${presence.closing ? 'f-fade-out' : 'f-fade-in'}`}
        data-testid={testId}
        data-full-sheet=""
      >
        <div className="flex-1 min-h-0 overflow-y-auto no-scrollbar pb-[var(--sab,0px)]">{children}</div>
      </div>,
      document.body,
    );
  }

  // Портал: шапка й колонки мають власний шар (transform/contain), і `fixed`
  // усередині них привʼязувався б до них, а не до екрана — композер лишався зверху.
  return createPortal(
    <div ref={layerRef} className={`fixed inset-0 ${layerClassName} flex flex-col justify-end font-sans`} data-testid={testId}>
      <div aria-hidden="true" onClick={backOrClose} className={`absolute inset-0 ${softScrim ? 'f-scrim-soft' : 'f-scrim'} ${presence.closing ? 'f-layer-scrim-out' : 'f-layer-scrim-in'}`} style={{ ...(drag.scrimStyle ?? {}), pointerEvents: 'auto' }} />
      <SheetFrame
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        className={`relative w-full md:max-w-[480px] md:mx-auto md:mb-[8vh] outline-none ${presence.closing ? 'f-sheet-out' : 'f-sheet-in'}`}
        style={drag.sheetStyle}
        title={title}
        crown={crown}
        hideTitle={hideTitle}
        tall={tall}
        onBack={onBack}
        backLabel={t('formSheet.back')}
        onClose={onClose}
        closeLabel={t('formSheet.close')}
        handleProps={drag.handlers}
        footer={footer}
      >
        {children}
      </SheetFrame>
    </div>,
    document.body,
  );
};

/** Рядок-двері: 48, іконка ink-2 20, відступ 16, слово subhead. */
export const DoorRow: React.FC<{
  icon?: React.ReactNode;
  label: string;
  hint?: string;
  tone?: 'ink' | 'danger';
  onClick: () => void;
  trailing?: React.ReactNode;
}> = ({ icon, label, hint, tone = 'ink', onClick, trailing }) => (
  <button
    type="button"
    onClick={onClick}
    className="w-full min-h-12 px-4 py-1.5 flex items-center gap-4 text-left hover:bg-overlay/60 active:bg-overlay transition-colors duration-quick ease-standard"
  >
    {icon && <span className={`w-5 shrink-0 flex items-center justify-center ${tone === 'danger' ? 'text-danger' : 'text-ink-2'}`}>{icon}</span>}
    <span className="flex-1 min-w-0">
      <span className={`block text-subhead ${tone === 'danger' ? 'text-danger' : 'text-ink'}`}>{label}</span>
      {hint && <span className="block text-footnote text-ink-3">{hint}</span>}
    </span>
    {trailing}
  </button>
);

/** Група рядків (вигляд D): `surface`, кути card, внутрішні лінії від тексту, не на всю ширину. */
export const DoorGroup: React.FC<{ children: React.ReactNode; className?: string; inset?: 'icon' | 'text' }> = ({ children, className = '', inset = 'icon' }) => {
  const rows = React.Children.toArray(children);
  if (rows.length === 0) return null;
  return (
    <div data-sheet-group className={`mx-4 rounded-card bg-surface overflow-hidden ${className}`}>
      {rows.map((row, i) => (
        <div key={React.isValidElement(row) && row.key != null ? row.key : i} className="relative">
          {i > 0 && <span aria-hidden="true" data-sheet-divider className={`absolute top-0 right-0 h-px bg-line ${inset === 'icon' ? 'left-[52px]' : 'left-4'}`} />}
          {row}
        </div>
      ))}
    </div>
  );
};

/** Одна головна дія екрана: 48, акцент; вимкнена — `f-raise` + `f-ink-3`. */
export const PrimaryButton: React.FC<{
  children: React.ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  type?: 'button' | 'submit';
  className?: string;
  testId?: string;
}> = ({ children, onClick, disabled, type = 'button', className = '', testId }) => (
  <button
    type={type}
    data-testid={testId}
    onClick={onClick}
    disabled={disabled}
    className={`w-full min-h-12 py-2 rounded-control text-headline transition-colors duration-instant ease-standard focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-line ${
      disabled ? 'bg-overlay text-ink-3' : 'bg-accent text-on-accent hover:bg-accent-hover active:bg-accent-press'
    } ${className}`}
  >
    {children}
  </button>
);

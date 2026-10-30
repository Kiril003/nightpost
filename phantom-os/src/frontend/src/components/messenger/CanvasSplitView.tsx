/**
 * Документ розмови (desk): блоки — текст, рішення, завдання, код, заголовок,
 * формула; три види — документ, дошка, схема. Живе в localStorage цього
 * пристрою (`phantom_canvas_v3_<chat>_<thread>`) і йде в розмову карткою
 * `widget:canvas` («У чат»). На телефон не їде: ChatArea вантажить його
 * ліниво лише в настільній збірці.
 *
 * 04.10 — та сама мова, що й решта месенджера: токени замість лісового
 * `#202E25`, `red-*`, `emerald-*`, `cyan-*` і `#5F6A60`; «Синхронізовано» із
 * зеленою крапкою (неправда — документ лише локальний) → «Лише на цьому
 * пристрої»; бейдж «Wasm Live Code» (нічого не виконував) прибрано; «вузол»,
 * «Canvas» і сирі `action-item` → слова людей.
 */
import { tNow, pickPlural } from '../chat/tNow';
import { clockLabel } from '../../utils/clockFormat';
import { useTranslation } from '../../i18n/useTranslation';
import React, { useState, useEffect, useRef } from 'react';
import {
  X,
  Plus,
  Copy,
  Check,
  Trash2,
  ChevronUp,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Maximize2,
  Minimize2,
  Send,
  RotateCcw,
  FileText,
  LayoutDashboard,
  Network,
  User,
  Code2,
  CheckSquare,
  Heading,
  Calculator,
  GripVertical,
} from 'lucide-react';
import { CanvasDocument, CanvasBlock, Message } from '../../types/messenger';
import { useMessengerStore } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';
import type { MessageKey } from '../../i18n/messages/uk';

/** Фрагмент, який людина послала «У документ» (виділення, аркуш дій). */
export interface CanvasInboxItem {
  id: string;
  text: string;
  type?: 'text' | 'decision' | 'action-item';
}

interface CanvasSplitViewProps {
  chatTitle: string;
  chatId?: string;
  threadId?: string;
  messages?: Message[];
  initialDoc?: CanvasDocument;
  onClose: () => void;
  onSave?: (doc: CanvasDocument) => void;
  widthMode?: 'half' | 'wide' | 'full';
  onToggleWidthMode?: (mode: 'half' | 'wide' | 'full') => void;
  /** Що чекає, щоб лягти в документ; забране повертається через `onInboxTaken`. */
  inbox?: CanvasInboxItem[];
  onInboxTaken?: (ids: string[]) => void;
}

export type ExtendedBlockType =
  | 'heading'
  | 'text'
  | 'decision'
  | 'action-item'
  | 'code'
  | 'callout'
  | 'math';

export interface ExtendedCanvasBlock extends Omit<CanvasBlock, 'type'> {
  type: ExtendedBlockType;
  priority?: 'urgent' | 'high' | 'med' | 'low';
  assignee?: string;
  dueDate?: string;
  status?: 'draft' | 'in_progress' | 'review' | 'approved' | 'rejected' | 'done';
  language?: string;
  x?: number;
  y?: number;
}

export const CanvasSplitView: React.FC<CanvasSplitViewProps> = ({
  chatTitle,
  chatId = 'current_chat',
  threadId = 'root_thread',
  messages: _messages = [],
  initialDoc,
  onClose,
  onSave: _onSave,
  widthMode = 'half',
  onToggleWidthMode,
  inbox,
  onInboxTaken,
}) => {
  const { t, locale } = useTranslation();
  const [docTitle, setDocTitle] = useState(initialDoc?.title || t('canvasSplitView.docTitleDefault', { chat: chatTitle }));
  const [viewMode, setViewMode] = useState<'doc' | 'board' | 'whiteboard'>('doc');
  const [copied, setCopied] = useState(false);
  const [published, setPublished] = useState(false);
  const [history, setHistory] = useState<ExtendedCanvasBlock[][]>([]);
  const [historyIdx, setHistoryIdx] = useState(0);

  // Dragging state for Whiteboard nodes
  const [dragNode, setDragNode] = useState<{ id: string; startX: number; startY: number; origX: number; origY: number } | null>(null);

  useEffect(() => {
    if (!dragNode) return;

    const handlePointerMove = (e: MouseEvent | TouchEvent) => {
      const clientX = 'touches' in e ? e.touches[0].clientX : e.clientX;
      const clientY = 'touches' in e ? e.touches[0].clientY : e.clientY;
      const dx = clientX - dragNode.startX;
      const dy = clientY - dragNode.startY;

      setBlocks((prev) =>
        prev.map((b) =>
          b.id === dragNode.id
            ? { ...b, x: Math.max(10, dragNode.origX + dx), y: Math.max(10, dragNode.origY + dy) }
            : b
        )
      );
    };

    const handlePointerUp = () => {
      setDragNode((current) => {
        if (current) {
          // Persist current state to history and storage on drag completion
          setBlocks((latest) => {
            pushHistory(latest);
            return latest;
          });
        }
        return null;
      });
    };

    window.addEventListener('mousemove', handlePointerMove);
    window.addEventListener('mouseup', handlePointerUp);
    window.addEventListener('touchmove', handlePointerMove, { passive: false });
    window.addEventListener('touchend', handlePointerUp);

    return () => {
      window.removeEventListener('mousemove', handlePointerMove);
      window.removeEventListener('mouseup', handlePointerUp);
      window.removeEventListener('touchmove', handlePointerMove);
      window.removeEventListener('touchend', handlePointerUp);
    };
  }, [dragNode]);

  const storageKey = `phantom_canvas_v3_${chatId}_${threadId}`;

  /**
   * Нова дошка порожня.
   *
   * Тут стояла засіяна: заголовок «Фінальні домовленості та архітектура»,
   * ухвалене рішення, два доручення на двох людей (одне вже з галочкою) і
   * сніпет коду. Людина відкривала дошку у СВОЇЙ розмові й бачила чужі
   * домовленості, яких ніхто не домовлявся: кожен рядок виглядав як робота
   * команди, а був літералом у коді.
   */
  const defaultBlocks: ExtendedCanvasBlock[] = [];

  const [blocks, setBlocks] = useState<ExtendedCanvasBlock[]>(() => {
    if (initialDoc && initialDoc.blocks) return initialDoc.blocks as ExtendedCanvasBlock[];
    try {
      const saved = localStorage.getItem(storageKey);
      if (saved) return JSON.parse(saved);
    } catch {}
    return defaultBlocks;
  });

  const pushHistory = (newBlocks: ExtendedCanvasBlock[]) => {
    const updated = history.slice(0, historyIdx + 1);
    updated.push(newBlocks);
    setHistory(updated);
    setHistoryIdx(updated.length - 1);
    setBlocks(newBlocks);
    try {
      localStorage.setItem(storageKey, JSON.stringify(newBlocks));
    } catch {}
  };

  const undo = () => {
    if (historyIdx > 0) {
      soundFx.playTap();
      const prev = history[historyIdx - 1];
      setHistoryIdx(historyIdx - 1);
      setBlocks(prev);
    }
  };

  const updateBlock = (id: string, updates: Partial<ExtendedCanvasBlock>) => {
    const next = blocks.map((b) => (b.id === id ? { ...b, ...updates, updatedAt: tNow('relClock.now') } : b));
    pushHistory(next);
  };

  const handleContentChange = (id: string, newContent: string) => {
    if (newContent.startsWith('/todo ') || newContent.startsWith('/task ')) {
      updateBlock(id, { type: 'action-item', content: newContent.replace(/^\/(todo|task)\s+/, ''), checked: false });
      return;
    }
    if (newContent.startsWith('/decision ') || newContent.startsWith('/d ')) {
      updateBlock(id, { type: 'decision', content: newContent.replace(/^\/(decision|d)\s+/, '') });
      return;
    }
    if (newContent.startsWith('/code ')) {
      updateBlock(id, { type: 'code', content: newContent.replace(/^\/code\s+/, '') });
      return;
    }
    if (newContent.startsWith('/h1 ') || newContent.startsWith('/heading ') || newContent.startsWith('# ')) {
      updateBlock(id, { type: 'heading', content: newContent.replace(/^(\/h1|\/heading|#)\s+/, '') });
      return;
    }
    if (newContent.startsWith('/quote ') || newContent.startsWith('> ')) {
      updateBlock(id, { type: 'callout', content: newContent.replace(/^(\/quote|>)\s+/, '') });
      return;
    }
    if (newContent.startsWith('/math ') || newContent.startsWith('$$ ')) {
      updateBlock(id, { type: 'math', content: newContent.replace(/^(\/math|\$\$)\s+/, '') });
      return;
    }
    updateBlock(id, { content: newContent });
  };

  // Скринька: кожен фрагмент лягає рівно раз (ref переживає подвійний
  // ефект StrictMode), і батько дізнається, що забрано.
  const takenRef = useRef<Set<string>>(new Set());
  useEffect(() => {
    const fresh = (inbox ?? []).filter((i) => i.text && !takenRef.current.has(i.id));
    if (fresh.length === 0) return;
    for (const i of fresh) takenRef.current.add(i.id);
    soundFx.playSend();
    const added: ExtendedCanvasBlock[] = fresh.map((i) => ({
      id: `b_${i.id}`,
      type: i.type || 'text',
      content: i.text,
      updatedAt: tNow('relClock.now'),
    }));
    setBlocks((prev) => {
      const next = [...prev, ...added];
      try {
        localStorage.setItem(storageKey, JSON.stringify(next));
      } catch {}
      return next;
    });
    onInboxTaken?.(fresh.map((i) => i.id));
  }, [inbox, storageKey, onInboxTaken]);

  const deleteBlock = (id: string) => {
    soundFx.playTap();
    const next = blocks.filter((b) => b.id !== id);
    pushHistory(next);
  };

  const moveBlock = (index: number, direction: 'up' | 'down') => {
    soundFx.playTap();
    const targetIdx = direction === 'up' ? index - 1 : index + 1;
    if (targetIdx < 0 || targetIdx >= blocks.length) return;
    const next = [...blocks];
    const [moved] = next.splice(index, 1);
    next.splice(targetIdx, 0, moved);
    pushHistory(next);
  };

  const addBlock = (type: ExtendedBlockType, afterIdx?: number) => {
    soundFx.playTap();
    const newB: ExtendedCanvasBlock = {
      id: `b_${Date.now()}_${Math.random().toString(36).substr(2, 4)}`,
      type,
      // Порожній блок — підказку дає placeholder, а не вигаданий вміст
      // («Новий заголовок», «Ухвалене рішення...», формула-зразок).
      content: '',
      checked: false,
      updatedAt: tNow('relClock.now'),
      x: 60 + ((blocks.length * 50) % 400),
      y: 60 + ((blocks.length * 70) % 350),
    };

    const next = [...blocks];
    if (afterIdx !== undefined && afterIdx >= 0) {
      next.splice(afterIdx + 1, 0, newB);
    } else {
      next.push(newB);
    }
    pushHistory(next);
  };

  // «AI Підсумок» тут складався з нічого — сталим реченням без жодного
  // виклику моделі (AUDIT №14). Кнопку й блок прибрано.

  const handlePublishToChat = () => {
    soundFx.playSend();
    const store = useMessengerStore.getState();
    const md = blocks.map((b) => b.content).join('\n\n');

    store.addCustomMessage({
      id: `msg_canvas_${Date.now()}`,
      senderId: store.currentUser.id,
      senderName: store.currentUser.name,
      senderAvatar: store.currentUser.avatar,
      timestamp: clockLabel(new Date()),
      type: 'widget:canvas',
      isSelf: true,
      canvasData: {
        id: `canvas_${Date.now()}`,
        threadId,
        conversationId: chatId,
        title: docTitle,
        rawMarkdown: md,
        decisionsCount: blocks.filter((b) => b.type === 'decision').length,
        openQuestionsCount: 0,
        updatedBy: store.currentUser.name,
        blocks: blocks.map((b) => ({
          id: b.id,
          type: b.type === 'heading' ? 'heading' : b.type === 'decision' ? 'decision' : b.type === 'action-item' ? 'action-item' : b.type === 'code' ? 'code' : 'text',
          content: b.content,
          checked: b.checked,
          updatedAt: b.updatedAt,
        })),
        lastUpdated: clockLabel(new Date()),
      },
    });

    setPublished(true);
    setTimeout(() => setPublished(false), 2000);
  };

  const copyMarkdown = () => {
    soundFx.playTap();
    const md = blocks.map((b) => b.content).join('\n\n');
    navigator.clipboard.writeText(md);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  // Колонки дошки
  const kanbanColumns = [
    { id: 'todo', title: t('canvasSplitView.todo'), status: 'draft' },
    { id: 'in_progress', title: t('canvasSplitView.inProgress'), status: 'in_progress' },
    { id: 'review', title: t('canvasSplitView.inReview'), status: 'review' },
    { id: 'done', title: t('canvasSplitView.done'), status: 'done' },
  ];

  const viewTab = (mode: 'doc' | 'board' | 'whiteboard', label: string, Icon: typeof FileText) => (
    <button
      type="button"
      onClick={() => {
        soundFx.playTap();
        setViewMode(mode);
      }}
      aria-pressed={viewMode === mode}
      title={label}
      className={`h-9 touch-floor-inline px-3 rounded-full flex items-center gap-1.5 text-subhead transition-colors duration-instant ease-standard ${
        viewMode === mode ? 'bg-overlay text-ink' : 'text-ink-2 hover:text-ink'
      }`}
    >
      <Icon className="w-4 h-4" strokeWidth={1.75} />
      <span>{label}</span>
    </button>
  );

  const ICON_BTN =
    'w-10 h-10 min-h-0 min-w-0 shrink-0 flex items-center justify-center rounded-full text-ink-2 hover:text-ink hover:bg-overlay transition-colors duration-instant ease-standard disabled:opacity-30';
  const SMALL_BTN =
    'w-8 h-8 touch-floor-inline flex items-center justify-center rounded-full text-ink-2 hover:text-ink hover:bg-overlay disabled:opacity-30';
  const FIELD = 'w-full bg-transparent text-ink placeholder:text-ink-3 focus:outline-none resize-none';

  const inserter: Array<{ type: ExtendedBlockType; label: string; Icon: typeof FileText }> = [
    { type: 'text', label: t('canvasSplitView.text'), Icon: FileText },
    { type: 'decision', label: t('canvasSplitView.decision'), Icon: Check },
    { type: 'action-item', label: t('canvasSplitView.task'), Icon: CheckSquare },
    { type: 'code', label: t('canvasSplitView.code'), Icon: Code2 },
    { type: 'heading', label: t('canvasSplitView.heading'), Icon: Heading },
    { type: 'math', label: t('canvasSplitView.formula'), Icon: Calculator },
  ];

  return (
    <div
      className="flex flex-col h-full bg-canvas text-ink select-text relative overflow-hidden"
      // Поля документа — сам текст на полотні (FIELD: bg-transparent). Нічне
      // правило messenger.css фарбує кожне input/textarea в --f-raise з
      // !important, і на полотні виходили сірі смуги під назвою й рішеннями.
      style={{ '--f-raise': 'transparent' } as React.CSSProperties}
    >
      {/* 1. Шапка: назва й дії, під ними — види. Двома рядами, бо панель
          поруч із розмовою вузька (~460 px): в один ряд назва стискалась до
          нуля, а «У чат» виїжджало за край. */}
      <div className="border-b border-line shrink-0">
      <div className="px-2 sm:px-3 h-14 flex items-center gap-2">
        <button type="button" onClick={onClose} className={`${ICON_BTN} md:hidden`} aria-label={t('canvasSplitView.backToChat')} title={t('canvasSplitView.backToChat')}>
          <ChevronLeft className="w-5 h-5" strokeWidth={1.75} />
        </button>
        <input
          type="text"
          value={docTitle}
          onChange={(e) => setDocTitle(e.target.value)}
          aria-label={t('canvasSplitView.docName')}
          className="flex-1 min-w-0 bg-transparent text-headline text-ink placeholder:text-ink-3 focus:outline-none truncate pl-2"
          placeholder={t('canvasSplitView.docName')}
        />


        <div className="flex items-center gap-0.5 shrink-0">
          <button type="button" onClick={undo} disabled={historyIdx === 0} className={ICON_BTN} aria-label={t('canvasSplitView.undo')} title={t('canvasSplitView.undo')}>
            <RotateCcw className="w-4 h-4" strokeWidth={1.75} />
          </button>
          <button type="button" onClick={copyMarkdown} className={ICON_BTN} aria-label={t('canvasSplitView.copyText')} title={t('canvasSplitView.copyText')}>
            {copied ? <Check className="w-4 h-4 text-success" strokeWidth={2} /> : <Copy className="w-4 h-4" strokeWidth={1.75} />}
          </button>
          {onToggleWidthMode && (
            <button
              type="button"
              onClick={() => onToggleWidthMode(widthMode === 'half' ? 'wide' : widthMode === 'wide' ? 'full' : 'half')}
              className={`${ICON_BTN} hidden sm:flex`}
              aria-label={t('canvasSplitView.panelWidth')}
              title={t('canvasSplitView.panelWidth')}
            >
              {widthMode === 'full' ? <Minimize2 className="w-4 h-4" strokeWidth={1.75} /> : <Maximize2 className="w-4 h-4" strokeWidth={1.75} />}
            </button>
          )}
          <button
            type="button"
            onClick={handlePublishToChat}
            className="h-10 touch-floor-inline px-4 ml-1 rounded-full bg-accent hover:bg-accent-hover active:bg-accent-press text-on-accent text-subhead font-semibold flex items-center gap-1.5 transition-colors duration-instant ease-standard"
            title={t('canvasSplitView.sendToChat')}
          >
            {published ? <Check className="w-4 h-4" strokeWidth={2} /> : <Send className="w-4 h-4" strokeWidth={1.75} />}
            <span>{published ? t('canvasSplitView.sent') : t('canvasSplitView.toChat')}</span>
          </button>
          <button type="button" onClick={onClose} className={ICON_BTN} aria-label={t('canvasSplitView.closeDoc')} title={t('canvasSplitView.closeDoc')}>
            <X className="w-5 h-5" strokeWidth={1.75} />
          </button>
        </div>
      </div>
      <div className="px-3 pb-2 flex">
        <div className="flex items-center gap-0.5 p-0.5 rounded-full bg-surface">
          {viewTab('doc', t('canvasSplitView.doc'), FileText)}
          {viewTab('board', t('canvasSplitView.board'), LayoutDashboard)}
          {viewTab('whiteboard', t('canvasSplitView.diagram'), Network)}
        </div>
      </div>
      </div>

      {/* 2. Вид */}

      {/* А) Документ — блоки один під одним */}
      {viewMode === 'doc' && (
        <div className="flex-1 min-h-0 px-4 sm:px-6 md:px-8 py-6 overflow-y-auto custom-scrollbar space-y-2 max-w-3xl mx-auto w-full">
          {blocks.length === 0 && (
            <p className="text-callout text-ink-2 py-6">
              {t('canvasSplitView.docEmpty')}
            </p>
          )}
          {blocks.map((block, idx) => (
            <div
              key={block.id}
              className="group relative flex items-start gap-2 p-2 rounded-card hover:bg-surface transition-colors duration-instant ease-standard"
            >
              {/* Рух і видалення — ліве поле, видно під курсором */}
              <div className="opacity-0 group-hover:opacity-100 focus-within:opacity-100 flex flex-col items-center transition-opacity select-none shrink-0">
                <button type="button" onClick={() => moveBlock(idx, 'up')} disabled={idx === 0} className={SMALL_BTN} aria-label={t('canvasSplitView.up')} title={t('canvasSplitView.up')}>
                  <ChevronUp className="w-4 h-4" strokeWidth={1.75} />
                </button>
                <button
                  type="button"
                  onClick={() => moveBlock(idx, 'down')}
                  disabled={idx === blocks.length - 1}
                  className={SMALL_BTN}
                  aria-label={t('canvasSplitView.down')}
                  title={t('canvasSplitView.down')}
                >
                  <ChevronDown className="w-4 h-4" strokeWidth={1.75} />
                </button>
                <button
                  type="button"
                  onClick={() => deleteBlock(block.id)}
                  className="w-8 h-8 touch-floor-inline flex items-center justify-center rounded-full text-danger hover:bg-danger/10"
                  aria-label={t('canvasSplitView.deleteBlock')}
                  title={t('canvasSplitView.deleteBlock')}
                >
                  <Trash2 className="w-4 h-4" strokeWidth={1.75} />
                </button>
              </div>

              <div className="flex-1 min-w-0">
                {block.type === 'heading' ? (
                  <input
                    type="text"
                    value={block.content}
                    onChange={(e) => handleContentChange(block.id, e.target.value)}
                    placeholder={t('canvasSplitView.heading')}
                    className={`${FIELD} text-title-2 py-1`}
                  />
                ) : block.type === 'decision' ? (
                  <div className="border-l-2 border-accent-line pl-3 py-2 rounded-r-card bg-accent-soft">
                    <span className="text-caption text-accent-text flex items-center gap-1 pb-0.5">
                      <Check className="w-3.5 h-3.5" strokeWidth={2} aria-hidden="true" />
                      {t('canvasSplitView.decisionMade')}
                    </span>
                    <textarea
                      value={block.content}
                      onChange={(e) => handleContentChange(block.id, e.target.value)}
                      rows={2}
                      className={`${FIELD} text-callout`}
                      placeholder={t('canvasSplitView.whatDecided')}
                    />
                  </div>
                ) : block.type === 'action-item' ? (
                  <div className="flex items-center gap-3 min-h-11 px-3 rounded-control bg-surface">
                    <input
                      type="checkbox"
                      checked={Boolean(block.checked || block.status === 'done')}
                      onChange={(e) => {
                        const checked = e.target.checked;
                        updateBlock(block.id, { checked, status: checked ? 'done' : 'in_progress' });
                      }}
                      aria-label={t('canvasSplitView.done')}
                      className="w-5 h-5 accent-f-accent cursor-pointer shrink-0"
                    />
                    <input
                      type="text"
                      value={block.content}
                      onChange={(e) => handleContentChange(block.id, e.target.value)}
                      placeholder={t('canvasSplitView.whatToDo')}
                      className={`flex-1 min-w-0 bg-transparent text-callout placeholder:text-ink-3 focus:outline-none ${
                        block.checked || block.status === 'done' ? 'line-through text-ink-3' : 'text-ink'
                      }`}
                    />
                    {block.assignee && (
                      <span className="text-caption text-ink-2 px-2 py-0.5 rounded-full bg-overlay shrink-0">{block.assignee}</span>
                    )}
                  </div>
                ) : block.type === 'code' ? (
                  <div className="w-full rounded-card bg-surface overflow-hidden my-1">
                    <div className="px-3 pt-2 text-caption text-ink-3">{block.language || t('canvasSplitView.code')}</div>
                    <textarea
                      value={block.content}
                      onChange={(e) => handleContentChange(block.id, e.target.value)}
                      rows={4}
                      spellCheck={false}
                      className={`${FIELD} font-mono text-footnote px-3 pb-3 pt-1 custom-scrollbar`}
                      placeholder={t('canvasSplitView.codePlaceholder')}
                    />
                  </div>
                ) : block.type === 'callout' ? (
                  <div className="p-3 rounded-card bg-surface">
                    <textarea
                      value={block.content}
                      onChange={(e) => handleContentChange(block.id, e.target.value)}
                      rows={2}
                      className={`${FIELD} text-callout text-ink-2`}
                      placeholder={t('canvasSplitView.note')}
                    />
                  </div>
                ) : block.type === 'math' ? (
                  <div className="p-3 rounded-card bg-surface">
                    <div className="text-caption text-ink-3 pb-1">{t('canvasSplitView.formula')}</div>
                    <textarea
                      value={block.content}
                      onChange={(e) => handleContentChange(block.id, e.target.value)}
                      rows={2}
                      spellCheck={false}
                      className={`${FIELD} font-mono text-footnote`}
                      placeholder="f(x) = …"
                    />
                  </div>
                ) : (
                  <textarea
                    value={block.content}
                    onChange={(e) => handleContentChange(block.id, e.target.value)}
                    rows={Math.max(1, Math.ceil((block.content.length || 1) / 60))}
                    className={`${FIELD} text-callout py-1`}
                    placeholder={t('canvasSplitView.textPlaceholder')}
                  />
                )}
              </div>
            </div>
          ))}

          {/* Додати блок */}
          <div className="pt-4 mt-2 border-t border-line">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-footnote text-ink-3 pr-1 flex items-center gap-1">
                <Plus className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
                {t('canvasSplitView.addBlockLabel')}
              </span>
              {inserter.map(({ type, label, Icon }) => (
                <button
                  key={type}
                  type="button"
                  onClick={() => addBlock(type)}
                  className="h-9 touch-floor-inline px-3 rounded-full bg-surface hover:bg-overlay text-subhead text-ink flex items-center gap-1.5 transition-colors duration-instant ease-standard"
                >
                  <Icon className="w-4 h-4 text-ink-2" strokeWidth={1.75} aria-hidden="true" />
                  <span>{label}</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* Б) Дошка — завдання за станом */}
      {viewMode === 'board' && (
        <div className="flex-1 min-h-0 p-4 md:p-6 overflow-x-auto overflow-y-hidden">
          <div className="flex gap-3 h-full min-w-[760px]">
            {kanbanColumns.map((col) => {
              const colBlocks = blocks.filter((b) => {
                if (col.id === 'done') return b.status === 'done' || b.checked;
                if (col.id === 'review') return b.status === 'review';
                if (col.id === 'in_progress') return b.status === 'in_progress' && !b.checked;
                return b.status === 'draft' || (!b.status && b.type === 'action-item' && !b.checked);
              });

              return (
                <div key={col.id} className="flex-1 bg-surface rounded-card p-2 flex flex-col h-full">
                  <div className="flex items-center justify-between px-2 pb-2">
                    <div className="flex items-center gap-2 text-subhead text-ink">
                      <span>{col.title}</span>
                      <span className="text-caption text-ink-3">{colBlocks.length}</span>
                    </div>
                    <button type="button" onClick={() => addBlock('action-item')} className={SMALL_BTN} aria-label={t('canvasSplitView.addTask')} title={t('canvasSplitView.addTask')}>
                      <Plus className="w-4 h-4" strokeWidth={1.75} />
                    </button>
                  </div>

                  <div className="flex-1 overflow-y-auto space-y-2 custom-scrollbar">
                    {colBlocks.map((b) => (
                      <div key={b.id} className="bg-raised p-3 rounded-control space-y-2 group text-ink">
                        <div className="flex items-center justify-between gap-1.5">
                          <span className={`text-caption ${b.type === 'decision' ? 'text-accent-text' : 'text-ink-3'}`}>
                            {t(BLOCK_WORD[b.type] ?? 'canvasSplitView.block')}
                          </span>
                          <button
                            type="button"
                            onClick={() => deleteBlock(b.id)}
                            className="w-8 h-8 touch-floor-inline flex items-center justify-center rounded-full opacity-0 group-hover:opacity-100 text-danger hover:bg-danger/10 transition-opacity"
                            aria-label={t('canvasSplitView.deleteCard')}
                            title={t('canvasSplitView.deleteCard')}
                          >
                            <Trash2 className="w-4 h-4" strokeWidth={1.75} />
                          </button>
                        </div>

                        <textarea
                          value={b.content}
                          onChange={(e) => handleContentChange(b.id, e.target.value)}
                          rows={2}
                          className={`${FIELD} text-callout`}
                          placeholder={t('canvasSplitView.whatToDo')}
                        />

                        <div className="flex items-center justify-between pt-1 text-caption text-ink-2">
                          {b.assignee ? (
                            <span className="flex items-center gap-1.5">
                              <User className="w-3.5 h-3.5" strokeWidth={1.75} aria-hidden="true" />
                              <span>{b.assignee}</span>
                            </span>
                          ) : (
                            <span />
                          )}

                          <div className="flex gap-1">
                            {col.id !== 'todo' && (
                              <button
                                type="button"
                                onClick={() => updateBlock(b.id, { status: col.id === 'done' ? 'review' : 'draft', checked: false })}
                                className="w-8 h-8 touch-floor-inline flex items-center justify-center rounded-full bg-overlay text-ink-2 hover:text-ink"
                                aria-label={t('canvasSplitView.stepBack')}
                                title={t('canvasSplitView.stepBack')}
                              >
                                <ChevronLeft className="w-4 h-4" strokeWidth={1.75} />
                              </button>
                            )}
                            {col.id !== 'done' && (
                              <button
                                type="button"
                                onClick={() => {
                                  const nextStatus = col.id === 'todo' ? 'in_progress' : col.id === 'in_progress' ? 'review' : 'done';
                                  updateBlock(b.id, { status: nextStatus as ExtendedCanvasBlock['status'], checked: nextStatus === 'done' });
                                }}
                                className="w-8 h-8 touch-floor-inline flex items-center justify-center rounded-full bg-accent hover:bg-accent-hover text-on-accent"
                                aria-label={t('canvasSplitView.stepForward')}
                                title={t('canvasSplitView.stepForward')}
                              >
                                <ChevronRight className="w-4 h-4" strokeWidth={1.75} />
                              </button>
                            )}
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* В) Схема — ті самі блоки, розкладені вільно */}
      {viewMode === 'whiteboard' && (
        <div className="flex-1 min-h-0 relative overflow-hidden bg-surface bg-[radial-gradient(var(--ph-line)_1px,transparent_1px)] [background-size:16px_16px]">
          <div className="absolute inset-0 p-6 overflow-auto custom-scrollbar">
            {/* Звʼязки між сусідніми блоками */}
            <svg className="absolute inset-0 w-[1200px] h-[900px] pointer-events-none stroke-line-strong stroke-2">
              {blocks.slice(0, -1).map((b, i) => {
                const next = blocks[i + 1];
                const x1 = (b.x || 100) + 120;
                const y1 = (b.y || 100) + 40;
                const x2 = (next.x || 200) + 120;
                const y2 = (next.y || 200) + 40;
                return <line key={`line_${b.id}_${next.id}`} x1={x1} y1={y1} x2={x2} y2={y2} strokeDasharray="4 4" />;
              })}
            </svg>

            {blocks.map((b) => (
              <div
                key={b.id}
                style={{
                  position: 'absolute',
                  left: `${b.x || 100}px`,
                  top: `${b.y || 100}px`,
                  width: '240px',
                }}
                className="bg-raised rounded-card p-3 shadow-e2 space-y-1.5 select-text z-10 text-ink"
              >
                <div
                  onMouseDown={(e) => {
                    if (e.button !== 0 || (e.target as HTMLElement).closest('button')) return;
                    e.preventDefault();
                    setDragNode({
                      id: b.id,
                      startX: e.clientX,
                      startY: e.clientY,
                      origX: b.x || 100,
                      origY: b.y || 100,
                    });
                  }}
                  onTouchStart={(e) => {
                    if ((e.target as HTMLElement).closest('button')) return;
                    const touch = e.touches[0];
                    setDragNode({
                      id: b.id,
                      startX: touch.clientX,
                      startY: touch.clientY,
                      origX: b.x || 100,
                      origY: b.y || 100,
                    });
                  }}
                  className="flex items-center justify-between gap-1 text-caption text-ink-3 cursor-grab active:cursor-grabbing select-none"
                >
                  <span className={`flex items-center gap-1 ${b.type === 'decision' ? 'text-accent-text' : ''}`}>
                    <GripVertical className="w-4 h-4" strokeWidth={1.75} aria-hidden="true" />
                    {t(BLOCK_WORD[b.type] ?? 'canvasSplitView.block')}
                  </span>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      deleteBlock(b.id);
                    }}
                    className="w-8 h-8 touch-floor-inline flex items-center justify-center rounded-full text-ink-3 hover:text-danger hover:bg-danger/10"
                    aria-label={t('canvasSplitView.deleteBlock')}
                    title={t('canvasSplitView.deleteBlock')}
                  >
                    <Trash2 className="w-4 h-4" strokeWidth={1.75} />
                  </button>
                </div>

                <textarea
                  value={b.content}
                  onChange={(e) => handleContentChange(b.id, e.target.value)}
                  rows={3}
                  className={`${FIELD} text-callout`}
                  placeholder={t('canvasSplitView.thought')}
                />
              </div>
            ))}

            <button
              type="button"
              onClick={() => addBlock('decision')}
              className="absolute bottom-6 right-6 z-20 h-11 min-h-0 px-4 rounded-full bg-accent hover:bg-accent-hover text-on-accent shadow-e2 text-subhead font-semibold flex items-center gap-1.5"
            >
              <Plus className="w-4 h-4" strokeWidth={2} aria-hidden="true" />
              <span>{t('canvasSplitView.newBlock')}</span>
            </button>
          </div>
        </div>
      )}

      {/* 3. Стан — правда: документ живе лише тут */}
      <div className="px-4 h-9 border-t border-line flex items-center justify-between text-caption text-ink-3 shrink-0">
        <span>
          {blocks.length}{' '}
          {t(pickPlural(locale, blocks.length, 'canvasSplitView.blockOne', 'canvasSplitView.blockFew', 'canvasSplitView.blockMany'))}
        </span>
        <span>{t('canvasSplitView.onlyHere')}</span>
      </div>
    </div>
  );
};

const BLOCK_WORD: Record<ExtendedBlockType, MessageKey> = {
  heading: 'canvasSplitView.heading',
  text: 'canvasSplitView.text',
  decision: 'canvasSplitView.decision',
  'action-item': 'canvasSplitView.task',
  code: 'canvasSplitView.code',
  callout: 'canvasSplitView.note',
  math: 'canvasSplitView.formula',
};

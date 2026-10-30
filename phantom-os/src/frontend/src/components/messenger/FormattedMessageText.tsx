import React, { useEffect, useRef, useState } from 'react';
import { useShallow } from 'zustand/react/shallow';
import { useTranslation } from '../../i18n/useTranslation';
import { Check, Copy, Eye, EyeOff } from 'lucide-react';
import { soundFx } from '../../utils/messengerSound';
import { readPeerWire } from '../../services/peerWire';
import { openTagSearch } from '../../services/tagSearch';
import { openGroupLink } from '../../services/groupLinks';
import { findAllMoney, type MoneyFinding } from '../../services/understand';
import { groupPayRef } from '../../services/money/payNotes';
import { solanaApi } from '../../services/solanaActions';
import { useMessengerStore } from '../../stores/messengerStore';
import { PeerWireBubble } from './PeerWireBubble';
import { GroupMoneySheet, type GroupMoneyPrefill } from './solana/GroupMoneySheet';
import { PayMoneySheet } from './solana/PayMoneySheet';

type PayMoneyProps = React.ComponentProps<typeof PayMoneySheet>;

type OpenSheet =
  | {
      kind: 'dm';
      peer: PayMoneyProps['peer'];
      cluster: PayMoneyProps['cluster'];
      chat: PayMoneyProps['chat'];
    }
  | {
      kind: 'group';
      chatId: string;
      prefill: GroupMoneyPrefill;
    };

interface FormattedMessageTextProps {
  text: string;
  onMentionClick?: (handleOrName: string) => void;
  searchQuery?: string;
  isActiveMatch?: boolean;
  /** Невидимий розпірник у кінці ОСТАННЬОГО рядка — місце під час бульбашки (§7.4). */
  tailSpace?: number;
  amountsTappable?: boolean;
  memberNames?: string[];
  members?: Array<{ name: string; handle?: string } | string>;
}

const escapeRegex = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

import { stripWorkTag } from '../../services/workEvents';

export const FormattedMessageText: React.FC<FormattedMessageTextProps> = ({
  text: rawText,
  onMentionClick,
  searchQuery,
  isActiveMatch,
  tailSpace = 0,
  amountsTappable = false,
  memberNames,
  members,
}) => {
  const { t } = useTranslation();
  const text = stripWorkTag(rawText);
  const [revealedSpoilers, setRevealedSpoilers] = useState<Record<number, boolean>>({});
  const [copiedBlocks, setCopiedBlocks] = useState<Record<number, boolean>>({});
  const [sheet, setSheet] = useState<OpenSheet | null>(null);
  const busyRef = useRef(false);
  const mountedRef = useRef(true);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pointerStartRef = useRef<{ x: number; y: number } | null>(null);
  const longPressedRef = useRef(false);

  const storeMemberNames = useMessengerStore(
    useShallow((s) => {
      if (!s.activeChatId) return [] as string[];
      const chat = s.chats.find((c) => c.id === s.activeChatId);
      return (chat?.members ?? []).map((m) => m.name).filter(Boolean);
    }),
  );

  const { activeChatId, isDm, peerId, peerName } = useMessengerStore(
    useShallow((s) => {
      if (!amountsTappable || !s.activeChatId) {
        return { activeChatId: null, isDm: false, peerId: null, peerName: null };
      }
      const chat = s.chats.find((c) => c.id === s.activeChatId);
      if (!chat) {
        return { activeChatId: null, isDm: false, peerId: null, peerName: null };
      }
      const chatType = chat.type as string | undefined;
      const direct = !chatType || chatType === 'direct' || chatType === 'dm';
      if (!direct) {
        return { activeChatId: chat.id, isDm: false, peerId: null, peerName: null };
      }
      const meId = s.currentUser?.id;
      const member = (chat.members ?? []).find((m) => !m.isMe && m.id !== meId);
      const id = chat.peerNodeId || member?.id || null;
      const name = id ? member?.name || chat.title || id : null;
      return { activeChatId: chat.id, isDm: true, peerId: id, peerName: name };
    }),
  );

  const clearPressTimer = () => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    pointerStartRef.current = null;
  };

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      clearPressTimer();
    };
  }, []);

  const closeSheet = () => {
    busyRef.current = false;
    if (mountedRef.current) {
      setSheet(null);
    }
  };

  const openAmount = async (hit: MoneyFinding & { raw: string }) => {
    if (!amountsTappable || !activeChatId || busyRef.current || sheet) return;
    if (!isDm) {
      if (!mountedRef.current) return;
      busyRef.current = true;
      soundFx.playTap();
      const prefill: GroupMoneyPrefill =
        hit.currency === 'USD'
          ? { token: 'USDC', total: String(hit.amount) }
          : { note: hit.raw };
      setSheet({ kind: 'group', chatId: activeChatId, prefill });
      return;
    }
    if (!peerId || !peerName) return;
    busyRef.current = true;
    soundFx.playTap();
    try {
      const res = await solanaApi.peer(peerId);
      if (!mountedRef.current) {
        busyRef.current = false;
        return;
      }
      if (res && res.can_pay && res.address) {
        const prefill =
          hit.currency === 'USD'
            ? { token: 'USDC' as const, amount: String(hit.amount) }
            : hit.currency === 'EUR'
              ? { token: 'EURC' as const, amount: String(hit.amount) }
              : { note: hit.raw };
        setSheet({
          kind: 'dm',
          peer: { id: peerId, name: peerName },
          cluster: res.cluster,
          chat: {
            id: activeChatId,
            ref: groupPayRef(),
            ...prefill,
          },
        });
      } else {
        busyRef.current = false;
      }
    } catch {
      busyRef.current = false;
    }
  };

  const toggleSpoiler = (index: number, e: React.MouseEvent) => {
    e.stopPropagation();
    soundFx.playTap();
    setRevealedSpoilers((prev) => ({
      ...prev,
      [index]: !prev[index],
    }));
  };

  /**
   * Тіло кадру меша ніколи не доходить до розбору тексту.
   *
   * Це єдині двері, якими ChatArea пускає слова в бульбашку, — і саме тому
   * заборона стоїть тут: інакше кожен новий рід кадру доводилось би вимикати
   * в кількох місцях, і одне з них лишалось би з сирим дротом на склі, як
   * маршрут 04.09.
   *
   * Розібране мостом стає карткою, сире — чесним реченням, керівне — нічим.
   */
  const wire = readPeerWire(text);

  const highlightContent = (content: string) => {
    if (!searchQuery || !searchQuery.trim()) return content;
    const escaped = searchQuery.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const regex = new RegExp(`(${escaped})`, 'gi');
    const parts = content.split(regex);
    if (parts.length === 1) return content;

    // Підсвітка пошуку: активний збіг — золотий акцент, решта — тиха обсидіанова підкладка.
    const highlightStyle = isActiveMatch
      ? 'bg-accent text-on-accent font-bold rounded-sm px-0.5'
      : 'bg-accent/25 text-accent-text border border-accent-line/30 rounded-sm px-0.5';

    return parts.map((part, idx) =>
      regex.test(part) ? (
        <mark key={idx} className={highlightStyle}>
          {part}
        </mark>
      ) : (
        part
      )
    );
  };

  const renderPlainText = (token: string, uniqueKey: string, inFormatting: boolean): React.ReactNode => {
    if (inFormatting || !amountsTappable || !activeChatId) {
      return <React.Fragment key={uniqueKey}>{highlightContent(token)}</React.Fragment>;
    }
    const moneyHits = findAllMoney(token);
    if (moneyHits.length === 0) {
      return <React.Fragment key={uniqueKey}>{highlightContent(token)}</React.Fragment>;
    }
    const segments: React.ReactNode[] = [];
    let cursor = 0;
    moneyHits.forEach((hit, idx) => {
      if (hit.index > cursor) {
        segments.push(
          <React.Fragment key={`${uniqueKey}-t-${idx}`}>
            {highlightContent(token.slice(cursor, hit.index))}
          </React.Fragment>,
        );
      }
      segments.push(
        <span
          key={`${uniqueKey}-m-${idx}`}
          role="button"
          tabIndex={0}
          data-amount={hit.currency}
          title={t('amountTap.pay', { amount: hit.raw })}
          onClick={(e) => {
            e.stopPropagation();
            if (longPressedRef.current) {
              longPressedRef.current = false;
              return;
            }
            void openAmount(hit);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.stopPropagation();
              if (e.repeat) return;
              void openAmount(hit);
            }
          }}
          onPointerDown={(e) => {
            e.stopPropagation();
            clearPressTimer();
            longPressedRef.current = false;
            if (busyRef.current) return;
            pointerStartRef.current = { x: e.clientX, y: e.clientY };
            timerRef.current = setTimeout(() => {
              timerRef.current = null;
              pointerStartRef.current = null;
              if (busyRef.current) return;
              longPressedRef.current = true;
              void openAmount(hit);
            }, 500);
          }}
          onPointerUp={(e) => {
            e.stopPropagation();
            clearPressTimer();
          }}
          onPointerLeave={(e) => {
            e.stopPropagation();
            clearPressTimer();
          }}
          onPointerCancel={(e) => {
            e.stopPropagation();
            clearPressTimer();
          }}
          onPointerMove={(e) => {
            e.stopPropagation();
            if (timerRef.current !== null && pointerStartRef.current) {
              const dx = e.clientX - pointerStartRef.current.x;
              const dy = e.clientY - pointerStartRef.current.y;
              if (Math.hypot(dx, dy) > 10) {
                clearPressTimer();
              }
            }
          }}
          onContextMenu={(e) => {
            e.preventDefault();
            e.stopPropagation();
          }}
          className="font-medium text-accent-text cursor-pointer underline underline-offset-2 touch-manipulation [-webkit-touch-callout:none]"
          style={{ touchAction: 'manipulation', WebkitTouchCallout: 'none' }}
        >
          {highlightContent(hit.raw)}
        </span>,
      );
      cursor = hit.index + hit.raw.length;
    });
    if (cursor < token.length) {
      segments.push(
        <React.Fragment key={`${uniqueKey}-tail`}>
          {highlightContent(token.slice(cursor))}
        </React.Fragment>,
      );
    }
    return <React.Fragment key={uniqueKey}>{segments}</React.Fragment>;
  };

  const renderFormattedSegments = () => {
    const knownNames = [
      ...(memberNames ?? []),
      ...(members ?? []).map((m) => (typeof m === 'string' ? m : m.name)),
      ...storeMemberNames,
    ]
      .map((n) => n.trim())
      .filter((n) => n.length > 0);
    const uniqueNames = [...new Set(knownNames)].sort((a, b) => b.length - a.length);
    const mentionSource =
      uniqueNames.length > 0
        ? `(?<![\\p{L}\\p{N}_])@(?:${uniqueNames.map(escapeRegex).join('|')}|[\\p{L}\\p{N}_]+)(?![\\p{L}\\p{N}_])`
        : '(?<![\\p{L}\\p{N}_])@[\\p{L}\\p{N}_]+';

    const inlineSource = [
      '`[^`\\n]+`',
      '\\|\\|.+?\\|\\|',
      '\\[[^\\]\\n]+\\]\\([^)\\s]+\\)',
      'https?:\\/\\/[^\\s]+',
      '\\*\\*(?=\\S).+?(?<=\\S)\\*\\*',
      '(?<![\\p{L}\\p{N}])__(?=\\S).+?(?<=\\S)__(?![\\p{L}\\p{N}])',
      '~~(?=\\S).+?(?<=\\S)~~',
      '(?<!~)~(?=\\S)[^~\\n]+?(?<=\\S)~(?!~)',
      '(?<!\\*)\\*(?=\\S)[^*\\n]+?(?<=\\S)\\*(?!\\*)',
      '(?<![\\p{L}\\p{N}_])_(?=[^\\s_]).+?(?<=[^\\s_])_(?![\\p{L}\\p{N}_])',
      mentionSource,
      '(?<![\\p{L}\\p{N}_])#[a-zA-Z0-9_\\u0400-\\u04FF]+',
    ].join('|');

    let spoilerSeq = 0;

    const parseInline = (input: string, keyPrefix: string, inFormatting = false): React.ReactNode[] => {
      const rx = new RegExp(inlineSource, 'gu');
      const nodes: React.ReactNode[] = [];
      let lastIdx = 0;
      let match: RegExpExecArray | null;

      while ((match = rx.exec(input)) !== null) {
        if (match.index > lastIdx) {
          nodes.push(renderPlainText(input.slice(lastIdx, match.index), `${keyPrefix}-p-${lastIdx}`, inFormatting));
        }
        const token = match[0];
        const uniqueKey = `${keyPrefix}-t-${match.index}`;

        if (token.startsWith('`') && token.endsWith('`') && token.length >= 2) {
          nodes.push(
            <code
              key={uniqueKey}
              className="bg-overlay text-ink px-1 py-0.5 rounded-chip font-mono text-footnote select-text"
            >
              {highlightContent(token.slice(1, -1))}
            </code>,
          );
        } else if (token.startsWith('||') && token.endsWith('||') && token.length >= 4) {
          const spoilerContent = token.slice(2, -2);
          const idx = spoilerSeq++;
          const isRevealed = !!revealedSpoilers[idx];
          nodes.push(
            <span
              key={uniqueKey}
              onClick={(e) => toggleSpoiler(idx, e)}
              className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded cursor-pointer transition-all select-none ${
                isRevealed
                  ? 'bg-raised text-ink'
                  : 'bg-raised text-transparent blur-[4px] hover:blur-[2px]'
              }`}
              title={isRevealed ? t('formattedMessageText.spoilerHide') : t('formattedMessageText.spoilerShow')}
            >
              <span>{isRevealed ? parseInline(spoilerContent, uniqueKey, true) : spoilerContent}</span>
              {isRevealed ? (
                <EyeOff className="w-3.5 h-3.5 inline text-ink-3" strokeWidth={1.75} />
              ) : (
                <Eye className="w-3.5 h-3.5 inline text-ink-3" strokeWidth={1.75} />
              )}
            </span>,
          );
        } else if (token.startsWith('[')) {
          const linkMatch = /^\[([^\][\n]+)\]\(([^)\s]+)\)$/.exec(token);
          const label = linkMatch?.[1] ?? '';
          const url = linkMatch?.[2]?.trim() ?? '';
          if (linkMatch && /^(https?:\/\/|mailto:)/i.test(url)) {
            nodes.push(
              <a
                key={uniqueKey}
                href={url}
                target="_blank"
                rel="noopener noreferrer"
                onClick={(e) => {
                  e.stopPropagation();
                  if (openGroupLink(url)) e.preventDefault();
                }}
                className="inline underline underline-offset-2 break-all text-accent-text hover:underline transition-colors"
              >
                {parseInline(label, uniqueKey, true)}
              </a>,
            );
          } else {
            nodes.push(renderPlainText(token, uniqueKey, inFormatting));
          }
        } else if (token.startsWith('http://') || token.startsWith('https://')) {
          nodes.push(
            <a
              key={uniqueKey}
              href={token}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => {
                e.stopPropagation();
                if (openGroupLink(token)) e.preventDefault();
              }}
              className="inline underline underline-offset-2 break-all text-accent-text hover:underline transition-colors"
            >
              {highlightContent(token)}
            </a>,
          );
        } else if (token.startsWith('**') && token.endsWith('**') && token.length >= 4) {
          nodes.push(
            <strong key={uniqueKey} className="font-bold text-ink">
              {parseInline(token.slice(2, -2), uniqueKey, true)}
            </strong>,
          );
        } else if (token.startsWith('__') && token.endsWith('__') && token.length >= 4) {
          nodes.push(
            <u key={uniqueKey} className="underline underline-offset-2">
              {parseInline(token.slice(2, -2), uniqueKey, true)}
            </u>,
          );
        } else if (token.startsWith('~~') && token.endsWith('~~') && token.length >= 4) {
          nodes.push(
            <del key={uniqueKey} className="line-through text-ink-3">
              {parseInline(token.slice(2, -2), uniqueKey, true)}
            </del>,
          );
        } else if (token.startsWith('~') && token.endsWith('~') && token.length >= 2) {
          nodes.push(
            <del key={uniqueKey} className="line-through text-ink-3">
              {parseInline(token.slice(1, -1), uniqueKey, true)}
            </del>,
          );
        } else if (
          (token.startsWith('*') && token.endsWith('*') && token.length >= 2) ||
          (token.startsWith('_') && token.endsWith('_') && token.length >= 2)
        ) {
          nodes.push(
            <em key={uniqueKey} className="italic text-ink-2">
              {parseInline(token.slice(1, -1), uniqueKey, true)}
            </em>,
          );
        } else if (token.startsWith('@') && token.length > 1) {
          const handle = token.slice(1);
          nodes.push(
            <button
              type="button"
              key={uniqueKey}
              onClick={(e) => {
                e.stopPropagation();
                soundFx.playTap();
                onMentionClick?.(handle);
              }}
              className="inline font-semibold text-accent-text hover:underline underline-offset-2 transition-colors align-baseline"
              title={t('formattedMessageText.viewProfile', { handle })}
            >
              @{highlightContent(handle)}
            </button>,
          );
        } else if (token.startsWith('#') && token.length > 1) {
          const tag = token.slice(1);
          const open = (e: React.SyntheticEvent) => {
            e.stopPropagation();
            soundFx.playTap();
            openTagSearch(tag);
          };
          nodes.push(
            <span
              key={uniqueKey}
              role="link"
              tabIndex={0}
              data-hashtag={tag.toLowerCase()}
              onClick={open}
              onKeyDown={(e) => {
                if (e.key === 'Enter') open(e);
              }}
              className="font-medium text-accent-text cursor-pointer hover:underline underline-offset-2"
              title={t('formattedMessageText.searchTag', { tag })}
            >
              #{highlightContent(tag)}
            </span>,
          );
        } else {
          nodes.push(renderPlainText(token, uniqueKey, inFormatting));
        }

        lastIdx = match.index + token.length;
      }

      if (lastIdx < input.length) {
        nodes.push(renderPlainText(input.slice(lastIdx), `${keyPrefix}-p-${lastIdx}`, inFormatting));
      }
      return nodes;
    };

    type Block =
      | { kind: 'code'; code: string }
      | { kind: 'quote'; lines: string[] }
      | { kind: 'paragraph'; lines: string[] };

    const blocks: Block[] = [];
    const pushTextSegment = (segment: string) => {
      const lines = segment.split('\n');
      for (const line of lines) {
        const isBlockquote = line.startsWith('> ') || line === '>';
        if (isBlockquote) {
          const qLine = line.startsWith('> ') ? line.slice(2) : '';
          const prev = blocks[blocks.length - 1];
          if (prev?.kind === 'quote') {
            prev.lines.push(qLine);
          } else {
            blocks.push({ kind: 'quote', lines: [qLine] });
          }
        } else {
          const prev = blocks[blocks.length - 1];
          if (prev?.kind === 'paragraph') {
            prev.lines.push(line);
          } else {
            blocks.push({ kind: 'paragraph', lines: [line] });
          }
        }
      }
    };

    const fenceRx = /```([\s\S]*?)```/g;
    let cursor = 0;
    let fenceMatch: RegExpExecArray | null;
    while ((fenceMatch = fenceRx.exec(text)) !== null) {
      if (fenceMatch.index > cursor) {
        const before = text.slice(cursor, fenceMatch.index).replace(/\r?\n$/, '');
        if (before.length > 0 || cursor === 0 && fenceMatch.index > 0) {
          pushTextSegment(before);
        }
      }
      let codeText = fenceMatch[1];
      if (/^\r?\n/.test(codeText)) {
        codeText = codeText.replace(/^\r?\n/, '').replace(/\r?\n$/, '');
      } else if (/^[a-zA-Z0-9_+-]{1,20}[ \t]*\r?\n[\s\S]*\r?\n$/.test(codeText)) {
        codeText = codeText.replace(/^[a-zA-Z0-9_+-]{1,20}[ \t]*\r?\n/, '').replace(/\r?\n$/, '');
      }
      blocks.push({ kind: 'code', code: codeText });
      cursor = fenceMatch.index + fenceMatch[0].length;
    }

    if (cursor < text.length) {
      const after = cursor > 0 ? text.slice(cursor).replace(/^\r?\n/, '') : text;
      if (after.length > 0 || blocks.length === 0) {
        pushTextSegment(after);
      }
    } else if (blocks.length === 0) {
      pushTextSegment('');
    }

    return blocks.map((block, blockIdx) => {
      if (block.kind === 'code') {
        const isCopied = !!copiedBlocks[blockIdx];
        const copyLabel = isCopied ? t('messageEmbeds.copied') : t('messageEmbeds.copy');
        return (
          <div
            key={blockIdx}
            className={`relative rounded-control bg-overlay text-ink ${blockIdx > 0 ? 'mt-1.5' : ''}`}
          >
            <button
              type="button"
              data-testid="code-copy"
              aria-label={copyLabel}
              title={copyLabel}
              onClick={(e) => {
                e.stopPropagation();
                soundFx.playTap();
                void navigator.clipboard?.writeText(block.code);
                setCopiedBlocks((prev) => ({ ...prev, [blockIdx]: true }));
              }}
              className="w-11 h-11 min-w-[44px] min-h-[44px] absolute top-0.5 right-0.5 rounded-control flex items-center justify-center text-ink-2 hover:bg-raised hover:text-ink transition-colors"
            >
              {isCopied ? (
                <Check className="w-4 h-4" strokeWidth={1.75} aria-hidden />
              ) : (
                <Copy className="w-4 h-4" strokeWidth={1.75} aria-hidden />
              )}
            </button>
            <pre className="p-3 pr-12 font-mono text-footnote overflow-x-auto whitespace-pre select-text">
              <code>{highlightContent(block.code)}</code>
            </pre>
          </div>
        );
      }

      if (block.kind === 'quote') {
        return (
          <blockquote
            key={blockIdx}
            className={`border-l-[3px] border-accent-line bg-overlay/60 pl-3 py-1 text-ink-2 rounded-chip whitespace-pre-wrap ${
              blockIdx > 0 ? 'mt-1.5' : ''
            }`}
          >
            {block.lines.map((line, lineIdx) => (
              <React.Fragment key={lineIdx}>
                {lineIdx > 0 && '\n'}
                {parseInline(line, `q-${blockIdx}-${lineIdx}`)}
              </React.Fragment>
            ))}
          </blockquote>
        );
      }

      const last = blockIdx === blocks.length - 1;
      return (
        <p key={blockIdx} className={`break-words whitespace-pre-wrap ${blockIdx > 0 ? 'mt-1.5' : ''}`}>
          {block.lines.map((line, lineIdx) => (
            <React.Fragment key={lineIdx}>
              {lineIdx > 0 && '\n'}
              {parseInline(line, `p-${blockIdx}-${lineIdx}`)}
            </React.Fragment>
          ))}
          {last && tailSpace > 0 && (
            <span aria-hidden data-meta-space className="inline-block align-baseline" style={{ width: tailSpace, height: 1 }} />
          )}
        </p>
      );
    });
  };

  if (wire) return <PeerWireBubble wire={wire} />;

  return (
    <>
      <div className="select-text break-words text-ink">{renderFormattedSegments()}</div>
      {sheet?.kind === 'dm' && (
        <PayMoneySheet
          peer={sheet.peer}
          cluster={sheet.cluster}
          chat={sheet.chat}
          onClose={closeSheet}
        />
      )}
      {sheet?.kind === 'group' && (
        <GroupMoneySheet
          chatId={sheet.chatId}
          prefill={sheet.prefill}
          onClose={closeSheet}
        />
      )}
    </>
  );
};

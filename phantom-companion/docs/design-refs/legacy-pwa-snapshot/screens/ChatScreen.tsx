// Full text-chat surface — the operator's primary "talk to PHANTOM
// from the phone" surface. Text input + image paste + file pick +
// share-target landing. Sends through `chatTurn()` which respects the
// hybrid AI policy (host vs queued) and lands on `/api/v1/chat/message`.
//
// Why this lives at /chat (and is also offered as the / fallback):
// the existing NowScreen is a dashboard — vitals, predictions,
// inbox glances. It is not where you type to PHANTOM. ChatScreen is.
// Shares from outside apps (intent ACTION_SEND) land here too,
// pre-filling the composer with the shared text/file.

import { useEffect, useRef, useState } from 'react';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { PrimaryActionBar } from '@/components/PrimaryActionBar';
import { Banner } from '@/components/Banner';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useStateAccent } from '@/hooks/useStateAccent';
import { useChatStore, type ChatAttachment, type ChatTurn } from '@/state/chatStore';
import { chatTurn, isHostUnreachable, type ChatTurnResult } from '@/services/aiRouter';
import { useToastStore } from '@/state/toastStore';
import { useDeviceStore } from '@/state/deviceStore';
import { recognizeOnce, isSpeechRecognitionAvailable, type RecognizeHandle } from '@/services/webSpeech';
import { driveUpload } from '@/services/driveClient';
import type { PhantomTheme } from '@/types/theme';

interface ChatScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

const MAX_INLINE_BYTES = 256 * 1024;

function makeId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return (crypto as Crypto).randomUUID();
  }
  return `t-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

export function ChatScreen({ themeOverride, framed = false }: ChatScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const { accent } = useStateAccent();
  const turns = useChatStore((s) => s.turns);
  const draft = useChatStore((s) => s.draft);
  const draftAttachments = useChatStore((s) => s.draftAttachments);
  const setDraft = useChatStore((s) => s.setDraft);
  const addDraftAttachment = useChatStore((s) => s.addDraftAttachment);
  const removeDraftAttachment = useChatStore((s) => s.removeDraftAttachment);
  const pushTurn = useChatStore((s) => s.pushTurn);
  const patchTurn = useChatStore((s) => s.patchTurn);
  const clearDraft = useChatStore((s) => s.clearDraft);
  const sessionId = useChatStore((s) => s.sessionId);
  const setSessionId = useChatStore((s) => s.setSessionId);
  const pairStatus = useDeviceStore((s) => s.status);
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const recHandleRef = useRef<RecognizeHandle | null>(null);
  const [listening, setListening] = useState(false);
  const sttAvailable = isSpeechRecognitionAvailable();

  useEffect(() => {
    // Auto-scroll to bottom whenever turns or pending status changes.
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [turns.length]);

  // Paste handler — triggers when a user pastes into the textarea.
  const onPaste = async (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const items = Array.from(e.clipboardData?.items ?? []);
    for (const item of items) {
      if (item.kind === 'file') {
        const file = item.getAsFile();
        if (file) {
          await ingestFile(file, addDraftAttachment);
          e.preventDefault();
        }
      }
    }
  };

  const onFilePicked = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    for (const f of files) {
      await ingestFile(f, addDraftAttachment);
    }
    e.target.value = '';
  };

  const onMic = async () => {
    if (listening) {
      recHandleRef.current?.cancel();
      recHandleRef.current = null;
      setListening(false);
      return;
    }
    if (!sttAvailable) {
      useToastStore.getState().push('warn', 'Голосовий ввід недоступний у цьому браузері');
      return;
    }
    setListening(true);
    const handle = recognizeOnce({ lang: 'uk-UA' });
    recHandleRef.current = handle;
    try {
      const result = await handle.promise;
      const fragment = result.text.trim();
      if (fragment) {
        const merged = draft ? `${draft} ${fragment}` : fragment;
        setDraft(merged);
        textareaRef.current?.focus();
      }
    } catch (err) {
      useToastStore.getState().push('warn', `Голос: ${(err as Error).message}`);
    } finally {
      recHandleRef.current = null;
      setListening(false);
    }
  };

  const onSend = async () => {
    const text = draft.trim();
    if (!text && draftAttachments.length === 0) return;
    if (pairStatus !== 'paired') {
      useToastStore.getState().push('warn', 'Не спарено — спочатку привʼяжи phantom-os');
      return;
    }

    const userTurn: ChatTurn = {
      id: makeId(),
      role: 'user',
      text,
      attachments: draftAttachments,
      ts: Date.now(),
      sessionId,
    };
    pushTurn(userTurn);

    // Pending placeholder for assistant.
    const pendingId = makeId();
    pushTurn({
      id: pendingId,
      role: 'assistant',
      text: '',
      attachments: [],
      ts: Date.now(),
      pending: true,
      sessionId,
    });

    clearDraft();

    try {
      // Upload binary attachments to /api/v1/drive/upload first; the
      // chat message then references their server label so the desktop
      // can re-attach them instead of guessing from a filename.
      const uploadedRefs: string[] = [];
      for (const a of userTurn.attachments) {
        if (!a.bytes || a.bytes.byteLength === 0) continue;
        try {
          const blob = new Blob([new Uint8Array(a.bytes)], { type: a.mime });
          (blob as Blob & { name?: string }).name = a.name;
          const res = await driveUpload(blob, `chat:${a.name}`);
          uploadedRefs.push(`[uploaded: ${a.name} · ${res.saved_path ?? '?'}]`);
        } catch (uploadErr) {
          uploadedRefs.push(`[upload-failed: ${a.name} · ${(uploadErr as Error).message}]`);
        }
      }

      const attachmentLine = [
        ...userTurn.attachments
          .filter((a) => !a.bytes || a.bytes.byteLength === 0)
          .map((a) => `[attached: ${a.name} · ${a.mime}${a.text ? ` · ${a.text.slice(0, 80)}` : ''}]`),
        ...uploadedRefs,
      ].join('\n');
      const fullText = attachmentLine ? `${text}\n\n${attachmentLine}` : text;

      const result: ChatTurnResult = await chatTurn({
        text: fullText,
        sessionId,
      });
      patchTurn(pendingId, {
        text: result.text || '(порожня відповідь)',
        pending: false,
        via: result.provider ?? result.backend,
      });
    } catch (err) {
      patchTurn(pendingId, {
        text: isHostUnreachable(err) ? '⚠️ phantom-os недоступний.' : `⚠️ ${(err as Error).message}`,
        pending: false,
      });
    }
  };

  const onRetry = async (turn: ChatTurn) => {
    if (pairStatus !== 'paired') {
      useToastStore.getState().push('warn', 'Не спарено');
      return;
    }
    patchTurn(turn.id, { text: '', pending: true });
    try {
      const result: ChatTurnResult = await chatTurn({
        text: turn.text.replace(/^⚠️[^\n]*\n?/, '') || 'continue',
        sessionId,
      });
      patchTurn(turn.id, {
        text: result.text || '(порожня відповідь)',
        pending: false,
        via: result.provider ?? result.backend,
      });
    } catch (err) {
      patchTurn(turn.id, {
        text: isHostUnreachable(err) ? '⚠️ phantom-os недоступний.' : `⚠️ ${(err as Error).message}`,
        pending: false,
      });
    }
  };

  // Cleanup recognition on unmount.
  useEffect(() => {
    return () => {
      recHandleRef.current?.cancel();
      recHandleRef.current = null;
    };
  }, []);

  // Note: backend doesn't return session_id from /chat/message yet
  // in the response shape we use; we keep using the existing sessionId.
  void setSessionId;

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        ref={scrollRef}
        style={{
          flex: 1,
          padding: '8px 12px 8px',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
          minHeight: 0,
          overflowY: 'auto',
        }}
      >
        {pairStatus !== 'paired' && (
          <Banner
            theme={theme}
            kind="warn"
            message="Без phantom-os AI не відповідає. Спарити з десктопу?"
            action={{
              label: 'Спарити',
              onPress: () => (window.location.href = '/pair'),
            }}
            pinned
          />
        )}

        {turns.length === 0 ? (
          <EmptyState
            theme={theme}
            icon="◌"
            title="чат із phantom"
            hint="Напиши, скинь скрін, перепиши задачу. Phantom відповідає голосом і дією."
          />
        ) : (
          turns.map((t) => (
            <ChatBubble
              key={t.id}
              turn={t}
              theme={theme}
              accent={accent}
              onRetry={
                t.role === 'assistant' && !t.pending && t.text.startsWith('⚠️')
                  ? () => void onRetry(t)
                  : undefined
              }
            />
          ))
        )}
      </div>

      {draftAttachments.length > 0 && (
        <div
          style={{
            padding: '6px 12px',
            display: 'flex',
            gap: 6,
            overflowX: 'auto',
            borderTop: `1px solid ${theme.glassBorder}`,
            background: theme.glassPanel,
          }}
        >
          {draftAttachments.map((a, i) => (
            <AttachmentChip
              key={i}
              attachment={a}
              theme={theme}
              onRemove={() => removeDraftAttachment(i)}
            />
          ))}
        </div>
      )}

      <div
        style={{
          padding: '8px 10px 4px',
          display: 'flex',
          gap: 6,
          alignItems: 'flex-end',
          background: theme.bgGradient,
          borderTop: `1px solid ${theme.glassBorder}`,
        }}
        data-no-tab-swipe
      >
        <button
          onClick={() => fileInputRef.current?.click()}
          aria-label="Прикріпити файл"
          style={{
            width: 44,
            height: 44,
            borderRadius: 12,
            border: `1px solid ${theme.glassBorder}`,
            background: 'transparent',
            color: theme.ink2,
            fontSize: 22,
            cursor: 'pointer',
          }}
        >
          ＋
        </button>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*,application/pdf,text/*,.json,.md,.txt,.log"
          multiple
          onChange={(e) => void onFilePicked(e)}
          style={{ display: 'none' }}
        />
        <button
          onClick={() => void onMic()}
          aria-label={listening ? 'Зупинити запис' : 'Голосовий ввід'}
          aria-pressed={listening}
          disabled={!sttAvailable}
          style={{
            width: 44,
            height: 44,
            borderRadius: 12,
            border: `1px solid ${listening ? accent : theme.glassBorder}`,
            background: listening ? accent : 'transparent',
            color: listening ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
            fontSize: 18,
            cursor: sttAvailable ? 'pointer' : 'not-allowed',
            opacity: sttAvailable ? 1 : 0.4,
          }}
        >
          {listening ? '◉' : '🎙'}
        </button>
        <textarea
          ref={textareaRef}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onPaste={onPaste}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              void onSend();
            }
          }}
          rows={1}
          placeholder="Напиши Phantom…"
          style={{
            flex: 1,
            minHeight: 44,
            maxHeight: 144,
            padding: '10px 12px',
            borderRadius: 12,
            border: `1px solid ${theme.glassBorder}`,
            background: theme.glassPanel,
            color: theme.ink,
            fontFamily: 'var(--sans)',
            fontSize: 14,
            resize: 'none',
            lineHeight: 1.4,
          }}
        />
      </div>

      <PrimaryActionBar
        theme={theme}
        accent={accent}
        label={pairStatus === 'paired' ? 'Надіслати' : 'Очікую paired'}
        hint={
          draftAttachments.length > 0
            ? `${draftAttachments.length} вкладень готові`
            : 'Enter надсилає · Shift+Enter — новий рядок'
        }
        icon="↑"
        onPress={() => void onSend()}
        disabled={pairStatus !== 'paired' || (!draft.trim() && draftAttachments.length === 0)}
      />

      <BottomNav theme={theme} accent={accent} />
    </PhoneShell>
  );
}

interface ChatBubbleProps {
  turn: ChatTurn;
  theme: PhantomTheme;
  accent: string;
  onRetry?: () => void;
}

function ChatBubble({ turn, theme, accent, onRetry }: ChatBubbleProps) {
  const isUser = turn.role === 'user';
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: isUser ? 'flex-end' : 'flex-start',
        gap: 4,
      }}
    >
      <GlassCard
        level={isUser ? 'subtle' : 'card'}
        theme={theme}
        accentBorder={!isUser}
        accent={accent}
        style={{
          padding: '10px 12px',
          maxWidth: '85%',
          borderRadius: 14,
          background: isUser ? theme.glassPanel : theme.glassCard,
        }}
      >
        {turn.attachments.length > 0 && (
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 6 }}>
            {turn.attachments.map((a, i) => (
              <div
                key={i}
                style={{
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                  padding: '2px 8px',
                  borderRadius: 999,
                  background: theme.surfaceBase,
                  color: theme.ink3,
                  border: `1px solid ${theme.glassBorder}`,
                  letterSpacing: '0.06em',
                }}
              >
                {a.mime.split('/')[0]} · {a.name.slice(0, 24)}
              </div>
            ))}
          </div>
        )}
        {turn.pending ? (
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 11,
              letterSpacing: '0.18em',
              color: theme.ink3,
              opacity: 0.7,
            }}
          >
            …
          </div>
        ) : (
          <div
            style={{
              fontFamily: isUser ? 'var(--sans)' : 'var(--serif)',
              fontStyle: isUser ? 'normal' : 'italic',
              fontSize: 14,
              color: theme.ink,
              lineHeight: 1.45,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {turn.text}
          </div>
        )}
      </GlassCard>
      <div
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 9,
          color: theme.ink3,
          opacity: 0.6,
          letterSpacing: '0.12em',
          display: 'flex',
          alignItems: 'center',
          gap: 6,
        }}
      >
        <span>{new Date(turn.ts).toLocaleTimeString('en-GB')}</span>
        {turn.via && <span>· {turn.via}</span>}
        {onRetry && (
          <button
            onClick={onRetry}
            style={{
              background: 'transparent',
              border: `1px solid ${accent}55`,
              color: accent,
              padding: '2px 8px',
              borderRadius: 999,
              fontFamily: 'var(--mono)',
              fontSize: 9,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              cursor: 'pointer',
            }}
          >
            ↻ повторити
          </button>
        )}
      </div>
    </div>
  );
}

interface AttachmentChipProps {
  attachment: ChatAttachment;
  theme: PhantomTheme;
  onRemove: () => void;
}

function AttachmentChip({ attachment, theme, onRemove }: AttachmentChipProps) {
  return (
    <div
      style={{
        flex: '0 0 auto',
        display: 'flex',
        alignItems: 'center',
        gap: 6,
        padding: '4px 8px 4px 4px',
        borderRadius: 10,
        background: theme.glassCard,
        border: `1px solid ${theme.glassBorder}`,
      }}
    >
      {attachment.previewUrl && attachment.mime.startsWith('image/') ? (
        <img
          src={attachment.previewUrl}
          alt={attachment.name}
          style={{
            width: 36,
            height: 36,
            objectFit: 'cover',
            borderRadius: 6,
          }}
        />
      ) : (
        <div
          style={{
            width: 36,
            height: 36,
            borderRadius: 6,
            background: theme.surfaceBase,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 14,
            color: theme.ink3,
            fontFamily: 'var(--mono)',
          }}
        >
          {attachment.mime.split('/')[0]?.slice(0, 3).toUpperCase()}
        </div>
      )}
      <div
        style={{
          fontFamily: 'var(--mono)',
          fontSize: 10,
          color: theme.ink2,
          maxWidth: 80,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {attachment.name}
      </div>
      <button
        onClick={onRemove}
        aria-label="Видалити"
        style={{
          width: 22,
          height: 22,
          borderRadius: 6,
          border: 'none',
          background: 'transparent',
          color: theme.ink3,
          cursor: 'pointer',
          fontSize: 12,
        }}
      >
        ×
      </button>
    </div>
  );
}

async function ingestFile(
  file: File,
  add: (a: ChatAttachment) => void,
): Promise<void> {
  const a: ChatAttachment = {
    mime: file.type || 'application/octet-stream',
    name: file.name || `file-${Date.now()}`,
  };
  if (file.type.startsWith('image/')) {
    a.previewUrl = URL.createObjectURL(file);
  }
  if (file.size <= MAX_INLINE_BYTES) {
    try {
      a.bytes = await file.arrayBuffer();
      if (file.type.startsWith('text/')) {
        a.text = await file.text();
      }
    } catch {
      /* ignore — keep metadata only */
    }
  }
  add(a);
}

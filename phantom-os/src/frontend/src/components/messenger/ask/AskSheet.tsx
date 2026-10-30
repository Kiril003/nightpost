/**
 * «Спитати Nightpost» — один аркуш на весь застосунок (рендер ask-sheet,
 * 28.10). Шапка зі знаком, мітка поверхні, підказки лише з даних, поле з
 * мікрофоном; питання → живий рядок → відповідь або чесна причина.
 * Чернетка композера — свій варіант: операції над справжнім текстом і «Вставити».
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowUp, Mic, type LucideIcon } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import { FormSheet } from '../FormSheet';
import { PhantomGlyph, PhantomMark } from '../PhantomMark';
import { useAskSheet } from '../../../stores/askSheetStore';
import { askAbout, composeQuestion, type AskNone, type ComposeOp } from '../../../services/askAbout';
import type { AskContext, AskSurface } from '../../../services/askContext';
import { askNightpost, talkToNightpost } from '../../../services/askNightpost';
import { canNavigate } from '../../../services/nav';
import { soundFx } from '../../../utils/messengerSound';

/** Знак Nightpost у плитці аркуша вкладень (плитка чекає іконку lucide). */
export const NightpostIcon = PhantomGlyph as unknown as LucideIcon;

export const AskGlyphButton: React.FC<{ onClick: () => void; testId: string; className?: string }> = ({ onClick, testId, className }) => {
  const { t } = useTranslation();
  return (
    <button
      type="button"
      data-testid={testId}
      onClick={() => {
        soundFx.playTap();
        onClick();
      }}
      aria-label={t('ask.open')}
      title={t('ask.open')}
      className={className ?? 'w-11 h-11 shrink-0 rounded-full flex items-center justify-center text-ink hover:bg-overlay active:bg-overlay'}
    >
      <PhantomGlyph className="w-6 h-6" />
    </button>
  );
};

const FIELD: Record<AskSurface, MessageKey> = {
  day: 'ask.field.day',
  wallet: 'ask.field.wallet',
  person: 'ask.field.person',
  chat: 'ask.field.chat',
  group: 'ask.field.chat',
  compose: 'ask.field.compose',
};

const NONE: Record<AskNone, MessageKey> = {
  no_mind: 'ask.none.no_mind',
  offline: 'ask.none.offline',
  limit: 'ask.none.limit',
  failed: 'ask.none.failed',
};

const OPS: Array<{ op: ComposeOp; key: MessageKey }> = [
  { op: 'shorter', key: 'ask.op.shorter' },
  { op: 'warmer', key: 'ask.op.warmer' },
  { op: 'english', key: 'ask.op.english' },
];

/** Фокус — поле; дії тональні й ніколи не в акценті профілю. */
const TONAL = 'min-h-11 px-5 rounded-full bg-overlay text-subhead font-medium text-ink active:opacity-70 disabled:opacity-40';
const OUTLINE = 'min-h-11 px-4 rounded-full border border-line-strong text-subhead text-ink active:bg-overlay disabled:opacity-40';

type Phase =
  | { k: 'idle' }
  | { k: 'asking'; q: string }
  | { k: 'answer'; q: string; text: string }
  | { k: 'none'; q: string; reason: AskNone; detail?: string };

const Field: React.FC<{
  placeholder: string;
  onSubmit: (q: string) => void;
  disabled?: boolean;
}> = ({ placeholder, onSubmit, disabled = false }) => {
  const { t } = useTranslation();
  const [value, setValue] = useState('');
  const [voice, setVoice] = useState(() => canNavigate());
  const close = useAskSheet((s) => s.close);
  const submit = () => {
    const q = value.trim();
    if (!q || disabled) return;
    setValue('');
    onSubmit(q);
  };
  return (
    <div data-ask-field className="h-12 rounded-full bg-raised flex items-center gap-1 pl-4 pr-1">
      <input
        data-testid="ask-input"
        type="text"
        value={value}
        disabled={disabled}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            submit();
          }
        }}
        enterKeyHint="send"
        aria-label={placeholder}
        placeholder={placeholder}
        className="flex-1 min-w-0 h-11 bg-transparent text-body text-ink placeholder:text-ink-3 focus:outline-none"
      />
      {value.trim() ? (
        <button
          type="button"
          data-testid="ask-send"
          onClick={submit}
          aria-label={t('ask.send')}
          className="w-11 h-11 shrink-0 rounded-full flex items-center justify-center text-ink active:bg-overlay"
        >
          <ArrowUp className="w-5 h-5" strokeWidth={1.75} />
        </button>
      ) : voice ? (
        <button
          type="button"
          data-testid="ask-voice"
          onClick={() => {
            soundFx.playTap();
            if (talkToNightpost()) close();
            else setVoice(false);
          }}
          aria-label={t('ask.voice')}
          className="w-11 h-11 shrink-0 rounded-full flex items-center justify-center text-ink-2 active:bg-overlay"
        >
          <Mic className="w-5 h-5" strokeWidth={1.75} />
        </button>
      ) : null}
    </div>
  );
};

const Question: React.FC<{ q: string }> = ({ q }) => (
  <p data-testid="ask-question" className="self-end max-w-[85%] px-4 py-2 rounded-bubble bg-self text-on-self text-callout break-words">
    {q}
  </p>
);

const Asking: React.FC = () => {
  const { t } = useTranslation();
  return (
    <p data-testid="ask-asking" role="status" className="min-h-6 flex items-center">
      <span className="sr-only">{t('ask.thinking')}</span>
      <span aria-hidden className="w-2 h-5 rounded-sm bg-ink-3" />
    </p>
  );
};

const NoneBlock: React.FC<{ reason: AskNone; detail?: string; onRetry: () => void }> = ({ reason, detail, onRetry }) => {
  const { t } = useTranslation();
  return (
    <div data-testid="ask-none" data-reason={reason} className="flex flex-col items-start gap-2">
      <p className="text-headline text-ink">{t(NONE[reason])}</p>
      {detail && <p className="text-footnote text-ink-2 break-words">{detail}</p>}
      <button type="button" data-testid="ask-retry" onClick={onRetry} className={TONAL}>
        {t('ask.retry')}
      </button>
    </div>
  );
};

/** Один запит за раз; відповідь закритого чи перевідкритого аркуша мовчки губиться. */
function useAsk(ctx: AskContext) {
  const [phase, setPhase] = useState<Phase>({ k: 'idle' });
  const live = useRef(0);
  useEffect(() => () => void (live.current += 1), []);
  const run = useCallback(
    async (q: string, sent: string = q) => {
      const ticket = ++live.current;
      setPhase({ k: 'asking', q });
      const out = await askAbout(ctx, sent);
      if (ticket !== live.current) return null;
      setPhase(out.kind === 'answer' ? { k: 'answer', q, text: out.text } : { k: 'none', q, reason: out.reason, detail: out.detail });
      return out;
    },
    [ctx],
  );
  return { phase, run };
}

const AskBody: React.FC<{ ctx: AskContext; onClose: () => void }> = ({ ctx, onClose }) => {
  const { t } = useTranslation();
  const { phase, run } = useAsk(ctx);
  const busy = phase.k === 'asking';
  return (
    <>
      {phase.k === 'idle' && ctx.suggestions.length > 0 && (
        <div className="flex flex-col gap-2">
          {ctx.suggestions.map((s) => (
            <button
              key={s}
              type="button"
              data-testid="ask-suggestion"
              onClick={() => {
                soundFx.playTap();
                void run(s);
              }}
              className="w-full min-h-11 px-4 py-2 rounded-full border border-line-strong text-left text-callout text-ink active:bg-overlay"
            >
              {s}
            </button>
          ))}
        </div>
      )}
      {phase.k !== 'idle' && <Question q={phase.q} />}
      {phase.k === 'asking' && <Asking />}
      {phase.k === 'answer' && (
        <>
          <p data-testid="ask-answer" className="text-body text-ink whitespace-pre-wrap break-words">
            {phase.text}
          </p>
          <div className="flex items-center gap-2">
            <button
              type="button"
              data-testid="ask-continue"
              onClick={() => {
                soundFx.playTap();
                askNightpost(phase.q);
                onClose();
              }}
              className={TONAL}
            >
              {t('ask.continue')}
            </button>
          </div>
        </>
      )}
      {phase.k === 'none' && <NoneBlock reason={phase.reason} detail={phase.detail} onRetry={() => void run(phase.q)} />}
      <Field placeholder={t(FIELD[ctx.surface])} disabled={busy} onSubmit={(q) => void run(q)} />
    </>
  );
};

const ComposeBody: React.FC<{ ctx: AskContext; onClose: () => void; onInsert: ((text: string) => void) | null }> = ({ ctx, onClose, onInsert }) => {
  const { t } = useTranslation();
  const { phase, run } = useAsk(ctx);
  const [working, setWorking] = useState(ctx.draft ?? '');
  const last = useRef<{ q: string; sent: string } | null>(null);
  const busy = phase.k === 'asking';

  const go = async (q: string, sent: string) => {
    last.current = { q, sent };
    const out = await run(q, sent);
    if (out?.kind === 'answer') setWorking(out.text);
  };

  return (
    <>
      {working ? (
        <>
          <p className="text-footnote text-ink-3">{t('ask.draft')}</p>
          <p data-testid="ask-draft" className="px-4 py-3 rounded-control bg-raised text-body text-ink whitespace-pre-wrap break-words">
            {working}
          </p>
          <div className="flex flex-wrap gap-2">
            {OPS.map(({ op, key }) => (
              <button
                key={op}
                type="button"
                data-testid={`ask-op-${op}`}
                disabled={busy}
                onClick={() => {
                  soundFx.playTap();
                  void go(t(key), composeQuestion(op, working));
                }}
                className={OUTLINE}
              >
                {t(key)}
              </button>
            ))}
          </div>
        </>
      ) : (
        phase.k !== 'idle' && <Question q={phase.q} />
      )}
      {busy && <Asking />}
      {phase.k === 'none' && (
        <NoneBlock
          reason={phase.reason}
          detail={phase.detail}
          onRetry={() => last.current && void go(last.current.q, last.current.sent)}
        />
      )}
      {working ? (
        <div className="flex items-center gap-2">
          <button
            type="button"
            data-testid="ask-insert"
            disabled={busy || !onInsert}
            onClick={() => {
              soundFx.playTap();
              onInsert?.(working);
              onClose();
            }}
            className={TONAL}
          >
            {t('ask.insert')}
          </button>
          <button
            type="button"
            data-testid="ask-again"
            disabled={busy}
            onClick={() => {
              soundFx.playTap();
              void go(t('ask.again'), composeQuestion('again', working));
            }}
            className={OUTLINE}
          >
            {t('ask.again')}
          </button>
        </div>
      ) : (
        <Field placeholder={t(FIELD.compose)} disabled={busy} onSubmit={(q) => void go(q, composeQuestion('write', '', q))} />
      )}
    </>
  );
};

export const AskSheet: React.FC<{ ctx: AskContext; onClose: () => void; onInsert: ((text: string) => void) | null }> = ({
  ctx,
  onClose,
  onInsert,
}) => (
  <FormSheet open onClose={onClose} title="Nightpost" hideTitle softScrim layerKey="ask" layerClassName="z-[1300]" testId="ask-sheet">
    <div className="px-4 pb-4 flex flex-col gap-3">
      <div className="min-h-11 flex items-center gap-3">
        <PhantomMark size="w-8 h-8" />
        <span className="text-headline text-ink">Nightpost</span>
      </div>
      <span data-testid="ask-context" className="self-start max-w-full truncate px-2.5 py-1 rounded-chip bg-raised text-footnote text-ink-2">
        {ctx.label}
      </span>
      {ctx.surface === 'compose' ? (
        <ComposeBody ctx={ctx} onClose={onClose} onInsert={onInsert} />
      ) : (
        <AskBody ctx={ctx} onClose={onClose} />
      )}
    </div>
  </FormSheet>
);

/** Монтується один раз у корені месенджера; відкривають його `openAsk(ctx)`. */
export const AskSheetHost: React.FC = () => {
  const ctx = useAskSheet((s) => s.ctx);
  const seq = useAskSheet((s) => s.seq);
  const onInsert = useAskSheet((s) => s.onInsert);
  const close = useAskSheet((s) => s.close);
  if (!ctx) return null;
  return <AskSheet key={seq} ctx={ctx} onClose={close} onInsert={onInsert} />;
};

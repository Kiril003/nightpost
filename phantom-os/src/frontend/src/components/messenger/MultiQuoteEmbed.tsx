import React, { useState } from 'react';
import { Quote, ListChecks, ChevronDown, ChevronUp, Copy, Check } from 'lucide-react';
import { MultiQuoteData } from '../../types/messenger';
import { soundFx } from '../../utils/messengerSound';
import { useTranslation } from '../../i18n/useTranslation';

interface MultiQuoteEmbedProps {
  data: MultiQuoteData;
  isSelf?: boolean;
}

export const MultiQuoteEmbed: React.FC<MultiQuoteEmbedProps> = ({ data }) => {
  const [showFullQuotes, setShowFullQuotes] = useState(false);
  const [isCopied, setIsCopied] = useState(false);
  const { t } = useTranslation();

  const copySynthesis = () => {
    soundFx.playTap();
    const textToCopy = t('multiQuoteEmbed.copyText', {
      title: data.title || t('multiQuoteEmbed.summaryTitle'),
      keyPoints: data.synthesis?.keyPoints.map((k) => `• ${k}`).join('\n') ?? '',
      conclusion: data.synthesis?.conclusion || '',
    });
    navigator.clipboard.writeText(textToCopy);
    setIsCopied(true);
    setTimeout(() => setIsCopied(false), 2000);
  };

  return (
    <div className="space-y-3 pt-1 select-none text-ink">
      {/* Header */}
      <div className="flex items-center justify-between gap-2 pb-2.5 border-b border-line">
        <div className="flex items-center gap-2">
          <div className="p-2 bg-surface text-accent-text border border-line rounded-control">
            <Quote className="w-4 h-4" strokeWidth={1.75} />
          </div>
          <div>
            <h4 className="font-bold text-xs sm:text-sm text-ink leading-tight">
              {data?.title || t('multiQuoteEmbed.combinedTitle')}
            </h4>
            <span className="text-caption text-ink-2">
              {t('multiQuoteEmbed.backedMessages', { count: (data?.quotes || []).length })}
            </span>
          </div>
        </div>

        <button
          onClick={copySynthesis}
          className="touch-floor min-h-[44px] min-w-[44px] p-2.5 bg-surface hover:bg-overlay text-ink-2 hover:text-ink rounded-control text-xs transition-colors border border-line flex items-center justify-center"
          title={t('multiQuoteEmbed.copy')}
        >
          {isCopied ? <Check className="w-4 h-4 text-success" strokeWidth={2} /> : <Copy className="w-4 h-4" strokeWidth={1.75} />}
        </button>
      </div>

      {/* Synthesis Box */}
      {data?.synthesis && (
        <div className="p-3.5 bg-raised rounded-card border border-line space-y-2 text-xs text-ink">
          <div className="flex items-center gap-1.5 text-caption font-bold text-accent-text">
            <ListChecks className="w-3.5 h-3.5 text-accent-text" strokeWidth={1.75} />
            <span>{t('multiQuoteEmbed.keyPoints')}</span>
          </div>

          <div className="space-y-1.5 pl-1">
            {(data.synthesis.keyPoints || []).map((point, idx) => (
              <div key={idx} className="flex items-start gap-2 text-ink">
                <span className="w-1.5 h-1.5 rounded-full bg-accent mt-1.5 shrink-0" />
                <span className="leading-snug text-ink-2">{point}</span>
              </div>
            ))}
          </div>

          {data.synthesis.conclusion && (
            <div className="pt-2 border-t border-line text-caption text-ink-2 font-medium leading-relaxed">
              <span className="font-bold text-ink">{t('multiQuoteEmbed.conclusion')}</span> {data.synthesis.conclusion}
            </div>
          )}
        </div>
      )}

      {/* Accordion to view original quoted messages */}
      <div className="bg-raised rounded-card border border-line overflow-hidden">
        <button
          onClick={() => {
            soundFx.playTap();
            setShowFullQuotes(!showFullQuotes);
          }}
          className="touch-floor min-h-[44px] w-full px-3 py-2.5 text-left flex items-center justify-between text-xs font-semibold text-ink-2 hover:text-ink hover:bg-overlay transition-colors"
        >
          <span>{t('multiQuoteEmbed.originalQuotes', { count: (data?.quotes || []).length })}</span>
          {showFullQuotes ? <ChevronUp className="w-3.5 h-3.5" strokeWidth={1.75} /> : <ChevronDown className="w-3.5 h-3.5" strokeWidth={1.75} />}
        </button>

        {showFullQuotes && (
          <div className="p-2 space-y-2 border-t border-line bg-surface max-h-48 overflow-y-auto">
            {(data?.quotes || []).map((q) => (
              <div
                key={q.id}
                className="p-2.5 bg-raised rounded-control border border-line text-xs space-y-1 shadow-sm"
              >
                <div className="flex items-center justify-between text-caption text-ink-2 font-medium">
                  <span className="font-bold text-ink">{q.senderName}</span>
                  <span className=" text-ink-3">{q.timestamp}</span>
                </div>
                <p className="text-ink-2 italic leading-relaxed">
                  "{q.text}"
                </p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

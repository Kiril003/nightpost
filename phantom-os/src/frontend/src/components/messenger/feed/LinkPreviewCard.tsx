import React from 'react';
import type { Message } from '../../../types/messenger';

/** Картка посилання під текстом листа. Відкриває посилання там само, де й саме посилання в тексті. */
export const LinkPreviewCard: React.FC<{ preview: NonNullable<Message['linkPreview']> }> = ({ preview }) => (
  <a
    href={preview.url}
    target="_blank"
    rel="noopener noreferrer"
    onClick={(e) => e.stopPropagation()}
    data-testid="link-preview"
    className="mt-2 flex gap-3 overflow-hidden rounded-control border border-line bg-raised p-2 no-underline"
  >
    {preview.image && (
      <img src={preview.image} alt="" className="h-16 w-16 shrink-0 rounded-control object-cover" />
    )}
    <span className="flex min-w-0 flex-col">
      {preview.site && <span className="truncate text-caption text-ink-3">{preview.site}</span>}
      <span className="line-clamp-2 text-callout font-semibold text-ink">{preview.title}</span>
      {preview.description && <span className="line-clamp-2 text-footnote text-ink-2">{preview.description}</span>}
    </span>
  </a>
);

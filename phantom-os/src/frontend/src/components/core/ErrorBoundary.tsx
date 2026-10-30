import React from 'react';
import type { MessageKey } from '../../i18n/messages/uk';
import { translate } from '../../i18n';
import { resolveLocale } from '../../i18n/useTranslation';
import { recordCrash } from '../../lib/crashReport';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../../stores/settingsStore';

interface Props {
  children: React.ReactNode;
}

interface State {
  error: Error | null;
}

function isChunkLoadError(error: Error): boolean {
  // A stale deploy or offline webview fails to fetch a lazy chunk. Vite/
  // Rollup surface this as ChunkLoadError or a "dynamically imported
  // module" fetch failure — a reload (fresh manifest) is the real fix.
  return (
    error.name === 'ChunkLoadError' ||
    /dynamically imported module|Failed to fetch|Importing a module script failed/i.test(
      error.message,
    )
  );
}

/**
 * App-level error boundary around the lazy route/overlay tree. Without it a
 * failed chunk load (offline, stale deploy) or a render throw blanks the whole
 * screen. Token-styled, no hardcoded colours; offers retry (and a reload for
 * chunk-load failures). Copy is Ukrainian-first per the product's locale.
 */
export class ErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary]', error, info.componentStack);
    if (!isChunkLoadError(error)) recordCrash(error);
  }

  private reset = (): void => this.setState({ error: null });

  render(): React.ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;

    const chunk = isChunkLoadError(error);
    // Клас не може користуватись хуком: мову беремо з того самого сховища напряму.
    const locale = resolveLocale(useSettingsStore.getState().values[LANGUAGE_SETTING_KEY]);
    const t = (key: MessageKey) => translate(key, locale);
    // Мова PHANTOM: полотно, роль title-2 без курсиву, кнопка raised із межею
    // line. Раніше заголовок читав `--ink-base` — такої змінної немає ніде,
    // тож колір падав у батьківський, — і малювався серифним курсивом на
    // брунатному `--surface-void`.
    return (
      <div
        role="alert"
        className="w-full h-full flex items-center justify-center p-8"
        style={{ background: 'var(--ph-canvas)' }}
      >
        <div className="flex flex-col items-center gap-4 text-center max-w-md">
          <span className="text-title-2 text-ink">
            {chunk ? t('errorBoundary.stale') : t('errorBoundary.broke')}
          </span>
          <span className="text-callout text-ink-2">
            {chunk
              ? t('errorBoundary.reloadHint')
              : t('errorBoundary.retryHint')}
          </span>
          <button
            type="button"
            onClick={chunk ? () => window.location.reload() : this.reset}
            className="px-4 py-2 min-h-11 bg-raised text-ink text-subhead rounded-control border border-line transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-line"
          >
            {chunk ? t('errorBoundary.reload') : t('errorBoundary.retry')}
          </button>
        </div>
      </div>
    );
  }
}

export default ErrorBoundary;

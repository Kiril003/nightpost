import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { ViewerProps } from '../../../services/preview/registry';

type Status = 'loading' | 'ready' | 'too-big' | 'failed';

function readBlobText(blob: Blob): Promise<string> {
  if (typeof blob.text === 'function') {
    return blob.text();
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '');
    reader.onerror = () => reject(reader.error ?? new Error('read_failed'));
    reader.readAsText(blob);
  });
}

export default function TextViewer({ url, maxBytes, onFail }: ViewerProps) {
  const { t } = useTranslation();
  const [status, setStatus] = useState<Status>('loading');
  const [text, setText] = useState<string | null>(null);
  const preRef = useRef<HTMLPreElement | null>(null);
  const onFailRef = useRef(onFail);
  onFailRef.current = onFail;

  useEffect(() => {
    let cancelled = false;
    setStatus('loading');
    setText(null);

    void (async () => {
      try {
        const res = await fetch(url);
        if (!res.ok) throw new Error('fetch_failed');
        const blob = await res.blob();
        if (cancelled) return;
        if (blob.size > maxBytes) {
          setStatus('too-big');
          return;
        }
        const content = await readBlobText(blob);
        if (cancelled) return;
        setText(content);
        setStatus('ready');
      } catch {
        if (!cancelled) {
          setStatus('failed');
          onFailRef.current?.();
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [url, maxBytes]);

  const setPreNode = useCallback(
    (node: HTMLPreElement | null) => {
      preRef.current = node;
      if (node && text !== null) {
        node.textContent = text;
      }
    },
    [text],
  );

  useEffect(() => {
    if (status === 'ready' && preRef.current && text !== null) {
      preRef.current.textContent = text;
    }
  }, [status, text]);

  if (status === 'failed') return null;
  if (status === 'too-big') {
    return (
      <p className="text-footnote text-ink-3 p-4" data-testid="preview-too-big">
        {t('filePreview.tooBig')}
      </p>
    );
  }
  if (status === 'loading') {
    return <div className="text-callout text-ink-2" />;
  }

  return (
    <pre
      ref={setPreNode}
      className="font-mono text-footnote text-ink whitespace-pre-wrap break-words p-4"
    />
  );
}

import { useEffect, useRef, useState } from 'react';
import type { ViewerProps } from '../../../services/preview/registry';

function isSvgFile(name: string, mime: string): boolean {
  const cleanName = (name || '').trim().toLowerCase();
  const cleanMime = (mime || '').trim().toLowerCase().split(';')[0].trim();
  return cleanName.endsWith('.svg') || cleanMime === 'image/svg+xml';
}

export default function ImageViewer({ url, name, mime, maxBytes, onFail }: ViewerProps) {
  const svg = isSvgFile(name, mime);
  const [svgSrc, setSvgSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const onFailRef = useRef(onFail);
  onFailRef.current = onFail;

  useEffect(() => {
    setFailed(false);
    if (!svg) {
      setSvgSrc(null);
      return;
    }

    let cancelled = false;
    let createdUrl: string | undefined;
    let hasCreatedUrl = false;

    void (async () => {
      try {
        const res = await fetch(url);
        if (!res.ok) throw new Error('fetch_failed');
        const b = await res.blob();
        if (b.size > maxBytes) throw new Error('too_big');
        if (cancelled) return;
        // Перепаковуємо як image/svg+xml для <img>, де скрипти не виконуються.
        const typed = new Blob([b], { type: 'image/svg+xml' });
        createdUrl = URL.createObjectURL(typed);
        hasCreatedUrl = true;
        setSvgSrc(createdUrl ?? '');
      } catch {
        if (!cancelled) {
          setFailed(true);
          onFailRef.current?.();
        }
      }
    })();

    return () => {
      cancelled = true;
      if (hasCreatedUrl) {
        URL.revokeObjectURL(createdUrl as string);
      }
    };
  }, [url, svg, maxBytes]);

  if (failed) return null;
  if (svg && svgSrc === null) return null;

  const src = svg ? svgSrc || undefined : url;

  return (
    <img
      src={src}
      alt={name}
      draggable={false}
      onError={() => {
        setFailed(true);
        onFail?.();
      }}
      className="max-w-full max-h-full object-contain"
    />
  );
}

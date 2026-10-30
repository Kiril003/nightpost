import { useMemo } from 'react';
import { encodeQrMatrix, encodeQrSvgPath } from '../../lib/qr';

export default function Qr({ text, label }: { text: string; label: string }) {
  const qr = useMemo(() => {
    const m = encodeQrMatrix(text);
    return { size: m.size, path: encodeQrSvgPath(m) };
  }, [text]);
  const v = qr.size + 8;
  return (
    <svg className="qr" viewBox={`-4 -4 ${v} ${v}`} role="img" aria-label={label} shapeRendering="crispEdges">
      <rect x={-4} y={-4} width={v} height={v} />
      <path d={qr.path} />
    </svg>
  );
}

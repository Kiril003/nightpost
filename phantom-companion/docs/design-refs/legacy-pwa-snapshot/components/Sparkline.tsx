interface SparklineProps {
  data: number[];
  color: string;
  w?: number;
  h?: number;
}

export function Sparkline({ data, color, w = 56, h = 18 }: SparklineProps) {
  if (!data || data.length < 2) {
    return (
      <svg width={w} height={h} aria-hidden>
        <line x1={0} y1={h - 2} x2={w} y2={h - 2} stroke={color} strokeOpacity="0.2" strokeWidth="1.4" strokeDasharray="3,3" />
      </svg>
    );
  }
  const min = Math.min(...data);
  const max = Math.max(...data);
  const range = max - min || 1;
  const norm = data.map((v) => (v - min) / range);
  const pts = norm.map((v, i) => `${(i / (norm.length - 1)) * w},${h - v * h}`).join(' ');
  return (
    <svg width={w} height={h} style={{ display: 'block' }}>
      <polyline
        points={pts}
        fill="none"
        stroke={color}
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        opacity="0.85"
      />
    </svg>
  );
}

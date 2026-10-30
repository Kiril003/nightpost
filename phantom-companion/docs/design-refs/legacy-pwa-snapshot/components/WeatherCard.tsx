// Compact weather card for NowScreen — current temp + glyph + 24h
// sparkline. Hits Open-Meteo via `weatherClient` so it works without
// phantom-os. Re-fetches every 30 minutes (clientcache enforces).

import { useEffect, useState } from 'react';
import { GlassCard } from '@/components/GlassCard';
import { fetchWeather, weatherLabel, type WeatherSnapshot } from '@/services/weatherClient';
import type { PhantomTheme } from '@/types/theme';

interface WeatherCardProps {
  theme: PhantomTheme;
  lat: number;
  lon: number;
}

export function WeatherCard({ theme, lat, lon }: WeatherCardProps) {
  const [snap, setSnap] = useState<WeatherSnapshot | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const out = await fetchWeather(lat, lon);
        if (!cancelled) setSnap(out);
      } catch (e) {
        if (!cancelled) setErr((e as Error).message);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [lat, lon]);

  if (err) {
    return (
      <GlassCard level="subtle" theme={theme} style={{ padding: '8px 12px' }}>
        <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3 }}>
          погода: {err}
        </div>
      </GlassCard>
    );
  }

  if (!snap) {
    return (
      <GlassCard level="subtle" theme={theme} style={{ padding: '8px 12px' }}>
        <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: theme.ink3 }}>погода…</div>
      </GlassCard>
    );
  }

  const { glyph, label } = weatherLabel(snap.current.weatherCode, snap.current.isDay);
  const temps = snap.hourly.map((h) => h.tempC);
  const minT = Math.min(...temps);
  const maxT = Math.max(...temps);
  const range = Math.max(0.001, maxT - minT);

  return (
    <GlassCard level="card" theme={theme} style={{ padding: '12px 14px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{ fontSize: 36 }}>{glyph}</div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 22, fontWeight: 700, color: theme.ink, lineHeight: 1 }}>
            {snap.current.tempC.toFixed(0)}°
          </div>
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              color: theme.ink3,
              letterSpacing: '0.16em',
              textTransform: 'uppercase',
              marginTop: 4,
            }}
          >
            {label} · вітер {snap.current.windKmh.toFixed(0)} км/год
          </div>
        </div>
      </div>
      {/* 24h sparkline — relative scale. */}
      <div
        style={{
          marginTop: 10,
          height: 36,
          display: 'flex',
          alignItems: 'flex-end',
          gap: 2,
        }}
      >
        {snap.hourly.map((h, i) => {
          const ratio = (h.tempC - minT) / range;
          const h_px = 4 + ratio * 30;
          return (
            <div
              key={i}
              title={`${new Date(h.ts).getHours()}:00 · ${h.tempC.toFixed(1)}° · ${h.precipPct}%`}
              style={{
                flex: 1,
                height: `${h_px}px`,
                background: h.precipPct > 50 ? theme.primary : theme.ink3,
                borderRadius: 1,
                opacity: h.precipPct > 50 ? 0.9 : 0.5,
              }}
            />
          );
        })}
      </div>
      <div
        style={{
          marginTop: 4,
          fontFamily: 'var(--mono)',
          fontSize: 8,
          color: theme.ink3,
          letterSpacing: '0.16em',
          textTransform: 'uppercase',
        }}
      >
        наступні 24h · {minT.toFixed(0)}° → {maxT.toFixed(0)}°
      </div>
    </GlassCard>
  );
}

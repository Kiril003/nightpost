import { motion } from 'framer-motion';
import { GlassCard } from './GlassCard';
import type { PhantomTheme } from '@/types/theme';

interface AnimatedTabsProps<T extends string> {
  options: readonly T[];
  active: T;
  onChange: (v: T) => void;
  accent: string;
  theme: PhantomTheme;
}

export function AnimatedTabs<T extends string>({ options, active, onChange, accent, theme }: AnimatedTabsProps<T>) {
  return (
    <GlassCard level="subtle" theme={theme} style={{ padding: 4, display: 'flex', borderRadius: 999, position: 'relative' }}>
      {options.map((t) => {
        const isActive = t === active;
        return (
          <button
            key={t}
            onClick={() => onChange(t)}
            style={{
              flex: 1,
              padding: '8px 0',
              textAlign: 'center',
              fontFamily: 'var(--mono)',
              fontSize: 9.5,
              letterSpacing: '0.1em',
              fontWeight: 700,
              textTransform: 'uppercase',
              borderRadius: 999,
              color: isActive ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
              position: 'relative',
              zIndex: 2,
              transition: 'color 180ms ease',
            }}
          >
            {isActive && (
              <motion.div
                layoutId="phantom-tab-pill"
                transition={{ type: 'spring', stiffness: 380, damping: 32 }}
                style={{
                  position: 'absolute',
                  inset: 0,
                  background: accent,
                  borderRadius: 999,
                  zIndex: -1,
                  boxShadow: `0 6px 18px ${accent}55`,
                }}
              />
            )}
            {t}
          </button>
        );
      })}
    </GlassCard>
  );
}

import { motion } from 'framer-motion';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useT } from '@/hooks/useT';

export function SplashScreen() {
  const { theme } = useThemeContext();
  const t = useT();
  return (
    <motion.div
      initial={{ opacity: 1 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.4 }}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 200,
        background: theme.bgGradient,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 18,
        color: theme.ink,
      }}
      role="status"
      aria-label="Loading"
    >
      <motion.div
        initial={{ scale: 0.7, opacity: 0 }}
        animate={{ scale: [0.95, 1.05, 1], opacity: 1 }}
        transition={{ duration: 1.2, ease: 'easeOut' }}
        style={{
          width: 120,
          height: 120,
          borderRadius: '50%',
          background: `radial-gradient(circle at 35% 30%, ${theme.primarySoft} 0%, ${theme.primary} 50%, ${theme.primaryDeep} 100%)`,
          filter: `drop-shadow(0 0 36px ${theme.glowPrimary})`,
        }}
      />
      <motion.div
        initial={{ opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.6, delay: 0.4 }}
        style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: '0.32em', color: theme.ink3, fontWeight: 700 }}
      >
        PHANTOM · COMPANION
      </motion.div>
      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.6, delay: 0.7 }}
        style={{ fontFamily: 'var(--serif)', fontStyle: 'italic', fontSize: 14, color: theme.primaryDeep }}
      >
        {t('app.tagline')}
      </motion.div>
    </motion.div>
  );
}

import { useEffect, type ReactNode } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { useThemeContext } from '@/theme/ThemeProvider';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title?: string;
  children: ReactNode;
}

export function Modal({ open, onClose, title, children }: ModalProps) {
  const { theme } = useThemeContext();
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            style={{
              position: 'fixed',
              inset: 0,
              background: 'rgba(0,0,0,0.45)',
              zIndex: 80,
              backdropFilter: 'blur(6px)',
              WebkitBackdropFilter: 'blur(6px)',
            }}
          />
          <motion.div
            initial={{ y: 80, opacity: 0 }}
            animate={{ y: 0, opacity: 1 }}
            exit={{ y: 60, opacity: 0 }}
            transition={{ type: 'spring', damping: 22, stiffness: 280 }}
            style={{
              position: 'fixed',
              left: 12,
              right: 12,
              bottom: 12,
              zIndex: 90,
              background: theme.glassElevated,
              backdropFilter: 'blur(32px) saturate(1.1)',
              WebkitBackdropFilter: 'blur(32px) saturate(1.1)',
              border: `1px solid ${theme.glassBorder}`,
              borderRadius: 24,
              padding: 18,
              color: theme.ink,
              maxWidth: 420,
              margin: '0 auto',
              boxShadow: '0 24px 64px rgba(0,0,0,0.4)',
            }}
            role="dialog"
            aria-modal="true"
          >
            <div
              style={{
                width: 36,
                height: 4,
                borderRadius: 2,
                background: theme.ink3,
                margin: '0 auto 14px',
                opacity: 0.4,
              }}
            />
            {title && (
              <div
                style={{
                  fontFamily: 'var(--mono)',
                  fontSize: 11,
                  letterSpacing: '0.22em',
                  textTransform: 'uppercase',
                  fontWeight: 700,
                  color: theme.primary,
                  marginBottom: 12,
                }}
              >
                {title}
              </div>
            )}
            {children}
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}

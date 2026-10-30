import { motion } from 'framer-motion';
import type { ReactNode } from 'react';

const variants = {
  initial: { opacity: 0, x: 16 },
  enter: { opacity: 1, x: 0 },
  exit: { opacity: 0, x: -12 },
};

export function PageTransition({ children, keyId }: { children: ReactNode; keyId: string }) {
  return (
    <motion.div
      key={keyId}
      initial="initial"
      animate="enter"
      exit="exit"
      variants={variants}
      transition={{ duration: 0.22, ease: 'easeOut' }}
      style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}
    >
      {children}
    </motion.div>
  );
}

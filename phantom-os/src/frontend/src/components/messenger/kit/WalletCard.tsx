import React from 'react';
import type { LucideIcon } from 'lucide-react';

export interface WalletCardProps {
  icon: LucideIcon;
  label: string;
  footer?: React.ReactNode;
  testId?: string;
  /** Тихий знак праворуч у шапці (тестова мережа). */
  tag?: React.ReactNode;
  children?: React.ReactNode;
}

export const WalletCard: React.FC<WalletCardProps> = ({ icon: Icon, label, footer, testId, tag, children }) => (
  <div
    data-kit="wallet-card"
    data-testid={testId}
    className="w-[300px] max-w-full rounded-bubble border border-line bg-surface px-4 pt-3.5 pb-3 text-ink overflow-hidden"
  >
    <span data-kit-wallet-label data-card-kind className="flex items-center gap-1.5 text-caption font-semibold text-ink-3">
      <Icon className="w-4 h-4" strokeWidth={1.75} aria-hidden />
      {label}
      {tag}
    </span>
    {children}
    {footer && <div className="mt-2.5 border-t border-line pt-2.5">{footer}</div>}
  </div>
);

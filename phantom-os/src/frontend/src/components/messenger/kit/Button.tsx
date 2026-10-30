import React from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'text';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  testId?: string;
}

const VARIANT: Record<ButtonVariant, string> = {
  primary: 'h-9 px-4 rounded-full bg-accent text-on-accent text-callout font-semibold active:opacity-80',
  secondary: 'h-9 px-4 rounded-full bg-ink/[.08] text-ink text-callout font-medium active:opacity-80 transition-opacity duration-instant',
  text: 'text-callout font-medium text-accent active:opacity-80',
};

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ variant = 'secondary', testId, className = '', type = 'button', children, ...rest }, ref) => (
    <button
      ref={ref}
      type={type}
      data-kit="button"
      data-kit-variant={variant}
      data-testid={testId}
      className={`touch-floor-inline ${VARIANT[variant]} ${className}`.trim()}
      {...rest}
    >
      {children}
    </button>
  ),
);

Button.displayName = 'Button';

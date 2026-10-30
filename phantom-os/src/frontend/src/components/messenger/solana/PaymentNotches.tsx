import { useMemo } from 'react';
import { policy, readEnv } from '../../../feel/motionTokens';
import { NOTCHES, type PayStep } from '../../../feel/paymentSteps';

export function PaymentNotches({ step, label }: { step: PayStep; label: string }) {
  const spec = useMemo(() => policy(readEnv()), []);
  const quiet = spec.fill.end || spec.stamp.end;
  const fill = `${spec.fill.prop} ${spec.fill.ms}ms ${spec.fill.easing}`;
  const stamp = `${spec.stamp.prop} ${spec.stamp.ms}ms ${spec.stamp.easing}`;

  return (
    <div
      data-testid="pay-steps"
      data-state={step.state}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={NOTCHES}
      aria-valuenow={step.notch}
      className="mt-2 flex gap-1"
    >
      {Array.from({ length: NOTCHES }, (_, i) => {
        const filled = i < step.notch;
        const last = filled && i === step.notch - 1;
        const tone = last && step.stamp ? 'bg-success' : last && step.state === 'refused' ? 'bg-danger' : 'bg-ink';
        // Остання засічка чекає штампу трохи меншою; росте лише разом зі зміною стану.
        const waits = i === NOTCHES - 1 && !step.stamp;
        return (
          <span key={i} className="relative h-1 flex-1 overflow-hidden rounded-full bg-overlay">
            <span
              data-notch={filled ? 'on' : 'off'}
              className={`absolute inset-0 rounded-full ${tone}`}
              style={{
                clipPath: filled ? 'inset(0 0% 0 0)' : 'inset(0 100% 0 0)',
                transform: !quiet && waits ? 'scale(0.9)' : 'none',
                transition: quiet ? 'none' : `${fill}, ${stamp}`,
              }}
            />
          </span>
        );
      })}
    </div>
  );
}

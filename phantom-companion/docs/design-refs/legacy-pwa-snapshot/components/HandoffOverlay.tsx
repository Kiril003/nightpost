// Cross-device handoff overlay.
//
// When phantom-os (or another paired phone) creates a handoff
// targeted at this device, the WS subscription pushes the row into
// `useHandoffStore.pending`. This component is mounted globally from
// PhoneShell so any pending row produces a slide-up sheet at the
// bottom: "Phantom-os хоче відкрити маршрут на цьому телефоні —
// прийняти?"
//
// Accept → POST /handoff/{id}/accept and route to the appropriate
// screen (`map_route` → /map, `vault_card` → /vault, `chat_thread` →
// /voice). Reject → POST /handoff/{id}/reject. Both close the sheet.
// Expiration is server-side; the WS frame flips status and the row
// drops out automatically.

import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { GlassCard } from '@/components/GlassCard';
import { Banner } from '@/components/Banner';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import {
  acceptHandoff,
  rejectHandoff,
  type Handoff,
  type HandoffKind,
} from '@/services/handoffClient';
import { useHandoffStore, shouldAutoAcceptHandoff } from '@/state/handoffStore';
import { useCompanionControlStore } from '@/state/companionControlStore';
import { useSensorStore } from '@/state/sensorStore';

const KIND_LABEL: Record<HandoffKind, string> = {
  map_route: 'маршрут',
  vault_card: 'картка з vault',
  chat_thread: 'голосовий тред',
  file_drop: 'файл',
  voice_continuation: 'продовжити voice',
  custom: 'дія',
};

const KIND_ROUTE: Partial<Record<HandoffKind, string>> = {
  map_route: '/map',
  vault_card: '/vault',
  chat_thread: '/voice',
  voice_continuation: '/voice',
  file_drop: '/now',
};

export function HandoffOverlay() {
  const { theme } = useThemeContext();
  const navigate = useNavigate();
  const pending = useHandoffStore((s) => s.pending);
  const autoAcceptDrivingRoute = useHandoffStore((s) => s.autoAcceptDrivingRoute);
  const lastAutoAcceptedId = useHandoffStore((s) => s.lastAutoAcceptedId);
  const motionClass = useSensorStore((s) => s.motionClass);
  const [busy, setBusy] = useState(false);
  // Track ids we've already auto-accepted in this session so the
  // effect doesn't fire twice for the same row.
  const autoHandled = useRef<Set<string>>(new Set());
  const [autoAcceptedBanner, setAutoAcceptedBanner] = useState(false);

  // Show only the most-recent pending handoff so we never stack
  // overlays. Older ones queue silently — operator can expand them
  // from /now → "Очікують" once the first is resolved.
  const top = useMemo<Handoff | null>(() => {
    if (pending.length === 0) return null;
    return [...pending].sort((a, b) => b.created_at.localeCompare(a.created_at))[0]!;
  }, [pending]);

  useEffect(() => {
    // Reset busy when the active handoff disappears (resolved by
    // another device or a remote cancel arrived over WS).
    if (!top) setBusy(false);
  }, [top]);

  // Sticky one-shot banner — visible only as long as the matching
  // auto-accepted id is still in lastAutoAcceptedId. Auto-hides
  // after the Banner's autoHideMs.
  useEffect(() => {
    if (lastAutoAcceptedId) setAutoAcceptedBanner(true);
  }, [lastAutoAcceptedId]);

  const acceptRow = async (row: Handoff): Promise<void> => {
    useHandoffStore.getState().resolveLocal(row.id, 'accepted');
    try {
      await acceptHandoff(row.id);
    } catch (err) {
      useToastStore.getState().push('warn', `Handoff accept: ${(err as Error).message}`);
    }
    if (row.kind === 'map_route') {
      useCompanionControlStore.getState().setPendingRoute(row.payload);
    } else if (row.kind === 'vault_card') {
      const cardId = String((row.payload as Record<string, unknown>).card_id ?? '');
      useCompanionControlStore.getState().setPendingOpenCard(cardId || null);
    }
    const target = KIND_ROUTE[row.kind];
    if (target) navigate(target);
  };

  // Auto-accept driving route. Only fires once per row; manual UX
  // still owns every other kind + every non-driving state.
  useEffect(() => {
    if (!top) return;
    if (autoHandled.current.has(top.id)) return;
    if (!shouldAutoAcceptHandoff(top, motionClass, autoAcceptDrivingRoute)) return;
    autoHandled.current.add(top.id);
    useHandoffStore.getState().setLastAutoAcceptedId(top.id);
    void acceptRow(top);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [top?.id, motionClass, autoAcceptDrivingRoute]);

  if (!top) {
    if (autoAcceptedBanner) {
      return (
        <div
          style={{
            position: 'fixed',
            left: 0,
            right: 0,
            bottom: 0,
            padding: '12px 12px calc(12px + env(safe-area-inset-bottom, 0px))',
            zIndex: 90,
            pointerEvents: 'none',
          }}
        >
          <div style={{ pointerEvents: 'auto' }}>
            <Banner
              theme={theme}
              kind="success"
              title="маршрут авто-прийнято"
              message="phantom-os передав маршрут поки ти за кермом — відкриваю /map"
              autoHideMs={3500}
              onDismiss={() => {
                setAutoAcceptedBanner(false);
                useHandoffStore.getState().setLastAutoAcceptedId(null);
              }}
            />
          </div>
        </div>
      );
    }
    return null;
  }

  // Suppress the manual sheet for the row we're about to auto-accept;
  // the banner above (after pending clears) owns the feedback path.
  if (shouldAutoAcceptHandoff(top, motionClass, autoAcceptDrivingRoute)) {
    return null;
  }

  const onAccept = async () => {
    if (busy) return;
    setBusy(true);
    await acceptRow(top);
  };

  const onReject = async () => {
    if (busy) return;
    setBusy(true);
    useHandoffStore.getState().resolveLocal(top.id, 'rejected');
    try {
      await rejectHandoff(top.id);
    } catch (err) {
      useToastStore.getState().push('warn', `Handoff reject: ${(err as Error).message}`);
    }
  };

  return (
    <div
      role="dialog"
      aria-label="Cross-device handoff"
      style={{
        position: 'fixed',
        left: 0,
        right: 0,
        bottom: 0,
        padding: '12px 12px calc(12px + env(safe-area-inset-bottom, 0px))',
        zIndex: 90,
        pointerEvents: 'none',
      }}
    >
      <GlassCard
        level="elevated"
        theme={theme}
        accentBorder
        accent={theme.primary}
        style={{
          padding: '14px 14px 12px',
          pointerEvents: 'auto',
          display: 'flex',
          flexDirection: 'column',
          gap: 10,
        }}
      >
        <div
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 10,
            letterSpacing: '0.22em',
            textTransform: 'uppercase',
            color: theme.primary,
            fontWeight: 700,
          }}
        >
          ↓ {KIND_LABEL[top.kind]} з phantom-os
        </div>
        <div
          style={{
            fontSize: 14,
            color: theme.ink,
            lineHeight: 1.4,
          }}
        >
          {top.title || 'Хочу передати на цей пристрій'}
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button
            onClick={() => void onReject()}
            disabled={busy}
            style={{
              flex: 1,
              minHeight: 44,
              padding: '0 12px',
              borderRadius: 12,
              background: 'transparent',
              color: theme.ink2,
              border: `1px solid ${theme.glassBorder}`,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              opacity: busy ? 0.5 : 1,
            }}
          >
            Відхилити
          </button>
          <button
            onClick={() => void onAccept()}
            disabled={busy}
            style={{
              flex: 1,
              minHeight: 44,
              padding: '0 12px',
              borderRadius: 12,
              background: theme.primary,
              color: theme.isDark ? '#1a1208' : '#fff',
              border: `1px solid ${theme.primary}`,
              fontFamily: 'var(--mono)',
              fontSize: 11,
              fontWeight: 700,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
              opacity: busy ? 0.5 : 1,
            }}
          >
            {busy ? '…' : 'Прийняти'}
          </button>
        </div>
      </GlassCard>
    </div>
  );
}

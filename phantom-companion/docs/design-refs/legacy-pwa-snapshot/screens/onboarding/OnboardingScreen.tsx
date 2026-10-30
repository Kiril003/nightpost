import { useReducer, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { hashPin, WeakPinError } from '@/services/pinHasher';
import {
  useProfileStore,
  type LocalProfile,
} from '@/state/profileStore';

import { NameStep } from './NameStep';
import { PinStep } from './PinStep';
import { PairOfferStep } from './PairOfferStep';

// Phase-1 onboarding flow: Name → PIN → PairOffer (Scan / Skip).
// Mirrors the now-archived companion-android `OnboardingViewModel`
// state machine. Biometric + voice-print steps are deferred per the
// canonical plan §11 — they are not required for the Phase-5 public-
// launch demo, and adding them prematurely just complicates rebases.
//
// State machine (sealed-union via discriminated `kind`):
//
//     name ──▶ pin ──▶ creating ──▶ pair-offer ──▶ done
//      ▲       │                       │
//      │       └── back ───────────────┘
//      └── cancel
//
// `done` is a terminal sentinel — the screen itself navigates to /pair
// or /pulse and unmounts. Errors surface inline and re-arm the
// previous step rather than landing on a dedicated error screen.

type Step =
  | { kind: 'name'; draftName: string }
  | { kind: 'pin'; displayName: string }
  | { kind: 'creating'; displayName: string }
  | { kind: 'pair-offer'; profileId: string; displayName: string }
  | { kind: 'done' };

type StepEvent =
  | { type: 'name-submitted'; displayName: string }
  | { type: 'pin-back' }
  | { type: 'pin-submitted'; profileId: string; displayName: string }
  | { type: 'pair-offer'; profileId: string; displayName: string }
  | { type: 'done' };

function reduce(state: Step, event: StepEvent): Step {
  switch (event.type) {
    case 'name-submitted':
      return { kind: 'pin', displayName: event.displayName };
    case 'pin-back':
      if (state.kind === 'pin') return { kind: 'name', draftName: state.displayName };
      return state;
    case 'pin-submitted':
      return { kind: 'creating', displayName: event.displayName };
    case 'pair-offer':
      return { kind: 'pair-offer', profileId: event.profileId, displayName: event.displayName };
    case 'done':
      return { kind: 'done' };
    default:
      return state;
  }
}

function newProfileId(): string {
  // Phase 1: optimistic local-only id (plain UUID, 36 chars to match
  // backend `Profile.id`). On first pair, pairingClient skips sending
  // it (the server has never seen it) and patches the local row with
  // whatever canonical id /pair/claim returns. Until that round-trip
  // lands the local id keeps the launcher / vault scoping working in
  // offline-first mode.
  return crypto.randomUUID();
}

export function OnboardingScreen() {
  const navigate = useNavigate();
  const upsertProfile = useProfileStore((s) => s.upsertProfile);
  const switchActive = useProfileStore((s) => s.switchActive);

  const [step, dispatch] = useReducer(reduce, { kind: 'name', draftName: '' } as Step);
  const [error, setError] = useState<string | null>(null);

  const handleNameSubmit = (displayName: string) => {
    setError(null);
    dispatch({ type: 'name-submitted', displayName });
  };

  const handlePinSubmit = async (pin: string, displayName: string) => {
    setError(null);
    dispatch({ type: 'pin-submitted', profileId: '', displayName });
    try {
      const pinHash = await hashPin(pin);
      const id = newProfileId();
      const now = new Date().toISOString();
      const profile: LocalProfile = {
        id,
        displayName,
        role: 'OPERATOR',
        language: 'uk',
        voiceProfile: 'default',
        themePreference: 'sunrise',
        pairedAt: null,
        lastSeen: now,
        pinHash,
        deviceJwt: '',
        hostBaseUrl: '',
        biometricEnrolled: false,
        createdAtLocal: now,
        archived: false,
      };
      upsertProfile(profile);
      switchActive(id);
      dispatch({ type: 'pair-offer', profileId: id, displayName });
    } catch (err) {
      const message =
        err instanceof WeakPinError
          ? err.message
          : `Не вдалось створити профіль: ${(err as Error).message}`;
      setError(message);
      dispatch({ type: 'pin-back' });
    }
  };

  const handleStartPairing = () => {
    dispatch({ type: 'done' });
    navigate('/pair', { replace: true });
  };

  const handleSkipPairing = () => {
    // Operator deferred pairing. PairingGate now whitelist-s `/me`
    // for unpaired clients, so we drop them there with an
    // unpaired-state banner that links back to /pair when they're
    // near the desktop. Setting the deferred timestamp so MeScreen
    // can render a softer "deferred" subtitle vs a hard
    // "never-paired" warning.
    try {
      if (typeof localStorage !== 'undefined') {
        localStorage.setItem('phantom.pair.deferred', new Date().toISOString());
      }
    } catch {
      /* storage disabled — proceed anyway */
    }
    dispatch({ type: 'done' });
    navigate('/me', { replace: true });
  };

  switch (step.kind) {
    case 'name':
      return <NameStep initialName={step.draftName} error={error} onSubmit={handleNameSubmit} />;
    case 'pin':
      return (
        <PinStep
          displayName={step.displayName}
          error={error}
          onSubmit={(pin) => handlePinSubmit(pin, step.displayName)}
          onBack={() => dispatch({ type: 'pin-back' })}
        />
      );
    case 'creating':
      return <CreatingProfileIndicator displayName={step.displayName} />;
    case 'pair-offer':
      return (
        <PairOfferStep
          displayName={step.displayName}
          onStartPair={handleStartPairing}
          onSkip={handleSkipPairing}
        />
      );
    case 'done':
      return null;
    default:
      return null;
  }
}

function CreatingProfileIndicator({ displayName }: { displayName: string }) {
  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        padding: 24,
      }}
    >
      <div
        style={{
          width: 36,
          height: 36,
          borderRadius: '50%',
          border: '3px solid rgba(255,255,255,0.12)',
          borderTopColor: 'currentColor',
          animation: 'spin 0.9s linear infinite',
        }}
      />
      <div style={{ fontSize: 18, opacity: 0.85, textAlign: 'center' }}>
        Створюю профіль для <strong>{displayName}</strong>…
      </div>
      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

/**
 * faceStore — стан запису обличчя (enrollment).
 *
 * faceApi і useFaceDetection (камера-оверлей Overlays) пішли разом з віконним
 * менеджером ПК; faceStore лишився, бо його читає settingsBootstrap
 * (поріг і режим приватності з налаштувань).
 */
import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { useFaceStore } from '../stores/faceStore';

/* ═══════════════════════════════════════════════════════════════════════════
   faceStore — enrollment state machine
   ═══════════════════════════════════════════════════════════════════════════ */

describe('faceStore', () => {
  beforeEach(() => {
    // Reset the store between tests.
    useFaceStore.setState({
      enabled: true,
      running: false,
      ready: false,
      lastDetection: null,
      recognized: null,
      unknownSince: null,
      enrollSamples: [],
      enrollStatus: 'idle',
      enrollError: null,
      cameraError: null,
      threshold: 0.75,
      privacyMode: 'landmarks',
    });
  });

  it('startEnrollment clears samples and moves to collecting', () => {
    const { result } = renderHook(() => useFaceStore());
    act(() => {
      result.current.pushEnrollmentSample([0.1, 0.2]);
      result.current.pushEnrollmentSample([0.2, 0.3]);
      result.current.startEnrollment();
    });
    expect(result.current.enrollSamples.length).toBe(0);
    expect(result.current.enrollStatus).toBe('collecting');
  });

  it('pushEnrollmentSample appends samples while collecting', () => {
    const { result } = renderHook(() => useFaceStore());
    act(() => {
      result.current.startEnrollment();
      result.current.pushEnrollmentSample([0.1]);
      result.current.pushEnrollmentSample([0.2]);
    });
    expect(result.current.enrollSamples.length).toBe(2);
  });

  it('setEnrollStatus propagates error text', () => {
    const { result } = renderHook(() => useFaceStore());
    act(() => {
      result.current.setEnrollStatus('error', 'backend blew up');
    });
    expect(result.current.enrollStatus).toBe('error');
    expect(result.current.enrollError).toBe('backend blew up');
  });
});

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, render } from '@testing-library/react';
import { CallSettingsSheet } from '../components/messenger/CallSettingsSheet';
import { CALL_COST_KEY } from '../services/callCost';
import { uk } from '../i18n/messages/uk';

describe('CallSettingsSheet call cost rows', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it('renders video cost row with 3 mobile samples and omits audio row when below threshold', () => {
    localStorage.setItem(
      CALL_COST_KEY,
      JSON.stringify({
        wifi: { audio: [], video: [] },
        mobile: { audio: [], video: [3, 4.5, 9] },
      }),
    );

    render(<CallSettingsSheet open readDataUsedBytes={() => null} />);

    const videoRow = document.querySelector('[data-call-cost="video"]');
    expect(videoRow).not.toBeNull();
    expect(videoRow?.getAttribute('data-call-setting')).toBe('cost-video');
    expect(videoRow?.textContent).toContain('≈ 45');
    expect(videoRow?.textContent).toContain(uk['callSettings.mb']);

    const audioRow = document.querySelector('[data-call-cost="audio"]');
    expect(audioRow).toBeNull();
  });

  it('renders no cost rows when fewer than 3 samples exist', () => {
    localStorage.setItem(
      CALL_COST_KEY,
      JSON.stringify({
        wifi: { audio: [1, 2], video: [3, 4.5] },
        mobile: { audio: [1, 2], video: [3, 4.5] },
      }),
    );

    render(<CallSettingsSheet open readDataUsedBytes={() => null} />);

    expect(document.querySelectorAll('[data-call-cost]').length).toBe(0);
  });
});

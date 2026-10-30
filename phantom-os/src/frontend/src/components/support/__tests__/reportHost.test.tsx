import React from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, screen } from '@testing-library/react';
import { ReportSheetHost } from '../ReportSheet';
import { useSupportStore } from '../../../stores/supportStore';

afterEach(() => {
  cleanup();
  useSupportStore.getState().reset();
});

describe('аркуш скарги відкривається, навіть коли React відкидає рендер', () => {
  it('у StrictMode (подвійний рендер) «Далі» відкриває аркуш', async () => {
    render(
      <React.StrictMode>
        <ReportSheetHost />
      </React.StrictMode>,
    );
    act(() => {
      useSupportStore.getState().openReport({ targetKind: 'message', targetId: 'm1', chatId: 'c1', messageIds: ['m1'], messageText: 'Привіт', senderKey: 'p1', peerId: 'p1' });
    });
    expect(await screen.findByTestId('report-sheet')).toBeTruthy();
  });

  it('два хости — лише один аркуш', async () => {
    render(
      <React.StrictMode>
        <ReportSheetHost />
        <ReportSheetHost />
      </React.StrictMode>,
    );
    act(() => {
      useSupportStore.getState().openReport({ targetKind: 'person', targetId: 'p1', senderKey: 'p1', peerId: 'p1' });
    });
    await screen.findByTestId('report-sheet');
    expect(screen.getAllByTestId('report-sheet')).toHaveLength(1);
  });
});

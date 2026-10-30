import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render } from '@testing-library/react';
import { rb } from '../feel/physics';
import { useUIStore } from '../stores/uiStore';
import { ToastRail } from '../components/core/ToastRail';

const src = (p: string) => readFileSync(join(__dirname, '..', p), 'utf8');

describe('polish-chat', () => {
  it('T26: toast with an action shows a life bar', () => {
    useUIStore.getState().toast({ kind: 'info', message: 'm', action: { label: 'Undo', run: () => {} } });
    const { container } = render(<ToastRail />);
    expect(container.querySelector('[data-testid="toast-timer"]')).toBeTruthy();
  });
  it('T48: upward over-pull of a sheet stays within 16px', () => {
    expect(src('hooks/useSheetDrag.ts')).toContain('-rb(-dy, 16)');
    expect(rb(2000, 16)).toBeLessThanOrEqual(16);
  });
  it('T80: peer profile has no «search in chat» row', () => {
    expect(src('components/messenger/PeerProfileSheet.tsx')).not.toContain('peerProfileSheet.searchChat');
  });
  it('T24: chip row inset matches the list gutter', () => {
    expect(src('components/messenger/today/TopSheetView.tsx')).toContain('gap-1.5 px-4 ${desk');
  });
});

describe('polish-chat 2', () => {
  it('T21: share sheet reuses chat-list faces and hides raw solana: uri', () => {
    const s = src('components/messenger/ShareInSheet.tsx');
    expect(s).toContain('faces[chat.peerNodeId]');
    expect(s).toContain('shareIn.paymentLink');
  });
  it('T38: recorder circle sits in the top third, controls are 48', () => {
    const s = src('components/messenger/VideoNoteRecorder.tsx');
    expect(s).toContain('justify-start pt-[max(');
    expect(s).not.toContain('w-14 h-14');
  });
});

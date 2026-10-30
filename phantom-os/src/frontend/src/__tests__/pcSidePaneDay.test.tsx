import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DeskSidePane } from '../components/messenger/DeskSidePane';
import { useUIStore } from '../stores/uiStore';
import { useIntentsStore } from '../stores/intentsStore';
import { useMessengerStore } from '../stores/messengerStore';

const signals = vi.hoisted(() => ({
  value: {
    agentBusy: false,
    agentGoal: null as string | null,
    waiting: [] as unknown[],
    nextEvent: null as null | { title: string; inMinutes: number; location: string | null },
    noticed: null as null | { text: string },
  },
}));

vi.mock('../components/shell/useShellSignals', () => ({
  useShellSignals: () => signals.value,
}));

describe('pcSidePaneDay: колонка День', () => {
  beforeEach(() => {
    useUIStore.setState({ deskPane: 'day', toasts: [] });
    useMessengerStore.setState({ chats: [] });
    useIntentsStore.setState({ rows: null, refresh: async () => {} });
    signals.value = { agentBusy: false, agentGoal: null, waiting: [], nextEvent: null, noticed: null };
  });

  it('подія завтра вранці: «за 15 годин», а не «за 900m» англійською літерою', () => {
    signals.value = { ...signals.value, nextEvent: { title: 'Зустріч', inMinutes: 900, location: null } };
    render(<DeskSidePane />);
    const card = screen.getByTestId('desk-pane-next-event');
    expect(card).toHaveTextContent('за 15 годин');
    expect(card.textContent).not.toMatch(/\d+m\b/);
  });

  it('подія через 95 хвилин читається словами: «1 годину 35 хв»', () => {
    signals.value = { ...signals.value, nextEvent: { title: 'Зустріч', inMinutes: 95, location: null } };
    render(<DeskSidePane />);
    expect(screen.getByTestId('desk-pane-next-event')).toHaveTextContent('за 1 годину 35 хв');
  });

  it('помічник зайнятий без названої мети й більше нічого немає: чесний порожній стан, а не порожня колонка', () => {
    signals.value = { ...signals.value, agentBusy: true, agentGoal: null };
    render(<DeskSidePane />);
    expect(screen.getByTestId('desk-pane-day-empty')).toBeInTheDocument();
  });

  it('прохання до ПК — лише список; другого рядка-кнопки, що грає звук і нічого не робить, немає', () => {
    useIntentsStore.setState({
      rows: [
        {
          id: 'i1',
          op: 'who_online',
          title: 'Хто на звʼязку',
          created_ms: 1,
          deadline_ms: 2,
          phase: 'SENT',
          tries: [],
          relay_allowed: false,
          answer: null,
        },
      ],
    });
    render(<DeskSidePane />);
    expect(screen.getByTestId('intents-list')).toBeInTheDocument();
    expect(screen.queryByTestId('me-intents')).toBeNull();
  });
});

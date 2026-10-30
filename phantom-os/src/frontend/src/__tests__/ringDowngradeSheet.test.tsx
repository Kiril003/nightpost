import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CircleChoices } from '../components/messenger/circles';
import { CIRCLE_KEY, CIRCLES } from '../components/messenger/todayFormat';
import { uk } from '../i18n/messages/uk';
import { chatPins } from '../services/chatPins';

const { mockRequest, mockQuiet, mockSetQuiet, mockPhoneBridge } = vi.hoisted(() => ({
  mockRequest: vi.fn(),
  mockQuiet: vi.fn(),
  mockSetQuiet: vi.fn(),
  mockPhoneBridge: vi.fn(),
}));

vi.mock('../services/api', () => ({
  request: mockRequest,
}));

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    phoneBridge: mockPhoneBridge,
    messengerApi: {
      ...actual.messengerApi,
      quiet: mockQuiet,
      setQuiet: mockSetQuiet,
    },
  };
});

const CLOSE = CIRCLES[0];
const SOCIAL = CIRCLES[1];
const PEER = 'peer-node-1';
const CHAT = 'chat-1';

describe('RingDowngradeSheet via CircleChoices', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    chatPins.reset();
    mockPhoneBridge.mockReturnValue({});
    mockQuiet.mockResolvedValue({ enabled: true, except: [PEER] });
    mockSetQuiet.mockResolvedValue(true);
    mockRequest.mockImplementation(async (method: string, path: string) => {
      if (method === 'GET' && path === `/messenger/where/${PEER}`) {
        return {
          mine: { precision: 'district', until_ms: null, stand: false },
          peer: null,
          now_ms: 1000,
        };
      }
      if (method === 'DELETE' && path === `/messenger/where/${PEER}`) {
        return { mine: null, peer: null, now_ms: 1000 };
      }
      if (method === 'GET' && path === '/messenger/moments/prefs') {
        return { audience: { kind: 'contacts' } };
      }
      return {};
    });
  });

  it('opens sheet on downgrade, lists active ties, applies both and closes', async () => {
    const spy = vi.fn();
    render(
      <CircleChoices
        current={CLOSE}
        peer={{ id: PEER, chatId: CHAT, name: 'Оля' }}
        onPick={spy}
      />,
    );

    fireEvent.click(screen.getByText(uk[CIRCLE_KEY[SOCIAL]]));
    expect(spy).toHaveBeenCalledWith(SOCIAL);

    await screen.findByTestId('ring-downgrade-sheet');
    expect(screen.getByText('Не показувати, де я')).toBeTruthy();
    expect(screen.getByText('Не будити вночі')).toBeTruthy();

    fireEvent.click(screen.getByText('Застосувати'));

    await waitFor(() => {
      expect(screen.queryByTestId('ring-downgrade-sheet')).toBeNull();
    });
    expect(mockRequest).toHaveBeenCalledWith('DELETE', `/messenger/where/${PEER}`);
    expect(mockSetQuiet).toHaveBeenCalledWith({ except: [] });
  });

  it('does not probe or open sheet when upgrading to a closer ring', () => {
    const spy = vi.fn();
    render(
      <CircleChoices
        current={SOCIAL}
        peer={{ id: PEER, chatId: CHAT, name: 'Оля' }}
        onPick={spy}
      />,
    );

    fireEvent.click(screen.getByText(uk[CIRCLE_KEY[CLOSE]]));
    expect(spy).toHaveBeenCalledWith(CLOSE);
    expect(screen.queryByTestId('ring-downgrade-sheet')).toBeNull();
    expect(mockRequest).not.toHaveBeenCalled();
    expect(mockQuiet).not.toHaveBeenCalled();
  });

  it('opens no sheet on wider pick when peer prop is absent', () => {
    const spy = vi.fn();
    render(<CircleChoices current={CLOSE} onPick={spy} />);

    fireEvent.click(screen.getByText(uk[CIRCLE_KEY[SOCIAL]]));
    expect(spy).toHaveBeenCalledWith(SOCIAL);
    expect(screen.queryByTestId('ring-downgrade-sheet')).toBeNull();
    expect(mockRequest).not.toHaveBeenCalled();
    expect(mockQuiet).not.toHaveBeenCalled();
  });
});

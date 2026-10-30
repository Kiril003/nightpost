/**
 * Картка Solana в стрічці (СЛ-03, пакет W0-1).
 *
 * Міст (`WebMessengerKinds.solana` + `WebMessengerSolana.decorate`) кладе поруч
 * із реченням `body` поле `payment` або `seal` — і веб його не читав: переказ
 * і печатка були рядком тексту, а живий стан мережі губився. Контракт —
 * `docs/solana/PAYMENT_CARD_SPEC.md`; тут доводиться:
 *   1. дріт → Message: поле доїжджає, кривe поле не доїжджає (лишається body);
 *   2. п'ять станів → п'ять речень специфікації; `mismatch` — червоним словом;
 *   3. «отримано» не кажемо ніколи — лише «на ваш гаманець» чи адресу 4…4;
 *   4. на картці немає ні мережі, ні відбитка транзакції, ні посилання: вони лише в «Подробицях»
 *      (solanaCardDetails.test.tsx);
 *   5. досяжність: рядок стрічки (MessageBody → MessageEmbeds) з полем малює
 *      картку, а не речення.
 */
import { describe, it, expect, afterEach, vi, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import React from 'react';
import { messageFromNode } from '../services/messengerApi';
import type { NodeMessage } from '../services/messengerApi';
import { SolanaCard } from '../components/messenger/feed/SolanaCard';
import { MessageBody } from '../components/messenger/feed/MessageBody';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { buildFeedRows } from '../components/messenger/feed/feedModel';
import type { Message } from '../types/messenger';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

const buzz = vi.hoisted(() => vi.fn(() => true));
vi.mock('../utils/haptic', () => ({ haptic: buzz }));

const TO = 'BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH';
const SIG = '4WVSq3Jz9yGm1kXcR7pLtH2aNbE5uD8fW6vQhKjZsY3oTi1MnBc9xAeP4rUgmZHT';
const SHA = '3f2a9c0b1d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e';

const PAY = {
  amount: '2.5',
  token: 'USDC',
  units: '2500000',
  decimals: 6,
  cluster: 'devnet',
  to: TO,
  signature: SIG,
  offline_cash: false,
  status: 'pending',
  to_mine: false,
};

const SEAL = { sha256: SHA, signature: SIG, cluster: 'devnet', status: 'confirmed', block_time: 1759406700 };

const row = (over: Partial<NodeMessage> & Record<string, unknown>): NodeMessage =>
  ({
    id: 'm1', conversation_id: 'c1', client_id: 'x', seq: 1, author_id: 'peer', author_name: 'Оксана',
    kind: 'text', body: 'Переказ 2.5 USDC → BxsS…DfJH · чекає підтвердження мережі', ciphertext: null,
    transport: 'p2p', sent_at: '2026-10-02T10:00:00Z', edited_at: null, deleted_at: null,
    ...over,
  }) as NodeMessage;

const payMsg = (p: Record<string, unknown> = {}) => messageFromNode(row({ payment: { ...PAY, ...p } }), 'me', 'peer');
const sealMsg = (s: Record<string, unknown> = {}) =>
  messageFromNode(row({ body: 'Засвідчено в мережі: відбиток файла 3f2a9c…', seal: { ...SEAL, ...s } }), 'me', 'peer');

afterEach(cleanup);
beforeEach(() => buzz.mockClear());

describe('дріт → Message', () => {
  it('payment доїжджає цілим: сума рядком, без округлень', () => {
    const m = payMsg();
    expect(m.payment).toMatchObject({ amount: '2.5', token: 'USDC', cluster: 'devnet', to: TO, signature: SIG, status: 'pending' });
    expect(m.payment?.toMine).toBe(false);
    expect(m.text).toContain('Переказ 2.5 USDC');
  });

  it('seal доїжджає з часом блоку', () => {
    const m = sealMsg();
    expect(m.seal).toMatchObject({ sha256: SHA, signature: SIG, cluster: 'devnet', status: 'confirmed', blockTime: 1759406700 });
  });

  it('без поля — нічого не вигадано; криве поле не стає карткою', () => {
    expect(messageFromNode(row({}), 'me', 'peer').payment).toBeUndefined();
    expect(payMsg({ token: 'DOGE' }).payment).toBeUndefined();
    expect(payMsg({ amount: '2,5e9' }).payment).toBeUndefined();
    expect(payMsg({ cluster: 'testnet' }).payment).toBeUndefined();
    expect(payMsg({ signature: '' }).payment).toBeUndefined();
    expect(sealMsg({ sha256: 'abc' }).seal).toBeUndefined();
  });

  it('незнаний стан мережі — «невідомо», а не «підтверджено»', () => {
    expect(payMsg({ status: 'finalized' }).payment?.status).toBe('unknown');
    expect(payMsg({ status: undefined }).payment?.status).toBe('unknown');
    expect(payMsg({ to_mine: null }).payment?.toMine).toBeNull();
  });
});

const draw = (m: Message) => render(<SolanaCard msg={m} />);

describe('картка переказу', () => {
  const SAID: Array<[string, string]> = [
    ['pending', 'чекає підтвердження мережі'],
    ['confirmed', 'підтверджено мережею'],
    ['failed', 'мережа відхилила'],
    ['mismatch', 'у мережі не збігається з карткою'],
    ['unknown', 'стан невідомий — немає звʼязку'],
  ];
  for (const [status, sentence] of SAID) {
    it(`${status} → «${sentence}»`, () => {
      draw(payMsg({ status }));
      expect(screen.getByTestId('solana-status').textContent).toBe(sentence);
    });
  }

  it('готівка, що чекає, — два яруси, і в оглядачі ще нічого шукати', () => {
    draw(payMsg({ offline_cash: true, status: 'pending' }));
    expect(screen.getByTestId('solana-status').textContent).toBe('Ще не зараховано');
    expect(screen.getByTestId('solana-status-detail').textContent).toBe('Зарахується з першим інтернетом');
    expect(screen.getByText('Готівка')).toBeTruthy();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('зарахована готівка — «Зараховано» і посилання в оглядач', () => {
    draw(payMsg({ offline_cash: true, status: 'confirmed' }));
    expect(screen.getByTestId('solana-status').textContent).toBe('Зараховано');
    expect(screen.getByTestId('solana-status-detail').textContent).toBe('Підтверджено мережею Solana');
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('купюра-двійник, яку мережа відхилила, — ярус готівки червоним словом, без вигаданого пояснення', () => {
    draw(payMsg({ offline_cash: true, status: 'failed' }));
    const said = screen.getByTestId('solana-status');
    expect(said.textContent).toBe('мережа відхилила');
    expect(said.className).toContain('text-subhead');
    expect(said.className).toContain('text-danger');
    expect(screen.queryByTestId('solana-status-detail')).toBeNull();
  });

  it('на виду картки купюри — Nightpost, а не старе імʼя', () => {
    const { container } = draw(payMsg({ offline_cash: true, seeker_verified: true }));
    const seeker = screen.getByTestId('solana-seeker');
    expect(seeker.getAttribute('aria-label')).toContain('Nightpost');
    expect(container.innerHTML).not.toMatch(/phantom/i);
  });

  it('перехід «ще не зараховано → зараховано» на очах — печатка й одна вібрація', () => {
    const view = draw(payMsg({ offline_cash: true, status: 'pending', to_mine: true }));
    expect(view.container.querySelector('.ph-seal')).toBeNull();
    view.rerender(<SolanaCard msg={payMsg({ offline_cash: true, status: 'confirmed', to_mine: true })} />);
    expect(view.container.querySelector('.ph-seal')).not.toBeNull();
    expect(view.container.querySelector('.ph-seal-lock')).not.toBeNull();
    expect(buzz).toHaveBeenCalledTimes(1);
    expect(buzz).toHaveBeenCalledWith('success');
    view.rerender(<SolanaCard msg={payMsg({ offline_cash: true, status: 'confirmed', to_mine: true })} />);
    expect(buzz).toHaveBeenCalledTimes(1);
  });

  it('картка, що прийшла вже зарахованою, не вібрує й не оживає', () => {
    const { container } = draw(payMsg({ offline_cash: true, status: 'confirmed' }));
    expect(container.querySelector('.ph-seal')).toBeNull();
    expect(buzz).not.toHaveBeenCalled();
  });

  it('mismatch — червоне слово; підтверджене — ні', () => {
    draw(payMsg({ status: 'mismatch' }));
    expect(screen.getByTestId('solana-status').className).toContain('text-danger');
    cleanup();
    draw(payMsg({ status: 'confirmed' }));
    expect(screen.getByTestId('solana-status').className).not.toContain('text-danger');
  });

  it('сума й монета як є: 2,5 USDC', () => {
    draw(payMsg());
    expect(screen.getByTestId('solana-amount').textContent).toBe('2,5 USDC');
  });

  it('англійською — крапка, як у рядку списку: 2.5 USDC', () => {
    const was = useSettingsStore.getState().values[LANGUAGE_SETTING_KEY];
    useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
    try {
      draw(payMsg());
      expect(screen.getByTestId('solana-amount').textContent).toBe('2.5 USDC');
    } finally {
      useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: was } }));
    }
  });

  it('EURC — така сама картка, монета як є; незнана монета картки не дає', () => {
    const m = payMsg({ token: 'EURC', cluster: 'mainnet', amount: '5', units: '5000000' });
    expect(m.payment).toMatchObject({ amount: '5', token: 'EURC', cluster: 'mainnet' });
    draw(m);
    expect(screen.getByTestId('solana-amount').textContent).toBe('5 EURC');
    expect(payMsg({ token: 'EUR' }).payment).toBeUndefined();
  });

  it('«отримано» не каже ніколи; чужа адреса — 4…4', () => {
    for (const toMine of [false, null]) {
      const { container } = draw(payMsg({ to_mine: toMine, status: 'confirmed' }));
      expect(container.textContent?.toLowerCase()).not.toContain('отрим');
      expect(container.textContent).toContain('на адресу BxsS…DfJH');
      cleanup();
    }
    const { container } = draw(payMsg({ to_mine: true, status: 'confirmed' }));
    expect(container.textContent).toContain('на ваш гаманець');
    expect(container.textContent).not.toContain('твій');
    expect(container.textContent?.toLowerCase()).not.toContain('отрим');
  });

  it('на картці немає ні devnet, ні відбитка транзакції, ні посилання — на жодній мережі', () => {
    for (const cluster of ['devnet', 'mainnet']) {
      const { container } = draw(payMsg({ cluster, status: 'confirmed' }));
      expect(screen.queryByTestId('solana-cluster')).toBeNull();
      expect(container.textContent?.toLowerCase()).not.toContain('devnet');
      expect(container.textContent).not.toContain('4WVS…mZHT');
      expect(container.querySelector('a')).toBeNull();
      cleanup();
    }
  });

  it('«Seeker перевірено» — лише на готівці і лише за суворе true', () => {
    expect(payMsg({ offline_cash: true, seeker_verified: true }).payment?.seekerVerified).toBe(true);
    expect(payMsg({ offline_cash: true, seeker_verified: 'true' }).payment?.seekerVerified).toBe(false);
    expect(payMsg({ offline_cash: true }).payment?.seekerVerified).toBe(false);

    draw(payMsg({ offline_cash: true, seeker_verified: true }));
    expect(screen.getByTestId('solana-seeker').textContent).toBe('Відправник — перевірений Seeker');
    expect(screen.getByTestId('solana-seeker').className).toContain('text-footnote');
    cleanup();
    draw(payMsg({ offline_cash: true, seeker_verified: 1 }));
    expect(screen.queryByTestId('solana-seeker')).toBeNull();
    expect(screen.getByTestId('solana-seeker-none').textContent).toBe('Відправника не перевірено');
    cleanup();
    draw(payMsg({ offline_cash: false, seeker_verified: true }));
    expect(screen.queryByTestId('solana-seeker')).toBeNull();
  });

  it('стейк SKR: рівень суворо, рядок знака з телефона — лише на готівці', () => {
    const LABEL = 'Seeker перевірено · стейкає ≥10 тис. SKR';
    const m = payMsg({ offline_cash: true, seeker_verified: true, seeker_skr_tier: '10k', seeker_label: LABEL }).payment;
    expect(m?.seekerSkrTier).toBe('10k');
    expect(m?.seekerLabel).toBe(LABEL);
    expect(payMsg({ offline_cash: true, seeker_skr_tier: '100k' }).payment?.seekerSkrTier).toBeNull();
    expect(payMsg({ offline_cash: true, seeker_label: 42 }).payment?.seekerLabel).toBeNull();
    expect(payMsg({ offline_cash: true, seeker_label: '   ' }).payment?.seekerLabel).toBeNull();

    draw(payMsg({ offline_cash: true, seeker_verified: true, seeker_skr_tier: '10k', seeker_label: LABEL }));
    expect(screen.getByTestId('solana-seeker').textContent).toBe('Відправник — перевірений Seeker · стейкає ≥10 тис. SKR');
    cleanup();
    draw(payMsg({ offline_cash: true, seeker_verified: false, seeker_skr_tier: 'staker', seeker_label: 'стейкає SKR' }));
    expect(screen.getByTestId('solana-seeker').textContent).toBe('Відправник стейкає SKR');
    cleanup();
    draw(payMsg({ offline_cash: false, seeker_label: LABEL }));
    expect(screen.queryByTestId('solana-seeker')).toBeNull();
  });

  it('мова форми: без hex, без розмиття, Lucide 1.75', () => {
    const { container } = draw(payMsg({ status: 'mismatch' }));
    const html = container.innerHTML;
    expect(html).not.toMatch(/\[#[0-9A-Fa-f]{3,8}\]/);
    expect(html).not.toMatch(/blur|gradient/);
    const svgs = [...container.querySelectorAll('svg')];
    expect(svgs.length).toBeGreaterThan(0);
    for (const s of svgs) expect(s.getAttribute('stroke-width')).toBe('1.75');
  });
});

describe('картка печатки', () => {
  it('підтверджено — час блоку; відбиток коротко', () => {
    const { container } = draw(sealMsg());
    expect(screen.getByTestId('solana-status').textContent).toMatch(/^засвідчено мережею · .*2025/);
    expect(container.textContent).toContain('3f2a9c…5d6e');
    expect(container.querySelector('a')).toBeNull();
    expect(container.textContent?.toLowerCase()).not.toContain('devnet');
  });

  it('стани печатки', () => {
    for (const [status, sentence] of [
      ['pending', 'чекає підтвердження мережі'],
      ['failed', 'мережа відхилила'],
      ['mismatch', 'у мережі не збігається з карткою'],
    ]) {
      draw(sealMsg({ status, block_time: undefined }));
      expect(screen.getByTestId('solana-status').textContent).toBe(sentence);
      cleanup();
    }
  });

  it('confirmed без часу блоку — не вигадує дати', () => {
    draw(sealMsg({ block_time: null }));
    expect(screen.getByTestId('solana-status').textContent).toBe('засвідчено мережею');
  });
});

describe('досяжність: рядок стрічки', () => {
  const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;
  const inFeed = (m: Message) => {
    const [r] = buildFeedRows([m], 'me');
    return render(
      <FeedActionsContext.Provider value={actions}>
        <MessageBody row={r} phone currentUserId="me" currentUserName="Я" isActiveSearchMatch={false} expanded={false} copied={false} />
      </FeedActionsContext.Provider>,
    );
  };

  it('з полем payment — картка замість речення', () => {
    const { container } = inFeed(payMsg({ status: 'confirmed' }));
    expect(screen.getByTestId('solana-card')).toBeTruthy();
    expect(container.textContent).not.toContain('Переказ 2.5 USDC → BxsS…DfJH');
  });

  it('рядок стану: час і галочка, без відбитка транзакції та значка оглядача', () => {
    const { container } = inFeed({ ...payMsg({ status: 'confirmed' }), timestamp: '15:20' });
    expect(screen.getByTestId('solana-card-time').textContent).toBe('15:20');
    expect(container.querySelector('a')).toBeNull();
    expect(container.textContent).not.toContain('4WVS…mZHT');
  });

  it('з полем seal — картка печатки', () => {
    inFeed(sealMsg());
    expect(screen.getByTestId('solana-card').textContent).toContain('Відбиток файла');
  });

  it('без поля — запасне речення body, картки немає', () => {
    const { container } = inFeed(messageFromNode(row({}), 'me', 'peer'));
    expect(screen.queryByTestId('solana-card')).toBeNull();
    expect(container.textContent).toContain('Переказ 2.5 USDC');
  });
});

// React у JSX-розмітці тесту.
void React;

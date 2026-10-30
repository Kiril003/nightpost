/**
 * Дзвінки на телефоні: «поки ні», сказане на екрані — і жодного чужого сервера.
 *
 * Виміряно 03.09: у WebView усередині APK міст віддає 404 на `relay|ice|call`.
 * При цьому `callEngine.iceConfig()` на 404 віддавав `stun.l.google.com`, а
 * `messengerNetworkEngine` тримав три STUN Google константою — тобто дотик до
 * слухавки (або до «P2P Протоколи» → ручний офер) слав STUN-запит у Google і
 * показував йому адресу апарата. Це витік, а не незручність: продукт, який
 * каже «без сервера», не сміє мати запасного чужого сервера.
 *
 * Тут доводиться дві речі, і обидві — про телефон:
 *   1. дверей до дзвінка нема, натомість сказано словами, чому;
 *   2. список ICE порожній, а не «запасний».
 *
 * 04.09 перше з двох переписано. Воно рендерило `UserProfileModal` із пропом
 * `viewingMember`, якого продукт НЕ ПЕРЕДАЄ ЖОДНОГО РАЗУ: тест сам підставляв
 * умову, за якої гілка існує, і був зелений про кнопки, яких людина ніколи не
 * бачила. Тепер монтується те саме, що монтує застосунок — `MessengerRoot`, —
 * а слухавку шукаємо там, де вона справді живе: у шапці розмови.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessengerRoot } from '../components/messenger/MessengerRoot';
import { ToastRail } from '../components/core/ToastRail';
import { useUIStore } from '../stores/uiStore';
import { useMessengerStore } from '../stores/messengerStore';
import { messengerApi } from '../services/messengerApi';
import type { NodeContact, NodeConversation } from '../services/messengerApi';
import { NO_CALL_ROADS } from '../services/callGate';

const bridge404 = {
  call: (_m: string, path: string) => {
    if (path.startsWith('/messenger/ice')) {
      return JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' });
    }
    return JSON.stringify({ ok: true, status: 200, data: {} });
  },
};

/** Міст, який каже вголос, що вхідні дзвінки приносить: `inbound: true` у /messenger/ice. */
const bridgeInbound = {
  call: (_m: string, path: string) => {
    if (path.startsWith('/messenger/ice')) {
      return JSON.stringify({ ok: true, status: 200, data: { iceServers: [], turn: false, ttl: 0, inbound: true } });
    }
    return JSON.stringify({ ok: true, status: 200, data: {} });
  },
};

const setPhone = (on: boolean, bridge: unknown = bridge404) => {
  if (on) (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = bridge;
  else delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
};

const PEER = 'f0e1d2c3b4a59687';

const CONVERSATION: NodeConversation = {
  id: 'conv_marta',
  title: 'Марта',
  kind: 'dm',
  circle: 'friends',
  handle: null,
  avatar: null,
  pinned: false,
  archived: false,
  created_at: '2026-09-04T10:00:00',
  updated_at: '2026-09-04T10:00:00',
  peer_node_id: PEER,
};

const CONTACT: NodeContact = {
  id: 'ct_marta',
  peer_node_id: PEER,
  display_name: 'Марта',
  peer_address: null,
  safety_number: '111112222233333444445555566666',
  safety_number_pretty: '11111 22222 33333 44444 55555 66666',
  verified: false,
  session_ready: true,
  created_at: '2026-09-01T10:00:00',
};

const openConversation = async () => {
  // Телефон сам жодної розмови не відкриває (порожній стан говорить сам), тож
  // ставимо ту, яку відкрив би палець; гідрація її не збиває — вона є в ростері.
  useMessengerStore.setState({ chats: [], activeChatId: CONVERSATION.id, hydrated: false });
  vi.spyOn(messengerApi, 'bootstrap').mockResolvedValue([CONVERSATION]);
  vi.spyOn(messengerApi, 'listMessages').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([CONTACT]);
  // `ToastRail` стоїть поруч, бо саме так його монтує корінь застосунку
  // (`App.tsx`) — без нього тест питав би «чи сказано вголос» у кімнаті, де
  // рота немає, і мовчазна відмова лишилась би зеленою.
  render(
    <MemoryRouter>
      <MessengerRoot />
      <ToastRail />
    </MemoryRouter>,
  );
  return screen.findByTitle('Профіль');
};

afterEach(() => {
  setPhone(false);
  vi.restoreAllMocks();
  // Тости живуть у модульному сторі й переживають розмонтування: без цього
  // наступний тест бачив би скаргу попереднього й міряв чужий екран.
  useUIStore.setState({ toasts: [] });
  // Рушій кешує конфіг ICE у модульному стані — без скидання наступний тест
  // міряє чужий кеш, а не свій телефон.
  vi.resetModules();
});

describe('1:1 дзвінки на телефоні та ПК', () => {
  /**
   * 12.09 тут стверджувалось протилежне: слухавки на телефоні НЕМАЄ зовсім, бо
   * відповідь співрозмовника в сторінку не приносив ніхто. Відтоді дорогу
   * добудували — `PeerCallHub` віддає тіло сигналу в `__phantomCallSignal`, і
   * міст її кличе, — а умова ховання лишилась. Ціна виміряна 15.09 на апараті
   * власника: `inbound` питається ОДИН раз за монтування, а слухач сітки
   * підіймає сокет пізніше, ніж веб устигає спитати. Одна відповідь `false`,
   * узята в ту гонку, ховала пару кнопок на весь сеанс — і «дзвінки взагалі не
   * працюють» було буквальною правдою: натискати не було чого.
   *
   * Новий договір: кнопка стоїть завжди. Поки вузол не сказав «приймаю» —
   * вона сіра, дзвінка НЕ починає, і на дотик каже причину вголос. Сірий
   * колір без слів на тачскріні — це та сама мовчазна відмова.
   */
  it('вузол ще не сказав inbound — слухавка на місці, мовчки не відмовляє', async () => {
    const { fireEvent } = await import('@testing-library/react');
    setPhone(true);
    await openConversation();

    const audio = await screen.findByLabelText('Аудіодзвінок');
    expect(audio.getAttribute('aria-disabled')).toBe('true');
    expect(screen.getByLabelText('Відеодзвінок').getAttribute('aria-disabled')).toBe('true');

    // Дзвінок не починається — це головне, що стерегла стара умова.
    const started = vi.fn();
    window.addEventListener('phantom:start-call', started);
    fireEvent.click(audio);
    expect(started).not.toHaveBeenCalled();
    window.removeEventListener('phantom:start-call', started);

    // …але причина сказана, а не залишена в `title`, якого на дотик не видно.
    expect(
      await screen.findByText(NO_CALL_ROADS),
    ).toBeTruthy();
  });

  it('міст сказав inbound:true — слухавка й камера на телефоні живі', async () => {
    setPhone(true, bridgeInbound);
    await openConversation();
    const audio = await screen.findByLabelText('Аудіодзвінок');
    await vi.waitFor(() => expect(audio.getAttribute('aria-disabled')).toBe('false'));
    expect(screen.getByLabelText('Відеодзвінок').getAttribute('aria-disabled')).toBe('false');
    expect(screen.queryByText(NO_CALL_ROADS)).toBeNull();
  });

  it('на ПК ті самі кнопки лишаються: там дзвінок їде', async () => {
    setPhone(false);
    await openConversation();
    expect(await screen.findByLabelText('Аудіодзвінок')).toBeTruthy();
    expect(screen.getByLabelText('Відеодзвінок')).toBeTruthy();
  });
});

describe('порожній список ICE замість чужого STUN', () => {
  beforeEach(() => setPhone(true));

  it('404 від мосту на /messenger/ice = жодного сервера, не Google', async () => {
    const { callEngine } = await import('../services/callEngine');
    const info = await callEngine.iceInfo();
    expect(info.urls).toEqual([]);
    expect(info.turn).toBe(false);
    // Найдорожчий рядок цього тесту: доведення, що запасного чужого нема.
    expect(info.urls.join(' ')).not.toContain('google');
  });

  it('транспортний рушій на телефоні будує зʼєднання без жодного сервера', async () => {
    const seen: RTCConfiguration[] = [];
    class FakePC {
      constructor(config: RTCConfiguration) {
        seen.push(config);
      }
      createDataChannel() {
        return {};
      }
      async createOffer() {
        return { type: 'offer', sdp: '' };
      }
      async setLocalDescription() {}
      localDescription = { type: 'offer', sdp: '' };
      set onicecandidate(_fn: unknown) {}
    }
    vi.stubGlobal('RTCPeerConnection', FakePC as unknown as typeof RTCPeerConnection);

    const { messengerNetworkEngine } = await import('../services/messengerNetworkEngine');
    await messengerNetworkEngine.createManualOffer();

    expect(seen.length).toBeGreaterThan(0);
    for (const config of seen) {
      expect(config.iceServers ?? []).toEqual([]);
    }
    vi.unstubAllGlobals();
  });
});

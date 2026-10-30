import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useSystemStore, IDLE_STATE_SYNC } from '../stores/systemStore';
import { contextApi } from '../services/api';
import { SystemState } from '@shared/types';

vi.mock('../services/api', () => ({
  contextApi: {
    setState: vi.fn().mockResolvedValue({}),
  },
}));

const nodeCall = vi.mocked(contextApi.setState);

describe('systemStore', () => {
  beforeEach(() => {
    nodeCall.mockReset();
    nodeCall.mockResolvedValue({ to: SystemState.SHADOW });
    useSystemStore.setState({
      state: SystemState.SHADOW,
      previousState: null,
      stateHistory: [],
      context: null,
      authenticated: false,
      wsConnected: false,
      stateSync: IDLE_STATE_SYNC,
    });
  });

  it('starts in SHADOW state', () => {
    const { state } = useSystemStore.getState();
    expect(state).toBe(SystemState.SHADOW);
  });

  it('transitions to new state', () => {
    useSystemStore.getState().setState(SystemState.FOCUS, {
      trigger: 'work_context_active',
      timestamp: 1000,
      auto: true,
    });

    const { state, previousState } = useSystemStore.getState();
    expect(state).toBe(SystemState.FOCUS);
    expect(previousState).toBe(SystemState.SHADOW);
  });

  it('records state history', () => {
    useSystemStore.getState().setState(SystemState.FOCUS, { trigger: 'test', timestamp: 1000, auto: true });
    useSystemStore.getState().setState(SystemState.DIALOGUE, { trigger: 'voice_input', timestamp: 2000, auto: false });

    const { stateHistory } = useSystemStore.getState();
    expect(stateHistory).toHaveLength(2);
    expect(stateHistory[0].from).toBe(SystemState.SHADOW);
    expect(stateHistory[0].to).toBe(SystemState.FOCUS);
    expect(stateHistory[1].from).toBe(SystemState.FOCUS);
    expect(stateHistory[1].to).toBe(SystemState.DIALOGUE);
  });

  it('does not transition to same state', () => {
    useSystemStore.getState().setState(SystemState.SHADOW, { trigger: 'test', timestamp: 1000, auto: true });

    const { stateHistory, previousState } = useSystemStore.getState();
    expect(stateHistory).toHaveLength(0);
    expect(previousState).toBeNull();
  });

  it('limits history to 100 entries', () => {
    for (let i = 0; i < 150; i++) {
      const to = i % 2 === 0 ? SystemState.FOCUS : SystemState.SHADOW;
      useSystemStore.getState().setState(to, { trigger: `test_${i}`, timestamp: i * 1000, auto: true });
    }

    const { stateHistory } = useSystemStore.getState();
    expect(stateHistory.length).toBeLessThanOrEqual(100);
  });

  it('sets authenticated state', () => {
    useSystemStore.getState().setAuthenticated(true);
    expect(useSystemStore.getState().authenticated).toBe(true);
  });

  it('sets ws connected state', () => {
    useSystemStore.getState().setWsConnected(true);
    expect(useSystemStore.getState().wsConnected).toBe(true);
  });

  it('sets context snapshot', () => {
    const mockContext = createMockContext(SystemState.FOCUS);
    useSystemStore.getState().setContext(mockContext);

    const { context } = useSystemStore.getState();
    expect(context).not.toBeNull();
    expect(context!.system.state).toBe(SystemState.FOCUS);
    expect(context!.who.username).toBe('test_user');
  });

  it('transitions through all valid states', () => {
    const states = [
      SystemState.FOCUS,
      SystemState.DIALOGUE,
      SystemState.SENTINEL,
      SystemState.GHOST,
      SystemState.DREAM,
      SystemState.SHADOW,
    ];

    for (const s of states) {
      useSystemStore.getState().setState(s, { trigger: 'test', timestamp: Date.now(), auto: true });
      expect(useSystemStore.getState().state).toBe(s);
    }

    expect(useSystemStore.getState().stateHistory).toHaveLength(states.length);
  });
});

/**
 * Ручна зміна режиму — це два кроки: скло міняє себе одразу, вузол
 * дізнається окремим запитом. Другий крок мовчав: `void contextApi.setState()`
 * викидав проміс, тож відмова не доходила ні до людини, ні до тесту.
 *
 * ЩО САМЕ ТУТ ТРИМАЄТЬСЯ (мутації, від яких має червоніти):
 *   • повернути `void contextApi.setState(...)`  -> «відмова тікає» стає
 *     необробленою відмовою промісу і ловиться першим тестом;
 *   • підмінити на `.catch(() => {})`            -> відмова більше не тікає,
 *     але й не стає станом: `stateSync.status` лишається 'pending';
 *   • прибрати `try/catch` навколо виклику       -> синхронний зрив вилітає
 *     назовні з `setState` і ловиться третім тестом.
 *
 * ТІНЬ / ВАРТОВИЙ / ПРИВИД — режими приватності й безпеки, і ПРИВИД гасить
 * камеру локально. Мовчазна відмова означає захисний режим на склі, якого
 * вузол не вмикав; саме тому це тест, а не запис у документі.
 */
describe('systemStore — чи знає вузол про ручну зміну режиму', () => {
  beforeEach(() => {
    nodeCall.mockReset();
    nodeCall.mockResolvedValue({ to: SystemState.SHADOW });
    useSystemStore.setState({
      state: SystemState.SHADOW,
      previousState: null,
      stateHistory: [],
      stateSync: IDLE_STATE_SYNC,
    });
  });

  const goManually = (to: SystemState) =>
    useSystemStore.getState().setState(to, {
      trigger: 'toolbar',
      timestamp: Date.now(),
      auto: false,
    });

  it('відмова вузла стає станом, а не необробленою відмовою промісу', async () => {
    // Слухач ловить рівно те, чим хворів `void ...`: проміс, у якого немає
    // обробника відмови. Якщо його повернуть — тут зʼявиться запис.
    const escaped: unknown[] = [];
    const onUnhandled = (reason: unknown) => escaped.push(reason);
    process.on('unhandledRejection', onUnhandled);
    try {
      nodeCall.mockRejectedValueOnce(new Error('503 вузол не відповідає'));
      goManually(SystemState.GHOST);
      // Necessary: Node оголошує відмову необробленою наступним тіком, а не
      // в мікрозадачі, тож без реального очікування вимір був би ні про що.
      await new Promise((r) => setTimeout(r, 20));
    } finally {
      process.off('unhandledRejection', onUnhandled);
    }

    expect(
      escaped,
      'відмова вузла вилетіла в нікуди — саме це давало помилку без жодного червоного тесту',
    ).toEqual([]);

    const sync = useSystemStore.getState().stateSync;
    expect(sync.status, 'порожній .catch() — те саме ковтання, тільки тихіше').toBe('failed');
    expect(sync.target).toBe(SystemState.GHOST);
    expect(sync.reason).toContain('503');
  });

  it('лишає режим увімкненим локально: ПРИВИД не вмикають назад через мережу', async () => {
    nodeCall.mockRejectedValueOnce(new Error('мережі немає'));
    goManually(SystemState.GHOST);
    await new Promise((r) => setTimeout(r, 20));
    // Відкат був би гіршим за мовчання: він УВІМКНУВ БИ камеру назад через
    // те, що не доїхав запит. Локально лишаємось у захисному режимі й
    // говоримо правду про вузол.
    expect(useSystemStore.getState().state).toBe(SystemState.GHOST);
    expect(useSystemStore.getState().stateSync.status).toBe('failed');
  });

  it('синхронний зрив виклику теж стає станом, а не вилітає з setState', () => {
    // Рівно та форма, що падала в deadControls: доступ до клієнта зривався
    // СИНХРОННО, тож промісу не існувало й `.catch` не спіймав би нічого.
    nodeCall.mockImplementationOnce(() => {
      throw new Error('клієнта до вузла немає');
    });
    expect(() => goManually(SystemState.SENTINEL)).not.toThrow();
    const sync = useSystemStore.getState().stateSync;
    expect(sync.status).toBe('failed');
    expect(sync.target).toBe(SystemState.SENTINEL);
    expect(sync.reason).toContain('клієнта');
  });

  it('підтверджена зміна не лишає тривоги на склі', async () => {
    goManually(SystemState.SENTINEL);
    expect(useSystemStore.getState().stateSync.status).toBe('pending');
    await new Promise((r) => setTimeout(r, 20));
    const sync = useSystemStore.getState().stateSync;
    expect(sync.status).toBe('synced');
    expect(sync.reason).toBeNull();
  });

  it('пізня відмова на скасовану зміну не переписує свіжу', async () => {
    let failFirst: (e: Error) => void = () => {};
    nodeCall.mockImplementationOnce(
      () => new Promise((_ok, rej) => { failFirst = rej; }),
    );
    goManually(SystemState.SENTINEL);
    goManually(SystemState.SHADOW);
    failFirst(new Error('запізніла відмова про ВАРТОВОГО'));
    await new Promise((r) => setTimeout(r, 20));
    const sync = useSystemStore.getState().stateSync;
    expect(sync.target, 'смужка назвала б режим, якого вже немає').toBe(SystemState.SHADOW);
  });

  it('автоматичні переходи вузлу не шлються й тривоги не чіпають', async () => {
    useSystemStore.getState().setState(SystemState.FOCUS, {
      trigger: 'work_context_active',
      timestamp: 1000,
      auto: true,
    });
    await new Promise((r) => setTimeout(r, 20));
    expect(nodeCall).not.toHaveBeenCalled();
    expect(useSystemStore.getState().stateSync.status).toBe('idle');
  });

  it('clearStateSync прибирає тривогу — і лише за дотиком', () => {
    useSystemStore.setState({
      stateSync: { status: 'failed', target: SystemState.GHOST, reason: 'x', at: 1 },
    });
    useSystemStore.getState().clearStateSync();
    expect(useSystemStore.getState().stateSync).toEqual(IDLE_STATE_SYNC);
  });
});

function createMockContext(state: SystemState) {
  return {
    timestamp: Date.now(),
    who: {
      user_id: 'u1',
      username: 'test_user',
      confidence: 1,
      auth_method: 'pin' as const,
      role: 'ROOT' as const,
    },
    where: {
      lat: 50.45,
      lon: 30.52,
      fix: true,
      satellites: 8,
      speed_kmh: 0,
      place_known: true,
      place_name: 'Home',
      first_visit: false,
    },
    when: {
      time: '14:30',
      hour: 14,
      day_of_week: 'mon',
      date: '2026-04-16',
      work_hours: true,
      is_night: false,
    },
    body: {
      breathing_bpm: 16,
      breathing_state: 'calm' as const,
      stress_level: 0.2,
      motion_energy: 30,
      static_energy: 50,
      user_distance_cm: 80,
    },
    env: {
      temp_c: 22.5,
      pressure_hpa: 1013,
      aqi: 42,
    },
    presence: {
      user_detected: true,
      user_distance_cm: 80,
      other_detected: false,
      other_distance_cm: null,
    },
    history: {
      last_interaction_ago_s: 10,
      last_state_change_ago_s: 300,
      mood_trend: 'stable' as const,
      active_timers: 0,
      pending_events_1h: 0,
    },
    memory_hints: ['User prefers short answers'],
    system: {
      state,
      uptime_s: 3600,
      cpu_percent: 25,
      ram_percent: 45,
      disk_percent: 60,
      wifi_connected: true,
      internet_available: true,
      ai_provider: 'gemini' as const,
      stt_engine: 'whisper' as const,
    },
  };
}

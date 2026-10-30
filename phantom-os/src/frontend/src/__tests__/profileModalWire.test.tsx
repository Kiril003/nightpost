/**
 * Профіль на екрані: чотири стани видно по-різному, а кадр справді їде.
 *
 * **Чому цей файл існує.** `peerProfile.test.ts` доводить, що ЧОТИРИ написи
 * різні — але це твердження про рядки в модулі, не про екран. Між ними лежала
 * рівно та вада, заради якої все й писалось: у дереві 16.09 модуль
 * `peerProfile` був імпортований у `UserProfileModal` і не викликаний ЖОДНОГО
 * разу (`tsc --noEmit`: 15 помилок TS6133 в одному файлі). Тобто розрізнення
 * їхало дротом, лежало в типі, було зелене в тесті — і не доходило до ока.
 *
 * Тому тут питається саме про намальоване: різні `data-testid`, різні значок і
 * колір. Рядки звіряються між собою, а не з очікуваним літералом: тест мусить
 * ловити ЗЛИТТЯ станів, а не забороняти переписати речення.
 *
 * **Про двері.** Компонент монтується прямо, а не через `MessengerRoot`, і це
 * свідомо: досяжність картки вже доведено окремо
 * (`peerCardReachable.test.tsx` — дотик до назви розмови відкриває саме її).
 * Дублювати той прохід на кожне твердження про вміст означало б платити
 * секунду за факт, який уже доведено.
 *
 * Червоніти вміє: зведіть `FieldGap` до одного сірого прочерку — падають
 * перші три; приберіть `await publishToWire()` з `handleSave` — падають
 * наступні чотири; поверніть `setAvatar(url)` замість `handlePresetPick` —
 * падає «пресет не їде мовчки».
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { UserProfileModal } from '../components/messenger/UserProfileModal';
import { messengerApi } from '../services/messengerApi';
import type { NodeContact, PublishProfileReport } from '../services/messengerApi';
import { storagePersistence } from '../services/storagePersistence';
import { useUIStore } from '../stores/uiStore';
import type { UserProfile } from '../types/messenger';

const PEER = 'aa11bb22cc33dd44';

const ME: UserProfile = {
  id: 'me',
  name: 'Кирило',
  handle: '@kyrylo',
  avatar: '',
  status: 'Фокус над кодом',
  statusEmoji: '⚡',
  bio: 'Будую Nightpost',
  phone: '',
  locationName: '',
  batteryLevel: 80,
  isMutedAlerts: false,
};

const contact = (over: Partial<NodeContact>): NodeContact => ({
  id: 'ct_1',
  peer_node_id: PEER,
  display_name: 'Марта',
  peer_address: null,
  safety_number: '111112222233333444445555566666',
  safety_number_pretty: '11111 22222 33333 44444 55555 66666',
  fingerprint_pretty: 'AB12 CD34 EF56 7890',
  verified: false,
  session_ready: true,
  created_at: '2026-09-01T10:00:00',
  ...over,
});

const REPORT: PublishProfileReport = {
  rev: 3,
  frame_bytes: 812,
  sent: ['p1', 'p2'],
  sent_count: 2,
  skipped: [{ peer_node_id: 'p3', reason: 'no_key' }],
  skipped_count: 1,
};

/** Найдрібніший справжній JPEG — лише шапка, якої досить для `imageKind`. */
const JPEG_B64 = ((): string => {
  const bytes = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]);
  let s = '';
  bytes.forEach((b) => { s += String.fromCharCode(b); });
  return btoa(s);
})();

const SVG_PRESET = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg"></svg>';

const openPeer = (over: Partial<NodeContact>) => {
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([contact(over)]);
  render(
    <UserProfileModal
      isOpen
      onClose={() => {}}
      currentUser={ME}
      targetUser={{ id: PEER, name: 'Марта', avatar: '', handle: 'marta', peerNodeId: PEER } as never}
    />,
  );
};

const openSelf = (avatar = '') => {
  render(
    <UserProfileModal
      isOpen
      onClose={() => {}}
      currentUser={{ ...ME, avatar }}
      targetUser={null}
    />,
  );
};

beforeEach(() => {
  vi.spyOn(messengerApi, 'identity').mockRejectedValue(new Error('немає моста'));
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(storagePersistence, 'saveUserProfile').mockResolvedValue(undefined as never);
  vi.spyOn(storagePersistence, 'getLocalUsageBytes').mockReturnValue(0);
  useUIStore.setState({ toasts: [] });
});

afterEach(() => {
  // Аркуш живе в порталі на body: спершу розмонтувати, потім чистити body.
  cleanup();
  vi.restoreAllMocks();
  document.body.innerHTML = '';
});

describe('свій профіль: кадр їде, і звіт про нього не бреше', () => {
  it('«Зберегти й надіслати» справді кличе вузол', async () => {
    const pub = vi.spyOn(messengerApi, 'publishProfile').mockResolvedValue(REPORT);
    openSelf();
    fireEvent.click(screen.getByText('Зберегти й надіслати'));
    await waitFor(() => expect(pub).toHaveBeenCalledTimes(1));
    const sent = pub.mock.calls[0][0];
    expect(sent.status_text).toBe('Фокус над кодом');
    expect(sent.bio).toBe('Будую Nightpost');
    // Фото немає — це «в мене його немає», а не «нічого нового про фото».
    expect(sent.avatar).toBeNull();
  });

  it('звіт — людськими словами: кому надіслано, без байтів і ключів', async () => {
    vi.spyOn(messengerApi, 'publishProfile').mockResolvedValue(REPORT);
    openSelf();
    fireEvent.click(screen.getByText('Зберегти й надіслати'));
    const line = await screen.findByTestId('profile-wire-report');
    expect(line.textContent).toContain('Надіслано 2 контактам');
    expect(line.textContent).not.toMatch(/байт|ключ|кадр|версія|\b812\b/);
  });

  it('відмова вузла не малюється як успіх', async () => {
    vi.spyOn(messengerApi, 'publishProfile').mockRejectedValue(
      Object.assign(new Error('біо: 640 байт понад стелю 512'), { code: 'too_big' }),
    );
    openSelf();
    fireEvent.click(screen.getByText('Зберегти й надіслати'));
    const err = await screen.findByTestId('profile-wire-error');
    // Слово ВУЗЛА, не наше: він знає причину точніше.
    expect(err.textContent).toContain('640 байт');
    expect(screen.queryByTestId('profile-wire-report')).toBeNull();
  });

  it('пресет-SVG не їде — і про це сказано, а не змовчано', async () => {
    const pub = vi.spyOn(messengerApi, 'publishProfile').mockResolvedValue(REPORT);
    openSelf(SVG_PRESET);
    fireEvent.click(screen.getByText('Зберегти й надіслати'));
    await waitFor(() => expect(pub).toHaveBeenCalled());
    // Тега в кадрі немає: у співрозмовника лишається те, що він знав.
    expect('avatar' in pub.mock.calls[0][0]).toBe(false);
    const line = await screen.findByTestId('profile-wire-report');
    expect(line.textContent).toContain('контакти не побачать');
  });

  it('збережена мініатюра їде й без повторного вибору файлу', async () => {
    const pub = vi.spyOn(messengerApi, 'publishProfile').mockResolvedValue(REPORT);
    openSelf(`data:image/jpeg;base64,${JPEG_B64}`);
    fireEvent.click(screen.getByText('Зберегти й надіслати'));
    await waitFor(() => expect(pub).toHaveBeenCalled());
    expect(pub.mock.calls[0][0].avatar).toBe(JPEG_B64);
  });

  it('«прибрати фото» шле ЯВНЕ очищення, а не мовчанку', async () => {
    const pub = vi.spyOn(messengerApi, 'publishProfile').mockResolvedValue(REPORT);
    openSelf(`data:image/jpeg;base64,${JPEG_B64}`);
    fireEvent.click(screen.getByTestId('profile-avatar-clear'));
    expect(screen.getByTestId('profile-avatar-note').textContent).toContain('прибрано');
    fireEvent.click(screen.getByText('Зберегти й надіслати'));
    await waitFor(() => expect(pub).toHaveBeenCalled());
    expect(pub.mock.calls[0][0].avatar).toBeNull();
  });
});

describe('фото на повний екран', () => {
  it('дотик до власного фото відкриває його без технічної примітки', async () => {
    openSelf(`data:image/jpeg;base64,${JPEG_B64}`);
    fireEvent.click(screen.getByTestId('profile-own-avatar'));
    expect(await screen.findByTestId('avatar-viewer')).toBeTruthy();
    expect(screen.queryByText(/Саме це побачать ваші контакти/)).toBeNull();
  });

  it('коли фото немає — відкривати нічого, і кнопка про це знає', async () => {
    openSelf('');
    const btn = screen.getByTestId('profile-own-avatar') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
  });
});

describe('чужий профіль — той самий екран, що з шапки розмови', () => {
  it('учасник групи відкривається в профілі людини, а не в старій картці з відбитком', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    openPeer({});
    expect(await screen.findByTestId('peer-profile')).toBeTruthy();
    expect(screen.queryByTestId('peer-fingerprint')).toBeNull();
  });
});

describe('свій профіль на телефоні: імʼя — вибір людини, а не модель апарата', () => {
  const asPhone = () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {};
  };
  afterEach(() => {
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });

  it('поле імені показує те, що дав вузол, і нове імʼя їде у вузол', async () => {
    asPhone();
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({ bundle: { name: 'Nightpost-A52' } } as never);
    const rename = vi.spyOn(messengerApi, 'renameSelf').mockResolvedValue({ bundle: { name: 'Кирило' } } as never);
    vi.spyOn(messengerApi, 'publishProfile').mockResolvedValue(REPORT);
    openSelf();
    const field = (await screen.findByTestId('profile-name')) as HTMLInputElement;
    await waitFor(() => expect(field.value).toBe('Nightpost-A52'));
    fireEvent.change(field, { target: { value: 'Кирило' } });
    fireEvent.click(screen.getByText('Зберегти й надіслати'));
    await waitFor(() => expect(rename).toHaveBeenCalledWith('Кирило'));
  });

  it('імʼя не чіпали — вузол не питаємо перейменувати', async () => {
    asPhone();
    vi.spyOn(messengerApi, 'identity').mockResolvedValue({ bundle: { name: 'Кирило' } } as never);
    const rename = vi.spyOn(messengerApi, 'renameSelf');
    const pub = vi.spyOn(messengerApi, 'publishProfile').mockResolvedValue(REPORT);
    openSelf();
    fireEvent.click(await screen.findByText('Зберегти й надіслати'));
    await waitFor(() => expect(pub).toHaveBeenCalled());
    expect(rename).not.toHaveBeenCalled();
  });
});

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { useMessengerStore } from '../stores/messengerStore';
import { loadProfiles, useProfiles } from '../stores/profilesStore';
import { useChatProfiles } from '../stores/chatProfileStore';
import { useUIStore } from '../stores/uiStore';
import { installNativeOpenChat } from '../services/nativeOpenChat';

type Win = { __phantomOpenChat?: (id: string) => boolean };
const open = (id: string) => (window as unknown as Win).__phantomOpenChat?.(id);
const chat = (id: string) => ({ id, type: 'direct', title: id, messages: [] });

let personal = '';
let work = '';
let off: () => void = () => undefined;

beforeEach(() => {
  localStorage.clear();
  useProfiles.setState(loadProfiles());
  personal = useProfiles.getState().profiles[0].id;
  work = useProfiles.getState().addFromTemplate('work').id;
  useProfiles.getState().setActive(personal);
  useChatProfiles.setState({ map: { peer_w: work, peer_p: personal } });
  useUIStore.setState({ toasts: [] });
  useMessengerStore.setState({ chats: [], activeChatId: '', hydrated: true } as never);
});

afterEach(() => off());

describe('тап по сповіщенню з іншого профілю', () => {
  it('перемикає на профіль розмови і каже «Ви в «Робота»»', () => {
    useMessengerStore.setState({ chats: [chat('peer_w'), chat('peer_p')] } as never);
    off = installNativeOpenChat();
    expect(open('peer_w')).toBe(true);
    expect(useProfiles.getState().activeId).toBe(work);
    expect(useMessengerStore.getState().activeChatId).toBe('peer_w');
    expect(useUIStore.getState().toasts.map((t) => t.message)).toEqual(['Ви в «Робота»']);
  });

  it('розмова свого профілю — без перемикання й без тосту', () => {
    useMessengerStore.setState({ chats: [chat('peer_w'), chat('peer_p')] } as never);
    off = installNativeOpenChat();
    expect(open('peer_p')).toBe(true);
    expect(useProfiles.getState().activeId).toBe(personal);
    expect(useUIStore.getState().toasts).toEqual([]);
  });

  it('розмова, що прийшла пізніше, теж відкривається у своєму профілі', () => {
    off = installNativeOpenChat();
    expect(open('peer_w')).toBe(false);
    expect(useProfiles.getState().activeId).toBe(personal);
    useMessengerStore.setState({ chats: [chat('peer_w')] } as never);
    expect(useMessengerStore.getState().activeChatId).toBe('peer_w');
    expect(useProfiles.getState().activeId).toBe(work);
  });
});

import { afterEach, describe, expect, it } from 'vitest';
import { chatFromNode, SAVED_NOTES_ID, type NodeConversation } from '../services/messengerApi';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

const row = (id: string, title: string) =>
  ({ id, title, kind: 'dm', circle: 'all', avatar: null, pinned: true, archived: false, created_at: '2026-10-18T00:00:00Z', updated_at: '2026-10-18T00:00:00Z' }) as NodeConversation;
const lang = (l: string) => useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

afterEach(() => lang('uk'));

describe('«Збережене» is named by the web catalogue, not by the node', () => {
  it('the node sends «Saved», the row reads in the app language', () => {
    for (const [l, name] of [['uk', 'Збережене'], ['en', 'Saved'], ['cs', 'Uložené']] as const) {
      lang(l);
      expect(chatFromNode(row(SAVED_NOTES_ID, 'Saved')).title).toBe(name);
    }
  });

  it('a person keeps the name the node sent', () => {
    lang('uk');
    expect(chatFromNode(row('k1', 'Saved')).title).toBe('Saved');
  });
});

describe('«Збережене» follows a language switch without a reload', () => {
  it('the stored row is renamed when the app language changes', async () => {
    const { useMessengerStore } = await import('../stores/messengerStore');
    lang('uk');
    useMessengerStore.setState({ chats: [chatFromNode(row(SAVED_NOTES_ID, 'Saved')), chatFromNode(row('k1', 'Оксана'))] });
    void useSettingsStore.getState().setLanguage('cs');
    expect(useMessengerStore.getState().chats.map((c) => c.title)).toEqual(['Uložené', 'Оксана']);
    void useSettingsStore.getState().setLanguage('en');
    expect(useMessengerStore.getState().chats[0].title).toBe('Saved');
  });
});

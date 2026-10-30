/**
 * «Налаштування» ПК — людською мовою, з живим записом у вузол.
 *   1. На екрані жодної механіки (логування, ESP32, NPU, токени, Gemini…).
 *   2. «Приходити самому» пише обидва перемикачі ініціативи вузла.
 *   3. Вузол відмовив — значення повертається, людина бачить, що не збереглось.
 *   4. Параметра немає у вузлі — немає й рядка.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DeskSettings } from '../components/shell/DeskSettings';
import { useSettingsStore } from '../stores/settingsStore';
import { settingsApi } from '../services/api';

const VALUES: Record<string, unknown> = {
  ai_initiative_enabled: true,
  agent_proactive_enabled: true,
  ai_response_language: 'auto',
  voice_mode: 'wake_word',
  voice_wake_phrase: 'фантом',
  voice_tts_enabled: true,
  voice_tts_speed: 1,
  voice_silence_timeout_ms: 1200,
  agent_browser_geolocation_enabled: true,
  security_session_timeout_m: 480,
  log_level: 'INFO',
};

const mount = () =>
  render(
    <MemoryRouter>
      <DeskSettings />
    </MemoryRouter>,
  );

beforeEach(() => {
  useSettingsStore.setState({ values: { ...VALUES }, loaded: true });
});
afterEach(() => vi.restoreAllMocks());

describe('Налаштування ПК', () => {
  it('людською мовою, без механіки', () => {
    mount();
    for (const human of ['Приходити самому', 'Слухати', 'Імʼя, на яке озиватись', 'Відповідати вголос', 'Знати, де я']) {
      expect(screen.getByText(human)).toBeTruthy();
    }
    const text = document.body.textContent ?? '';
    for (const tech of ['логуван', 'ESP32', 'NPU', 'токен', 'Gemini', 'Ollama', 'TTS', 'STT', 'wake', 'мс']) {
      expect(text, tech).not.toContain(tech);
    }
    expect(screen.getByText('Усі параметри')).toBeTruthy();
  });

  it('«Приходити самому» пише обидва перемикачі ініціативи вузла', async () => {
    const set = vi.spyOn(settingsApi, 'set').mockResolvedValue({} as never);
    mount();
    fireEvent.click(screen.getByRole('switch', { name: 'Приходити самому' }));
    await waitFor(() => expect(set).toHaveBeenCalledTimes(2));
    expect(set).toHaveBeenCalledWith('ai_initiative_enabled', false);
    expect(set).toHaveBeenCalledWith('agent_proactive_enabled', false);
  });

  it('вузол відмовив — значення повертається і про це сказано', async () => {
    vi.spyOn(settingsApi, 'set').mockRejectedValue(new Error('403'));
    mount();
    fireEvent.click(screen.getByRole('radio', { name: 'Завжди' }));
    expect(await screen.findByText('Nightpost не зберіг — спробуйте ще.')).toBeTruthy();
    expect(useSettingsStore.getState().values.voice_mode).toBe('wake_word');
  });

  it('параметра немає у вузлі — немає й рядка; розділ без рядків не малюється', () => {
    useSettingsStore.setState({ values: { voice_mode: 'off' }, loaded: true });
    mount();
    expect(screen.queryByText('Приходити самому')).toBeNull();
    expect(screen.queryByText('Відповідати вголос')).toBeNull();
    expect(screen.queryByRole('region', { name: 'Приватність' })).toBeNull();
    expect(screen.getByRole('region', { name: 'Голос' })).toBeTruthy();
  });
});

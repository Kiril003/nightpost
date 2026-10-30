import { describe, it, expect, beforeEach } from 'vitest';
import { registerPhoneLayer, usePhoneLayerStore } from '../stores/phoneLayerStore';

/**
 * Переглядач зображення, панель реакцій і контекстне меню живуть локальним
 * станом — системний BACK на телефоні їх не бачив і виходив із розмови
 * (відгук тестувальника 12.09). Реєстр робить їх шарами стеку BACK.
 */
describe('локальні шари для системного BACK', () => {
  beforeEach(() => usePhoneLayerStore.setState({ layers: {} }));

  it('відкритий шар реєструється разом зі своїм закриттям, закритий — знімається', () => {
    let closed = 0;
    registerPhoneLayer('viewer', true, () => { closed += 1; });
    expect(Object.keys(usePhoneLayerStore.getState().layers)).toEqual(['viewer']);
    usePhoneLayerStore.getState().layers.viewer();
    expect(closed).toBe(1);
    registerPhoneLayer('viewer', false, () => undefined);
    expect(usePhoneLayerStore.getState().layers).toEqual({});
  });

  it('прибирання ефекту знімає шар, навіть якщо компонент зник відкритим', () => {
    const cleanup = registerPhoneLayer('picker', true, () => undefined);
    expect('picker' in usePhoneLayerStore.getState().layers).toBe(true);
    cleanup();
    expect('picker' in usePhoneLayerStore.getState().layers).toBe(false);
  });
});

/**
 * Тема оболонки ПК: Ніч або День за сонцем над цим комп'ютером
 * (services/theme.ts), перепитується щохвилини — на заході сонця День сам
 * перетікає в Ніч (600 мс, `phase-shift`).
 */
import { useEffect } from 'react';
import { startThemeWatch } from '../../services/theme';

export function useDeskPhase(): void {
  useEffect(() => {
    // Позначка «це оболонка ПК»: під нею старі змінні теми беруть значення
    // з форми (shell.css). Телефонна збірка оболонки не має.
    document.documentElement.dataset.desk = '';
    return startThemeWatch();
  }, []);
}

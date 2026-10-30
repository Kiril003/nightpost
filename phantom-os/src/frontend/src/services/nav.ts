/**
 * Навігація між вкладками застосунку (контракт — docs/messenger/NAV_CONTRACT.md).
 *
 * Інформаційна архітектура (02.10): нижні вкладки Чати · Мапа · Профіль, їх
 * малює РІДНА оболонка; застосунок відкривається на «Чатах»; PHANTOM — перша
 * закріплена розмова з кнопкою дзвінка в шапці.
 *
 * Веб лише просить: «покажи мапу», «покажи профіль», «подзвони PHANTOM».
 * Кожне прохання:
 *   1. подія `phantom:nav` на window (detail: { to, ...args }) — завжди;
 *   2. `PhantomBridge.navigate(to, argsJson)`, якщо міст його має → true;
 *   3. інакше — запасний шлях усередині вебу (повертає false, і викликач
 *      відкриває своє: профіль — екран «Я», мапа — `openMapHere`).
 */
export type NavTarget = 'chats' | 'map' | 'profile' | 'call-phantom' | 'scan';

export const NAV_EVENT = 'phantom:nav';

interface NavBridge {
  navigate?: (to: string, args?: string) => boolean;
  openMapHere?: () => boolean;
}

const bridge = (): NavBridge | null =>
  typeof window === 'undefined' ? null : ((window as unknown as { PhantomBridge?: NavBridge }).PhantomBridge ?? null);

export function canNavigate(): boolean {
  const b = bridge();
  return !!b && typeof b.navigate === 'function';
}

/** Попросити рідну оболонку відкрити вкладку чи дію. true — оболонка взялась. */
export function navigateTo(to: NavTarget, args: Record<string, unknown> = {}): boolean {
  if (typeof window !== 'undefined') {
    window.dispatchEvent(new CustomEvent(NAV_EVENT, { detail: { to, ...args } }));
  }
  const b = bridge();
  if (b && typeof b.navigate === 'function') {
    try {
      return b.navigate(to, JSON.stringify(args)) !== false;
    } catch {
      return false;
    }
  }
  // Старий APK без navigate: мапу вміє відкрити openMapHere.
  if (to === 'map' && b && typeof b.openMapHere === 'function') {
    try {
      return b.openMapHere() !== false;
    } catch {
      return false;
    }
  }
  return false;
}

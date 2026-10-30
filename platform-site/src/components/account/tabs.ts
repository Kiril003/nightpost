/** Nightpost is a free early build, so the tabs that exist only for a paid plan stay out of the sidebar. */
export const PAID_PLANS = false;

export const PAID_TABS = ['licenses', 'purchases'] as const;

export function tabShown(id: string, paid: boolean = PAID_PLANS): boolean {
  return paid || !(PAID_TABS as readonly string[]).includes(id);
}

import { useProfiles, type Profile } from '../../../stores/profilesStore';

/** Активний профіль, якщо він бізнесовий; інакше null — напрямку «Бізнес» тоді немає. */
export const useActiveBusiness = (): Profile | null =>
  useProfiles((s) => {
    const p = s.profiles.find((x) => x.id === s.activeId) ?? null;
    return p && p.kind === 'business' ? p : null;
  });

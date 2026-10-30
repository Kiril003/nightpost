/** Стиснуте чи оригінал: вибір людини для фото, памʼятає цей пристрій. */
export type PhotoQuality = 'compressed' | 'original';

export const PHOTO_QUALITY_KEY = 'np.photoQuality.v1';

export function readPhotoQuality(): PhotoQuality {
  try {
    return localStorage.getItem(PHOTO_QUALITY_KEY) === 'original' ? 'original' : 'compressed';
  } catch {
    return 'compressed';
  }
}

export function writePhotoQuality(q: PhotoQuality): void {
  try {
    if (q === 'original') localStorage.setItem(PHOTO_QUALITY_KEY, q);
    else localStorage.removeItem(PHOTO_QUALITY_KEY);
  } catch { /* приватне вікно: вибір живе до закриття передогляду */ }
}

export const TICKET_IMAGE_MAX_BYTES = 500_000;
const MAX_SIDE = 1280;

export interface TicketImage {
  mime: 'image/jpeg';
  size: number;
  dataB64: string;
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('unreadable image'));
    };
    img.src = url;
  });
}

/** Знімок стискається до JPEG ≤ 500 КБ на телефоні: на вузол їде лише він. */
export async function prepareTicketImage(file: File): Promise<TicketImage> {
  if (!file.type.startsWith('image/')) throw new Error('not an image');
  const img = await loadImage(file);
  const scale = Math.min(1, MAX_SIDE / Math.max(img.naturalWidth, img.naturalHeight));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
  canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new Error('no canvas');
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  for (const q of [0.82, 0.7, 0.55, 0.4]) {
    const url = canvas.toDataURL('image/jpeg', q);
    const dataB64 = url.slice(url.indexOf(',') + 1);
    const size = Math.floor((dataB64.length * 3) / 4);
    if (size <= TICKET_IMAGE_MAX_BYTES) return { mime: 'image/jpeg', size, dataB64 };
  }
  throw new Error('image too large');
}

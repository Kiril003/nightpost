/**
 * Останні фото, які людина вже надіслала з цього застосунку (TG-look 3B).
 * Беремо лише з локальних розмов: нічого нового не зберігаємо, нікуди не шлемо.
 * Зниклі, видалені й ті, що не поїхали, сюди не повертаються.
 */
import type { Chat, Message } from '../../types/messenger';
import { isRenderableImage } from '../../services/messengerMedia';

export const RECENT_PHOTOS_MAX = 30;

const when = (m: Message, order: number): number => {
  const t = m.sentAt ? Date.parse(m.sentAt) : NaN;
  return Number.isFinite(t) ? t : order;
};

export function recentSentPhotos(chats: Chat[], max = RECENT_PHOTOS_MAX): Message[] {
  const found: { m: Message; at: number }[] = [];
  for (const chat of chats) {
    chat.messages.forEach((m, i) => {
      const media = m.media;
      if (!m.isSelf || m.type !== 'image' || !media || m.isDeleted || m.ephemeral) return;
      if (m.status === 'failed' || m.status === 'sending') return;
      if (!isRenderableImage(media.name, media.mime)) return;
      found.push({ m, at: when(m, i) });
    });
  }
  found.sort((a, b) => b.at - a.at);
  const seen = new Set<string>();
  const out: Message[] = [];
  for (const { m } of found) {
    const key = m.media!.sha256 || m.media!.blobId;
    const name = `${m.media!.name}:${m.media!.size}`;
    if (seen.has(key) || seen.has(name)) continue;
    seen.add(key);
    seen.add(name);
    out.push(m);
    if (out.length >= max) break;
  }
  return out;
}

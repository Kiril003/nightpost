/**
 * Зберегти розшифроване фото чи файл — туди, де людина його знайде.
 *
 * У WebView телефона `<a download>` не робить нічого: обробника завантажень
 * там немає, і кнопка «Зберегти» до 25.09 була мертвою. На телефоні байти
 * їдуть у міст (`POST /messenger/save`) і лягають у Pictures/PHANTOM чи
 * Download/PHANTOM; на ПК працює звичайне завантаження.
 */
import { tNow } from '../components/chat/tNow';
import { messengerApi } from './messengerApi';
import { useUIStore } from '../stores/uiStore';
import { isPhoneShell } from '../components/messenger/phoneShell';

const toBase64 = (buf: Uint8Array): string => {
  let bin = '';
  const step = 0x8000;
  for (let i = 0; i < buf.length; i += step) bin += String.fromCharCode(...buf.subarray(i, i + step));
  return btoa(bin);
};

const placeWord = (where: string): string =>
  where.startsWith('Pictures') ? tNow('media.savedGallery')
  : where.startsWith('Movies') ? tNow('media.savedMovies')
  : where.startsWith('Download') ? tNow('media.savedDownloads')
  : tNow('media.savedElsewhere', { where });

export async function saveMedia(url: string, name: string, mime = ''): Promise<boolean> {
  const toast = useUIStore.getState().toast;
  if (!(__PHONE_BUILD__ || isPhoneShell())) {
    const a = document.createElement('a');
    a.href = url;
    a.download = name || 'Nightpost';
    a.click();
    return true;
  }
  try {
    const res = await fetch(url);
    const bytes = new Uint8Array(await res.arrayBuffer());
    const type = mime || res.headers?.get('content-type') || 'application/octet-stream';
    // Без імені телефон назве файл сам — «Nightpost.<рід>» (WebMessengerSave.cleanName).
    const said = await messengerApi.saveToPhone(name, type, toBase64(bytes));
    toast({ kind: 'success', message: tNow('media.saved', { place: placeWord(said.where) }) });
    return true;
  } catch (err) {
    const status = (err as { status?: number }).status;
    const detail = (err as { message?: string }).message;
    toast({
      kind: 'warn',
      message:
        status === 404
          ? tNow('media.saveOld')
          : status === 413
          ? tNow('media.saveBig')
          : status === 415
          ? detail || tNow('media.saveBlocked')
          : tNow('media.saveFailed'),
    });
    return false;
  }
}

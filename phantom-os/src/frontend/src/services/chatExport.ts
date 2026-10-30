import { copyText } from '../utils/messengerInvite';

type Bridge = { shareText?: (text: string, title?: string) => boolean };

/** Віддає текст у «Поділитися» телефона; без нього — у буфер. */
export async function shareChatText(text: string, title: string): Promise<'shared' | 'copied' | 'failed'> {
  const bridge = (typeof window !== 'undefined' && (window as unknown as { PhantomBridge?: Bridge }).PhantomBridge) || undefined;
  try {
    if (bridge && typeof bridge.shareText === 'function' && bridge.shareText(text, title)) return 'shared';
    if (typeof navigator !== 'undefined' && typeof navigator.share === 'function') {
      await navigator.share({ title, text });
      return 'shared';
    }
  } catch {
    return 'failed';
  }
  return (await copyText(text)) ? 'copied' : 'failed';
}

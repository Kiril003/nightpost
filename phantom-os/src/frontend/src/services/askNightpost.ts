/**
 * «Спитати Nightpost» — питання й голос з капсули над списком розмов.
 *
 * Збірка 17 мала окреме поле питання над пошуком; власник 09.10: «подвійна
 * лінія вводу ріже око». Тепер це одна капсула «Пошук або спитати Nightpost»
 * (PhoneChatList): мікрофон у ній — голос, а перший рядок пошуку — питання.
 *
 * Питання йде в ТУ САМУ розмову з Nightpost, що закріплена в списку
 * (`setActiveChat` + `sendMessage`: відповідає застосунок за мостом), голос —
 * у живу розмову (`navigateTo('call-phantom')`; дозвіл мікрофона питає рідна
 * оболонка). Своїх відповідей тут не вигадуємо.
 */
import { useMessengerStore } from '../stores/messengerStore';
import { navigateTo } from './nav';
import { phantomChatOf } from './phantomHome';

export type AskOutcome = 'sent' | 'empty' | 'notReady';

/** Питання — у справжню розмову з Nightpost, тією ж дорогою, що й з композера. */
export const askNightpost = (text: string): AskOutcome => {
  const q = text.trim();
  if (!q) return 'empty';
  const st = useMessengerStore.getState();
  const chat = phantomChatOf(st.chats);
  if (!chat) return 'notReady';
  st.setActiveChat(chat.id);
  void st.sendMessage(q);
  return 'sent';
};

/** Голос — жива розмова з Nightpost. false — оболонка не взялась (ПК, старий APK). */
export const talkToNightpost = (): boolean => navigateTo('call-phantom');

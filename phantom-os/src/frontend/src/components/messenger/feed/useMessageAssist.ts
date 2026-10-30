/**
 * Допомога над листом: переклад, підсумок і завдання від локального агента
 * PHANTOM, озвучування, закладки. Винесено з ChatArea без змін поведінки —
 * це стан МЕНЮ листа, а не стрічки, і стрічка не мусить перемальовуватись,
 * коли він змінюється (рядок отримує лише свою частку: `translated[msg.id]`).
 */
import { useState } from 'react';
import type { Message } from '../../../types/messenger';
import { soundFx } from '../../../utils/messengerSound';
import { chatApi } from '../../../services/api';
import { messengerApi } from '../../../services/messengerApi';
import { phantomSelfApi } from '../../../services/phantomSelfApi';
import { isPhoneShell } from '../phoneShell';
import { attachmentName, translationLanguages } from './feedParts';
import { tNow } from '../../chat/tNow';
import { assistText } from '../../../services/assistantInput';

/** Відповідь «завдань немає» будь-якою з мов застосунку (модель відповідає мовою людини). */
export const noTasksReply = (reply: string): boolean =>
  /^(немає|нема|none|no tasks|nothing|žádné|žádný|nic)[.!]?$/i.test(reply.trim());

/** Що прочитати вголос. Опитування з віджета — те саме опитування. */
export function speakText(msg: Message): string {
  if (msg.text) return msg.text;
  if (msg.type === 'file') return tNow('messageAssist.file', { name: attachmentName(msg) });
  if (msg.type === 'poll' || msg.type === 'widget:voting') {
    return tNow('messageAssist.poll', { question: msg.pollData?.question ?? msg.votingData?.question ?? '' });
  }
  return tNow('messageAssist.message');
}

export function useMessageAssist(showToast: (text: string) => void) {
  // Відповіді локального агента на повідомлення (переклад, підсумок, завдання)
  const [translatedMessages, setTranslatedMessages] = useState<Record<string, { text: string; lang: string }>>({});
  const [summarizedMessages, setSummarizedMessages] = useState<Record<string, string>>({});
  const [actionItemMessages, setActionItemMessages] = useState<Record<string, string[]>>({});
  const [agentTask, setAgentTask] = useState<{ msgId: string; label: string } | null>(null);

  // Mobile Touch Gestures & TTS State
  const [speakingMsgId, setSpeakingMsgId] = useState<string | null>(null);

  // Text-To-Speech (TTS) Reader
  const handleSpeakMessage = (msg: Message) => {
    // У WebView телефона speechSynthesis немає — говорить голос застосунку.
    if (isPhoneShell()) {
      if (speakingMsgId === msg.id) {
        void messengerApi.stopSpeaking().catch(() => undefined);
        setSpeakingMsgId(null);
        return;
      }
      const said = msg.text || (msg.type === 'file' ? tNow('messageAssist.file', { name: attachmentName(msg) }) : '');
      if (!said) return;
      setSpeakingMsgId(msg.id);
      // Кінця мовлення міст не повідомляє: знімаємо позначку за оцінкою тривалості.
      window.setTimeout(() => setSpeakingMsgId((id) => (id === msg.id ? null : id)), Math.min(60_000, 1500 + said.length * 70));
      void messengerApi.speak(said).catch(() => {
        setSpeakingMsgId(null);
        showToast(tNow('messageAssist.voiceDown'));
      });
      return;
    }
    if (typeof window === 'undefined' || !('speechSynthesis' in window)) {
      showToast(tNow('messageAssist.noSynth'));
      return;
    }

    if (speakingMsgId === msg.id) {
      window.speechSynthesis.cancel();
      setSpeakingMsgId(null);
      showToast(tNow('messageAssist.stopped'));
      return;
    }

    window.speechSynthesis.cancel();
    const textToSpeak = speakText(msg);
    if (!textToSpeak) return;

    const utterance = new SpeechSynthesisUtterance(textToSpeak);
    const hasLatinWords = /[a-zA-Z]{5,}/.test(textToSpeak);
    utterance.lang = hasLatinWords ? 'en-US' : 'uk-UA';
    utterance.rate = 1.0;
    utterance.pitch = 1.0;

    utterance.onend = () => setSpeakingMsgId(null);
    utterance.onerror = () => setSpeakingMsgId(null);

    setSpeakingMsgId(msg.id);
    window.speechSynthesis.speak(utterance);
    showToast(tNow('messageAssist.reading'));
  };

  // Один запит до локального агента PHANTOM. На порожню відповідь чи помилку
  // повертає null: показувати щось замість відповіді моделі не можна.
  const askLocalAgent = async (msgId: string, label: string, prompt: string): Promise<string | null> => {
    if (agentTask) return null;
    setAgentTask({ msgId, label });
    try {
      // На телефоні — разовий запит до розуму PHANTOM поза журналом; на ПК — чат вузла.
      const reply = isPhoneShell()
        ? ((await phantomSelfApi.ask(prompt)).answer || '').trim()
        : ((await chatApi.sendMessage({ content: prompt, input_method: 'text' }))?.message?.content || '').trim();
      if (!reply) {
        showToast(tNow('messageAssist.noAnswer'));
        return null;
      }
      return reply;
    } catch (err) {
      const detail = (err as { message?: string } | null)?.message;
      showToast(detail && /[а-яіїєґ]/i.test(detail) ? detail : tNow('messageAssist.cantAnswer'));
      return null;
    } finally {
      setAgentTask(null);
    }
  };

  const handleToggleTranslate = async (msg: Message, targetLang: string = 'EN') => {
    soundFx.playTap();
    if (translatedMessages[msg.id] && translatedMessages[msg.id].lang === targetLang) {
      setTranslatedMessages((prev) => {
        const next = { ...prev };
        delete next[msg.id];
        return next;
      });
      return;
    }

    const originalText = assistText(msg);
    if (originalText === null) {
      showToast(tNow('store.assistantTextOnly'));
      return;
    }
    if (!originalText) return;

    const langName =
      translationLanguages.find((l) => l.code === targetLang)?.name || targetLang;
    const translation = await askLocalAgent(
      msg.id,
      tNow('messageAssist.translating', { lang: targetLang }),
      tNow('assist.prompt.translate', { lang: langName, text: originalText })
    );
    if (!translation) return;

    setTranslatedMessages((prev) => ({
      ...prev,
      [msg.id]: { text: translation, lang: targetLang },
    }));
  };

  const handleToggleSummary = async (msg: Message) => {
    soundFx.playTap();
    if (summarizedMessages[msg.id]) {
      setSummarizedMessages((prev) => {
        const next = { ...prev };
        delete next[msg.id];
        return next;
      });
      return;
    }

    const originalText = assistText(msg);
    if (originalText === null) {
      showToast(tNow('store.assistantTextOnly'));
      return;
    }
    if (!originalText) return;

    const summary = await askLocalAgent(
      msg.id,
      tNow('messageAssist.summarizing'),
      tNow('assist.prompt.summary', { replyIn: tNow('coreChat.replyIn'), text: originalText })
    );
    if (!summary) return;

    setSummarizedMessages((prev) => ({
      ...prev,
      [msg.id]: summary,
    }));
  };

  const handleGenerateActionItems = async (msg: Message) => {
    soundFx.playChime();
    if (actionItemMessages[msg.id]) {
      setActionItemMessages((prev) => {
        const next = { ...prev };
        delete next[msg.id];
        return next;
      });
      return;
    }

    const originalText = assistText(msg);
    if (originalText === null) {
      showToast(tNow('store.assistantTextOnly'));
      return;
    }
    if (!originalText) return;

    const reply = await askLocalAgent(
      msg.id,
      tNow('messageAssist.findingTasks'),
      tNow('assist.prompt.tasks', { replyIn: tNow('coreChat.replyIn'), text: originalText })
    );
    if (!reply) return;

    const items = reply
      .split('\n')
      .map((line) => line.replace(/^[-•*\d.)\s]+/, '').trim())
      .filter(Boolean);

    if (items.length === 0 || noTasksReply(reply)) {
      showToast(tNow('messageAssist.noTasks'));
      return;
    }

    setActionItemMessages((prev) => ({
      ...prev,
      [msg.id]: items,
    }));
  };

  return {
    translatedMessages,
    summarizedMessages,
    actionItemMessages,
    agentTask,
    speakingMsgId,
    handleSpeakMessage,
    handleToggleTranslate,
    handleToggleSummary,
    handleGenerateActionItems,
  };
}

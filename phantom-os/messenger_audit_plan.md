# Глибокий технічний аналіз кодової бази месенджера PHANTOM (`wt-web-msgrui-21sep`)

> **Цільовий стек:** React 18, TypeScript, TailwindCSS, Zustand, `@tanstack/react-virtual`, Web Audio API, WebCrypto AES-GCM.  
> **Локація:** `/home/kyrylo/phantom_ai/wt-web-msgrui-21sep/src/frontend/`

---

## 1. Детальний перелік вузьких місць швидкодії (Lag / Jank)

### 1.1. Псевдо-віртуалізація та розростання DOM у `ChatArea.tsx`
* **Фактичний стан:** Замість встановленої в `package.json` бібліотеки `@tanstack/react-virtual` (`^3.13.26`) в `ChatArea.tsx` реалізовано наївне вікно:
  ```ts
  const WINDOW_STEP = 120;
  const [windowSize, setWindowSize] = useState(WINDOW_STEP);
  const windowOffset = Math.max(0, messages.length - windowSize);
  const windowedMessages = windowOffset === 0 ? messages : messages.slice(windowOffset);
  ```
* **Причина лагів:** При скролі вгору вікно **лише збільшується** (`setWindowSize(n => n + 120)`), але **ніколи не обрізається знизу**. Якщо користувач читає історію чату, в DOM накопичується 600–1200+ надскладних вузлів.
* **Внутрішній бенчмарк розробників (рядки 251–258 у ChatArea.tsx):**
  > *«одне наведення миші коштувало 2,8 мс на 50 листах, 9,1 мс на 200 і 23,6 мс на 600. Лінійно, ~0,039 мс на лист... На A52 це помножити ще на 4-6 — і виходить рівно те, що власник назвав «незграбний і лаганий»»*.
  При 600 повідомленнях на Samsung A52 один `hover` або `touch` блокує головний потік на **100–150 мс**, викликаючи падіння кадрів до **6–10 FPS**.

### 1.2. Моноліт `ChatArea.tsx` з 35+ `useState` хуками без декомпозиції
* В єдиному компоненті `ChatArea` (3495 рядків!) співіснують:
  `windowSize`, `playingVoiceId`, `voiceProgress`, `voiceSpeed`, `hoveredMessageId`, `swipingMsgId`, `swipeOffset`, `expandedMsgIds`, `expandedTranscripts`, `contextMenuMsg`, `deletingMessage`, `translatedMessages`, `summarizedMessages`, `actionItemMessages`, `savedMessages`, `chatSearchQuery`, `searchMatchIndex`, `isCanvasSplitOpen`, `splitWidth` тощо.
* **Катастрофічний вплив на рендеринг:**
  1. **Hover:** `onMouseEnter={() => setHoveredMessageId(msg.id)}` — тригерить повний рендер `ChatArea` і всіх 120–1000 повідомлень.
  2. **Свайп (TouchMove):** При жесті свайпу повідомлення для цитування (`handleTouchMove`), `setSwipeOffset(clampedOffset)` викликається **на кожен рух пальця (60–120 разів на секунду)**. Перераховується весь список повідомлень!
  3. **Тік аудіо:** `ontimeupdate` викликає `setVoiceProgress` кожні 100–250 мс, примусово рендерячи всю стрічку розмови під час програвання аудіо.

### 1.3. Відсутність `React.memo` та інлайнові стрілочні функції
* Рядок повідомлення рендериться безпосередньо всередині `.map()` у тілі `ChatArea`.
* У коментарях (рядки 260–264) автори зазначили:
  > *«Лікується це не мемоізацією: щоб React.memo спрацював, рядок треба винести окремим компонентом і простягнути в нього ~40 замикань, з яких більшість — інлайнові стрілки... Мемо там не втримається й дня»*.
  Відмова від декомпозиції та контексту призвела до повної нездатності React оптимізувати дерево через shallow compare.

### 1.4. Рендеринг ростера в `Sidebar.tsx` без віртуалізації
* У `Sidebar.tsx` (рядок 1503):
  ```tsx
  {sortChatsByRecent(filteredChats).map((chat) => ...)}
  ```
  Сортування `sortChatsByRecent` виконується **синхронно в фазі рендеру** на кожен рендер компонента.
* Немає віртуалізації: якщо в акаунті 100+ чатів/каналів, створюється понад 100 важких DOM-нод з аватарами, бейджами та кнопками.
* Пошук `findLocally(chats, searchQuery)` та `matchesSearch` фільтрує повідомлення без `useDeferredValue` або `debounce`, скануючи повідомлення всіх чатів на кожне натискання клавіші.

### 1.5. Каскадний рендер із `MessengerRoot.tsx`
* У `MessengerRoot.tsx` (рядок 76):
  `const store = useMessengerStore();` (викликано **без селектора**!).
  Будь-який оновлений статус у Zustand-сторі (тайпінг когось у чаті, зміна статусу підключення) викликає ре-рендер `MessengerRoot` -> `Header` -> `Sidebar` -> `ChatArea` -> `MessageComposer`. Жоден із них не захищений `React.memo`.

### 1.6. Cumulative Layout Shift (CLS) та блокування рендерингу зображень
* `SecureMediaBubble.tsx`:
  - Поки медіа розшифровується (`phase === 'reading'`), висота бабла становить ~32px (`розшифровуємо…`).
  - Після завершення завантаження бабл стрибає до 260px (`<img>`).
  - Відсутність фіксованого aspect-ratio / розмірних заглушок / Blurhash змушує весь скрол-контейнер смикатись і ламає позицію якоря (`scrollHeight - anchor`).
* **Полінг вкладень:** Кожен екземпляр `SecureMediaBubble` для власного відправленого файлу встановлює `setInterval(ask, 15000)` для перевірки `blobStatus`. 40 зображень у стрічці = 40 одночасних інтервалів!

---

## 2. Незавершені елементи («Зняли стару дорогу і не поклали нову»)

| Компонент / Модуль | Що розпочали робити | На чому зупинились / Що зламано |
| :--- | :--- | :--- |
| **Голосові повідомлення** | Створено `VoiceMediaBubble.tsx` для нової підсистеми медіа (`msg.voiceData.media`). | У `ChatArea.tsx` (рядки 2026–2130) збережено дублюючий старий інлайн-плеєр для `!msg.voiceData.media`. Новий `VoiceMediaBubble` **не вміє перемотувати (seek)**, не має вибору швидкості (1x/1.5x/2x), і не зупиняє сусідні аудіо при старті! |
| **Кешування медіа (`messengerMedia.ts`)** | Запроваджено шифрування AES-GCM у браузері. | Кеш дешифрованих файлів живе виключно в оперативній пам'яті (`ready = new Map()`). Немає збереження в `IndexedDB`. При F5 або переході між чатами всі фото/голосові скачуються і розшифровуються з нуля. `URL.revokeObjectURL` не викликається. |
| **Запис голосу (`MessageComposer.tsx`)** | Запис WebM/Opus через `MediaRecorder`. | Рядки 397–399: створений `Blob` перетворюється на `URL.createObjectURL(blob)`, а потім `deliverVoice` робить `fetch(url).then(r => r.blob())` замість передачі готового блоба! Витік пам'яті blob-посилань. |
| **Canvas Whiteboard (`CanvasSplitView.tsx`)** | Режим `viewMode === 'whiteboard'` з картками та стрілками. | Картки мають координати `b.x`, `b.y`, але **не мають жодного mouse/touch drag-handler**. Вони повністю статичні. Лінії зв'язку малюються тупо від елемента `i` до `i+1` за індексом масиву. |
| **Треди (`ThreadView.tsx`)** | Екран гілки обговорення кореневого повідомлення. | Фільтрує тільки плоский список `replyTo.id === rootId` (немає вкладених тредів). Поле введення — голий `<textarea>` без прикріплень, медіа та стікерів. Немає списку тредів у навігації. |
| **Закладки (`savedMessages`)** | Додано збереження в `localStorage` (`messageBookmarks`). | У сайдбарі розділ «Збережене» фільтрує чати за ознакою `chat.circle === 'saved'`, тому збережені окремі повідомлення туди не потрапляють. |
| **Стікери** | У `types/messenger.ts` додано флаг дозволу `sendStickersAndGifs: boolean`. | Жодної підтримки стікерів у UI: немає пікера стікерів, немає моделі стікер-паків, немає відображення стікера без бульбашки. |

---

## 3. Порівняльний аналіз із Telegram / Discord

| Фіча | Telegram / Discord | Поточний PHANTOM | Що необхідно зробити |
| :--- | :--- | :--- | :--- |
| **Віртуалізація стрічки** | 60–120 FPS, у DOM лише 15–20 елементів, плавне збереження скролу в обидва боки. | Slice на 120 повідомлень, необмежене зростання вгору при скролі, лаги при ховері. | Інтеграція `@tanstack/react-virtual` з dynamic measurement та sticky date headers. |
| **Завантаження медіа** | Миттєве завдяки Blurhash / LQA (1 КБ) прев'ю, кеш у IndexedDB / Cache API. | Текстове «розшифровуємо…», стрибки висоти (CLS), повторне завантаження при кожному переході. | Blurhash / прев'ю-розміри у кадрах; кешування розшифрованих ArrayBuffer у IndexedDB. |
| **Голосові нотатки** | Глобальний аудіо-рушій, seek по хвилі, швидкість 1.5x/2x, пауза при записі, lock-свайп вгору. | Два фрагментованих плеєри, відсутність глобального стану, блокуючий запис. | Єдиний `useAudioPlaybackStore`, Canvas-рендерер хвилі з drag-seeking, розширений рекордер. |
| **Відео-нотатки ("кружечки")** | Круглий запис відео з камери 1:1, стиснення на льоту, авто-плей без звуку. | Повністю відсутні. | Компонент запису круглих відео через `MediaRecorder` + WebGL маска / CSS `border-radius: 50%`. |
| **Стікери та GIF** | Швидкі стікер-паки (WebP / TGS / Lottie), вкладка поруч з Emoji, інлайн-пошук. | Відсутні (лише текст/емодзі). | Вкладка `StickerPicker`, підтримка WebP/SVG стікерів, рендер без тла бульбашки. |
| **Треди та гілки** | Окремі треди, лічильники відповідей, непрочитані у тредах, повноцінний композер. | Модальний віджет на 170 рядків із плоским списком і примітивним textarea. | Повноцінний Sidebar тредів, розширений редактор відповідей, sync із кореневим чатом. |
| **Пошук** | Серверний FTS + локальний індекс; фільтри за авторами, медіа, датами. | Простий `includes()` на завантаженому масиві в пам'яті. | FTS5 індекс на стороні вузла + клієнтський фасетний фільтр (`from:`, `has:file`, `date:`). |
| **Папки чатів** | Вкладки папок, лічильники непрочитаних із виключенням mutes, Drag&Drop. | Спрощені SmartFolders, відсутність верхніх швидких табів над ростером на телефоні. | Верхня горизонтальна смуга вкладок (All, DMs, Groups, Work), ізольований розрахунок badge. |

---

## 4. Чіткий план модернізації та архітектурний код

### Етап 1. Справжня віртуалізація стрічки на `@tanstack/react-virtual`
Заміна псевдо-вікна на бідирекційний віртуалізатор.

```tsx
// src/frontend/src/components/messenger/VirtualChatFeed.tsx
import React, { useRef, useEffect, useCallback } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import type { Message } from '../../types/messenger';
import { MessageRowMemo } from './MessageRow';

interface VirtualChatFeedProps {
  messages: Message[];
  currentUserId: string;
  onReply: (msg: Message) => void;
  onOpenMedia: (url: string, name?: string) => void;
  onScrollTopReach?: () => void;
}

export const VirtualChatFeed: React.FC<VirtualChatFeedProps> = ({
  messages,
  currentUserId,
  onReply,
  onOpenMedia,
  onScrollTopReach,
}) => {
  const parentRef = useRef<HTMLDivElement>(null);

  const rowVirtualizer = useVirtualizer({
    count: messages.length,
    getScrollElement: () => parentRef.current,
    estimateSize: useCallback(() => 64, []),
    overscan: 8,
  });

  // Автоскрол донизу при першому завантаженні або новому власному повідомленні
  const lastCountRef = useRef(messages.length);
  useEffect(() => {
    if (!parentRef.current) return;
    const el = parentRef.current;
    const isNearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120;
    
    if (isNearBottom || messages.length - lastCountRef.current === 1) {
      rowVirtualizer.scrollToIndex(messages.length - 1, { align: 'end' });
    }
    lastCountRef.current = messages.length;
  }, [messages.length, rowVirtualizer]);

  // Підвантаження старіших повідомлень при наближенні до стелі
  const handleScroll = () => {
    if (!parentRef.current) return;
    if (parentRef.current.scrollTop < 250 && onScrollTopReach) {
      onScrollTopReach();
    }
  };

  const virtualItems = rowVirtualizer.getVirtualItems();

  return (
    <div
      ref={parentRef}
      onScroll={handleScroll}
      className="flex-1 w-full overflow-y-auto overflow-x-hidden contain-strict select-text"
      style={{ willChange: 'transform' }}
    >
      <div
        className="relative w-full max-w-[760px] mx-auto"
        style={{ height: `${rowVirtualizer.getTotalSize()}px` }}
      >
        {virtualItems.map((virtualRow) => {
          const msg = messages[virtualRow.index];
          const prevMsg = virtualRow.index > 0 ? messages[virtualRow.index - 1] : undefined;
          const isFirstInGroup = !prevMsg || prevMsg.senderId !== msg.senderId;

          return (
            <div
              key={msg.id}
              data-index={virtualRow.index}
              ref={rowVirtualizer.measureElement}
              className="absolute top-0 left-0 w-full px-3 py-0.5"
              style={{
                transform: `translateY(${virtualRow.start}px)`,
                willChange: 'transform',
              }}
            >
              <MessageRowMemo
                msg={msg}
                isSelf={msg.senderId === currentUserId}
                isFirstInGroup={isFirstInGroup}
                onReply={onReply}
                onOpenMedia={onOpenMedia}
              />
            </div>
          );
        })}
      </div>
    </div>
  );
};
```

---

### Етап 2. Винесення `MessageRowMemo` з ізольованими жестами та станом
Ізоляція стану ховеру та свайпу всередині рядка усуває перерендер усієї стрічки!

```tsx
// src/frontend/src/components/messenger/MessageRow.tsx
import React, { memo, useState, useRef } from 'react';
import type { Message } from '../../types/messenger';
import { Avatar } from './Avatar';
import { Reply, Check, CheckCheck } from 'lucide-react';
import { SecureMediaBubble } from './SecureMediaBubble';
import { UnifiedVoiceBubble } from './UnifiedVoiceBubble';

interface MessageRowProps {
  msg: Message;
  isSelf: boolean;
  isFirstInGroup: boolean;
  onReply: (msg: Message) => void;
  onOpenMedia: (url: string, name?: string) => void;
}

export const MessageRowMemo = memo<MessageRowProps>(({
  msg,
  isSelf,
  isFirstInGroup,
  onReply,
  onOpenMedia,
}) => {
  const [swipeOffset, setSwipeOffset] = useState(0);
  const touchStartX = useRef(0);
  const swiping = useRef(false);

  const handleTouchStart = (e: React.TouchEvent) => {
    touchStartX.current = e.touches[0].clientX;
    swiping.current = false;
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    const diffX = e.touches[0].clientX - touchStartX.current;
    if (Math.abs(diffX) > 15) {
      swiping.current = true;
      const clamped = isSelf ? Math.min(0, Math.max(-60, diffX)) : Math.max(0, Math.min(60, diffX));
      setSwipeOffset(clamped);
    }
  };

  const handleTouchEnd = () => {
    if (Math.abs(swipeOffset) >= 40) {
      onReply(msg);
      if (typeof navigator !== 'undefined' && navigator.vibrate) navigator.vibrate(15);
    }
    setSwipeOffset(0);
    swiping.current = false;
  };

  return (
    <div className={`flex items-end gap-2 group relative ${isSelf ? 'justify-end' : 'justify-start'}`}>
      {!isSelf && (
        <div className="w-8 h-8 shrink-0 mb-0.5">
          {isFirstInGroup ? (
            <Avatar src={msg.senderAvatar} name={msg.senderName} className="w-8 h-8" />
          ) : <div className="w-8 h-8" />}
        </div>
      )}

      <div
        onTouchStart={handleTouchStart}
        onTouchMove={handleTouchMove}
        onTouchEnd={handleTouchEnd}
        style={{
          transform: swipeOffset !== 0 ? `translateX(${swipeOffset}px)` : undefined,
          transition: swiping.current ? 'none' : 'transform 0.18s cubic-bezier(0.16, 1, 0.3, 1)',
        }}
        className={`relative max-w-[85%] sm:max-w-[70%] rounded-2xl p-3 select-text shadow-xs ${
          msg.type === 'sticker'
            ? 'bg-transparent shadow-none p-0'
            : isSelf
            ? 'bg-[#1D2B22] text-[#F8FAF8] rounded-br-xs border border-[#2A3E31]'
            : 'bg-[#141C16] text-[#E0E8E3] rounded-bl-xs border border-[#1E2921]'
        }`}
      >
        {/* Swipe visual indicator */}
        {Math.abs(swipeOffset) > 15 && (
          <div className={`absolute top-1/2 -translate-y-1/2 ${isSelf ? 'left-[-32px]' : 'right-[-32px]'} text-[#F4AF25]`}>
            <Reply className="w-4 h-4" />
          </div>
        )}

        {/* Reply preview */}
        {msg.replyTo && (
          <div className="mb-1.5 pl-2 border-l-2 border-[#F4AF25] text-xs opacity-80 truncate">
            <span className="font-semibold text-[#F4AF25]">{msg.replyTo.senderName}: </span>
            <span>{msg.replyTo.text}</span>
          </div>
        )}

        {/* Content routing */}
        {msg.type === 'voice' && <UnifiedVoiceBubble msg={msg} />}
        {(msg.type === 'image' || msg.type === 'file') && (
          <SecureMediaBubble msg={msg} isSelf={isSelf} onOpenLightbox={onOpenMedia} />
        )}
        {msg.type === 'text' && (
          <p className="text-[14.5px] leading-relaxed break-words whitespace-pre-wrap">{msg.text}</p>
        )}

        {/* Metadata: Time & Status */}
        <div className="mt-1 flex items-center justify-end gap-1 text-[10.5px] text-[#8EA093] select-none">
          <span>{msg.timestamp}</span>
          {isSelf && (
            <span>
              {msg.status === 'read' ? (
                <CheckCheck className="w-3.5 h-3.5 text-[#F4AF25]" />
              ) : msg.status === 'delivered' ? (
                <CheckCheck className="w-3.5 h-3.5 text-[#8EA093]" />
              ) : (
                <Check className="w-3.5 h-3.5 text-[#8EA093]" />
              )}
            </span>
          )}
        </div>
      </div>
    </div>
  );
});
MessageRowMemo.displayName = 'MessageRowMemo';
```

---

### Етап 3. Глобальний `useAudioPlaybackStore` та об'єднаний `UnifiedVoiceBubble`
Забезпечує одночасне відтворення лише одного аудіо, перемотування та зміну швидкості (1x, 1.5x, 2x).

```tsx
// src/frontend/src/stores/audioPlaybackStore.ts
import { create } from 'zustand';

interface AudioState {
  currentMsgId: string | null;
  audioUrl: string | null;
  isPlaying: boolean;
  progress: number; // 0 to 1
  currentTime: number;
  duration: number;
  playbackRate: number;
  play: (msgId: string, url: string, initialDuration?: number) => void;
  pause: () => void;
  seek: (progress: number) => void;
  setSpeed: (rate: number) => void;
}

let globalAudio: HTMLAudioElement | null = null;

export const useAudioPlaybackStore = create<AudioState>((set, get) => ({
  currentMsgId: null,
  audioUrl: null,
  isPlaying: false,
  progress: 0,
  currentTime: 0,
  duration: 0,
  playbackRate: 1,

  play: (msgId, url, initialDuration = 0) => {
    if (!globalAudio) {
      globalAudio = new Audio();
      globalAudio.ontimeupdate = () => {
        if (!globalAudio || !globalAudio.duration) return;
        set({
          currentTime: globalAudio.currentTime,
          progress: globalAudio.currentTime / globalAudio.duration,
        });
      };
      globalAudio.onended = () => set({ isPlaying: false, progress: 0 });
    }

    if (get().currentMsgId === msgId && globalAudio.src === url) {
      globalAudio.play();
      set({ isPlaying: true });
      return;
    }

    globalAudio.src = url;
    globalAudio.playbackRate = get().playbackRate;
    globalAudio.play().then(() => {
      set({
        currentMsgId: msgId,
        audioUrl: url,
        isPlaying: true,
        duration: initialDuration || globalAudio?.duration || 0,
      });
    });
  },

  pause: () => {
    globalAudio?.pause();
    set({ isPlaying: false });
  },

  seek: (progress) => {
    if (!globalAudio || !globalAudio.duration) return;
    globalAudio.currentTime = progress * globalAudio.duration;
    set({ progress, currentTime: globalAudio.currentTime });
  },

  setSpeed: (rate) => {
    if (globalAudio) globalAudio.playbackRate = rate;
    set({ playbackRate: rate });
  },
}));
```

---

### Етап 4. Персистентне кешування медіа в `IndexedDB`
Знищує лаги завантаження медіа при повторному перегляді чатів.

```ts
// src/frontend/src/services/mediaStorageCache.ts
const DB_NAME = 'phantom_media_vault_v1';
const STORE_NAME = 'decrypted_blobs';

function openDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      req.result.createObjectStore(STORE_NAME);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function getCachedMediaBlob(blobId: string): Promise<Blob | null> {
  try {
    const db = await openDB();
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const req = store.get(blobId);
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => resolve(null);
    });
  } catch {
    return null;
  }
}

export async function putCachedMediaBlob(blobId: string, blob: Blob): Promise<void> {
  try {
    const db = await openDB();
    const tx = db.transaction(STORE_NAME, 'readwrite');
    tx.objectStore(STORE_NAME).put(blob, blobId);
  } catch {
    /* ignore storage quota errors */
  }
}
```

---

### Етап 5. Підтримка стікерів (Stickers Pack Engine)
Дозволяє відправляти стікери в 1 клік без фонової бульбашки та лагів.

```tsx
// src/frontend/src/components/messenger/StickerPicker.tsx
import React from 'react';

export interface StickerItem {
  id: string;
  packId: string;
  url: string;
  emojiAlt: string;
}

const DEFAULT_STICKERS: StickerItem[] = [
  { id: 'st_phantom_1', packId: 'phantom_sentinel', url: '/stickers/sentinel_ok.webp', emojiAlt: '👍' },
  { id: 'st_phantom_2', packId: 'phantom_sentinel', url: '/stickers/sentinel_fire.webp', emojiAlt: '🔥' },
  { id: 'st_phantom_3', packId: 'phantom_sentinel', url: '/stickers/sentinel_shield.webp', emojiAlt: '🛡️' },
  { id: 'st_phantom_4', packId: 'phantom_sentinel', url: '/stickers/sentinel_radar.webp', emojiAlt: '📡' },
];

export const StickerPicker: React.FC<{ onSelectSticker: (sticker: StickerItem) => void }> = ({ onSelectSticker }) => {
  return (
    <div className="p-3 grid grid-cols-4 gap-2 bg-[#121714] border border-[#233026] rounded-2xl shadow-xl max-h-[220px] overflow-y-auto">
      {DEFAULT_STICKERS.map((st) => (
        <button
          key={st.id}
          type="button"
          onClick={() => onSelectSticker(st)}
          className="touch-floor p-2 hover:bg-[#1C271F] rounded-xl transition-transform hover:scale-110 active:scale-95 flex items-center justify-center"
        >
          <img src={st.url} alt={st.emojiAlt} className="w-16 h-16 object-contain pointer-events-none" />
        </button>
      ))}
    </div>
  );
};
```

---

## 5. Підсумок рекомендацій для Negentropy / 120 FPS
1. **Знищити 35 `useState` у `ChatArea`**: Перенести стан кожної бульбашки (hover, swipe, voice progress) локально в `MessageRowMemo` та `audioPlaybackStore`.
2. **Увімкнути `@tanstack/react-virtual`**: Обмежити DOM до 15–20 видимих вузлів замість накопичення 1000+ повідомлень.
3. **Встановити aspect-ratio для медіа**: Зберігати `width` і `height` картинки у метаданих кадру повідомлення, щоб контейнер мав точні розміри до закінчення розшифрування (CLS = 0).
4. **Об'єднати дублюючі аудіо-рушії**: Видалити інлайн-код програвання голосу з `ChatArea.tsx` і залишити єдиний `UnifiedVoiceBubble`.
5. **Додати IndexedDB-кеш дешифрованих файлів**: Запобігти повторним запитам до мосту та повторному розшифруванню крипто-блобів.
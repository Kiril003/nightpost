/**
 * PHANTOM OS — Global Serverless P2P Mesh & Remote Internet Signaling
 * Забезпечує миттєвий звʼязок між двома будь-якими пристроями у світі через WSS / WebRTC.
 */

import { Message } from '../types/messenger';

/**
 * Адреса брокера НЕ живе в коді, і шлях закритий, поки її не задали.
 *
 * Так було: тут стояв зашитий `wss://broker.emqx.io:8084/mqtt` — безкоштовний
 * ЧУЖИЙ публічний брокер. У теми `phantom/mesh/user/<нік>` летів цілий обʼєкт
 * Message відкритим текстом, разом з іменем, ніком і аватаркою; тема містила
 * справжній нік. Будь-хто у світі, підписавшись на `phantom/mesh/#`, читав це.
 *
 * І це не було теорією: з ЦЬОГО джерела зібрано веб-бандл, що лежить у git
 * телефона, і 29.08 logcat живого пристрою показав рядок
 * «[GlobalMesh] Connected to MQTT broker as @kiril» з підпискою на
 * `phantom/mesh/user/kiril`. Тобто присутність власника публікувалась у чужий
 * брокер з його ж телефона.
 *
 * Тому дефолт — ВИМКНЕНО. Порожня змінна означає «дороги немає», а не «спробуй
 * якийсь брокер»: тихий запасний варіант тут і був причиною витоку.
 */
const BROKER_URL = String(import.meta.env?.VITE_MESH_BROKER_URL ?? '').trim();

/** Чи є в цієї збірки взагалі шлях у глобальну пошту. */
export const meshBrokerConfigured = (): boolean => BROKER_URL !== '';

export interface RemoteMeshPacket {
  version: number;
  type: 'message:new' | 'message:reaction' | 'message:typing' | 'call:signal' | 'presence:ping' | 'presence:pong';
  senderId: string;
  senderName: string;
  senderHandle: string;
  senderAvatar: string;
  targetHandle?: string;
  chatId?: string;
  timestamp: number;
  payload: any;
}

class GlobalP2PMeshService {
  private ws: WebSocket | null = null;
  private isConnected = false;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  
  private currentHandle: string = '';
  private currentUserId: string = '';
  private currentUserName: string = '';
  private currentUserAvatar: string = '';

  private subscribedTopics = new Set<string>();
  private messageListeners = new Set<(packet: RemoteMeshPacket) => void>();
  private onlinePeers = new Map<string, { handle: string; name: string; avatar: string; lastSeen: number }>();

  constructor() {
    // Автоматичний пінг кожні 30 секунд для тримання WSS каналу живим
    if (typeof window !== 'undefined') {
      window.addEventListener('online', () => this.connect());
    }
  }

  /**
   * Ініціалізація та привʼязка до поточного профілю користувача
   */
  public init(userId: string, userName: string, handle: string, avatar: string) {
    const cleanHandle = this.normalizeHandle(handle || userName || userId);
    this.currentUserId = userId;
    this.currentUserName = userName;
    this.currentHandle = cleanHandle;
    this.currentUserAvatar = avatar;

    this.connect();
  }

  public updateIdentity(handle: string, name?: string, avatar?: string) {
    const clean = this.normalizeHandle(handle);
    const handleChanged = clean !== this.currentHandle;
    if (handleChanged && this.currentHandle) {
      this.unsubscribe(`phantom/mesh/user/${this.currentHandle}`);
    }
    this.currentHandle = clean;
    if (name) this.currentUserName = name;
    if (avatar) this.currentUserAvatar = avatar;
    if (this.isConnected) {
      if (handleChanged) {
        this.subscribe(`phantom/mesh/user/${clean}`);
      }
      this.announcePresence();
    }
  }

  private normalizeHandle(handle: string): string {
    if (!handle) return 'anonymous';
    const parenMatch = handle.match(/\(([^)]+)\)/);
    const raw = parenMatch ? parenMatch[1] : handle;
    return raw.replace(/^@+/, '').trim().toLowerCase().replace(/[^a-z0-9_\u0400-\u04ff-]/g, '_') || 'anonymous';
  }

  /**
   * Стан глобальної пошти — рівно те, що можна чесно написати в UI.
   * `enabled: false` означає, що ця збірка НЕ має шляху назовні взагалі.
   */
  public status(): { enabled: boolean; connected: boolean; broker: string } {
    return {
      enabled: BROKER_URL !== '',
      connected: this.isConnected,
      // Назовні віддаємо лише хост, без токенів і шляху.
      broker: BROKER_URL ? BROKER_URL.replace(/^\w+:\/\//, '').split('/')[0] : '',
    };
  }

  /**
   * Підключення до брокера, заданого VITE_MESH_BROKER_URL. Без нього — no-op.
   */
  public connect() {
    if (typeof WebSocket === 'undefined') return;
    // Брокера не задано — дороги немає. Мовчазної спроби кудись підключитись
    // тут бути не може: саме вона й виносила текст назовні.
    if (!BROKER_URL) {
      this.isConnected = false;
      return;
    }
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    try {
      const ws = new WebSocket(BROKER_URL, ['mqtt']);
      ws.binaryType = 'arraybuffer';
      this.ws = ws;

      ws.onopen = () => {
        this.sendMqttConnect();
      };

      ws.onmessage = (event) => {
        try {
          const data = new Uint8Array(event.data as ArrayBuffer);
          this.handleMqttPacket(data);
        } catch (err) {
          console.warn('[GlobalMesh] Packet parse error:', err);
        }
      };

      ws.onclose = () => {
        this.isConnected = false;
        this.stopPing();
        this.scheduleReconnect();
      };

      ws.onerror = () => {
        try {
          ws.close();
        } catch {
          /* ignore */
        }
      };
    } catch {
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = setTimeout(() => {
      this.connect();
    }, 3000);
  }

  /* ─── MQTT 3.1.1 Бінарний протокол поверх WebSocket ────────────────────── */

  private sendMqttConnect() {
    const clientId = `ph_${Math.random().toString(36).substring(2, 10)}`;
    const clientIdBytes = new TextEncoder().encode(clientId);

    // Variable header: Protocol Name (MQTT), Level (4), Connect Flags (Clean Session = 0x02), KeepAlive (60s)
    const varHeader = new Uint8Array([
      0x00, 0x04, 0x4d, 0x51, 0x54, 0x54, // "MQTT"
      0x04,                               // Level 4 (MQTT 3.1.1)
      0x02,                               // Clean Session flag
      0x00, 0x3c,                         // Keep Alive: 60s
    ]);

    const payload = new Uint8Array(2 + clientIdBytes.length);
    payload[0] = (clientIdBytes.length >> 8) & 0xff;
    payload[1] = clientIdBytes.length & 0xff;
    payload.set(clientIdBytes, 2);

    const remLen = varHeader.length + payload.length;
    const packet = new Uint8Array(2 + remLen);
    packet[0] = 0x10; // CONNECT packet type
    packet[1] = remLen;
    packet.set(varHeader, 2);
    packet.set(payload, 2 + varHeader.length);

    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(packet);
    }
  }

  private handleMqttPacket(data: Uint8Array) {
    if (data.length < 2) return;
    const packetType = data[0] >> 4;

    switch (packetType) {
      case 2: // CONNACK (0x20)
        this.isConnected = true;
        this.startPing();
        console.log(`[GlobalMesh] Connected to MQTT broker as @${this.currentHandle}`);
        
        // Підписуємося на власну скриньку та глобальні події
        this.subscribedTopics.add('phantom/mesh/global/presence');
        if (this.currentHandle) {
          this.subscribedTopics.add(`phantom/mesh/user/${this.currentHandle}`);
        }
        for (const topic of this.subscribedTopics) {
          this.sendSubscribePacket(topic);
        }
        this.announcePresence();
        break;

      case 3: // PUBLISH (0x30)
        this.handlePublishPacket(data);
        break;

      case 13: // PINGRESP (0xD0)
        // Keep-alive підтверджено
        break;
    }
  }

  private handlePublishPacket(data: Uint8Array) {
    try {
      // Decode Remaining Length (variable byte)
      let index = 1;
      let multiplier = 1;
      let remainingLength = 0;
      let digit: number;
      do {
        digit = data[index++];
        remainingLength += (digit & 127) * multiplier;
        multiplier *= 128;
      } while ((digit & 128) !== 0 && index < data.length);

      // Кінець ЦЬОГО пакета, а не кінець кадру.
      //
      // `remainingLength` тут рахували й викидали, а корисне навантаження
      // брали як `data.subarray(index)` — тобто все до кінця кадру. Поки
      // брокер шле один MQTT-пакет на кадр, це збігається. Але MQTT поверх
      // WebSocket цього не гарантує: склеєні пакети — законні, і тоді в
      // JSON.parse летить хвіст наступного пакета, парсер падає, а catch
      // нижче ковтає помилку. Лист просто не приходить, і ніхто не дізнається.
      const packetEnd = Math.min(index + remainingLength, data.length);

      // Topic length
      const topicLen = (data[index] << 8) | data[index + 1];
      index += 2;
      const topicName = new TextDecoder().decode(data.subarray(index, index + topicLen));
      index += topicLen;

      // If QoS > 0, skip 2-byte packet identifier
      const qos = (data[0] >> 1) & 0x03;
      if (qos > 0) {
        index += 2;
      }

      // Payload
      const payloadBytes = data.subarray(index, packetEnd);
      const payloadStr = new TextDecoder().decode(payloadBytes);
      const packet: RemoteMeshPacket = JSON.parse(payloadStr);

      // Ігноруємо власні пакети від цього ж клієнта
      if (packet.senderId === this.currentUserId && packet.senderHandle === this.currentHandle) {
        return;
      }

      console.log(`[GlobalMesh] Received on ${topicName}:`, packet.type, packet.senderHandle);

      // Обробка подій
      if (packet.type === 'presence:ping') {
        this.onlinePeers.set(packet.senderHandle, {
          handle: packet.senderHandle,
          name: packet.senderName,
          avatar: packet.senderAvatar,
          lastSeen: Date.now(),
        });
      } else if (packet.type === 'call:signal') {
        // Пакет із сигналом дзвінка з мешу відкидаємо мовчки й назавжди.
        // Меш не підписує пакетів: `senderHandle` — це рядок, який відправник
        // написав про себе сам. До 05.09 цей `payload` їхав прямо в
        // `callEngine.onSignal`, тобто будь-хто, підписаний на тему, міг
        // підняти слухавку у власника від імені будь-якого вузла.
      } else {
        this.messageListeners.forEach((cb) => cb(packet));
      }
    } catch (err) {
      console.warn('[GlobalMesh] Error parsing publish:', err);
    }
  }

  public subscribe(topic: string) {
    this.subscribedTopics.add(topic);
    if (this.isConnected && this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.sendSubscribePacket(topic);
    }
  }

  private sendSubscribePacket(topic: string) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
    try {
      const topicBytes = new TextEncoder().encode(topic);
      const packetId = Math.floor(Math.random() * 60000) + 1;

      const payload = new Uint8Array(2 + 2 + topicBytes.length + 1);
      payload[0] = (packetId >> 8) & 0xff;
      payload[1] = packetId & 0xff;
      payload[2] = (topicBytes.length >> 8) & 0xff;
      payload[3] = topicBytes.length & 0xff;
      payload.set(topicBytes, 4);
      payload[4 + topicBytes.length] = 0x00; // QoS 0

      const remLen = payload.length;
      const lenBytes: number[] = [];
      let l = remLen;
      do {
        let byte = l % 128;
        l = Math.floor(l / 128);
        if (l > 0) byte |= 0x80;
        lenBytes.push(byte);
      } while (l > 0);

      const packet = new Uint8Array(1 + lenBytes.length + remLen);
      packet[0] = 0x82; // SUBSCRIBE (QoS 1)
      packet.set(new Uint8Array(lenBytes), 1);
      packet.set(payload, 1 + lenBytes.length);

      this.ws.send(packet);
      console.log(`[GlobalMesh] Subscribed to topic: ${topic}`);
    } catch (err) {
      console.warn('[GlobalMesh] Subscribe error:', err);
    }
  }

  public unsubscribe(topic: string) {
    this.subscribedTopics.delete(topic);
  }

  public publish(topic: string, data: any) {
    // Вузьке місце всього виходу назовні: якщо брокера не задано, звідси не
    // йде жоден байт. Перевірка стоїть тут, а не лише у викликачів, щоб нова
    // гілка коду не могла обійти її, не помітивши.
    if (!BROKER_URL) return;
    if (!this.isConnected || !this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return;
    }

    try {
      const topicBytes = new TextEncoder().encode(topic);
      const jsonStr = typeof data === 'string' ? data : JSON.stringify(data);
      const payloadBytes = new TextEncoder().encode(jsonStr);

      const varHeader = new Uint8Array(2 + topicBytes.length);
      varHeader[0] = (topicBytes.length >> 8) & 0xff;
      varHeader[1] = topicBytes.length & 0xff;
      varHeader.set(topicBytes, 2);

      const remLen = varHeader.length + payloadBytes.length;
      
      // Кодування довжини
      let lenBytes: number[] = [];
      let l = remLen;
      do {
        let byte = l % 128;
        l = Math.floor(l / 128);
        if (l > 0) byte |= 0x80;
        lenBytes.push(byte);
      } while (l > 0);

      const packet = new Uint8Array(1 + lenBytes.length + remLen);
      packet[0] = 0x30; // PUBLISH (QoS 0)
      packet.set(new Uint8Array(lenBytes), 1);
      packet.set(varHeader, 1 + lenBytes.length);
      packet.set(payloadBytes, 1 + lenBytes.length + varHeader.length);

      this.ws.send(packet);
    } catch (err) {
      console.warn('[GlobalMesh] Publish error:', err);
    }
  }

  private startPing() {
    this.stopPing();
    this.pingTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(new Uint8Array([0xc0, 0x00])); // PINGREQ
      }
    }, 25000);
  }

  private stopPing() {
    if (this.pingTimer) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  /* ─── Високорівневі методи звʼязку ────────────────────────────────────────── */

  public announcePresence() {
    if (!this.currentHandle) return;
    const packet: RemoteMeshPacket = {
      version: 1,
      type: 'presence:ping',
      senderId: this.currentUserId,
      senderName: this.currentUserName,
      senderHandle: this.currentHandle,
      senderAvatar: this.currentUserAvatar,
      timestamp: Date.now(),
      payload: { status: 'online' },
    };
    this.publish('phantom/mesh/global/presence', packet);
  }

  /**
   * Надіслати пряме повідомлення віддаленому користувачеві в будь-яку точку світу
   */
  public sendDirectMessage(targetHandle: string, message: Message, chatId?: string) {
    const cleanTarget = this.normalizeHandle(targetHandle);
    const packet: RemoteMeshPacket = {
      version: 1,
      type: 'message:new',
      senderId: this.currentUserId,
      senderName: this.currentUserName,
      senderHandle: this.currentHandle,
      senderAvatar: this.currentUserAvatar,
      targetHandle: cleanTarget,
      chatId: chatId || `chat_dm_${this.currentHandle}`,
      timestamp: Date.now(),
      payload: message,
    };

    // Відправляємо у персональний топік адресата
    this.publish(`phantom/mesh/user/${cleanTarget}`, packet);
  }

  /**
   * ЗАКРИТО 05.09, і закрито в коді, а не змінною оточення.
   *
   * Тут стояла публікація сигналів дзвінка в теми `phantom/mesh/user/<нік>` і
   * `phantom/mesh/call/<call_id>`. У `payload` їхав `signalData` рушія дзвінків
   * як є: **відкритий SDP** і **всі ICE-кандидати** — тобто внутрішні й зовнішні
   * адреси апарата, порти, і повний опис медіа-сесії. Меш не шифрує вміст
   * пакета й не перевіряє, хто підписаний на тему: будь-хто, підписавшись на
   * `phantom/mesh/#`, читав, звідки і куди дзвонить власник.
   *
   * Мовчало це рівно тому, що `VITE_MESH_BROKER_URL` порожня в цій збірці —
   * `publish()` виходить першим рядком. Тобто вся приватність дзвінка трималась
   * на одній незаданій змінній: задай її, щоб полагодити листування, — і SDP
   * поїде назовні тим самим рухом. Змінна оточення не є воротами.
   *
   * Ворота тепер справжні: метод не публікує нічого й ніколи. Щоб сигнали
   * дзвінка легально пішли мешем, спершу потрібне шифрування вмісту під ключ
   * адресата й перевірка відправника — доти правильна відповідь тут «ні».
   * Дзвінок від цього не втратив нічого: він і так їздить через вузол
   * (`POST /messenger/call/*`), який знає адресата і має його ключі.
   *
   * Сторож `callsAreHonest.test.ts` червоніє, якщо тіло цього методу знову
   * почне публікувати або якщо в `src/` зʼявиться його виклик.
   */
  // Метод видалено цілком, а не залишено порожнім: порожній метод — це
  // запрошення дописати в нього тіло. Сторож `callsAreHonest.test.ts` шукає
  // саме імʼя `sendCallSignal` в усьому `src/` і червоніє на його повернення.
  // Правильний шлях сигналу дзвінка — вузол, а не меш.

  /**
   * Підписка на отримання вхідних повідомлень
   */
  public onMessage(callback: (packet: RemoteMeshPacket) => void) {
    this.messageListeners.add(callback);
    return () => this.messageListeners.delete(callback);
  }

  // `onCallSignal` прибрано 05.09 разом із `sendCallSignal`: підписка на
  // сигнали дзвінка з непідписаного мешу не має жодного чесного застосування.

  public getOnlinePeers() {
    return Array.from(this.onlinePeers.values());
  }

  public isMeshConnected() {
    return this.isConnected;
  }
}

export const globalP2PMesh = new GlobalP2PMeshService();

if (typeof window !== 'undefined') {
  (window as any).__phantom_p2p_mesh = globalP2PMesh;
}

/**
 * jsQR поза головним потоком — для WebView без `BarcodeDetector`.
 *
 * Сторінка шле сюди вже вирізаний центральний квадрат кадру (≤480 px,
 * RGBA, буфер передається без копії) не частіше разу на 150 мс; воркер
 * відповідає `{ id, text }`. Головний потік тим часом малює аркуш і
 * відповідає на дотики — до 08.10 jsQR рахував там кожен кадр камери.
 *
 * CSP телефона (`script-src 'self'`, worker-src не задано) пускає воркер
 * того ж походження, тобто цей файл, зібраний Vite в `assets/`.
 */
import jsQR from 'jsqr';

export interface QrFrame {
  id: number;
  width: number;
  height: number;
  data: ArrayBuffer;
}

export interface QrAnswer {
  id: number;
  text: string | null;
}

export function decodeQrFrame(frame: QrFrame): QrAnswer {
  let text: string | null = null;
  try {
    const pixels = new Uint8ClampedArray(frame.data);
    if (pixels.length >= frame.width * frame.height * 4) {
      const code = jsQR(pixels, frame.width, frame.height, { inversionAttempts: 'dontInvert' });
      text = code?.data ? code.data : null;
    }
  } catch {
    text = null;
  }
  return { id: frame.id, text };
}

// Слухаємо лише всередині воркера: тест імпортує `decodeQrFrame` у jsdom, і
// там `self` — це вікно, якому чужий `onmessage` не потрібен.
const scope = globalThis as unknown as {
  WorkerGlobalScope?: unknown;
  onmessage: ((e: MessageEvent<QrFrame>) => void) | null;
  postMessage: (answer: QrAnswer) => void;
};
if (typeof scope.WorkerGlobalScope !== 'undefined') {
  scope.onmessage = (e) => scope.postMessage(decodeQrFrame(e.data));
}

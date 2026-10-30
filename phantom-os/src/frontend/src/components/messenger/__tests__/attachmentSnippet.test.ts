import { describe, expect, it } from 'vitest';
import { attachmentKindFromSnippet, kindLine } from '../chatListFormat';

// Власник 24.09 бачив у рядку PHANTOM сирий опис голосового замість слова.
describe('прев\'ю з сирого опису вкладення', () => {
  it('голосове з опису стає словом з тривалістю', () => {
    const raw = '{"name":"voice_1790276605513.webm","size":48211,"mime":"audio/webm","duration":12}';
    const kind = attachmentKindFromSnippet(raw);
    expect(kind).toBe('voice');
    expect(kindLine(kind, null, raw)).toBe('Голосове · 0:12');
  });

  it('кружок, фото, відео і файл розпізнаються за іменем і типом', () => {
    expect(attachmentKindFromSnippet('{"name":"video_note_1.webm"}')).toBe('video-note');
    expect(attachmentKindFromSnippet('{"name":"IMG_1.jpg"}')).toBe('image');
    expect(attachmentKindFromSnippet('{"name":"a.bin","mime":"video/mp4"}')).toBe('video');
    expect(attachmentKindFromSnippet('{"name":"report.pdf"}')).toBe('file');
  });

  it('звичайний текст і невалідний JSON не чіпаються', () => {
    expect(attachmentKindFromSnippet('Привіт {друже}')).toBeNull();
    expect(attachmentKindFromSnippet('{не json')).toBeNull();
    expect(attachmentKindFromSnippet('{"x":1}')).toBeNull();
  });
});

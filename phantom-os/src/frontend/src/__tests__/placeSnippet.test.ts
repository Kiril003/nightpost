/**
 * Превʼю місця в списку розмов (28.09): міст інколи кладе в превʼю текст
 * «точка phantom://map?lat=…» — людина бачила сире посилання. Має бути «Місце».
 */
import { describe, it, expect } from 'vitest';
import { attachmentKindFromSnippet, kindLine } from '../components/messenger/chatListFormat';

describe('превʼю місця', () => {
  it('посилання на точку мапи — це «Місце», а не адреса', () => {
    const raw = 'точка phantom://map?lat=49.838846594&lon=24.02324';
    const kind = attachmentKindFromSnippet(raw);
    expect(kind).toBe('geo:point');
    expect(kindLine(kind)).toBe('Місце');
  });

  it('geo:-посилання теж місце', () => {
    expect(attachmentKindFromSnippet('geo:50.45,30.52')).toBe('geo:point');
  });

  it('звичайний текст про мапу місцем не стає', () => {
    expect(attachmentKindFromSnippet('глянь на мапі, де ми')).toBeNull();
    expect(attachmentKindFromSnippet('phantom — найкращий')).toBeNull();
  });
});

/**
 * Меню листа на телефоні — без дверей у нікуди.
 *
 * 25.09 на A52: «Перекласти», «Підсумок агента» й «Завдання з повідомлення»
 * кликали POST /chat/message, а міст телефона відповідає на нього завжди 409.
 * З 26.09 є разовий запит POST /messenger/self/ask — пункти повернулись.
 * «Прослухати вголос» кликав speechSynthesis, якого у WebView немає, хоча
 * міст має /messenger/tts/speak.
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// Допомога над листом живе в useMessageAssist, пункти меню — в аркуші дій.
const SRC = readFileSync(join(process.cwd(), 'src/components/messenger/feed/useMessageAssist.ts'), 'utf8');
const SHEET = readFileSync(join(process.cwd(), 'src/components/messenger/feed/MessageActionSheet.tsx'), 'utf8');

describe('меню листа на телефоні', () => {
  it('переклад, підсумок і завдання на телефоні питають розум Nightpost разово, поза журналом', () => {
    const at = SRC.indexOf('const askLocalAgent');
    expect(SRC.slice(at, at + 900)).toContain('phantomSelfApi.ask(prompt)');
    for (const tag of ['AI Translation', 'AI Key Takeaway', 'AI Action Tasks']) {
      const i = SHEET.indexOf(`{/* ${tag} */}`);
      expect(i).toBeGreaterThan(0);
      expect(SHEET.slice(i, i + 200)).not.toContain('!isPhoneShell()');
    }
  });
  it('озвучування на телефоні — голосом застосунку через міст', () => {
    const at = SRC.indexOf('const handleSpeakMessage');
    expect(SRC.slice(at, at + 900)).toContain('messengerApi.speak(');
  });
});

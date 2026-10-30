import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

describe('RemindersReach', () => {
  const src = () => fs.readFileSync(path.resolve(__dirname, '../PhoneChatList.tsx'), 'utf8');

  it('PhoneChatList has no strips above the list (10 §6): no NextUp, no LiveRoomStrip', () => {
    expect(src()).not.toMatch(/<NextUp\b|<LiveRoomStrip\b|<LivingStrip\b|<StartDoors\b/);
  });

  it('PhoneChatList hosts the reminders sheet and refreshes it with the open chat', () => {
    expect(src()).toMatch(/<RemindersLine\b[^>]*refreshKey=\{activeChatId\}/);
  });
});

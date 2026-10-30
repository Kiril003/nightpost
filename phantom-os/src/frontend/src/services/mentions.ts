/**
 * Who a group line names: the members the author picked from the @ list, kept only
 * while their «@Name» is still in the text. The ids ride on the letter (`mn`), so
 * the receiver never guesses from words.
 */
export function mentionedIds(text: string, picked: ReadonlyMap<string, string>): string[] {
  const out: string[] = [];
  picked.forEach((name, id) => {
    const at = `@${name}`;
    let from = 0;
    for (;;) {
      const i = text.indexOf(at, from);
      if (i < 0) return;
      const next = text.charAt(i + at.length);
      if (!next || !/[\p{L}\p{N}_]/u.test(next)) {
        out.push(id);
        return;
      }
      from = i + 1;
    }
  });
  return out;
}

/** The @ on a row: the wire says so when the letter carries `mn`; old letters fall back to words. */
export function mentionsMeOnWire(mentions: readonly string[] | undefined, meId: string): boolean | null {
  if (!mentions) return null;
  return !!meId && mentions.includes(meId);
}

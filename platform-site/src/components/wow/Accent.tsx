/** Останні `words` слів заголовка — курсивом із засічками, як на головній. */
export default function Accent({ text, words = 1 }: { text: string; words?: number }) {
  const parts = text.split(' ');
  const cut = Math.max(parts.length - words, 0);
  const head = parts.slice(0, cut).join(' ');
  return (
    <>
      {head && `${head} `}
      <em>{parts.slice(cut).join(' ')}</em>
    </>
  );
}

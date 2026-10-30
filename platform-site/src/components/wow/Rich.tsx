import type { Rich as RichText } from '../../i18n/wow';

export default function Rich({ t }: { t: RichText }) {
  const [before, em, after] = t;
  return (
    <>
      {before}
      {em && <em>{em}</em>}
      {after}
    </>
  );
}

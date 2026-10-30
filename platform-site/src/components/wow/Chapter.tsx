import type { ReactNode } from 'react';
import type { WowPart } from '../../i18n/wow';
import Rich from './Rich';

export function Facts({ items, className = 'facts' }: { items: WowPart['facts']; className?: string }) {
  return (
    <ul className={className}>
      {items.map(([label, note]) => (
        <li key={label}>
          {label}
          {note && <span>{note}</span>}
        </li>
      ))}
    </ul>
  );
}

export function Cards({ items }: { items: WowPart['cards'] }) {
  return (
    <ul className="cards">
      {items.map(([title, text]) => (
        <li key={title}>
          <b>{title}</b>
          {text}
        </li>
      ))}
    </ul>
  );
}

export default function Chapter({
  part,
  className = '',
  children,
}: {
  part: WowPart;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section className={`ch ${className}`.trim()} id={part.id} aria-labelledby={`${part.id}-t`}>
      <div className="wrap">
        <div className="ch-head">
          <span className="kick">
            <span className="no">{part.no}</span>
            {part.name}
          </span>
          <span className="right">{part.right}</span>
        </div>
        <h2 className="h2" id={`${part.id}-t`}>
          <Rich t={part.title} />
        </h2>
        {children}
      </div>
    </section>
  );
}

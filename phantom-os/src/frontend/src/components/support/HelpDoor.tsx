import React, { lazy, Suspense, useEffect, useState } from 'react';
import { HelpCentre } from './HelpCentre';
import { PeopleScreen } from '../messenger/PeopleScreen';

const BuilderHub = lazy(() => import('../builder/BuilderPages').then((m) => ({ default: m.BuilderHub })));

type Place = 'help' | 'people' | 'builder';
const PLACES: readonly Place[] = ['help', 'people', 'builder'];

type DoorWindow = Window & {
  __phantomOpenHelp?: () => boolean;
  __phantomOpenPlace?: (key: string) => boolean;
};

/**
 * Веб-місця, яких на телефоні немає в рідних екранах: Допомога (скарги, звернення),
 * Мої люди, Мої сторінки. Відчиняє рідний Профіль › Налаштування.
 */
export const HelpDoor: React.FC = () => {
  const [open, setOpen] = useState<Place | null>(null);

  useEffect(() => {
    const w = window as DoorWindow;
    const help = () => {
      setOpen('help');
      return true;
    };
    const place = (key: string) => {
      const p = PLACES.find((x) => x === key);
      if (!p) return false;
      setOpen(p);
      return true;
    };
    w.__phantomOpenHelp = help;
    w.__phantomOpenPlace = place;
    return () => {
      if (w.__phantomOpenHelp === help) delete w.__phantomOpenHelp;
      if (w.__phantomOpenPlace === place) delete w.__phantomOpenPlace;
    };
  }, []);

  const close = () => setOpen(null);
  if (open === 'help') return <HelpCentre open onClose={close} />;
  if (open === 'people') return <PeopleScreen onClose={close} />;
  if (open === 'builder') {
    return (
      <Suspense fallback={null}>
        <BuilderHub onClose={close} />
      </Suspense>
    );
  }
  return null;
};

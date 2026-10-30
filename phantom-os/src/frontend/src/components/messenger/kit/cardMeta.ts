import { createContext } from 'react';
import type React from 'react';

/**
 * Час і ✓✓ листа, що несе картку. Рядок стрічки кладе їх сюди; перша картка
 * листа забирає їх у свій рядок мети, і тоді під карткою часу вже немає.
 */
export interface CardMeta {
  node: React.ReactNode;
  owner: string | null;
  claim: (id: string) => void;
  release: (id: string) => void;
}

export const CardMetaContext = createContext<CardMeta | null>(null);

export type Lang = 'en' | 'uk';

export const LANGS: { code: Lang; label: string }[] = [
  { code: 'en', label: 'EN' },
  { code: 'uk', label: 'UA' },
];

export interface PageMeta {
  title: string;
  description: string;
}

/** Generic titled bullet used across feature grids. */
export interface Item {
  title: string;
  desc: string;
}

/** Numbered/keyed step in a flow diagram. */
export interface Step {
  k: string;
  title: string;
  desc: string;
}

export interface StateInfo {
  name: string;
  epithet: string;
  desc: string;
}

interface SectionHead {
  eyebrow: string;
  title: string;
  lead: string;
}

export interface CtaBand {
  title: string;
  sub: string;
  primary: string;
  secondary: string;
}


export interface CineCaption {
  title: string;
  body: string;
}

export interface TourView {
  k: string;
  label: string;
  desc: string;
}


export interface Copy {
  meta: PageMeta; // site-level fallback
  nav: {
    home: string;
    os: string;
    companion: string;
  };
  footer: {
    tagline: string;
    productHeading: string;
    made: string;
    rights: string;
  };
  home: {
    spectrum: SectionHead & { states: StateInfo[] };
    cta: CtaBand;
  };

  os: {
    meta: PageMeta;
    hero: { eyebrow: string; title: string; sub: string; chips: string[] };
    intelligence: SectionHead & { features: Item[]; ladderTitle: string; ladder: Step[] };
    surface: SectionHead & { features: Item[] };
    desktop: SectionHead & { points: Item[] };
    reliability: SectionHead & { points: Item[] };
    cta: CtaBand;
  };
  companion: {
    meta: PageMeta;
    hero: { eyebrow: string; title: string; sub: string; chips: string[] };
    foundation: SectionHead & { points: Item[] };
    stream: SectionHead & { points: Item[] };
    nav: SectionHead & { points: Item[] };
    mesh: SectionHead & {
      points: Item[];
      proof: { label: string; value: string }[];
      limitTitle: string;
      limit: string;
    };
    voice: SectionHead & { points: Item[] };
    ai: SectionHead & { points: Item[] };
    data: SectionHead & { points: Item[] };
    everywhere: SectionHead & { points: Item[] };
    cta: CtaBand;
  };
}

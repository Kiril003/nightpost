import registryJson from '../../../server/blocks/registry.json' with { type: 'json' };
import refusalsJson from '../../../server/blocks/refusals.json' with { type: 'json' };

export type FieldSpec = {
  p: string;
  req?: boolean;
  max?: number;
  min?: number;
  step?: number;
  int?: boolean;
  of?: string;
  vocab?: string;
  options?: string[];
  scope?: string;
  item?: boolean;
  target?: string;
  token?: string;
  refusal?: string;
  max_days?: number;
  unique?: boolean;
};

export type PrimitiveSpec = Record<string, unknown> & { refusal: string };

export type BlockSpec = {
  family: string;
  tier: string;
  max: number;
  surfaces: string[];
  edit: string;
  fields: Record<string, FieldSpec>;
  records: Record<string, Record<string, FieldSpec>>;
  variants: string[];
  compact: string;
  empty: string;
  rules: string[];
  refusals: string[];
};

export type PageRules = {
  max_blocks: number;
  max_bytes: number;
  one_token: string[];
  publish: { all?: string[]; any?: string[][] };
};

export type Registry = {
  version: number;
  primitives: Record<string, PrimitiveSpec>;
  families: string[];
  vocab: Record<string, string[]>;
  blocks: Record<string, BlockSpec>;
  page_rules: PageRules;
};

export type RefusalTexts = Record<string, { uk: string; en: string }>;

export const REGISTRY = registryJson as unknown as Registry;
export const REFUSALS = refusalsJson as RefusalTexts;

export const blockKinds = (reg: Registry = REGISTRY): string[] => Object.keys(reg.blocks);
export const familyOf = (kind: string, reg: Registry = REGISTRY): string | undefined => reg.blocks[kind]?.family;
export const blocksOfFamily = (family: string, reg: Registry = REGISTRY): string[] =>
  Object.keys(reg.blocks).filter((k) => reg.blocks[k].family === family);
export const vocab = (name: string, reg: Registry = REGISTRY): string[] => reg.vocab[name] ?? [];
export const refusalText = (key: string, lang: 'uk' | 'en'): string => REFUSALS[key]?.[lang] ?? key;

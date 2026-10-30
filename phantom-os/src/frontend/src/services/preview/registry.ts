import type { ComponentType } from 'react';
import { MEDIA_LIMIT_BYTES, isRenderableImage } from '../messengerMedia';

export type PreviewFamily = 'image' | 'svg' | 'text' | 'markdown' | 'csv' | 'none';

export interface ViewerProps {
  url: string;
  name: string;
  mime: string;
  maxBytes: number;
  onFail?: () => void;
}

export interface ViewerEntry {
  family: Exclude<PreviewFamily, 'none'>;
  maxBytes: number;
  load: () => Promise<{ default: ComponentType<ViewerProps> }>;
}

const ONE_MIB = 1024 * 1024;
const TWO_MIB = 2 * ONE_MIB;

const TEXT_EXTS = new Set([
  'txt',
  'log',
  'json',
  'xml',
  'yaml',
  'yml',
  'ini',
  'conf',
  'toml',
  'js',
  'ts',
  'tsx',
  'jsx',
  'py',
  'kt',
  'java',
  'c',
  'h',
  'cpp',
  'rs',
  'go',
  'sh',
  'css',
  'sql',
]);

const MARKDOWN_EXTS = new Set(['md', 'markdown']);
const CSV_EXTS = new Set(['csv', 'tsv']);
const SVG_EXTS = new Set(['svg']);

const loadImage = () => import('../../components/messenger/preview/ImageViewer');
const loadText = () => import('../../components/messenger/preview/TextViewer');

function extOf(name: string): string {
  const clean = (name || '').trim().toLowerCase();
  const dot = clean.lastIndexOf('.');
  if (dot <= 0 || dot === clean.length - 1) return '';
  return clean.slice(dot + 1);
}

function normalizeMime(mime: string): string {
  return (mime || '').trim().toLowerCase().split(';')[0].trim();
}

function isVagueMime(normMime: string): boolean {
  return normMime === '' || normMime === 'application/octet-stream';
}

export function viewerFor(
  name: string,
  mime: string,
  size: number,
): ViewerEntry | { family: 'none'; reason: 'unknown' | 'too-big' } {
  if (isRenderableImage(name, mime)) {
    const maxBytes = MEDIA_LIMIT_BYTES;
    if (!Number.isFinite(size) || size < 0 || size > maxBytes) {
      return { family: 'none', reason: 'too-big' };
    }
    return { family: 'image', maxBytes, load: loadImage };
  }

  const ext = extOf(name);
  const normMime = normalizeMime(mime);
  const vague = isVagueMime(normMime);

  let family: Exclude<PreviewFamily, 'none' | 'image'> | null = null;
  let maxBytes = 0;
  let load: ViewerEntry['load'] = loadText;

  if (SVG_EXTS.has(ext)) {
    if (!vague && normMime !== 'image/svg+xml') {
      return { family: 'none', reason: 'unknown' };
    }
    family = 'svg';
    maxBytes = TWO_MIB;
    load = loadImage;
  } else if (TEXT_EXTS.has(ext)) {
    if (
      !vague &&
      !normMime.startsWith('text/') &&
      normMime !== 'application/json' &&
      normMime !== 'application/xml'
    ) {
      return { family: 'none', reason: 'unknown' };
    }
    family = 'text';
    maxBytes = ONE_MIB;
    load = loadText;
  } else if (MARKDOWN_EXTS.has(ext)) {
    if (!vague && !normMime.startsWith('text/')) {
      return { family: 'none', reason: 'unknown' };
    }
    family = 'markdown';
    maxBytes = ONE_MIB;
    load = loadText;
  } else if (CSV_EXTS.has(ext)) {
    if (!vague && !normMime.startsWith('text/')) {
      return { family: 'none', reason: 'unknown' };
    }
    family = 'csv';
    maxBytes = TWO_MIB;
    load = loadText;
  }

  if (!family) {
    return { family: 'none', reason: 'unknown' };
  }

  if (!Number.isFinite(size) || size < 0 || size > maxBytes) {
    return { family: 'none', reason: 'too-big' };
  }

  return { family, maxBytes, load };
}

import type { uk } from '../engine';

export const cs: Record<keyof typeof uk, string> = {
  'engine.tab.hq': 'Velín',
  'engine.tab.files': 'Soubory',
  'engineFiles.up': 'Nahoru',
  'engineFiles.search': 'Hledat soubory',
  'engineFiles.empty': 'Prázdné',
  'engineFiles.refused': 'Bez přístupu',
  'engineFiles.missing': 'Nenalezeno',
  'engineFiles.offline': 'Uzel neodpovídá',
  'engineFiles.pick': 'Vyberte soubor',
  'engineFiles.noPreview': 'Bez náhledu',
  'engineFiles.truncated': 'Zobrazen první 1 MB',
  'engineFiles.delete': 'Smazat',
  'engineFiles.deleteTitle': 'Smazat „{name}“?',
  'engineFiles.cancel': 'Zrušit',
  'engineFiles.deleteFailed': 'Nepodařilo se smazat',
};

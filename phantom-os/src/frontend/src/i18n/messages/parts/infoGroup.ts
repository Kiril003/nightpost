export const uk = {
  'infoGroup.kindGroup': 'Група · {count}',
  'infoGroup.kindChannel': 'Канал · {count}',
  'infoGroup.invite': 'Запросити',
  'infoGroup.addMembers': 'Додати учасників',
  'infoGroup.management': 'Керування',
  'infoGroup.admins': 'Адміністратори',
  'infoGroup.unpinChat': 'Відкріпити розмову',
  'infoGroup.pinChat': 'Закріпити розмову',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'infoGroup.kindGroup': 'Group · {count}',
  'infoGroup.kindChannel': 'Channel · {count}',
  'infoGroup.invite': 'Invite',
  'infoGroup.addMembers': 'Add members',
  'infoGroup.management': 'Management',
  'infoGroup.admins': 'Admins',
  'infoGroup.unpinChat': 'Unpin chat',
  'infoGroup.pinChat': 'Pin chat',
};

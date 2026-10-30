export const uk = {
  'chatActionsSheet.leaveAndDelete': 'Вийти й видалити',
  'chatActionsSheet.deleteSentenceGroup': 'Група зникне з цього телефона, а ви з неї вийдете.',
  'chatActionsSheet.clearSentenceGroup': 'Історія зникне з цього телефона. В інших учасників лишиться.',
  'sidebar.leaveFailed': 'Не вдалося вийти з групи',
  'chatActionsSheet.deleteSentenceLeft': 'Група зникне з цього телефона.',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'chatActionsSheet.leaveAndDelete': 'Leave and delete',
  'chatActionsSheet.deleteSentenceGroup': 'The group will disappear from this phone and you will leave it.',
  'chatActionsSheet.clearSentenceGroup': 'The history will disappear from this phone. Other members keep theirs.',
  'sidebar.leaveFailed': 'Could not leave the group',
  'chatActionsSheet.deleteSentenceLeft': 'The group will disappear from this phone.',
};

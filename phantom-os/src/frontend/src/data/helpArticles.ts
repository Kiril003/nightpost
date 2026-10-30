export type HelpTopic = 'calls' | 'messages' | 'money' | 'account' | 'map' | 'other';

export interface HelpArticle {
  id: string;
  topic: HelpTopic;
  steps: number;
}

export const HELP_TOPICS: HelpTopic[] = ['calls', 'messages', 'money', 'account', 'map', 'other'];

export const HELP_ARTICLES: HelpArticle[] = [
  { id: 'calls-no-sound', topic: 'calls', steps: 3 },
  { id: 'calls-mic', topic: 'calls', steps: 3 },
  { id: 'calls-drops', topic: 'calls', steps: 3 },
  { id: 'msg-not-sent', topic: 'messages', steps: 3 },
  { id: 'msg-photo', topic: 'messages', steps: 3 },
  { id: 'money-pending', topic: 'money', steps: 3 },
  { id: 'money-missing', topic: 'money', steps: 3 },
  { id: 'account-lost', topic: 'account', steps: 3 },
  { id: 'account-notify', topic: 'account', steps: 3 },
  { id: 'map-location', topic: 'map', steps: 3 },
  { id: 'map-load', topic: 'map', steps: 3 },
  { id: 'other-report', topic: 'other', steps: 3 },
  { id: 'other-reply', topic: 'other', steps: 3 },
];

export const helpArticleKeys = (article: HelpArticle): string[] => [
  `help.${article.id}.title`,
  ...Array.from({ length: article.steps }, (_, i) => `help.${article.id}.s${i + 1}`),
];

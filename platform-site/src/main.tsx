import React from 'react';
import ReactDOM from 'react-dom/client';
import '@fontsource-variable/inter-tight';
import '@fontsource-variable/manrope';
import '@fontsource-variable/source-serif-4/opsz.css';
import '@fontsource-variable/source-serif-4/opsz-italic.css';
import './styles/tokens.css';
import './styles/global.css';
import './styles/wow.css';
import './styles/top.css';
import App from './App';
import { LanguageProvider } from './i18n';
import { loadDownloads } from './lib/downloads';
import { wantsDownloads } from './lib/prefetch';
import { startTheme } from './lib/theme';

// Збірки потрібні лише картці на /download; на решті сторінок запит був
// зайвим 403 у консолі на кожному відкритті.
if (wantsDownloads(window.location.pathname)) loadDownloads();
startTheme();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <LanguageProvider>
      <App />
    </LanguageProvider>
  </React.StrictMode>,
);

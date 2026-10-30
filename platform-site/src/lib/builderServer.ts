/** Builder v2 (drafts, media, versions, publish, public pages) runs only on the Oracle server; accounts stay on LICENSE_SERVER. */
export const BUILDER_SERVER = (import.meta.env?.VITE_BUILDER_SERVER ?? 'https://api.phantom-os.dev').replace(/\/+$/, '');

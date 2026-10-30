export function wantsDownloads(pathname: string): boolean {
  return pathname.replace(/\/+$/, '') === '/download';
}

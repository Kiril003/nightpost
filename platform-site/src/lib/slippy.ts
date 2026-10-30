export interface MapView {
  lat: number;
  lon: number;
  z: number;
}

export interface TileBox {
  key: string;
  url: string;
  left: number;
  top: number;
}

export const TILE_SIZE = 256;
export const MIN_ZOOM = 2;
export const MAX_ZOOM = 19;
export const MAX_LAT = 85.0511;

export const WORLD_VIEW: MapView = { lat: 20, lon: 0, z: 2 };

export function clampLat(lat: number): number {
  return Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
}

export function wrapLon(lon: number): number {
  const w = ((((lon + 180) % 360) + 360) % 360) - 180;
  return w === -180 && lon > 0 ? 180 : w;
}

export function clampZoom(z: number): number {
  return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, Math.round(z)));
}

function worldSize(z: number): number {
  return TILE_SIZE * 2 ** z;
}

export function project(lat: number, lon: number, z: number): { x: number; y: number } {
  const size = worldSize(z);
  const clampedLat = clampLat(lat);
  const wrappedLon = wrapLon(lon);
  const sin = Math.sin((clampedLat * Math.PI) / 180);
  const x = ((wrappedLon + 180) / 360) * size;
  const y = (0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI)) * size;
  return { x, y };
}

export function unproject(x: number, y: number, z: number): { lat: number; lon: number } {
  const size = worldSize(z);
  const lon = wrapLon((x / size) * 360 - 180);
  const n = Math.PI - (2 * Math.PI * y) / size;
  const lat = clampLat((180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))));
  return { lat, lon };
}

export function tileUrl(z: number, x: number, y: number): string {
  return `https://tile.openstreetmap.org/${z}/${x}/${y}.png`;
}

export function tilesFor(view: MapView, w: number, h: number): TileBox[] {
  const z = clampZoom(view.z);
  const tilesPerSide = 2 ** z;
  const center = project(view.lat, view.lon, z);
  const x0 = center.x - w / 2;
  const y0 = center.y - h / 2;
  const minTileX = Math.floor(x0 / TILE_SIZE);
  const maxTileX = Math.floor((x0 + w - 1) / TILE_SIZE);
  const minTileY = Math.floor(y0 / TILE_SIZE);
  const maxTileY = Math.floor((y0 + h - 1) / TILE_SIZE);
  const out: TileBox[] = [];

  for (let ty = minTileY; ty <= maxTileY; ty++) {
    if (ty < 0 || ty >= tilesPerSide) continue;
    for (let tx = minTileX; tx <= maxTileX; tx++) {
      const wrappedX = ((tx % tilesPerSide) + tilesPerSide) % tilesPerSide;
      out.push({
        key: `${z}/${tx}/${ty}`,
        url: tileUrl(z, wrappedX, ty),
        left: Math.round(tx * TILE_SIZE - x0),
        top: Math.round(ty * TILE_SIZE - y0),
      });
    }
  }
  return out;
}

export function pointAt(view: MapView, w: number, h: number, px: number, py: number): { lat: number; lon: number } {
  const z = clampZoom(view.z);
  const center = project(view.lat, view.lon, z);
  return unproject(center.x + (px - w / 2), center.y + (py - h / 2), z);
}

export function zoomAt(view: MapView, w: number, h: number, px: number, py: number, dz: number): MapView {
  const nextZ = clampZoom(view.z + dz);
  if (nextZ === clampZoom(view.z)) {
    return { lat: clampLat(view.lat), lon: wrapLon(view.lon), z: nextZ };
  }
  const anchor = pointAt(view, w, h, px, py);
  const anchorPx = project(anchor.lat, anchor.lon, nextZ);
  const nextCenter = unproject(anchorPx.x - (px - w / 2), anchorPx.y - (py - h / 2), nextZ);
  return { lat: nextCenter.lat, lon: nextCenter.lon, z: nextZ };
}

export function panBy(view: MapView, dx: number, dy: number): MapView {
  const z = clampZoom(view.z);
  const center = project(view.lat, view.lon, z);
  const next = unproject(center.x - dx, center.y - dy, z);
  return { lat: next.lat, lon: next.lon, z };
}

export function pointText(lat: number, lon: number): string {
  const cLat = clampLat(lat);
  const cLon = wrapLon(lon);
  return `${cLat.toFixed(5)}, ${cLon.toFixed(5)}`;
}

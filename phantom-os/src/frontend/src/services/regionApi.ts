import { request } from './api';
import { setRegionCountry } from '../components/messenger/cards/cardModel';

export interface Region {
  country: string | null;
  currency: string;
  emergency: string;
  metric: boolean;
}

let cached: Promise<Region | null> | null = null;

export function resetRegionCache(): void {
  cached = null;
}

export async function loadRegion(): Promise<Region | null> {
  if (!cached) {
    cached = (async () => {
      try {
        const raw = await request<Region>('GET', '/messenger/region');
        if (!raw || typeof raw !== 'object') {
          cached = null;
          return null;
        }
        const country = typeof raw.country === 'string' && raw.country ? raw.country : null;
        const region: Region = {
          country,
          currency: typeof raw.currency === 'string' ? raw.currency : '',
          emergency: typeof raw.emergency === 'string' ? raw.emergency : '',
          metric: raw.metric !== false,
        };
        setRegionCountry(region.country);
        return region;
      } catch {
        cached = null;
        return null;
      }
    })();
  }
  return cached;
}

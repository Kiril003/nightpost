import type { MessageKey } from '../i18n';

type Kind =
  | 'square' | 'building' | 'street' | 'house' | 'city' | 'town' | 'village' | 'district' | 'area'
  | 'park' | 'station' | 'stop' | 'cafe' | 'restaurant' | 'shop' | 'hotel' | 'museum' | 'school'
  | 'university' | 'hospital' | 'pharmacy' | 'sight' | 'water' | 'peak' | 'place';

const KIND: Record<string, Kind> = {
  square: 'square', marketplace: 'square', plaza: 'square',
  building: 'building', yes: 'building', commercial: 'building', office: 'building', apartments: 'building',
  street: 'street', road: 'street', residential: 'street', primary: 'street', secondary: 'street',
  tertiary: 'street', unclassified: 'street', living_street: 'street', pedestrian: 'street',
  service: 'street', trunk: 'street', motorway: 'street', footway: 'street',
  house: 'house', detached: 'house',
  city: 'city', town: 'town', village: 'village', hamlet: 'village', isolated_dwelling: 'village',
  suburb: 'district', neighbourhood: 'district', quarter: 'district', borough: 'district', city_district: 'district',
  administrative: 'area', state: 'area', county: 'area', region: 'area', province: 'area', municipality: 'area',
  park: 'park', garden: 'park', playground: 'park', nature_reserve: 'park', forest: 'park', wood: 'park',
  station: 'station', halt: 'station', tram_stop: 'stop', bus_stop: 'stop', platform: 'stop', stop_position: 'stop',
  cafe: 'cafe', restaurant: 'restaurant', fast_food: 'restaurant', pub: 'restaurant', bar: 'restaurant', food_court: 'restaurant',
  shop: 'shop', supermarket: 'shop', convenience: 'shop', mall: 'shop', department_store: 'shop', bakery: 'shop',
  hotel: 'hotel', hostel: 'hotel', guest_house: 'hotel', motel: 'hotel',
  museum: 'museum', gallery: 'museum',
  school: 'school', kindergarten: 'school', college: 'university', university: 'university',
  hospital: 'hospital', clinic: 'hospital', doctors: 'hospital', pharmacy: 'pharmacy',
  attraction: 'sight', viewpoint: 'sight', monument: 'sight', memorial: 'sight', castle: 'sight',
  artwork: 'sight', place_of_worship: 'sight', theatre: 'sight', cinema: 'sight',
  river: 'water', stream: 'water', lake: 'water', water: 'water', reservoir: 'water', pond: 'water',
  peak: 'peak', hill: 'peak', volcano: 'peak',
};

export function osmTypeKey(type: string | null | undefined): MessageKey {
  return `map.osm.${KIND[(type ?? '').toLowerCase()] ?? 'place'}` as MessageKey;
}

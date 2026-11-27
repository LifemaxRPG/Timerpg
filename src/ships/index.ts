import { priceIndex } from '../economy';
import { dayNumberOf, type CalendarDate } from '../calendar';
import { rand } from '../sim/rng';

export type ShipKind = 'ferry' | 'liner' | 'freighter' | 'cruise';
export type TravelClass = 'deck' | 'third' | 'second' | 'first' | 'crew';
export type Port = Readonly<{ id: string; continentId: string }>;
export type Route = Readonly<{ from: Port; to: Port; nauticalMiles: number }>;

function indexedFare(cents1952: number, year: number): number | null {
  const index = priceIndex(year);
  return index === null ? null : Math.max(1, Math.round(cents1952 * index / priceIndex(1952)!));
}

const services = {
  ferry: { from: 1880, through: 2030, knots: 15, days: [0, 1, 2, 3, 4, 5, 6], fares: { deck: 150 } },
  liner: { from: 1880, through: 1975, knots: 20, days: [2, 5], fares: { third: 8500, second: 14500, first: 29000 } },
  freighter: { from: 1880, through: 2030, knots: 13, days: [3], fares: { crew: 0 } },
  cruise: { from: 1965, through: 2030, knots: 18, days: [6], fares: { second: 25000, first: 47000 } },
} as const;

export function timetable(route: Route, year: number) {
  if (priceIndex(year) === null || !Number.isFinite(route.nauticalMiles)
    || route.nauticalMiles <= 0 || route.from.id === route.to.id) return [];
  return (Object.keys(services) as ShipKind[]).flatMap(kind => {
    const service = services[kind];
    if (year < service.from || year > service.through || (kind === 'ferry' && route.nauticalMiles > 120)
      || (kind !== 'ferry' && route.nauticalMiles < 100)) return [];
    return [{
      kind, from: route.from.id, to: route.to.id, weekdays: [...service.days],
      departureHour: kind === 'ferry' ? 8 : 10,
      crossingHours: Math.ceil(route.nauticalMiles / service.knots),
      passportRequired: passportRequired(route, year),
      fares: Object.entries(service.fares).map(([travelClass, cents]) => ({
        travelClass: travelClass as TravelClass, cents: cents === 0 ? 0 : indexedFare(cents, year)!,
      })),
    }];
  });
}

export type PassportEra = 'paper' | 'booklet' | 'modern';
export type Passport = Readonly<{ era: PassportEra; readyOn: number }>;
type Document = 'birth_record' | 'identity_photo' | 'proof_of_address';

function passportEra(year: number): PassportEra | null {
  if (priceIndex(year) === null || year < 1915) return null;
  return year < 1940 ? 'paper' : year < 1980 ? 'booklet' : 'modern';
}

export function passportRequired(route: Route, year: number): boolean {
  return route.from.continentId !== route.to.continentId && passportEra(year) !== null;
}

export function passportOffice(year: number) {
  const era = passportEra(year);
  if (era === null) return null;
  const documents: Document[] = ['birth_record', 'identity_photo'];
  if (era === 'modern') documents.push('proof_of_address');
  return {
    era, office: era === 'paper' ? 'town clerk' : 'passport office',
    feeCents: indexedFare(era === 'modern' ? 1600 : era === 'booklet' ? 900 : 500, year)!,
    waitingDays: era === 'paper' ? 7 : era === 'booklet' ? 14 : 21, documents,
  };
}

export function applyForPassport(date: CalendarDate, documents: readonly Document[]) {
  const day = dayNumberOf(date);
  const application = passportOffice(date.year);
  if (day === null || !application) return null;
  const missing = application.documents.filter(document => !documents.includes(document));
  return {
    missing, feeCents: missing.length ? 0 : application.feeCents,
    passport: missing.length ? null : { era: application.era, readyOn: day + application.waitingDays },
  };
}

export function canBoard(route: Route, date: CalendarDate, passport: Passport | null): boolean {
  const day = dayNumberOf(date);
  if (day === null || priceIndex(date.year) === null) return false;
  if (!passportRequired(route, date.year)) return true;
  return passport !== null && passport.era === passportEra(date.year) && day >= passport.readyOn;
}

export type UnpaperedMethod = 'stowaway' | 'work_passage';

export function discoveryChance(year: number, method: UnpaperedMethod): number | null {
  if (priceIndex(year) === null) return null;
  const base = year < 1940 ? .12 : year < 1980 ? .3 : .55;
  return method === 'stowaway' ? base : base * .55;
}

export function unpaperedPassage(
  route: Route, date: CalendarDate, voyageId: string, kind: ShipKind, method: UnpaperedMethod, seed: string,
) {
  const day = dayNumberOf(date);
  const chance = discoveryChance(date.year, method);
  if (day === null || chance === null || !passportRequired(route, date.year)
    || (method === 'work_passage' && kind !== 'freighter')
    || !timetable(route, date.year).some(sailing => sailing.kind === kind
      && sailing.weekdays.some(weekday => weekday === (day + 1) % 7))) return null;
  const discovered = rand(seed, 'ship_discovery', route.from.id, route.to.id, day, voyageId, kind, method) < chance;
  return discovered
    ? { discovered, outcome: 'held_for_port_authorities', event: null } as const
    : { discovered, outcome: 'landed_without_clearance', event: {
      kind: 'illegal_landing', portId: route.to.id, voyageId,
    } } as const;
}

const aboard = {
  deck: { berth: null, meals: ['snack counter'], deck: 'open passenger deck', passengers: ['commuters', 'day trippers'] },
  third: { berth: 'shared bunk cabin', meals: ['porridge', 'stew', 'bread and soup'], deck: 'aft promenade', passengers: ['families', 'seasonal workers'] },
  second: { berth: 'two-berth cabin', meals: ['eggs and toast', 'fish pie', 'roast supper'], deck: 'main promenade', passengers: ['clerks', 'holidaymakers'] },
  first: { berth: 'private cabin', meals: ['breakfast service', 'luncheon', 'three-course supper'], deck: 'upper promenade', passengers: ['tourists', 'business travellers'] },
  crew: { berth: 'crew bunk', meals: ['galley breakfast', 'galley lunch', 'galley supper'], deck: 'working deck', passengers: ['deckhands', 'engine crew'] },
} as const;

export function dayAtSea(kind: ShipKind, travelClass: TravelClass, year: number, voyageDay: number, seed: string) {
  const service = services[kind];
  if (priceIndex(year) === null || year < service.from || year > service.through || !Number.isSafeInteger(voyageDay)
    || voyageDay < 1 || !Object.prototype.hasOwnProperty.call(service.fares, travelClass)) return null;
  const profile = aboard[travelClass];
  return {
    voyageDay, berth: profile.berth, meals: [...profile.meals], deck: profile.deck,
    encounter: profile.passengers[Math.floor(rand(seed, 'day_at_sea', kind, travelClass, year, voyageDay)
      * profile.passengers.length)]!,
  };
}

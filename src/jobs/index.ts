import { wage, type Job } from '../economy';
import { rand } from '../sim/rng';

type Role = 'shop_clerk' | 'stock_worker' | 'dishwasher' | 'mechanic_helper' | 'labourer' | 'night_clerk';

const roles: Record<Role, readonly [title: string, job: Job, share: number, startHour: number, minAge: number]> = {
  shop_clerk: ['Shop clerk', 'shop_clerk', 1, 9, 16],
  stock_worker: ['Stock clerk', 'shop_clerk', 2 / 3, 8, 16],
  dishwasher: ['Dishwasher', 'odd_jobs', 1, 16, 16],
  mechanic_helper: ["Mechanic's helper", 'mechanic', .65, 8, 18],
  labourer: ['Labourer', 'labourer', 1, 7, 18],
  night_clerk: ['Night clerk', 'shop_clerk', 1.15, 23, 18],
};

const shopRoles = {
  general: 'shop_clerk', market: 'stock_worker', hardware: 'stock_worker',
  diner: 'dishwasher', gas_station: 'mechanic_helper', garage: 'mechanic_helper',
  mill: 'labourer', factory: 'labourer', hotel: 'night_clerk', motel: 'night_clerk',
} satisfies Record<string, Role>;

export type JobPlace = Readonly<{ id: string; kind: string }>;
export type Opening = Readonly<{
  id: string;
  placeId: string;
  role: Role;
  title: string;
  hourlyCents: number;
  startHour: number;
  shiftHours: number;
  minAge: number;
  wantsReference: boolean;
  requiresPapers: boolean;
}>;

export function openings(places: readonly JobPlace[], year: number, day: number, seed: string): Opening[] {
  if (!Number.isSafeInteger(day) || day < 0) return [];
  const week = Math.floor(day / 7);
  return places.flatMap(place => {
    const kind = place.kind.trim().toLowerCase().replace(/[ -]+/g, '_');
    if (!Object.prototype.hasOwnProperty.call(shopRoles, kind)) return [];
    const primary = shopRoles[kind as keyof typeof shopRoles];
    if ((kind === 'motel' && year < 1950) || (kind === 'gas_station' && year < 1910)) return [];
    const available: Role[] = kind === 'gas_station' && year >= 1975 ? [primary, 'night_clerk'] : [primary];
    return available.flatMap(role => {
      const [title, payJob, share, startHour, minAge] = roles[role];
      const pay = wage(payJob, year);
      if (!pay || rand(seed, 'job_opening', place.id, role, year, week) >= .65) return [];
      return [{
        id: JSON.stringify([place.id, role, year, week]), placeId: place.id, role,
        title: role === 'stock_worker' && year < 1970 ? 'Stock boy' : title,
        hourlyCents: Math.round(pay.hourlyCents * share), startHour,
        shiftHours: role === 'dishwasher' ? 4 : pay.shiftHours, minAge,
        wantsReference: role !== 'dishwasher' && role !== 'labourer', requiresPapers: year >= 1987,
      }];
    });
  });
}

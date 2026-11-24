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

export type Reference = 'good' | 'poor' | 'none';
export type Applicant = Readonly<{
  age: number;
  references: readonly Reference[];
  reputation: number;
  hasPapers: boolean;
}>;

export type Employment = Readonly<{
  openingId: string;
  placeId: string;
  status: 'trial' | 'hired' | 'dismissed';
  hourlyCents: number;
  firstShiftDay: number | null;
  lastShiftDay: number | null;
  strikes: number;
  steadyShifts: number;
  hasRaise: boolean;
  reference: Reference;
}>;

export type Application =
  | { status: 'refused'; reason: 'age' | 'papers' | 'record' }
  | { status: 'trial' | 'hired'; reason: 'probation' | 'qualified'; job: Employment };

export function apply(opening: Opening, applicant: Applicant): Application {
  if (!Number.isFinite(applicant.age) || applicant.age < opening.minAge) return { status: 'refused', reason: 'age' };
  if (opening.requiresPapers && !applicant.hasPapers) return { status: 'refused', reason: 'papers' };
  if (!Number.isFinite(applicant.reputation) || applicant.reputation < .25) return { status: 'refused', reason: 'record' };
  const trial = applicant.reputation < .45 || applicant.references.includes('poor')
    || (opening.wantsReference && !applicant.references.includes('good'));
  const status = trial ? 'trial' : 'hired';
  return {
    status, reason: trial ? 'probation' : 'qualified',
    job: {
      openingId: opening.id, placeId: opening.placeId, status, hourlyCents: opening.hourlyCents,
      firstShiftDay: null, lastShiftDay: null, strikes: 0, steadyShifts: 0, hasRaise: false, reference: 'none',
    },
  };
}

export type ShiftRecord = Readonly<{ day: number; outcome: 'on_time' | 'late' | 'absent' | 'caught_stealing' }>;
export type ShiftResult = Readonly<{ job: Employment; event: 'none' | 'warning' | 'hired' | 'raise' | 'dismissed' }>;

export function shift(job: Employment, record: ShiftRecord): ShiftResult {
  if (job.status === 'dismissed' || !Number.isSafeInteger(record.day) || record.day < 0
    || (job.lastShiftDay !== null && record.day <= job.lastShiftDay)) return { job, event: 'none' };

  const firstShiftDay = job.firstShiftDay ?? record.day;
  const onTime = record.outcome === 'on_time';
  const strikes = job.strikes + (onTime ? 0 : 1);
  const steadyShifts = onTime ? job.steadyShifts + 1 : 0;
  const updated = { ...job, firstShiftDay, lastShiftDay: record.day, strikes, steadyShifts };
  if (strikes >= 2 || record.outcome === 'caught_stealing') {
    return { job: { ...updated, status: 'dismissed', reference: 'poor' }, event: 'dismissed' };
  }
  if (!onTime) return { job: { ...updated, reference: 'none' }, event: 'warning' };
  if (job.status === 'trial' && record.day - firstShiftDay >= 6 && steadyShifts >= 5) {
    return { job: { ...updated, status: 'hired' }, event: 'hired' };
  }
  if (job.status === 'hired' && steadyShifts >= 15 && record.day - firstShiftDay >= 14 && !job.hasRaise) {
    return { job: {
      ...updated, hourlyCents: Math.round(job.hourlyCents * 1.1), hasRaise: true, reference: 'good',
    }, event: 'raise' };
  }
  return { job: { ...updated, reference: job.status === 'hired' && steadyShifts >= 15 ? 'good' : job.reference }, event: 'none' };
}

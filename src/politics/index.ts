import { dateOf, dayNumberOf, type CalendarDate } from '../calendar';
import { clamp01, rand } from '../sim/rng';

export type TownSize = 'small' | 'medium' | 'large';
type OfficeKind = 'mayor' | 'council' | 'sheriff' | 'police_chief';

export function officesFor(size: TownSize, year: number) {
  if (dayNumberOf({ year, month: 1, day: 1 }) === null) return [];
  const modern = year >= 1960;
  const seats = (size === 'small' ? 3 : size === 'medium' ? 5 : 7) + (modern && size !== 'small' ? 2 : 0);
  const sheriff = size === 'small' && !modern;
  const office = (id: string, kind: OfficeKind, termYears: number, elected = true) => {
    let electionDate: CalendarDate | null = null;
    if (elected && (year - 1952) % termYears === 0) {
      const first = dateOf(dayNumberOf({ year, month: 10, day: 1 })!)!;
      electionDate = { year, month: 10, day: 1 + (2 - first.weekday + 7) % 7 };
    }
    return { id, kind, termYears, selection: elected ? 'elected' as const : 'appointed' as const, electionDate };
  };
  return [
    office('mayor', 'mayor', modern ? 4 : 2),
    ...Array.from({ length: seats }, (_, i) => office(`council_${i + 1}`, 'council', 2)),
    sheriff ? office('sheriff', 'sheriff', 4) : office('police_chief', 'police_chief', 4, false),
  ];
}

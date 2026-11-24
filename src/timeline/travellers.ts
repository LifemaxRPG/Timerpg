import { pick, rand, rngFor } from '../sim/rng';
import type {
  ContinentId,
  Timeline,
  TracesAtOptions,
  TravellerTrace,
  TravellerTraceKind,
  UnanchoredTraveller,
} from './types';

export const PROFESSOR_ID = 'professor';
export const PROFESSOR_NAME = 'Professor Hale';

const CONTINENTS: readonly ContinentId[] = [
  'Meridia',
  'Vespera',
  'Boreal Reach',
  'Solis Basin',
  'Aurelia Shelf',
];

/** Invented place roots & suffixes (no real-world towns or countries). */
const PLACE_ROOTS = [
  'Alder', 'Bram', 'Calder', 'Dun', 'Fen', 'Garr', 'Hal', 'Kel', 'Lor', 'Mar',
  'Nor', 'Ost', 'Pen', 'Quen', 'Sel', 'Tal', 'Ver', 'Wex', 'Yar', 'Thorn',
] as const;

const PLACE_SUFFIXES = [
  'wick', 'mere', 'haven', 'ford', 'crest', 'vale', 'port', 'mouth', 'brook', 'stead',
  'combe', 'bury', 'ridge', 'gate', 'land',
] as const;

const TRACE_KINDS: readonly TravellerTraceKind[] = [
  'letter',
  'cache',
  'photograph',
  'early_patent',
  'newspaper_oddity',
  'untraced_lodger',
  'local_memory',
  'anachronism',
];

interface TraceTemplate {
  kind: TravellerTraceKind;
  theme: string;
  describe: (placeName: string, year: number, detail: string) => string;
  details: readonly string[];
}

const TRACE_TEMPLATES: readonly TraceTemplate[] = [
  {
    kind: 'letter',
    theme: 'sealed correspondence',
    details: [
      'postmarked with ink that will not be manufactured for twenty years',
      'left in a hotel safe with instructions to hold it for an unnamed visitor',
      'folded inside an unclaimed parcel at the rail freight desk',
    ],
    describe: (place, year, detail) =>
      `A letter in ${place} (${year}), ${detail}, signed only with a stylized pendulum mark.`,
  },
  {
    kind: 'cache',
    theme: 'buried field cache',
    details: [
      'a brass cylinder containing tungsten wire and hand-drawn wiring diagrams',
      'a waxed-canvas satchel holding era-stamped bills from three different decades',
      'a locked tin box tucked behind a dry-stone culvert with glass vacuum tubes',
    ],
    describe: (place, year, detail) =>
      `A hidden cache near ${place} (${year}): ${detail}.`,
  },
  {
    kind: 'photograph',
    theme: 'unidentified bystander in a photograph',
    details: [
      'standing beside the civic clock dedication in a coat cut decades out of style',
      'caught at the edge of a harbor launch portrait holding a modern pocket watch',
      'blurred behind a town-square parade banner, looking straight at the lens',
    ],
    describe: (place, year, detail) =>
      `An old ${year} photograph from ${place} shows an unidentified older man ${detail}.`,
  },
  {
    kind: 'early_patent',
    theme: 'impossible patent filing',
    details: [
      'a solid-state signal amplifier sketched thirty years ahead of its time',
      'a compact ceramic isotope casing filed under an alias with no home address',
      'a frequency-stabilized quartz oscillator that local clerks could not build',
    ],
    describe: (place, year, detail) =>
      `A ${year} patent ledger in ${place} records ${detail}.`,
  },
  {
    kind: 'newspaper_oddity',
    theme: 'peculiar gazette notice',
    details: [
      'a paid classified notice predicting the exact hour of a solar flare',
      'a brief column about a stranger who paid for a room in spotless uncirculated coin',
      'an obituary-page cipher that makes no sense to anyone born before the turn of the century',
    ],
    describe: (place, year, detail) =>
      `The ${place} paper in ${year} ran ${detail}.`,
  },
  {
    kind: 'untraced_lodger',
    theme: 'boardinghouse register',
    details: [
      'checked in for six nights, paid cash, and left behind a diagram of planetary orbits',
      'rented an attic room above the telegraph office with no papers and no luggage',
      'signed the guestbook with a street address that will not be surveyed for forty years',
    ],
    describe: (place, year, detail) =>
      `A ${year} lodging ledger in ${place} lists an untraced guest who ${detail}.`,
  },
  {
    kind: 'local_memory',
    theme: 'eyewitness recollection',
    details: [
      'a quiet scientist who repaired a broken radio using parts nobody recognized',
      'an older traveller asking oddly specific questions about the town flood markers',
      'a soft-spoken visitor who hummed a melody nobody in town had ever heard',
    ],
    describe: (place, year, detail) =>
      `Locals in ${place} recall meeting ${detail} in ${year}.`,
  },
  {
    kind: 'anachronism',
    theme: 'out-of-era hardware',
    details: [
      'a pocket transistor receiver sitting unsold in a pawnshop window decades too early',
      'a synthetic-fiber field notebook left on a station bench with metric tolerances',
      'an alloy lens mount turned on a lathe finer than any machine shop in the region',
    ],
    describe: (place, year, detail) =>
      `An anachronism surfaced in ${place} (${year}): ${detail}.`,
  },
];

function eraLabelForYear(year: number): string {
  if (year < 1920) return 'Early Century';
  if (year < 1940) return 'Interwar';
  if (year < 1960) return 'Mid-Century';
  if (year < 1980) return 'Cold War Era';
  if (year < 2000) return 'Late Century';
  return 'Modern Era';
}

function decadeHintForYear(year: number): string {
  const decade = Math.floor(year / 10) * 10;
  return `the ${decade}s`;
}

function makePlaceName(seed: string, index: number, used: Set<string>): { placeId: string; placeName: string } {
  for (let attempt = 0; attempt < 32; attempt++) {
    const r = rngFor(seed, 'prof-place', index, attempt);
    const root = pick(r, PLACE_ROOTS);
    const suffix = pick(r, PLACE_SUFFIXES);
    const name = `${root}${suffix}`;
    if (!used.has(name)) {
      used.add(name);
      return {
        placeId: `place:${name.toLowerCase()}`,
        placeName: name,
      };
    }
  }
  const fallback = `Alderwick-${index}`;
  used.add(fallback);
  return { placeId: `place:${fallback.toLowerCase()}`, placeName: fallback };
}

/**
 * Deterministically generates the professor's unanchored trail from `seed`:
 * 5 to 9 traces across multiple eras and continents, each loosely pointing to the next.
 * Never required to be found; stored outside the sim so timeline changes never erase it.
 */
export function generateProfessorTrail(seed: string, startPlaceId = 'capital'): UnanchoredTraveller {
  const r = rngFor(seed, 'professor-trail');
  // Strictly 5..9 inclusive
  const count = 5 + Math.floor(r() * 5);

  // Choose years spanning multiple eras (between 1914 and 2022), well-separated across decades.
  const spanMin = 1914;
  const spanMax = 2022;
  const step = Math.floor((spanMax - spanMin) / count);
  const years: number[] = [];
  for (let i = 0; i < count; i++) {
    const bandStart = spanMin + i * step;
    const jitter = Math.floor(rand(seed, 'prof-year', i) * Math.max(1, step - 3));
    years.push(Math.min(spanMax, bandStart + jitter));
  }

  // Deterministic permutation of continents so consecutive stops cross continents
  const contOrder = [...CONTINENTS];
  for (let i = contOrder.length - 1; i > 0; i--) {
    const j = Math.floor(rand(seed, 'prof-cont-shuffle', i) * (i + 1));
    const tmp = contOrder[i]!;
    contOrder[i] = contOrder[j]!;
    contOrder[j] = tmp;
  }

  // Shuffle trace kinds for variety across the trail
  const kindOrder = [...TRACE_KINDS];
  for (let i = kindOrder.length - 1; i > 0; i--) {
    const j = Math.floor(rand(seed, 'prof-kind-shuffle', i) * (i + 1));
    const tmp = kindOrder[i]!;
    kindOrder[i] = kindOrder[j]!;
    kindOrder[j] = tmp;
  }

  const usedNames = new Set<string>(['Capital']);
  const stops: Array<{
    year: number;
    continent: ContinentId;
    placeId: string;
    placeName: string;
    template: TraceTemplate;
    detail: string;
  }> = [];

  for (let i = 0; i < count; i++) {
    const continent = contOrder[i % contOrder.length]!;
    const loc =
      i === 0
        ? { placeId: startPlaceId, placeName: startPlaceId === 'capital' ? 'Capital' : startPlaceId }
        : makePlaceName(seed, i, usedNames);
    const kind = kindOrder[i % kindOrder.length]!;
    const template = TRACE_TEMPLATES.find((t) => t.kind === kind) ?? TRACE_TEMPLATES[0]!;
    const detail = pick(rngFor(seed, 'prof-detail', i), template.details);
    stops.push({
      year: years[i]!,
      continent,
      placeId: loc.placeId,
      placeName: loc.placeName,
      template,
      detail,
    });
  }

  const traces: TravellerTrace[] = stops.map((stop, i) => {
    const next = stops[i + 1];
    const clueToNext = next
      ? `Marginal notes hint at heading toward ${next.continent} around ${decadeHintForYear(next.year)} — look for ${next.template.theme}.`
      : undefined;
    const nextHint = next
      ? {
          continent: next.continent,
          decadeHint: decadeHintForYear(next.year),
          themeHint: next.template.theme,
        }
      : undefined;

    return {
      id: `trace:${PROFESSOR_ID}:${seed}:${i}`,
      travellerId: PROFESSOR_ID,
      sequenceIndex: i,
      year: stop.year,
      eraLabel: eraLabelForYear(stop.year),
      continent: stop.continent,
      placeId: stop.placeId,
      placeName: stop.placeName,
      kind: stop.template.kind,
      summary: stop.template.describe(stop.placeName, stop.year, stop.detail),
      ...(clueToNext ? { clueToNext } : {}),
      ...(nextHint ? { nextHint } : {}),
    };
  });

  return {
    id: PROFESSOR_ID,
    name: PROFESSOR_NAME,
    role: 'professor',
    unanchoredSinceYear: years[0],
    traces,
  };
}

/**
 * Returns all unanchored traveller traces present at `(year, placeId)`.
 * Because travellers live outside the simulation, timeline interventions NEVER remove these traces.
 */
export function tracesAt(
  timeline: Timeline,
  year: number,
  placeId: string,
  options: TracesAtOptions = {},
): TravellerTrace[] {
  const exactOnly = options.exactYearOnly === true;
  const out: TravellerTrace[] = [];

  for (const traveller of timeline.travellers) {
    if (options.travellerId && traveller.id !== options.travellerId) continue;
    for (const trace of traveller.traces) {
      if (trace.placeId !== placeId) continue;
      if (exactOnly ? trace.year === year : trace.year <= year) {
        out.push({
          ...trace,
          ...(trace.nextHint ? { nextHint: { ...trace.nextHint } } : {}),
        });
      }
    }
  }

  out.sort((a, b) => a.year - b.year || a.sequenceIndex - b.sequenceIndex || a.id.localeCompare(b.id));
  return out;
}

/** Adds a new unanchored traveller outside the simulation without mutating `timeline`. */
export function addTraveller(timeline: Timeline, traveller: UnanchoredTraveller): Timeline {
  const existingIdx = timeline.travellers.findIndex((t) => t.id === traveller.id);
  const copy: UnanchoredTraveller = {
    ...traveller,
    traces: traveller.traces.map((tr) => ({
      ...tr,
      ...(tr.nextHint ? { nextHint: { ...tr.nextHint } } : {}),
    })),
  };
  const nextTravellers =
    existingIdx >= 0
      ? timeline.travellers.map((t, i) => (i === existingIdx ? copy : t))
      : [...timeline.travellers, copy];
  return {
    ...timeline,
    travellers: nextTravellers,
  };
}

/** Records a new mark left by an unanchored traveller without mutating `timeline`. */
export function addTravellerTrace(
  timeline: Timeline,
  travellerId: string,
  trace: Omit<TravellerTrace, 'id' | 'travellerId' | 'sequenceIndex' | 'eraLabel'> & {
    id?: string;
    sequenceIndex?: number;
    eraLabel?: string;
  },
): Timeline {
  const exists = timeline.travellers.some((t) => t.id === travellerId);
  const baseTravellers = exists
    ? timeline.travellers
    : [
        ...timeline.travellers,
        {
          id: travellerId,
          name: travellerId,
          role: 'traveller' as const,
          traces: [],
        },
      ];

  return {
    ...timeline,
    travellers: baseTravellers.map((t) => {
      if (t.id !== travellerId) return t;
      const seq = trace.sequenceIndex ?? t.traces.length;
      const fullTrace: TravellerTrace = {
        ...trace,
        id: trace.id ?? `trace:${travellerId}:${trace.year}:${trace.placeId}:${seq}`,
        travellerId,
        sequenceIndex: seq,
        eraLabel: trace.eraLabel ?? eraLabelForYear(trace.year),
        ...(trace.nextHint ? { nextHint: { ...trace.nextHint } } : {}),
      };
      return {
        ...t,
        traces: [...t.traces, fullTrace],
      };
    }),
  };
}

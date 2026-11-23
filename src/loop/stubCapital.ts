import { rand } from '../sim/rng';
import type { LoopInit, LoopPersonInit, LoopPlaceInit, LoopStreetGraph } from './types';

export const CAPITAL_JURISDICTION = 'jur:capital';
export const COUNTY_JURISDICTION = 'jur:county';

export const CAPITAL_IDS = {
  haleStore: 'place:hale_store',
  market: 'place:market',
  pawnShop: 'place:pawn_shop',
  policeStation: 'place:police_station',
  bakery: 'place:bakery',
  elmYard: 'place:elm_yard_1',
  elmHouse: 'place:home_01',
  marcusHale: 'p:hale_marcus',
  helenHale: 'p:hale_helen',
  windowWoman: 'p:finch_martha',
  neighbourEd: 'p:kowalski_ed',
  pawnClerk: 'p:vance_arthur',
  marketOwner: 'p:bell_clara',
  marketCoworker: 'p:market_joe',
  bakerOwner: 'p:miller_tom',
  haleBlockNode: 'node:hale_block',
} as const;

const FIRST_M = [
  'Arthur', 'George', 'Henry', 'Walter', 'Frank', 'Harold', 'Albert', 'Raymond',
  'Victor', 'Martin', 'Samuel', 'Daniel', 'Peter', 'Thomas', 'Edward', 'Joseph',
  'Louis', 'Charles', 'Philip', 'Robert',
];

const FIRST_F = [
  'Clara', 'Helen', 'Martha', 'Ruth', 'Evelyn', 'Alice', 'Dorothy', 'Frances',
  'Florence', 'Margaret', 'Beatrice', 'Eleanor', 'Lillian', 'Hazel', 'Nora', 'Grace',
  'Irene', 'Mildred', 'Edith', 'Mabel',
];

const SURNAMES = [
  'Hale', 'Finch', 'Kowalski', 'Vance', 'Bell', 'Miller', 'Carter', 'Brooks',
  'Bennett', 'Hayes', 'Turner', 'Parker', 'Collins', 'Foster', 'Howard', 'Ward',
  'Cooper', 'Reed', 'Bailey', 'Price',
];

export function buildCapitalStreetGraph(): LoopStreetGraph {
  return {
    nodes: [
      { id: 'node:main_west', position: { x: 0, y: 0 }, kind: 'intersection', jurisdictionId: CAPITAL_JURISDICTION, name: 'Main & 1st', streetLight: 0.7 },
      { id: CAPITAL_IDS.haleBlockNode, position: { x: 40, y: 0 }, kind: 'street', jurisdictionId: CAPITAL_JURISDICTION, name: 'Main St (Hale Block)', streetLight: 0.75 },
      { id: 'node:market_square', position: { x: 85, y: 0 }, kind: 'intersection', jurisdictionId: CAPITAL_JURISDICTION, name: 'Market Square', streetLight: 0.8 },
      { id: 'node:side_pawn', position: { x: 40, y: 38 }, kind: 'street', jurisdictionId: CAPITAL_JURISDICTION, name: 'Cedar Side St', streetLight: 0.55 },
      { id: 'node:station', position: { x: 85, y: 42 }, kind: 'station', jurisdictionId: CAPITAL_JURISDICTION, name: 'Precinct Plaza', streetLight: 0.85 },
      { id: 'node:elm_west', position: { x: 0, y: -45 }, kind: 'street', jurisdictionId: CAPITAL_JURISDICTION, name: 'Elm St West', streetLight: 0.5 },
      { id: 'node:elm_east', position: { x: 85, y: -45 }, kind: 'street', jurisdictionId: CAPITAL_JURISDICTION, name: 'Elm St East', streetLight: 0.5 },
    ],
    edges: [
      { from: 'node:main_west', to: CAPITAL_IDS.haleBlockNode, distance: 40 },
      { from: CAPITAL_IDS.haleBlockNode, to: 'node:market_square', distance: 45 },
      { from: CAPITAL_IDS.haleBlockNode, to: 'node:side_pawn', distance: 38 },
      { from: 'node:side_pawn', to: 'node:station', distance: 45 },
      { from: 'node:market_square', to: 'node:station', distance: 42 },
      { from: 'node:main_west', to: 'node:elm_west', distance: 45 },
      { from: 'node:elm_west', to: 'node:elm_east', distance: 85 },
      { from: 'node:elm_east', to: 'node:market_square', distance: 45 },
    ],
  };
}

export function buildCapitalPlaces(): LoopPlaceInit[] {
  const places: LoopPlaceInit[] = [
    {
      id: CAPITAL_IDS.haleStore,
      name: "Hale's General Store",
      kind: 'general_store',
      position: { x: 40, y: 4 },
      ownerId: CAPITAL_IDS.marcusHale,
      jurisdictionId: CAPITAL_JURISDICTION,
      townId: 'capital',
      openHours: [7, 19],
      nodeId: CAPITAL_IDS.haleBlockNode,
    },
    {
      id: CAPITAL_IDS.market,
      name: 'Capital Market',
      kind: 'market',
      position: { x: 85, y: 5 },
      ownerId: CAPITAL_IDS.marketOwner,
      jurisdictionId: CAPITAL_JURISDICTION,
      townId: 'capital',
      openHours: [6, 18],
      nodeId: 'node:market_square',
    },
    {
      id: CAPITAL_IDS.pawnShop,
      name: "Vance's Pawn & Loan",
      kind: 'pawn_shop',
      position: { x: 40, y: 38 },
      ownerId: CAPITAL_IDS.pawnClerk,
      jurisdictionId: CAPITAL_JURISDICTION,
      townId: 'capital',
      openHours: [9, 19],
      nodeId: 'node:side_pawn',
    },
    {
      id: CAPITAL_IDS.policeStation,
      name: 'Capital Precinct',
      kind: 'police_station',
      position: { x: 85, y: 42 },
      jurisdictionId: CAPITAL_JURISDICTION,
      townId: 'capital',
      openHours: [0, 24],
      nodeId: 'node:station',
    },
    {
      id: CAPITAL_IDS.bakery,
      name: 'Oakhaven Bakery',
      kind: 'workplace',
      position: { x: 10, y: 4 },
      ownerId: CAPITAL_IDS.bakerOwner,
      jurisdictionId: CAPITAL_JURISDICTION,
      townId: 'capital',
      openHours: [6, 18],
      nodeId: 'node:main_west',
    },
    {
      id: CAPITAL_IDS.elmYard,
      name: '101 Elm Yard',
      kind: 'yard',
      position: { x: 15, y: -42 },
      ownerId: CAPITAL_IDS.windowWoman,
      jurisdictionId: CAPITAL_JURISDICTION,
      townId: 'capital',
      nodeId: 'node:elm_west',
    },
  ];

  for (let h = 1; h <= 10; h++) {
    const id = `place:home_${String(h).padStart(2, '0')}`;
    const x = (h - 1) * 9 + 4;
    places.push({
      id,
      name: `${100 + h} Elm Street`,
      kind: 'home',
      position: { x, y: -45 },
      ...(h === 1 ? { ownerId: CAPITAL_IDS.windowWoman } : {}),
      ...(h === 2 ? { ownerId: CAPITAL_IDS.neighbourEd } : {}),
      ...(h === 3 ? { ownerId: CAPITAL_IDS.marcusHale } : {}),
      jurisdictionId: CAPITAL_JURISDICTION,
      townId: 'capital',
      nodeId: h <= 5 ? 'node:elm_west' : 'node:elm_east',
    });
  }

  return places;
}

/**
 * Builds the 50-person stub capital of a few blocks (general store, market, pawn shop,
 * police station, bakery, street of homes) with realistic families and friendships.
 */
export function buildStubCapital(opts: {
  seed?: string;
  year?: number;
  startDay?: number;
  startHour?: number;
  startDayOfWeek?: number;
  peopleCount?: number;
  playerName?: string;
} = {}): LoopInit {
  const seed = opts.seed ?? 'capital-loop-01';
  const year = opts.year ?? 1978;
  const targetPeople = Math.max(12, opts.peopleCount ?? 50);

  const people: LoopPersonInit[] = [
    {
      id: CAPITAL_IDS.marcusHale,
      first: 'Marcus',
      surname: 'Hale',
      sex: 'M',
      age: 52,
      traits: { ambition: 0.62, charm: 0.78, diligence: 0.85 },
      homePlaceId: 'place:home_03',
      workplacePlaceId: CAPITAL_IDS.haleStore,
      spouseId: CAPITAL_IDS.helenHale,
      friends: [
        { personId: CAPITAL_IDS.windowWoman, affinity: 0.72 },
        { personId: CAPITAL_IDS.neighbourEd, affinity: 0.68 },
        { personId: CAPITAL_IDS.marketOwner, affinity: 0.55 },
      ],
      reputation: 0.55,
    },
    {
      id: CAPITAL_IDS.helenHale,
      first: 'Helen',
      surname: 'Hale',
      sex: 'F',
      age: 49,
      traits: { ambition: 0.50, charm: 0.84, diligence: 0.76 },
      homePlaceId: 'place:home_03',
      workplacePlaceId: CAPITAL_IDS.haleStore,
      spouseId: CAPITAL_IDS.marcusHale,
      friends: [
        { personId: CAPITAL_IDS.windowWoman, affinity: 0.82 },
        { personId: CAPITAL_IDS.neighbourEd, affinity: 0.65 },
      ],
      reputation: 0.50,
    },
    {
      id: CAPITAL_IDS.windowWoman,
      first: 'Martha',
      surname: 'Finch',
      sex: 'F',
      age: 44,
      traits: { ambition: 0.45, charm: 0.92, diligence: 0.88 },
      homePlaceId: 'place:home_01',
      friends: [
        { personId: CAPITAL_IDS.marcusHale, affinity: 0.72 },
        { personId: CAPITAL_IDS.helenHale, affinity: 0.82 },
        { personId: CAPITAL_IDS.neighbourEd, affinity: 0.86 },
        { personId: CAPITAL_IDS.marketOwner, affinity: 0.65 },
      ],
      reputation: 0.48,
    },
    {
      id: CAPITAL_IDS.neighbourEd,
      first: 'Ed',
      surname: 'Kowalski',
      sex: 'M',
      age: 48,
      traits: { ambition: 0.42, charm: 0.80, diligence: 0.72 },
      homePlaceId: 'place:home_02',
      workplacePlaceId: CAPITAL_IDS.bakery,
      friends: [
        { personId: CAPITAL_IDS.windowWoman, affinity: 0.86 },
        { personId: CAPITAL_IDS.marcusHale, affinity: 0.68 },
        { personId: CAPITAL_IDS.bakerOwner, affinity: 0.64 },
      ],
      reputation: 0.35,
    },
    {
      id: CAPITAL_IDS.pawnClerk,
      first: 'Arthur',
      surname: 'Vance',
      sex: 'M',
      age: 55,
      traits: { ambition: 0.74, charm: 0.56, diligence: 0.82 },
      homePlaceId: 'place:home_04',
      workplacePlaceId: CAPITAL_IDS.pawnShop,
      friends: [{ personId: CAPITAL_IDS.marcusHale, affinity: 0.35 }],
      reputation: 0.15,
    },
    {
      id: CAPITAL_IDS.marketOwner,
      first: 'Clara',
      surname: 'Bell',
      sex: 'F',
      age: 41,
      traits: { ambition: 0.68, charm: 0.82, diligence: 0.80 },
      homePlaceId: 'place:home_05',
      workplacePlaceId: CAPITAL_IDS.market,
      friends: [
        { personId: CAPITAL_IDS.marcusHale, affinity: 0.55 },
        { personId: CAPITAL_IDS.windowWoman, affinity: 0.65 },
        { personId: CAPITAL_IDS.marketCoworker, affinity: 0.60 },
      ],
      reputation: 0.45,
    },
    {
      id: CAPITAL_IDS.marketCoworker,
      first: 'Joe',
      surname: 'Carter',
      sex: 'M',
      age: 29,
      traits: { ambition: 0.48, charm: 0.70, diligence: 0.74 },
      homePlaceId: 'place:home_06',
      workplacePlaceId: CAPITAL_IDS.market,
      friends: [{ personId: CAPITAL_IDS.marketOwner, affinity: 0.60 }],
      reputation: 0.25,
    },
    {
      id: CAPITAL_IDS.bakerOwner,
      first: 'Tom',
      surname: 'Miller',
      sex: 'M',
      age: 38,
      traits: { ambition: 0.60, charm: 0.74, diligence: 0.84 },
      homePlaceId: 'place:home_07',
      workplacePlaceId: CAPITAL_IDS.bakery,
      friends: [{ personId: CAPITAL_IDS.neighbourEd, affinity: 0.64 }],
      reputation: 0.42,
    },
  ];

  const workplaces = [CAPITAL_IDS.haleStore, CAPITAL_IDS.market, CAPITAL_IDS.bakery, CAPITAL_IDS.pawnShop];
  let idx = people.length;
  let familyNo = 1;

  // Populate remaining townsfolk as multi-person households with family & neighbourhood links
  while (people.length < targetPeople) {
    const homeNum = ((familyNo - 1) % 10) + 1;
    const homeId = `place:home_${String(homeNum).padStart(2, '0')}`;
    const surname = SURNAMES[(familyNo + 5) % SURNAMES.length]!;
    const fatherId = `p:cap_${String(idx++).padStart(4, '0')}`;
    const motherId = `p:cap_${String(idx++).padStart(4, '0')}`;
    const hasSpouse = people.length + 1 < targetPeople;
    const baseAge = 28 + Math.floor(rand(seed, 'fam_age', familyNo) * 28);

    const fatherFriends: Array<{ personId: string; affinity: number }> = [
      { personId: CAPITAL_IDS.windowWoman, affinity: 0.45 + rand(seed, 'ff1', familyNo) * 0.35 },
      { personId: CAPITAL_IDS.marcusHale, affinity: 0.40 + rand(seed, 'ff2', familyNo) * 0.35 },
    ];
    if (people.length > 10) {
      const prevId = people[people.length - 1]!.id;
      fatherFriends.push({ personId: prevId, affinity: 0.55 });
    }

    people.push({
      id: fatherId,
      first: FIRST_M[familyNo % FIRST_M.length]!,
      surname,
      sex: 'M',
      age: baseAge,
      traits: {
        ambition: 0.35 + rand(seed, 'a', fatherId) * 0.5,
        charm: 0.45 + rand(seed, 'c', fatherId) * 0.45,
        diligence: 0.4 + rand(seed, 'd', fatherId) * 0.45,
      },
      homePlaceId: homeId,
      workplacePlaceId: workplaces[familyNo % workplaces.length],
      ...(hasSpouse ? { spouseId: motherId } : {}),
      friends: fatherFriends,
      reputation: 0.2,
    });

    if (hasSpouse && people.length < targetPeople) {
      people.push({
        id: motherId,
        first: FIRST_F[familyNo % FIRST_F.length]!,
        surname,
        sex: 'F',
        age: Math.max(22, baseAge - 2),
        traits: {
          ambition: 0.35 + rand(seed, 'a', motherId) * 0.5,
          charm: 0.5 + rand(seed, 'c', motherId) * 0.42,
          diligence: 0.45 + rand(seed, 'd', motherId) * 0.45,
        },
        homePlaceId: homeId,
        ...(familyNo % 2 === 0 ? { workplacePlaceId: workplaces[(familyNo + 1) % workplaces.length] } : {}),
        spouseId: fatherId,
        friends: [
          { personId: CAPITAL_IDS.helenHale, affinity: 0.58 },
          { personId: CAPITAL_IDS.neighbourEd, affinity: 0.52 },
        ],
        reputation: 0.25,
      });

      // Add 1-2 children/young adults in the household while budget remains
      const childCount = people.length + 2 <= targetPeople ? 2 : people.length + 1 <= targetPeople ? 1 : 0;
      for (let c = 0; c < childCount; c++) {
        const childId = `p:cap_${String(idx++).padStart(4, '0')}`;
        const isFemale = (familyNo + c) % 2 === 0;
        const childAge = Math.max(12, Math.min(24, baseAge - 20 + c * 3));
        people.push({
          id: childId,
          first: isFemale
            ? FIRST_F[(familyNo * 3 + c) % FIRST_F.length]!
            : FIRST_M[(familyNo * 3 + c) % FIRST_M.length]!,
          surname,
          sex: isFemale ? 'F' : 'M',
          age: childAge,
          traits: {
            ambition: 0.4 + rand(seed, 'a', childId) * 0.45,
            charm: 0.5 + rand(seed, 'c', childId) * 0.4,
            diligence: 0.35 + rand(seed, 'd', childId) * 0.5,
          },
          homePlaceId: homeId,
          parentIds: [fatherId, motherId],
          friends: [{ personId: CAPITAL_IDS.neighbourEd, affinity: 0.42 }],
          reputation: 0.15,
        });
      }
    }

    familyNo++;
  }

  return {
    seed,
    year,
    startDay: opts.startDay ?? 1,
    startHour: opts.startHour ?? 10,
    startDayOfWeek: opts.startDayOfWeek ?? 1, // Tuesday!
    playerId: 'player',
    playerName: opts.playerName ?? 'Alex Mercer',
    playerAppearance: {
      height: 'average',
      build: 'slim',
      hair: 'dark',
      clothingColor: 'green',
    },
    people,
    places: buildCapitalPlaces(),
    streetGraph: buildCapitalStreetGraph(),
    streetLamps: [
      { position: { x: 40, y: 0 }, radius: 22, intensity: 0.85 },
      { position: { x: 85, y: 0 }, radius: 22, intensity: 0.85 },
      { position: { x: 40, y: 38 }, radius: 18, intensity: 0.7 },
      { position: { x: 85, y: 42 }, radius: 24, intensity: 0.9 },
    ],
    escalateJurisdiction: {
      [CAPITAL_JURISDICTION]: COUNTY_JURISDICTION,
    },
  };
}

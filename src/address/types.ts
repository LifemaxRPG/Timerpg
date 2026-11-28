/** Plain-JSON types for real building addresses, street naming, postal codes, GPS lookup, and era directories. */

export type CardinalDir = 'N' | 'S' | 'E' | 'W';
export type Parity = 'odd' | 'even';

export type StreetKind =
  | 'main'
  | 'street'
  | 'avenue'
  | 'depot'
  | 'court'
  | 'railroad'
  | 'south'
  | 'river'
  | 'bypass'
  | 'stub'
  | 'country'
  | 'ring'
  | 'boulevard'
  | 'arterial'
  | 'hill'
  | 'scenic'
  | 'quay'
  | 'lane'
  | 'grid';

/** One historical street rename, recorded with the year it took effect. */
export interface StreetRename {
  year: number;
  fromName: string;
  toName: string;
  reason: string;
}

/** A block break along a street (distance along the street axis where a new 100-block begins). */
export interface StreetBlockBreak {
  /** Distance along the street (tiles for grid towns, metres for Dove Beach). */
  along: number;
  /** The 100-block number starting here (100, 200, 300...). */
  blockNumber: number;
}

/** A single addressed street axis (either a full street or one directional half like E. Main Street). */
export interface StreetRecord {
  /** Stable deterministic street ID across eras and renames. */
  id: string;
  /** Canonical base street name in its latest/primary form (e.g. "Main Street"). */
  baseName: string;
  /** Optional directional prefix if split across the town origin ("N" | "S" | "E" | "W"). */
  direction?: CardinalDir;
  /** Full name in its latest/primary form (e.g. "E. Main Street"). */
  name: string;
  kind: StreetKind;
  /** Primary district the street serves. */
  district: string;
  /** Year the street first existed. */
  builtYear: number;
  /** Year the street ceased to exist, if ever demolished/abandoned. */
  goneYear?: number;
  /** Chronological list of historical renames (sorted ascending by year). */
  renames: StreetRename[];
  /** Indices into TownPlan.roads (empty for country roads and Dove Beach roads). */
  roadIndices: number[];
  /** Origin point of numbering (numbers rise moving away from this point). */
  origin: { x: number; y: number };
  /** Unit vector pointing in the direction of rising house numbers (for straight streets). */
  dirVec: { x: number; y: number };
  /** Total length in the town's coordinate units (tiles for grid towns, metres for Dove Beach). */
  length: number;
  /** Block breaks defining 100, 200, 300... ranges along the street. */
  blocks: StreetBlockBreak[];
  /** Sampled polyline points [x, y, cumulativeArcLength] for curved roads (Dove Beach). */
  polyline?: [number, number, number][];
}

/** A sub-unit inside a building (apartment, retail suite, split half-number, or duplex unit). */
export interface UnitSpec {
  /** Display label (e.g. "Apt 1A", "Apt 202", "Suite B", "1/2"). */
  label: string;
  kind: 'apartment' | 'suite' | 'half' | 'unit';
  floor?: number;
}

/** An era-specific name for a building (e.g. store renames, cinema eras, GLB state transitions). */
export interface EraBuildingLabel {
  from: number;
  to?: number;
  label: string;
  role?: string;
}

/** Immutable addressed parcel/building entry stored inside a TownAddressBook. */
export interface BuildingEntry {
  /** Primary canonical building ID (e.g. "shop:742,202", "glb:junction_diner", "landmark:cinema", "farm:...", "observatory"). */
  id: string;
  /** Alternative IDs that resolve to this building (e.g. "cinema" for "landmark:cinema"). */
  aliases: string[];
  /** Building category. */
  kind: 'house' | 'shop' | 'apartment' | 'landmark' | 'civic' | 'glb' | 'farm';
  /** Default display label when no sim override or era label applies. */
  label: string;
  /** Searchable role keywords (e.g. "diner", "gas station", "post office", "courthouse", "bakery"). */
  roles: string[];
  /** Raw district ID (e.g. "core", "strip", "old", "harbour", "newtown", "farms"). */
  district: string;
  /** Human-readable district name (e.g. "Downtown", "Old Town", "Seagrove"). */
  districtName: string;
  /** Street ID this building is addressed on. */
  streetId: string;
  /** Numeric house number (always > 0; odd on left of rising axis, even on right). */
  number: number;
  /** Optional suffix for split buildings ("1/2", "A", "B", ...). */
  numberSuffix?: string;
  /** Formatted house number string ("214", "214 1/2", "214A"). */
  houseNumber: string;
  /** Street side parity ("odd" | "even"). */
  parity: Parity;
  /** Distance along the street axis from the street's numbering origin. */
  along: number;
  /** Signed perpendicular offset from the street centerline. */
  sideOffset: number;
  /** Building entrance/reference coordinate in town units (plan tiles, or metres for Dove Beach). */
  position: { x: number; y: number };
  /** First year the building stands. */
  fromYear: number;
  /** Year the building is demolished/replaced (exclusive), if any. */
  toYear?: number;
  /** Internal units (apartments, suites, split halves) inside this building. */
  units: UnitSpec[];
  /** Era-specific display labels (stores, cinemas, multi-era GLB landmarks). */
  eraLabels?: EraBuildingLabel[];
}

/** Postal code era status for a given year. */
export interface PostalEraInfo {
  introducedYear: number;
  active: boolean;
  format: 'none' | 'five-digit' | 'five-digit-plus4';
}

/** The resolved, plain-JSON real address of a building (or unit) in a given year. */
export interface Address {
  buildingId: string;
  townId: string;
  townName: string;
  year: number;
  number: number;
  numberSuffix?: string;
  houseNumber: string;
  streetId: string;
  direction?: CardinalDir;
  baseStreetName: string;
  streetName: string;
  unit?: string;
  district: string;
  districtName: string;
  /** 5-digit postal code from 1963 onward, or null before postal codes were introduced. */
  postalCode: string | null;
  /** Optional 9-digit postal code extension (ZIP+4) from 1983 onward, or null before then. */
  postalCodePlus4: string | null;
  parity: Parity;
  /** Street line (e.g. "214 E. Main Street" or "214 E. Main Street, Apt 2B"). */
  line1: string;
  /** Town + postal line (e.g. "Alderford 46201" or "Alderford" before 1963). */
  line2: string;
  /** Full single-line printable address. */
  formatted: string;
  /** Multi-line letter/envelope address lines. */
  envelope: string[];
  position: { x: number; y: number };
  label: string;
  kind: BuildingEntry['kind'];
  units?: string[];
  /** Former street names (if the street was renamed in history). */
  formerStreetNames?: string[];
}

/** Serializable, plain-JSON address book for a single town. */
export interface TownAddressBook {
  version: 1;
  townId: string;
  townName: string;
  seed: string;
  foundedYear: number;
  archetype: string;
  coordinateUnit: 'tiles' | 'meters';
  origin: { x: number; y: number };
  /** 3-digit town postal prefix (e.g. "462"). */
  postalPrefix: string;
  /** Map of district ID -> 2-digit district postal suffix (e.g. { core: "01", strip: "04", farms: "09" }). */
  districtPostal: Record<string, string>;
  streets: StreetRecord[];
  buildings: BuildingEntry[];
  /** All historical street renames across the town, sorted by year. */
  renames: (StreetRename & { streetId: string })[];
}

export type MatchKind = 'exact' | 'prefix' | 'fuzzy' | 'relational' | 'resident' | 'postal';

export interface FindMatch {
  address: Address;
  score: number;
  matchKind: MatchKind;
  matchedOn: string[];
}

export interface NearestResult {
  /** Nearest standing building's address in this year (null only if the town has zero standing buildings). */
  address: Address | null;
  /** Euclidean distance in town coordinate units (tiles or metres). */
  distance: number;
  /** Interpolated street address at the exact query position (even in empty lots or parks). */
  interpolated: {
    streetId: string;
    streetName: string;
    houseNumber: string;
    number: number;
    parity: Parity;
    line1: string;
    distanceToStreet: number;
  } | null;
}

export interface DirectoryResidentEntry {
  personId: string;
  name: string;
  surname: string;
  firstName: string;
  age: number;
  occupation: string;
  buildingId: string;
  address: string;
  unit?: string;
  streetName: string;
  houseNumber: string;
  phone: string | null;
  workBuildingId?: string;
  workAddress?: string;
}

export interface DirectoryBusinessEntry {
  id: string;
  name: string;
  category: string;
  owner?: string;
  employees: string[];
  buildingId: string;
  address: string;
  unit?: string;
  streetName: string;
  houseNumber: string;
  phone: string | null;
  status?: string;
}

export interface DirectoryCivicEntry {
  buildingId: string;
  name: string;
  role: string;
  address: string;
  streetName: string;
  houseNumber: string;
  phone: string | null;
}

export interface DirectoryStreetListing {
  buildingId: string;
  houseNumber: string;
  number: number;
  parity: Parity;
  label: string;
  kind: BuildingEntry['kind'];
  units: { unit?: string; occupants: string[]; phone: string | null }[];
}

export interface DirectoryStreetSection {
  streetId: string;
  streetName: string;
  postalCode: string | null;
  listings: DirectoryStreetListing[];
}

/** Printable city directory / phone book for a town and year. */
export interface CityDirectory {
  townId: string;
  townName: string;
  year: number;
  title: string;
  postalCodes: string[];
  phoneEra: 'none' | 'three-digit' | 'exchange' | 'seven-digit' | 'ten-digit';
  exchangeName: string | null;
  civic: DirectoryCivicEntry[];
  businesses: DirectoryBusinessEntry[];
  residents: DirectoryResidentEntry[];
  streets: DirectoryStreetSection[];
}

export type AddressBridgeRequest =
  | { op: 'addressOf'; buildingId: string; year: number; unit?: string; includeInactive?: boolean }
  | { op: 'find'; query: string; year: number; limit?: number }
  | { op: 'nearest'; x: number; y: number; year: number }
  | { op: 'streets'; year: number }
  | { op: 'allAddresses'; year: number }
  | { op: 'directory'; year: number; format?: 'json' | 'text' }
  | { op: 'postalInfo'; year: number };

export interface AddressBridgeResponse {
  ok: boolean;
  error?: string;
  result?: unknown;
}

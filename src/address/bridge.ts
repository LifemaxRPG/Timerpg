import { archetypeOf } from '../sim/archetype';
import type { World } from '../sim/types';
import { CITY2_SEED, DISTRICTS } from '../town/city2/site';
import { planFarms } from '../town/farms';
import { planTown, type TownPlan } from '../town/plan';
import { CAPITAL_SEED, isCapital } from '../town/plat';
import { recipeOf, useRecipe, type TownRecipe } from '../town/recipe';
import { townFounded, townName } from '../world/names';
import { buildDirectory, formatDirectory } from './directory';
import { addressOf, allAddresses, find, nearestAddress } from './lookup';
import { numberDoveBeachBuildings, numberTownBuildings, type DoveBeachBuildingInput } from './numbering';
import { buildPostalTable, postalEraInfo } from './postal';
import { buildDoveBeachStreets, buildTownStreets, streetNameIn } from './streets';
import type {
  AddressBridgeRequest,
  AddressBridgeResponse,
  TownAddressBook,
} from './types';

export interface BuildTownAddressOptions {
  /** Include rural farmsteads and country lanes from planFarms(plan). Default: true. */
  includeFarms?: boolean;
  /** Optional display name override (e.g. from a world bake row). */
  townName?: string;
  /** Optional town ID override (defaults to recipe.place or seed). */
  townId?: string;
  /** Optional pre-computed TownPlan to avoid recomputing planTown(seed). */
  plan?: TownPlan;
}

/** Build the complete, deterministic, plain-JSON TownAddressBook for the capital or any procedural/recipe town. */
export function buildTownAddresses(seed: string, options?: BuildTownAddressOptions): TownAddressBook {
  const plan = options?.plan ?? planTown(seed);
  const includeFarms = options?.includeFarms !== false;
  const farmPlan = includeFarms ? planFarms(plan) : null;
  const recipe = recipeOf(seed);
  const displayName = options?.townName ?? townName(seed);
  const foundedYear = townFounded(seed);
  const archetype = archetypeOf(seed).id;
  const townId = options?.townId ?? recipe?.place ?? (isCapital(seed) ? 'capital' : `town:${seed}`);

  const streets = buildTownStreets(plan, farmPlan, displayName, foundedYear);
  const buildings = numberTownBuildings(plan, farmPlan, streets, foundedYear);

  const districts = [...new Set([...streets.map((s) => s.district), ...buildings.map((b) => b.district)])];
  const size = plan.v2?.size ?? 'M';
  const multiDistrict = isCapital(seed) || size === 'M' || size === 'L';
  const { postalPrefix, districtPostal } = buildPostalTable(seed, archetype, districts, multiDistrict);

  const renames = streets
    .flatMap((s) => s.renames.map((r) => ({ ...r, streetId: s.id })))
    .sort((a, b) => a.year - b.year || a.streetId.localeCompare(b.streetId));

  return {
    version: 1,
    townId,
    townName: displayName,
    seed,
    foundedYear,
    archetype,
    coordinateUnit: 'tiles',
    origin: { x: Math.round(plan.center.x * 100) / 100, y: Math.round(plan.center.y * 100) / 100 },
    postalPrefix,
    districtPostal,
    streets,
    buildings,
    renames,
  };
}

/**
 * Build a TownAddressBook directly from a baked TownRecipe (as produced by kotlin-street/tools/towns-bake.ts),
 * restoring any prior global recipe state afterward so callers stay isolated.
 */
export function buildTownAddressesFromRecipe(
  recipe: TownRecipe,
  options?: BuildTownAddressOptions,
): TownAddressBook {
  const prev = recipeOf(recipe.seed);
  useRecipe(recipe);
  try {
    return buildTownAddresses(recipe.seed, {
      townId: recipe.place,
      townName: recipe.name,
      ...options,
    });
  } finally {
    if (prev) useRecipe(prev);
  }
}

export interface BuildDoveBeachOptions {
  seed?: string;
  /** Optional extra Dove Beach buildings (e.g. from a Dove Beach lot generator) to address alongside landmarks. */
  extraBuildings?: readonly DoveBeachBuildingInput[];
}

/** Build the complete, deterministic, plain-JSON TownAddressBook for Dove Beach (src/town/city2/). */
export function buildDoveBeachAddresses(options?: BuildDoveBeachOptions): TownAddressBook {
  const seed = options?.seed ?? CITY2_SEED;
  const streets = buildDoveBeachStreets(seed);
  const buildings = numberDoveBeachBuildings(streets, options?.extraBuildings ?? []);
  const districts = [...new Set([...DISTRICTS.map((d) => d.id), ...buildings.map((b) => b.district)])];
  const { postalPrefix, districtPostal } = buildPostalTable(seed, 'harbor', districts, true);

  const renames = streets
    .flatMap((s) => s.renames.map((r) => ({ ...r, streetId: s.id })))
    .sort((a, b) => a.year - b.year || a.streetId.localeCompare(b.streetId));

  return {
    version: 1,
    townId: 'city2:dove-beach',
    townName: 'Dove Beach',
    seed,
    foundedYear: 1780,
    archetype: 'harbor',
    coordinateUnit: 'meters',
    origin: { x: 0, y: 140 },
    postalPrefix,
    districtPostal,
    streets,
    buildings,
    renames,
  };
}

/** Build the capital's TownAddressBook (convenience helper for CAPITAL_SEED). */
export function buildCapitalAddresses(options?: BuildTownAddressOptions): TownAddressBook {
  return buildTownAddresses(CAPITAL_SEED, { townId: 'capital', ...options });
}

/** Serialize a TownAddressBook to compact plain JSON for baking into phone assets. */
export function serializeAddressBook(book: TownAddressBook): string {
  return JSON.stringify(book);
}

/** Parse a baked plain-JSON TownAddressBook string. */
export function parseAddressBook(json: string): TownAddressBook {
  const parsed = JSON.parse(json) as TownAddressBook;
  if (!parsed || parsed.version !== 1 || !Array.isArray(parsed.streets) || !Array.isArray(parsed.buildings)) {
    throw new Error('Invalid TownAddressBook JSON');
  }
  return parsed;
}

/** Execute one plain-JSON request against a TownAddressBook (for the phone's JS bridge). */
export function handleAddressRequest(
  book: TownAddressBook,
  req: AddressBridgeRequest,
  world?: World,
): AddressBridgeResponse {
  try {
    switch (req.op) {
      case 'addressOf':
        return {
          ok: true,
          result: addressOf(book, req.buildingId, req.year, {
            world,
            unit: req.unit,
            includeInactive: req.includeInactive,
          }),
        };
      case 'find':
        return {
          ok: true,
          result: find(book, req.query, req.year, { world, limit: req.limit }),
        };
      case 'nearest':
        return {
          ok: true,
          result: nearestAddress(book, { x: req.x, y: req.y }, req.year, { world }),
        };
      case 'streets':
        return {
          ok: true,
          result: book.streets
            .filter((s) => req.year >= s.builtYear && (s.goneYear === undefined || req.year < s.goneYear))
            .map((s) => ({
              id: s.id,
              name: streetNameIn(s, req.year),
              kind: s.kind,
              district: s.district,
              builtYear: s.builtYear,
              renames: s.renames,
            })),
        };
      case 'allAddresses':
        return {
          ok: true,
          result: allAddresses(book, req.year, { world }),
        };
      case 'directory': {
        const dir = buildDirectory(book, req.year, world);
        return {
          ok: true,
          result: req.format === 'text' ? formatDirectory(dir) : dir,
        };
      }
      case 'postalInfo':
        return {
          ok: true,
          result: postalEraInfo(req.year),
        };
      default:
        return { ok: false, error: `Unknown address op: ${String((req as { op?: unknown }).op)}` };
    }
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

/** Pure JSON-string-in, JSON-string-out entrypoint for QuickJS-ng / Kotlin bridge callers. */
export function handleAddressJson(book: TownAddressBook, requestJson: string, world?: World): string {
  try {
    const req = JSON.parse(requestJson) as AddressBridgeRequest;
    return JSON.stringify(handleAddressRequest(book, req, world));
  } catch (err) {
    return JSON.stringify({
      ok: false,
      error: err instanceof Error ? err.message : String(err),
    } satisfies AddressBridgeResponse);
  }
}

/** Create a stateful bridge wrapper around a TownAddressBook (plain JSON in and out). */
export function createAddressBridge(book: TownAddressBook, world?: World) {
  return {
    handle: (req: AddressBridgeRequest): AddressBridgeResponse => handleAddressRequest(book, req, world),
    call: (requestJson: string): string => handleAddressJson(book, requestJson, world),
  };
}

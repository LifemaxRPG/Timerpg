import { interpolate } from './eras';
import type { Item } from './catalogue';

type Curve = readonly (readonly [number, number])[];
const curves = {
  electronics: [[0, 4], [5, 2.5], [12, 1.5], [25, 1]],
  calculator: [[1971, 110], [1978, 41], [1985, 8], [1995, 2], [2005, 1.22], [2025, 1]],
  appliances: [[1880, 2.5], [1952, 2], [1978, 1.6], [2005, 1.15], [2025, 1]],
  clothing: [[1880, 1.5], [1952, 1.3], [1978, 1.18], [2025, 1]],
  food: [[1880, 1.16], [1925, 1.04], [1952, 1], [1978, 1.06], [2025, 1]],
  fuel: [[1905, 1.34], [1952, .84], [1973, .74], [1981, 1.45], [1998, .72], [2025, 1]],
  services: [[1880, .63], [1925, .91], [1952, .94], [1978, .99], [2025, 1], [2030, 1.07]],
  tools: [[1880, 1.75], [1952, 1.32], [1978, 1.17], [2025, 1]],
} satisfies Record<string, Curve>;

export function relativePrice(item: Item, year: number): number {
  switch (item.category) {
    case 'electronics':
      if (item.id === 'pocket_calculator') return interpolate(curves.calculator, year);
      // The table radio's own generation matures before it leaves the catalogue.
      if (item.id === 'radio') return interpolate([[1922, 2.4], [1952, 1], [1970, .65]], year);
      return interpolate(curves.electronics, year - item.from)
        / interpolate(curves.electronics, 2025 - item.from);
    case 'appliances': case 'clothing': case 'fuel':
      return interpolate(curves[item.category], year);
    case 'pantry': case 'produce':
      return interpolate(curves.food, year);
    case 'counter':
      return interpolate(curves.services, year);
    case 'hardware_supply': case 'hand_tools': case 'power_tools':
      return interpolate(curves.tools, year);
    default:
      return 1;
  }
}

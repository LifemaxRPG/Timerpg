// The cinema in the whole-town export (street.json v2, tools/town-graph.ts): its landmark entry carries the era's front
// and its footprint, and `graph.cinema` what the building shows in the export's year (src/town/cinema.ts): the sign's
// name, the marquee's lines and the posters (now showing, coming soon), every film made up (src/town/films.ts). The
// building has no LimeZu sprite: the engine builds it (tools/make_buildings.py skips landmark_cinema_*).
import type { LandmarkPlot, TownLayout } from '../../src/town/layout';
import { cinemaProgram } from '../../src/town/cinema';
import type { Film } from '../../src/town/films';

const PX = 184 / 224; // street units per art pixel (tools/town-graph.ts PX)
const r3 = (v: number): number => Math.round(v * 1000) / 1000;

/** The cinema's plot and building, in tiles (the plot) and street units (the building, as rows' buildings give it). */
export interface CinemaFootprint { tilesW: number; tilesD: number; setback: number; depth: number; width: number }
export interface FilmJson {
  title: string; genre: string; tagline: string; rating?: string; star: string;
  poster: { style: string; motif: string; palette: string[]; seed: number };
}
export interface CinemaJson {
  landmark: string; name: string; era: string; decade: number; year: number; screens: number;
  marquee: string[]; nowShowing: FilmJson[]; comingSoon: FilmJson[];
}

/** The extra fields of the cinema's landmark entry (none for the other landmarks). */
export function cinemaLandmark(l: LandmarkPlot): { era?: string; footprint?: CinemaFootprint } {
  if (l.id !== 'cinema') return {};
  return { era: l.era, footprint: { tilesW: l.w, tilesD: l.h, setback: r3(32 * PX), depth: r3((l.h * 32 - 32) * PX), width: r3(l.w * 32 * PX) } };
}

const film = (f: Film): FilmJson => ({
  title: f.title, genre: f.genre, tagline: f.tagline, ...(f.rating ? { rating: f.rating } : {}), star: f.star,
  poster: { style: f.poster.style, motif: f.poster.motif, palette: [...f.poster.palette], seed: f.poster.seed },
});

/** `{ cinema }` for the graph when the town has one open in [year], else nothing. */
export function cinemaBlock(seed: string, year: number, layout: TownLayout): { cinema?: CinemaJson } {
  if (!layout.landmarks.some((l) => l.id === 'cinema')) return {};
  const p = cinemaProgram(seed, year);
  return {
    cinema: {
      landmark: 'landmark:cinema', name: p.name, era: p.era, decade: p.decade, year, screens: p.screens,
      marquee: p.marquee, nowShowing: p.nowShowing.map(film), comingSoon: p.comingSoon.map(film),
    },
  };
}

import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { GAS_PRICES, GLB_LANDMARKS, GLB_LANDMARK_KINDS, gasPrice, glbLandmarkOpenYear, glbLandmarkTiles } from './glbLandmarks';

const catalogue = JSON.parse(readFileSync(new URL('../../kotlin-street/app/src/main/assets/landmarks/landmarks.json', import.meta.url), 'utf8'));
const engine = readFileSync(new URL('../../kotlin-street/shared/src/filament/kotlin/scene3d/LandmarkKit.kt', import.meta.url), 'utf8');

describe('hand-made landmark models', () => {
  it('lists the same models, eras and lots as the engine\'s catalogue', () => {
    expect(catalogue.landmarks.map((l: { kind: string }) => l.kind).sort()).toEqual([...GLB_LANDMARK_KINDS].sort());
    for (const l of catalogue.landmarks) {
      const s = GLB_LANDMARKS[l.kind as keyof typeof GLB_LANDMARKS];
      expect(l.era, l.kind).toEqual(s.era);
      expect(l.footprint.lot, l.kind).toEqual(s.lot);
      expect(l.footprint.building, l.kind).toEqual(s.building);
      expect(l.name).toBe(s.name);
      expect(l.role).toBe(s.role);
    }
  });

  it('opens within its design years, deterministically', () => {
    for (const k of GLB_LANDMARK_KINDS) {
      const y = glbLandmarkOpenYear('gamma', k);
      expect(y).toBe(glbLandmarkOpenYear('gamma', k));
      expect(y).toBeGreaterThanOrEqual(GLB_LANDMARKS[k].era.from);
      expect(y).toBeLessThanOrEqual(GLB_LANDMARKS[k].era.to);
      const [w, d] = glbLandmarkTiles(k);
      expect(w * 1.24).toBeGreaterThanOrEqual(GLB_LANDMARKS[k].lot[0]);
      expect(d * 2.1).toBeGreaterThanOrEqual(GLB_LANDMARKS[k].lot[1]);
    }
  });

  it('prices gas as the engine does', () => {
    for (const [y, p] of GAS_PRICES) expect(engine).toContain(`${y} to ${p.toFixed(2).replace(/^0/, '')}f`);
    expect(gasPrice(1955)).toBeCloseTo(0.29);
    expect(gasPrice(1977)).toBeGreaterThan(0.57);
    expect(gasPrice(2030)).toBeGreaterThan(3.15);
  });
});

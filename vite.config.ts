import { defineConfig } from 'vitest/config';

// Relative base so the build works when served from a subpath or wrapped (Capacitor/Android).
export default defineConfig({
  base: './',
  test: { exclude: ['node_modules/**', 'dist/**', '.claude/**'] },
});

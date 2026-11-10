import { defineConfig } from 'vitest/config';

// Relative base so the build works when served from a subpath or wrapped (Capacitor/Android).
export default defineConfig({
  // Cross-origin isolation lets the language model use several threads (SharedArrayBuffer).
  server: { headers: { 'Cross-Origin-Opener-Policy': 'same-origin', 'Cross-Origin-Embedder-Policy': 'require-corp' } },
  preview: { headers: { 'Cross-Origin-Opener-Policy': 'same-origin', 'Cross-Origin-Embedder-Policy': 'require-corp' } },
  base: './',
  // One JS bundle (the language-model code is loaded lazily but must live in the same file for the single-file build).
  build: { rollupOptions: { output: { inlineDynamicImports: true } } },
  test: { exclude: ['node_modules/**', 'dist/**', '.claude/**'] },
});

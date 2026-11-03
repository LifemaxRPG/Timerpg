import { defineConfig } from 'vite';

// Relative base so the build works when served from a subpath or wrapped (Capacitor/Android).
export default defineConfig({ base: './' });

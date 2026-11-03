import type { CapacitorConfig } from '@capacitor/cli';

// Wraps the web build (dist/) as an Android app. See docs/ANDROID.md.
const config: CapacitorConfig = {
  appId: 'com.timerpg.game',
  appName: 'Timerpg',
  webDir: 'dist',
  backgroundColor: '#111111',
  android: { allowMixedContent: false },
};

export default config;

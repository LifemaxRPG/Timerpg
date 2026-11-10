// Builds ONE self-contained HTML file (export/timerpg.html) that works when opened directly from a phone or
// computer, with no web server: the game's JavaScript is inlined, the generation worker is embedded, and every
// sprite is embedded as a base64 data URI (read by WorldScene.preload through window.__EMBED__).
// Usage: npm run build:single
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

execSync('npx vite build', { stdio: 'inherit' });
const dist = path.resolve('dist');
let html = fs.readFileSync(path.join(dist, 'index.html'), 'utf8');

// 1) inline the main script (escape any "</script" inside it so the browser does not end the tag early)
const m = html.match(/<script type="module"[^>]*src="([^"]+)"[^>]*><\/script>/);
if (!m) throw new Error('main script tag not found in dist/index.html');
const js = fs.readFileSync(path.join(dist, m[1].replace(/^\.\//, '')), 'utf8').replace(/<\/script/gi, '<\\/script');
html = html.replace(/<link rel="modulepreload"[^>]*>/g, '').replace(m[0], '');

// 2) embed the manifest and every sprite it lists
const manifest = JSON.parse(fs.readFileSync(path.join(dist, 'assets', 'manifest.json'), 'utf8'));
const files = {};
for (const group of Object.values(manifest)) {
  for (const it of group) {
    const ext = path.extname(it.path).toLowerCase();
    const mime = ext === '.svg' ? 'image/svg+xml' : ext === '.jpg' || ext === '.jpeg' ? 'image/jpeg' : ext === '.webp' ? 'image/webp' : 'image/png';
    files[it.path] = `data:${mime};base64,${fs.readFileSync(path.join(dist, it.path)).toString('base64')}`;
  }
}
// the language-model engine (single-threaded; a saved file has no cross-origin isolation for threads)
const wasmFile = fs.readdirSync(path.join(dist, 'assets')).find((f) => /^wllama.*\.wasm$/.test(f) && fs.statSync(path.join(dist, 'assets', f)).size > 1e6);
const wasm = wasmFile ? `data:application/wasm;base64,${fs.readFileSync(path.join(dist, 'assets', wasmFile)).toString('base64')}` : undefined;
const embed = `<script>window.__EMBED__=${JSON.stringify({ manifest, files, wasm })};</script>`;

// (a function, not a string: minified JS is full of `$&` / `$'` that String.replace would expand)
html = html.replace('</body>', () => `${embed}\n<script type="module">${js}</script>\n</body>`);
fs.mkdirSync('export', { recursive: true });
fs.writeFileSync('export/timerpg.html', html);
console.log(`export/timerpg.html: ${(html.length / 1048576).toFixed(1)} MB, ${Object.keys(files).length} sprites embedded`);

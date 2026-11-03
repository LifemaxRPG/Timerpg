import Phaser from 'phaser';
import { WorldScene } from './game/WorldScene';

const params = new URLSearchParams(location.search);
const seed = params.get('seed') ?? 'demo';

const scene = new WorldScene(seed);
const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;
const slider = $<HTMLInputElement>('slider');
$('year').textContent = slider.value;

// Year slider: update the label at once, redraw at most once per frame.
let queued = false;
slider.addEventListener('input', () => {
  $('year').textContent = slider.value;
  if (queued) return;
  queued = true;
  requestAnimationFrame(() => { queued = false; scene.setYear(Number(slider.value)); });
});

// "Welcome to ..." banner when you walk into a town.
let hideTimer: number | undefined;
scene.onTown = (info, entered) => {
  $('town').textContent = info.name;
  $('mayor').textContent = [info.mayor ? `Mayor: ${info.mayor}` : '', info.population !== null ? `pop ${info.population}` : ''].filter(Boolean).join(' · ');
  if (!entered) return;
  const b = $('banner');
  b.querySelector('.name')!.textContent = info.name;
  b.querySelector('.sub')!.textContent = `Founded ${info.founded}${info.population !== null ? ` · Population ${info.population}` : ''}`;
  b.classList.add('show');
  window.clearTimeout(hideTimer);
  hideTimer = window.setTimeout(() => b.classList.remove('show'), 4000);
};
scene.onInfo = (lines) => {
  const box = $('info');
  if (!lines) { box.style.display = 'none'; return; }
  box.innerHTML = `<b>${lines[0]}</b>` + lines.slice(1).map((l) => `<div>${l}</div>`).join('');
  box.style.display = 'block';
};
scene.onLoading = (n) => { $('loading').style.display = n > 0 ? 'block' : 'none'; };

new Phaser.Game({
  type: Phaser.AUTO,
  parent: 'game',
  backgroundColor: '#2a5a30',
  scale: { mode: Phaser.Scale.RESIZE, width: window.innerWidth, height: window.innerHeight },
  scene,
  pixelArt: true,
});
(window as unknown as { __scene: WorldScene }).__scene = scene;

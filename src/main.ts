import Phaser from 'phaser';
import { TownScene, describeMayor } from './game/TownScene';
import { describeDiff, diffWorlds, fullName, runTimeline } from './sim';

const params = new URLSearchParams(location.search);
const seed = params.get('seed') ?? 'demo';
const START = 1950;
const PRESENT = 2025;

// Original history, and a "what if" where you killed the future mayor as a child.
const original = runTimeline({ seed, endYear: PRESENT, startYear: START });
const mayor = original.snapshots[PRESENT - START]!.people[original.snapshots[PRESENT - START]!.mayor!]!;
const killYear = Math.max(START + 1, mayor.birthYear + 5);
const altered = runTimeline({
  seed, endYear: PRESENT, startYear: START,
  interventions: [{ kind: 'kill', year: killYear, targetId: mayor.id }],
});

const scene = new TownScene();
scene.setWorlds(original.snapshots, altered.snapshots);

const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;
const slider = $<HTMLInputElement>('slider');
let which: 'original' | 'altered' = 'original';

function update(): void {
  const year = Number(slider.value);
  $('year').textContent = String(year);
  scene.setView(year, which);
  const diffBox = $('diff');
  if (which === 'altered') {
    const a = original.snapshots[year - START]!;
    const b = altered.snapshots[year - START]!;
    const lines = [
      `<b>What if you killed ${fullName(mayor)} in ${killYear}?</b>`,
      ...(year < killYear ? ['(that hasn\'t happened yet in ' + year + ')'] : describeDiff(diffWorlds(a, b), a, b)),
    ];
    diffBox.innerHTML = lines.map((l) => `<div>${l}</div>`).join('');
    diffBox.style.display = 'block';
  } else {
    diffBox.style.display = 'none';
  }
}

scene.onWorld = (w) => { $('mayor').textContent = describeMayor(w) + ` · pop ${Object.values(w.people).filter((p) => p.deathYear === undefined).length}`; };
scene.onInfo = (lines) => {
  const box = $('info');
  if (!lines) { box.style.display = 'none'; return; }
  box.innerHTML = `<b>${lines[0]}</b>` + lines.slice(1).map((l) => `<div>${l}</div>`).join('');
  box.style.display = 'block';
};

slider.addEventListener('input', update);
$('orig').addEventListener('click', () => { which = 'original'; $('orig').classList.add('on'); $('alt').classList.remove('on'); update(); });
$('alt').addEventListener('click', () => { which = 'altered'; $('alt').classList.add('on'); $('orig').classList.remove('on'); update(); });

new Phaser.Game({
  type: Phaser.AUTO,
  parent: 'game',
  backgroundColor: '#1b1b1b',
  scale: { mode: Phaser.Scale.RESIZE, width: window.innerWidth, height: window.innerHeight },
  scene,
  pixelArt: true,
});
scene.onReady = update;
(window as unknown as { __scene: TownScene }).__scene = scene;

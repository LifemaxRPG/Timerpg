import Phaser from 'phaser';
import { WorldScene, type ActionSpec, type Selection, type TownInfo } from './game/WorldScene';

const params = new URLSearchParams(location.search);
const seed = params.get('seed') ?? 'demo';

const scene = new WorldScene(seed);
const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;
const esc = (t: string): string => t.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
const slider = $<HTMLInputElement>('slider');
$('year').textContent = slider.value;

// ---------- year slider: the time machine ----------
let queued = false;
slider.addEventListener('input', () => {
  $('year').textContent = slider.value;
  const hint = document.getElementById('actyear');
  if (hint) hint.textContent = slider.value;
  if (queued) return;
  queued = true;
  requestAnimationFrame(() => { queued = false; scene.setYear(Number(slider.value)); scene.reselect(); });
});

// ---------- "Welcome to ..." banner, HUD and what-changed panel ----------
let hideTimer: number | undefined;
scene.onTown = (info: TownInfo, entered: boolean) => {
  $('town').textContent = info.name;
  $('mayor').textContent = [info.mayor ? `Mayor: ${info.mayor}` : '', info.population !== null ? `pop ${info.population}` : ''].filter(Boolean).join(' · ');
  renderChanges(info);
  if (!entered) return;
  const b = $('banner');
  b.querySelector('.name')!.textContent = info.name;
  b.querySelector('.sub')!.textContent = `Founded ${info.founded}${info.population !== null ? ` · Population ${info.population}` : ''}`;
  b.classList.add('show');
  window.clearTimeout(hideTimer);
  hideTimer = window.setTimeout(() => b.classList.remove('show'), 4000);
};

function renderChanges(info: TownInfo): void {
  const box = $('changes');
  if (info.changes === null) { box.style.display = 'none'; return; }
  const lines = info.changes.length ? info.changes : ['No visible difference yet in this year. Slide forward to see the ripples.'];
  box.innerHTML = `<h2>What you changed in ${esc(info.name)} (year ${slider.value})</h2>${lines.map((l) => `<div class="line">${esc(l)}</div>`).join('')}<div style="margin-top:6px"><button id="undo" data-town="${esc(info.townId)}">Undo all changes here</button></div>`;
  box.style.display = 'block';
}
$('changes').addEventListener('click', (e) => {
  const t = (e.target as HTMLElement).closest('#undo') as HTMLElement | null;
  if (t) scene.reset(t.dataset.town!);
});

// ---------- clicking a building: who lives there and what you can do to them ----------
let current: Selection | null = null;
scene.onSelect = (sel) => {
  current = sel;
  const box = $('info');
  if (!sel) { box.style.display = 'none'; return; }
  const adults = (id: string): string => scene.adultsOf(sel.townId, id).map((a) => `<option value="${esc(a.id)}">${esc(a.label)}</option>`).join('');
  const people = sel.people.map((p) => `
    <div class="row"><div class="who">${esc(p.label)}</div>
      <span><button class="bad" data-act="kill" data-id="${esc(p.id)}">Kill</button>
      <button data-act="smear" data-id="${esc(p.id)}">Smear</button>
      <button data-act="praise" data-id="${esc(p.id)}">Praise</button>
      <button data-act="gift" data-id="${esc(p.id)}">Gift 200</button></span>
      <span>Introduce to <select data-for="${esc(p.id)}">${adults(p.id)}</select> <button data-act="introduce" data-id="${esc(p.id)}">Go</button></span>
    </div>`).join('');
  const biz = sel.business ? `<div class="row"><div class="who">${esc(sel.business.label)}</div><span><button data-act="invest" data-id="${esc(sel.business.id)}">Invest 500</button> <button class="bad" data-act="sabotage" data-id="${esc(sel.business.id)}">Sabotage</button></span></div>` : '';
  box.innerHTML = `<b>${esc(sel.title)}</b>${sel.lines.map((l) => `<div>${esc(l)}</div>`).join('')}${people}${biz}
    <div class="hint">${sel.people.length || sel.business ? `You act in <b id="actyear">${slider.value}</b>: use the year slider to travel first.` : 'Nothing to act on here.'}</div><div class="result" id="result"></div>`;
  box.style.display = 'block';
};

$('info').addEventListener('click', async (e) => {
  const btn = (e.target as HTMLElement).closest('button[data-act]') as HTMLButtonElement | null;
  if (!btn || !current) return;
  const id = btn.dataset.id!;
  const act = btn.dataset.act!;
  const specs: Record<string, () => ActionSpec> = {
    kill: () => ({ kind: 'kill', targetId: id }),
    smear: () => ({ kind: 'rumor', targetId: id, amount: -1 }),
    praise: () => ({ kind: 'rumor', targetId: id, amount: 1 }),
    gift: () => ({ kind: 'gift', targetId: id, amount: 200 }),
    invest: () => ({ kind: 'invest', businessId: id, amount: 500 }),
    sabotage: () => ({ kind: 'sabotage', businessId: id }),
    introduce: () => {
      const sel = (btn.parentElement!.querySelector('select') as HTMLSelectElement | null)?.value ?? '';
      return { kind: 'introduce', a: id, b: sel };
    },
  };
  const spec = specs[act]?.();
  if (!spec) return;
  const result = $('result');
  result.className = 'result';
  result.textContent = 'Rewriting history…';
  const r = await scene.act(current.townId, spec);
  const el = document.getElementById('result');
  if (el) { el.className = `result ${r.ok ? 'ok' : 'no'}`; el.textContent = (r.ok ? '✓ ' : '✗ ') + r.message; }
});

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

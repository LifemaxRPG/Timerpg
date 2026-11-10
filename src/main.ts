import Phaser from 'phaser';
import { WorldScene, type ActionSpec, type Selection, type TownInfo } from './game/WorldScene';

const params = new URLSearchParams(location.search);
const seed = params.get('seed') ?? 'demo';

const scene = new WorldScene(seed);
const $ = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;
const esc = (t: string): string => t.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!);
const slider = $<HTMLInputElement>('slider');
$('year').textContent = slider.value;

// ---------- touch: on-screen joystick, zoom buttons (pinch is handled by the scene) ----------
const isTouch = matchMedia('(pointer: coarse)').matches || 'ontouchstart' in window || params.get('touch') === '1';
if (isTouch) document.body.classList.add('touch');
{
  const stick = $('stick'), knob = $('knob');
  const R = 46; // how far the knob can travel from the center, in px
  let active: number | null = null;
  const move = (e: PointerEvent): void => {
    const r = stick.getBoundingClientRect();
    let dx = e.clientX - (r.left + r.width / 2), dy = e.clientY - (r.top + r.height / 2);
    const len = Math.hypot(dx, dy);
    if (len > R) { dx = (dx / len) * R; dy = (dy / len) * R; }
    knob.style.transform = `translate(${dx}px, ${dy}px)`;
    scene.setMove(dx / R, dy / R);
  };
  stick.addEventListener('pointerdown', (e) => { active = e.pointerId; stick.setPointerCapture(e.pointerId); move(e); });
  stick.addEventListener('pointermove', (e) => { if (e.pointerId === active) move(e); });
  const end = (e: PointerEvent): void => { if (e.pointerId !== active) return; active = null; knob.style.transform = ''; scene.setMove(0, 0); };
  stick.addEventListener('pointerup', end);
  stick.addEventListener('pointercancel', end);
}
$('zin').addEventListener('click', () => scene.zoomBy(1.25));
$('zout').addEventListener('click', () => scene.zoomBy(0.8));

scene.onDoor = (label, inside) => {
  if (inside) $('banner').classList.remove('show');
  const button = $('door');button.textContent=isTouch?label.replace(' [E]','').replace(' [Esc]',''):label;button.style.display=label?'block':'none';
  button.dataset.inside=String(inside);
};
$('door').addEventListener('click',()=>scene.interact());

// ---------- year slider: the time machine ----------
const stepYear = (d: number): void => {
  slider.value = String(Math.min(2025, Math.max(1950, Number(slider.value) + d)));
  slider.dispatchEvent(new Event('input'));
};
$('yminus').addEventListener('click', () => stepYear(-1));
$('yplus').addEventListener('click', () => stepYear(1));
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
scene.onClock = (t) => { $('clock').textContent = t; };
scene.onSelect = (sel) => {
  current = sel;
  const box = $('info');
  if (!sel) { box.style.display = 'none'; return; }
  const people = sel.people.map((p) => `<div class="row"><div class="who">${esc(p.label)}</div></div>`).join('');
  const biz = sel.business ? `<div class="row"><div class="who">${esc(sel.business.label)}</div><span><button data-act="invest" data-id="${esc(sel.business.id)}">Invest 500</button> <button class="bad" data-act="sabotage" data-id="${esc(sel.business.id)}">Sabotage</button></span></div>` : '';
  box.innerHTML = `<div class="head"><b>${esc(sel.title)}</b><span class="close" id="closesel" role="button" aria-label="Close">×</span></div>${sel.lines.map((l) => `<div>${esc(l)}</div>`).join('')}${sel.people.length === 1 && !sel.business ? ((r) => (r ? `<div>${esc(r.job)}</div>` : ''))(scene.npcs.residentById(sel.people[0]!.id)) : ''}${people}${biz}
    <div class="hint">${sel.people.length || sel.business ? `Find people on the street and walk up to them to talk. You act in <b id="actyear">${slider.value}</b>.` : 'Nothing to act on here.'}</div><div class="result" id="result"></div>`;
  box.style.display = 'block';
};

$('info').addEventListener('click', async (e) => {
  if ((e.target as HTMLElement).id === 'closesel') { scene.clearSelection(); return; }
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

// ---------- walking up to someone: a conversation menu ----------
const talk = $('talk');
let noteTimer = 0;
scene.onNote = (text) => {
  const n = $('note');
  n.textContent = text; n.style.display = 'block';
  clearTimeout(noteTimer);
  noteTimer = window.setTimeout(() => { n.style.display = 'none'; }, 3500);
};
scene.onTalk = (card) => {
  if (!card) { talk.style.display = 'none'; return; }
  const opt = (o: { id: string; label: string; kind: string; enabled: boolean; note?: string }): string =>
    `<button class="${o.kind === 'danger' ? 'bad' : ''}" data-opt="${esc(o.id)}" ${o.enabled ? '' : 'disabled'}>${esc(o.label)}${o.note ? `<small>${esc(o.note)}</small>` : ''}</button>`;
  const say = card.options.filter((o) => o.kind === 'say'), act = card.options.filter((o) => o.kind === 'act'), bad = card.options.filter((o) => o.kind === 'danger');
  talk.innerHTML = `<div class="head"><b>${esc(card.name)}</b><span class="close" id="closetalk" role="button" aria-label="Close">×</span></div>
    <div class="sub">${esc(card.subtitle)}</div>
    <div class="line">${esc(card.line).replace(/\n/g, '<br>')}</div>
    ${card.wanted ? '<div class="hint">They want nothing to do with you right now.</div>' : `<div class="opts">${say.map(opt).join('')}</div><div class="opts">${act.map(opt).join('')}</div><details><summary>Something darker…</summary><div class="opts">${bad.map(opt).join('')}</div></details><div class="hint">You act in ${esc(slider.value)}; use the year slider first to change a different time.</div>`}`;
  talk.style.display = 'block';
};
talk.addEventListener('click', (e) => {
  const t = e.target as HTMLElement;
  if (t.id === 'closetalk') { scene.closeTalk(); return; }
  const b = t.closest('button[data-opt]') as HTMLButtonElement | null;
  if (b) void scene.talkChoose(b.dataset.opt!);
});
if (location.search.includes('debug')) (window as unknown as { __scene: unknown }).__scene = scene;

// ---------- the notebook: what people have told you ----------
const jbtn = $('journalbtn'), jbox = $('journal');
const drawJournal = (entries: { aboutName: string; text: string; from: string; year: number }[]): void => {
  jbox.innerHTML = `<div class="head"><b>Notebook</b><span class="close" id="closejournal" role="button" aria-label="Close">×</span></div>` +
    (entries.length ? entries.map((e) => `<div class="entry"><b>${esc(e.aboutName)}</b> ${esc(e.text)}<small>told by ${esc(e.from)}, ${e.year}</small></div>`).join('') : '<div class="hint">Nothing yet. Get to know people and ask what they\'ve heard.</div>');
};
scene.onJournal = drawJournal;
drawJournal(scene.journal);
jbtn.addEventListener('click', () => { jbox.style.display = jbox.style.display === 'block' ? 'none' : 'block'; });
jbox.addEventListener('click', (e) => { if ((e.target as HTMLElement).id === 'closejournal') jbox.style.display = 'none'; });

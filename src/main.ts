import { loreOf } from './world/lore';
import { siteAt } from './world/map';
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
  $('town').textContent = `${info.name} · ${info.kind}`;
  $('mayor').textContent = [info.mayor ? `Mayor: ${info.mayor}` : '', info.population !== null ? `pop ${info.population}` : ''].filter(Boolean).join(' · ');
  renderChanges(info);
  if (!entered) return;
  const b = $('banner');
  b.querySelector('.name')!.textContent = info.name;
  b.querySelector('.sub')!.textContent = `${info.blurb} · Founded ${info.founded}${info.population !== null ? ` · Population ${info.population}` : ''}`;
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
  document.body.classList.toggle('talking', !!card);
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
if (location.search.includes('debug')) {
  (window as unknown as { __scene: unknown }).__scene = scene;
  (window as unknown as { __lore: unknown }).__lore = (m: { loreId: string }) => { const [cx, cy] = m.loreId.split(',').map(Number) as [number, number]; return loreOf(scene.worldSeed, siteAt(scene.worldSeed, cx, cy)); };
}

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

// ---------- the on-device dialogue AI (Qwen3-0.6B): pick the model file once, then it works offline ----------
const aibox = $('ai'), aibtn = $('aibtn');
const drawAi = (): void => {
  const st = scene.llm.state;
  aibtn.textContent = st === 'ready' ? '🧠 AI on' : st === 'loading' ? '🧠 loading…' : '🧠 AI';
  aibox.innerHTML = `<div class="head"><b>Dialogue AI</b><span class="close" id="closeai" role="button" aria-label="Close">×</span></div>
    <p>${esc(scene.llm.detail)}</p>
    ${st === 'ready' || st === 'loading' ? '' : `<p>Everything runs on this device, with no internet. Choose the model file <b>qwen3-0.6b-q4_k_m.gguf</b> (0.4 GB) once; it is saved here afterward.</p>
    <label class="btn" for="aifile">Choose model file</label><input id="aifile" type="file" accept=".gguf" style="display:none">`}
    ${scene.llm.stats.loadSecs ? `<p style="font-size:12px">Loaded in ${scene.llm.stats.loadSecs.toFixed(0)} s · last reply ${scene.llm.stats.lastSecs.toFixed(1)} s · ${scene.llm.stats.replies} used, ${scene.llm.stats.rejected} fell back to built-in lines, ${scene.llm.stats.timeouts} timeouts</p>` : ''}
    ${scene.llm.stats.benchSecs ? `<p style="font-size:12px">Speed test: ${scene.llm.stats.benchSecs.toFixed(1)} s for ${scene.llm.stats.benchTokens} words</p>` : ''}
    ${st === 'ready' ? '<p><button id="aioff" class="btn">Use built-in lines instead</button></p>' : ''}
    <pre style="font-size:10.5px;white-space:pre-wrap;color:#9aa3cc;margin:6px 0 0;max-height:150px;overflow:auto">${esc(scene.llm.diag.join('\n'))}</pre>
    ${st === 'slow' ? '<p>You can still play: people just use the built-in lines.</p>' : ''}`;
};
scene.llm.onChange = drawAi;
drawAi();
aibtn.addEventListener('click', () => { aibox.style.display = aibox.style.display === 'block' ? 'none' : 'block'; });
aibox.addEventListener('click', (e) => { const id = (e.target as HTMLElement).id; if (id === 'closeai') aibox.style.display = 'none'; if (id === 'aioff') scene.llm.disable(); });
aibox.addEventListener('change', (e) => {
  const f = (e.target as HTMLInputElement).files?.[0];
  if (!f) return;
  scene.llm.state = 'off';
  void scene.llm.load(f);
});

// First-run help card
try {
  if (!localStorage.getItem('timerpg.helped')) {
    $('help').style.display = 'block';
    $('helpok').addEventListener('click', () => { $('help').style.display = 'none'; try { localStorage.setItem('timerpg.helped', '1'); } catch { /* private mode */ } });
  }
} catch { /* storage unavailable: skip the card */ }

// ---------- maps, compass and discoveries ----------
const wmap = $('worldmap');
const drawMap = (): void => {
  const rows = scene.worldGrid();
  const open = scene.maps.filter((m) => !scene.found.includes(m.loreId));
  wmap.innerHTML = `<div class="head"><b>Map</b><span class="close" id="closemap" role="button" aria-label="Close">×</span></div>
    <div class="grid" style="grid-template-columns: repeat(${rows[0]!.length}, 1fr)">${rows.flat().map((c) =>
      `<div class="cell${c.here ? ' here' : ''}${c.target ? ' target' : ''}">${c.name ? esc(c.name) : '?'}${c.target ? '<small>✕ ' + esc(c.title ?? '') + '</small>' : c.kind ? `<small>${esc(c.kind)}</small>` : c.here ? '<small>you are here</small>' : ''}</div>`).join('')}</div>
    <div class="legend">${open.length ? open.map((m) => `<div>✕ <b>${esc(m.targetName)}</b>: ${esc(m.title)}. Told in ${esc(m.originName)}; it happened in ${m.year}.</div>`).join('') : 'No map yet. Old residents know local legends: get to know one well and ask.'}
    ${scene.found.length ? `<div style="margin-top:6px">${scene.found.length} legend${scene.found.length > 1 ? 's' : ''} uncovered (see the Notebook).</div>` : ''}</div>`;
};
scene.onMaps = () => { if (wmap.style.display === 'block') drawMap(); };
$('mapbtn').addEventListener('click', () => { if (wmap.style.display === 'block') { wmap.style.display = 'none'; } else { drawMap(); wmap.style.display = 'block'; } });
wmap.addEventListener('click', (e) => { if ((e.target as HTMLElement).id === 'closemap') wmap.style.display = 'none'; });
scene.onCompass = (t) => { const c = $('compass'); c.style.display = t ? 'block' : 'none'; if (t) c.textContent = '🧭 ' + t; };
scene.onDiscover = (title, text) => {
  const d = $('discover');
  d.innerHTML = `<b>📍 ${esc(title)}</b><div style="margin-top:6px">${esc(text)}</div><div class="hint" style="margin-top:6px;color:#9aa3cc;font-size:12px">Added to your notebook.</div><button id="discoverok">Close</button>`;
  d.style.display = 'block';
  $('discoverok').addEventListener('click', () => { d.style.display = 'none'; });
};

// ---------- the Gazette: what has happened in this town, up to the year on the slider ----------
const gz = $('gazette');
const drawGazette = (): void => {
  const items = scene.gazette();
  gz.innerHTML = `<div class="head"><b>📰 The Gazette</b><span class="close" id="closegz" role="button" aria-label="Close">×</span></div>` +
    (items.length ? items.map((i) => `<div class="item"><small>${i.year} · ${esc(i.category)}</small><b>${esc(i.headline)}</b>${esc(i.body)}</div>`).join('') : '<div class="hint">Nothing reported yet.</div>');
};
$('gazettebtn').addEventListener('click', () => { if (gz.style.display === 'block') gz.style.display = 'none'; else { drawGazette(); gz.style.display = 'block'; } });
gz.addEventListener('click', (e) => { if ((e.target as HTMLElement).id === 'closegz') gz.style.display = 'none'; });
slider.addEventListener('input', () => { if (gz.style.display === 'block') window.setTimeout(drawGazette, 400); });

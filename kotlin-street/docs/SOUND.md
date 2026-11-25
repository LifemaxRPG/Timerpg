# Sound

The owner (2026-11-25): "it's jarring how silent our city is. The only thing that makes sound is our footsteps, and I
still don't love those ... driving around with completely silent cars is weird." This is the overhaul: footsteps to choose
from, an engine for every car, a town that sounds of its place, hour, weather and era, all through one mixer.

Code: `app/src/main/kotlin/audio/`. Assets: `app/src/main/assets/audio/` (credits: `audio/CREDITS.md`). Asset tool:
`tools/make_sounds.py`. Tests: `app/src/test/kotlin/{SoundRules,Footsteps,EngineSound,Mixer,SoundRender}Test.kt`.

## Licences (strict)

Only sounds we may ship in the APK: CC0 first, CC-BY 3.0 with the attribution recorded. Every licence was checked on the
sound's own page before download (Freesound: the page's licence link; OpenGameArt: the "License(s)" box and the pack's
licence files). GPL, CC-BY-SA, NC and "personal use" sources (BBC, Sonniss without a redistribution grant) were skipped,
even when they sounded best (Gregor Quendel's city ambiences on OpenGameArt are CC-BY-SA). kenney.nl and the Freesound API
were blocked from the build machine; Kenney's CC0 packs mirrored on OpenGameArt and Freesound's public CC0 previews were
used instead. Everything made by our own code (engines, tyres, traffic, sirens, bells, rain, wind, crickets, footstep set
E) needs no credit. New sounds go through `make_sounds.py` and get a CREDITS.md row in the same commit.

## Footsteps

Settings → Sound → "Footsteps: A B C D E" switches the set live (kept in the "sound" preferences).

| Set | What | Variations |
|---|---|---|
| **A** (default) | Freesound CC0 field recordings: real shoes on real concrete, asphalt, lawn, gravel, a hollow deck, wooden floors, carpet, snow | 16 per surface, all 8 surfaces |
| B | swuing's studio foley (congusbongus's cut, CC-BY 3.0) and Ali_6868's gravel (CC0) | 9-10 per surface; no asphalt, carpet, snow (they fall back) |
| C | OpenGameArt CC0 game packs (Kenney, Fantozzi, TinyWorlds, rubberduck, Corsica_S's snow) | 2-16; thin on grass |
| D | the old MOSS-SoundEffect steps (the set the owner did not love) | 5 per surface; wood is a dull 100 Hz thud |
| E | made live by `StepSynth`: heel and toe, thud, sole click, road grit, blades, stones, the boards' ring, a room's reflections, snow's crunch and squeak | endless (never the same step twice) |

Why A is the default: it is the only set with every surface, the most variations (16), and the cleanest recordings
measured (step-to-floor 37-41 dB on the hard surfaces against 29-33 for B and C); D's wood centroid is ~100 Hz (a thud).
That is a measurement, not a listening judgement: the owner decides by ear (the comparison files below).

How a step is chosen (`Footsteps.kt`, pure): the surface underfoot (`Footsteps.surfaceName`: indoors the floor or carpet;
snow outdoors in winter except decks; the yard's lawn, gravel, deck or path; the pavement; and now the road's **asphalt**,
`OpenWalk.onCarriageway`: between the kerbs of the derived row's road); a variation never the same as the last two; +-1.5 dB
and +-5 % pitch jitter; the feet alternating left and right (pan +-0.1) with the leading foot heavier; running louder and a
touch higher. The cadence is the drawn legs' own footfalls (walking and running already differ). A set without a surface
falls back to the nearest (`Footsteps.fallbacks`). Steps are decoded ahead in the background when the set is chosen.

## Engines

Every car in `VehicleWorld` (the seven drivable models; parked ones only when pushed or rolling) has a voice in `CarAudio`;
the nearest four that make sound are heard. The game writes each car's state into its `Feed` every frame
(`SoundFeed.cars`): position, velocity, forward speed, **the physics' own rpm and gear** (Jolt's engine and automatic
gearbox already run: `VehicleState.RPM/GEAR`), the throttle (`Car.throttle`, new), slip, whether the driver wants it
running and whether it can (`engineOff`: a breakdown or an empty tank).

`EngineState` (pure) turns that into what is heard: OFF → CRANK (the starter turning it over at ~200 rpm, 0.65-1.4 s by
era) → RUN (the catch flares to 1.7x idle and settles) → STOPPING (runs down) → OFF. A dead engine cranks on the gas and
never catches, retrying a few times. The load (eased throttle), the overrun (off the gas at revs), a shift's dip (from the
physics' gear changing). `GearModel` is the gearbox for cars the physics does not run (the far traffic): rpm from road
speed through the gear and final drive, automatic up/down shifts.

**Made live (default), `EngineSynth`:** the crank turns; each cylinder fires at its point of the 720° cycle into its
exhaust bank (a cross-plane V8 fires 1-8-4-3-6-5-7-2 so each bank's pulses are uneven: the burble). Each pulse's size
follows the load, varies cycle to cycle (rough old engines more), a lumpy cam lopes at idle, a rough engine misses now
and then, the rev limiter cuts at the redline, the overrun pops occasionally. Each bank's pulses ring in its pipe (a
delay line fed back through a damping filter: the pipe's resonances) and come out through the muffler (two low-passes and
the body's boom). Over it: the intake's roar (throttle and revs), the valve train's tick, the starter's whirr. Electric:
the motor's whine and harmonics, the inverter's hum, nothing firing.

| Car | Engine | Character |
|---|---|---|
| Alderwick Six 1928 | vintage side-valve straight six, 480-3000 rpm | slow chuff, loose, tappets ticking, long crank |
| Fenmere Club Coupe 1938 | flathead V8, 520-3600 | soft, burbling, mellow |
| Northcairn Terrace 1956 | OHV V8 on a single exhaust, 580-4400 | deep hum, some burble |
| Rowanvale Sprint 1970 | big-block V8, dual exhaust, lumpy cam, 720-5600 | loud, lopey idle, crackles on the overrun |
| Tallmere 72 Pickup | truck V8, 600-4400 | heavier, lower |
| Rillhaven Torrel 1968 | twin-carb sports four, 820-6800 | rasping, eager |
| Rillhaven Torrel RS 1988 | twin-cam four, 850-7000 | smooth, high, intake-heavy |
| (traffic only) | modern four, electric | quiet and smooth; a whine |

**Recorded loops (Settings → Engines: "Recorded loops"), `LoopEngine`:** the common game approach for comparison: a CC0
idle recording per family (V8, old, four, car) and six CC0 load loops measured at their firing rates, played faster or
slower for the engine's speed and equal-power crossfaded by it. It cannot do the eras' differences or the crank and
is pitch-shifted far from where it was recorded at high revs; kept as an option for the owner's ears.

**Tyres (`TyreSynth`)**: a rolling roar rising and brightening with speed (gritty on gravel, soft on grass), and the
squeal: close partials wavering with band noise, from `CarAudio.slip`: sliding sideways faster than ~2 m/s, locking under
hard braking at speed, spinning the wheels pulling away hard in first; no squeal on grass, gravel or snow.

**Crashes and doors:** `VehicleWorld.onImpact` (the speed lost in a frame) plays a clank, a crash with glass or a big
smash by severity, from where the car is. Getting in and out: the door opens, then shuts (CC0 recordings).

**Doppler** for every car but the driven one (the listener rides in it): the radial speed relative to the listener's own
velocity scales every frequency of the synth (`doppler`), eased.

## Ambience

`SoundRules.at(hour, rain, cloud, mist, season, town, Place)` (pure) decides the levels; `SoundFeed.look` measures the
`Place` a few times a second: **green** (trees and bushes within 40 m, standing on a lawn), **urban** (landmarks and big
stores within 150 m), **crowd** (people drawn within 25 m, and where they gather), the metres to the **water** (the town's
river polygons, nearest point), indoors / in a car, the **police** out looking (LoopSession's searching officers), cars
moving near. The **year** is the town's.

| Layer | When, where | Source |
|---|---|---|
| City hum (city_day, city_morning, residential, night_town, highway_far) | by day downtown, residential streets, the night's far traffic; **only once the era has modern traffic** (none before ~1946, all by ~1966) | CC0 recordings |
| The night's hush | before the motor age (so 1920 is not dead silent) | made live (low rush) |
| Far traffic | cars of the year's fleet passing on streets around (22-120 m off), Doppler, darkened by distance and buildings; how many a minute by rush hours, day, urban, the era's car share (1925 about a fifth of now) | made live (`TrafficSource`: the engine synth) |
| Horses | before ~1945, by day, more downtown | CC0 recording |
| Birds | by day (suburban birdsong), parks (green²), the dawn chorus in gardens; fewer in winter, none in rain | CC0 recordings |
| Crickets | at night, warm seasons, gardens | made live |
| Wind, rain (light, heavy), thunder | from the sky | made live (thunder CC0) |
| Dogs | scattered 30-140 m off, more in green streets and at night | CC0 recordings |
| River / sea | from the nearest point of the water, by distance (within 400 m) | CC0 recordings |
| Crowd murmur | from where the people near gather, by how many | CC0 recording |
| Fountain | the plaza's fountain, positional | CC0 recording |
| Bells | the clock tower (else a church) strikes the hour (12-hour count, 2.6 s apart), 7:00-22:00 | made live (`BellSynth`: hum, prime, tierce, quint, nominal partials) |
| Siren | while the police search: bursts of 8-20 s from the nearest officer's direction, 250 m off; a bell before 1915, a motor siren's wail to 1965, then electronic wail and yelp | made live (`SirenSource`) |
| Shop door bell | entering a shop | CC0 recordings |
| Bike | the chain's purr pedalling, the freewheel's ticks coasting, gravel's crunch (tyre hiss stays off: the owner heard it as wind) | made live |

Indoors the ambience bus is low-passed to ~500 Hz and 45 % quieter; in a car about half that.

## Mixing and cost

One `Mixer` (pure Kotlin, `Mixer.kt`) renders 20 ms blocks into the existing low-latency float AudioTrack on its own
audio-priority thread (`SoundScape`). Four buses: ambience, effects (steps, bells, doors), vehicles, music; each with its
Settings volume (0-150 %) and a master. **Ducking:** while an NPC voice plays (Kokoro / Supertonic: `onSpeaking`) or a
conversation is open, ambience dips to 35 %, music 30 %, vehicles 60 %, effects 80 %, eased. **Voices:** one-shots at most
28 at a time; past that the lowest priority and quietest are dropped (bells and crashes outrank steps outrank dogs).
**Positional:** pan by the camera's heading (`OrbitCamera.yaw`), inverse-distance fade from each sound's reference
distance, a low-pass darkening with distance (and a little behind the listener). **Assets** decode on a background loader
thread (never the audio thread; a sound not decoded yet is skipped, then ready) into 16-bit PCM; a bed decodes the first
time it is wanted and gives its memory back after two minutes unheard (all twelve beds would be ~65 MB; a street at once
uses about 30-40 MB; a 30 s loop is 5.8 MB). Continuous sounds are otherwise made live.

**Measured (desktop JVM, not the S25):** a busy street (every bed, the far traffic at its three-car maximum, the driven
big-block V8 pulling at 4000 rpm, three more cars, a step every half second) mixes in **1.1-1.65 ms per 20 ms block,
5.5-8 % of one core** (three runs, the machine shared with other jobs) (`MixerTest.theMixIsCheap` prints it). The S25's Oryon cores should be faster per clock than this
server; it must be measured on the phone (Perfetto: the "soundscape" thread).

## Listening (the owner's comparison files)

Rendered by `tools/make_sounds.py`'s pipeline and `SoundRenderTest` (SOUND_OUT=dir SOUND_LOOPS=dir with the engine loops
as raw float), as MP3 in the job's scratchpad `sound/` folder: `footsteps_A..D.mp3` (each surface walking then running,
picked as the game picks; `footsteps_guide.txt` gives the times), `footsteps_E_synth.mp3`, `engines_made_live.mp3` and
`engines_recorded_loops.mp3` (each car through a drive: start, idle, a blip, pulling away through the gears, lifting off,
braking with a squeal, idle, off), `traffic_1925/1970/2025.mp3`, `sirens.mp3` (bell, mechanical, electronic), `bell_tower.mp3`.
They are desktop renders of the same code, not S25 recordings.

## Needs the S25

- The CPU cost of the mix on the phone, and its heat over a long drive (expected small: one thread, ~6-8 % of a desktop core).
- Latency of footsteps against the drawn feet (the low-latency track's buffer; the steps fire on the drawn footfall).
- Levels on the phone's speakers and headphones: the beds and the engines were balanced on desktop renders by numbers.
- That `MediaExtractor` decodes every new Ogg (all are Vorbis 48 kHz, as before).
- Jolt's reported rpm while driving (the sound follows it; if it jumps, `EngineState` eases it).

## Next

- Moving NPC traffic does not exist yet: when it does, feed its cars to `CarAudio` (the far traffic then thins out).
- Music: the mixer's music bus and moods are wired; no tracks ship yet.
- Interiors: a room tone per kind (a shop's hum, a diner's clatter) and reverb by room size.
- A horn (the driving HUD has no button yet; CC0 horns exist).

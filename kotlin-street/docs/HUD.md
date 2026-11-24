# The HUD (2026-11-24)

The owner found the screen cluttered ("figure out our menus so that we don't have so many buttons on our screen") and
asked for a better place for a more detailed minimap. The HUD now follows top open-world games: a few things always on
screen, contextual buttons only when they apply, everything else behind one menu button.

## Where things are

| Place | What | Why |
|---|---|---|
| **Top left** | The minimap (150 dp, round) and, under it, the street and the district/town | The left thumb lives bottom left on the floating stick, so a bottom-left map would be under the hand and would take the stick's touches. Top left stays visible while walking and driving, and is where the eye goes first. |
| **Top right** | The status cluster: weather word, time, date, money (red with stolen goods), then the menu button (a red dot when an update waits); the owner's 📷 / 🔖 buttons left of it when they are on | One small cluster instead of a time bar, a wallet row and chips. |
| Under it | Small tags: the player's condition (sprain, hungry), items carried; the mic as a small dot; short notices (car fuel, rent due, job news, a mic problem) | Small, read at a glance, red when urgent. |
| **Top centre** | The building in front / the turn, while it applies | Contextual. |
| **Bottom left** | The stick (floating under the thumb), the bicycle button beside it on foot | Unchanged. |
| **Bottom right** | Contextual actions only when they apply (Talk to, the shop, Go inside, the stop, the station's maps, Enter a car); in a car the large driving controls (reset, speed, Exit, Hand, Brake, Gas) | Under the right thumb, as a pad's buttons are. |

## The menu (one button, top right; the back key closes it)

- **Map**: open the full map; minimap north up or turning with the camera; zoom; zoom out with speed when driving; clear the waypoint.
- **You**: money, what is carried, condition, room, work, car.
- **Camera**: put the camera behind me (also a double tap on the world), open world or street view, what a drag does, the bike camera.
- **Settings**: the dialogue AI's state and the former AI panel (model, voices, mic, renderer, quality, screenshots to Claude, crash reports).
- **Debug**: the time-of-day slider, the season, the weather (Auto gives it back to the calendar), a test car beside the player, the loop's debug panel.

Opening the menu stops the stick (as the AI panel did); the world keeps running behind it (not a pause yet).

## Code

- `shared/src/main/kotlin/hud/GameHud.kt`: the platform-free pieces and corners (`HudMapCorner`, `HudTopRight`,
  `HudLocation`, `HudStatus`, `HudMenuButton`, `HudTags`, `HudNote`, `HudMic`, `HudMenu`, `HudButton`, `HudRow`).
- `app/src/main/kotlin/GameActivity.kt`: lays them out (the "HUD" block near the end of `GameScreen`) and fills the menu;
  `MapHud.kt` the minimap's corner. The menu-open flag is still `aiPanel` (the playtest log's and the back key's).
- Desktop check: `MapsTest` renders `hud_walk.png`, `hud_drive.png` and `hud_menu.png` (`desktop/src/crossroadsTest/kotlin/HudMock.kt`:
  the same shared pieces and the real minimap, with stand-ins drawn where the app draws the stick, the actions and the
  pedals) at the S25's 2244 x 1038 px, 2.81 px a dp. A layout check from the desktop, not an S25 capture.

## Next

- A real pause (the sim's clock stopped) while the menu is open, if the owner wants it.
- The stand-ins in the mock are hand-placed copies of the app's; a change to the stick or the pedals' places needs the mock changed too.

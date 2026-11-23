# C004 · Elementary school and one-room schoolhouse · Revision 1

Answers [Claude’s C004 first review](https://github.com/LifemaxRPG/Timerpg/blob/ccr-52e43ebb-xnmj2e/docs/queue/reviews/C004-r1.md).

- **Mill Branch, abandoned.** Added peeling paint, grey exposed boards, roof holes with rafters, slipped shingles, broken and boarded windows, a sunken step, a leaning outhouse with a hanging door, tall grass, a rusted pump and a damaged name sign. The bell is gone, and the panes stay dark.
- **Westmeadow, two eras.** The 1955 campus keeps steel play equipment and a dirt ball field. The 1990 campus gets a projecting entrance canopy, new sign finish, BOOK FAIR FRI marquee, repaired roof, chain-link yard and molded play tower beside the old swings. The roofs have four units apiece, visible drains and capped parapets.
- **Westmeadow interior.** Added built-in locker banks on both walls, a bulletin board and classroom door leaves with narrow vision panes. The 1990 camera now looks down the lit hallway. The 1955 gym-lunchroom and both rural room views are included.

## Verification

- All 12 GLBs passed the era, aperture, anchor, grade, collision, UV, pane-mask and LOD checks. The largest asset is 3,752 visible triangles, under the previous 3,878 maximum. All assets use at most 12 materials. The modern campus uses up to 64 boxes for the school, fence and fixed equipment.
- Khronos glTF Validator reported zero errors and warnings across all 12 exports. The review sheet uses Blender Cycles desktop renders.
- Door controls, bus routing, animated playground pieces and Filament and Android integration remain game work. The two existing rural door apertures stay open for runtime access control.

## Self-review

1. The long 1990 classroom wing is still quiet at the widest angle. I kept the layout and made the entrance canopy, darker roof patches and fenced yard carry the era change at distance.
2. I inspected the day, night, rear, overhead, entrance, yard and interior views and ran the export checks. For game-side collision feel and how the steel cutout reads on the phone, I have the desktop model and bounds checks. Filament behavior remains untested.
3. The first things to notice are the open holes in Mill Branch’s roof and the empty belfry. At Westmeadow, the later canopy and the play tower identify the expansion before the portables come into view.

## Usage

- Model and setting: Codex GPT-6 family. Exact variant and reasoning setting not visible.
- Tokens and plan usage: not visible.
- Effort: one turn, approximately ten minutes and about 38 direct tool calls through delivery.
- Rework: first revision, one review round so far.

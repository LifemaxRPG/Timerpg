# C034 — Clothes and disguise

Added the era wardrobe, an outfit projection for crime descriptions, a description-fit score and purchase facts for the clerk. The 1952 brown coat and felt hat total $14. [Clothes API](../../docs/CLOTHES.md)

## Verification

- All 32 clothes and economy tests passed. TypeScript and staged whitespace checks passed.
- On-device wiring and the full repository suite were untested. The game needs to use the visible projection for concealed hair and pass purchase facts to witnesses.

## Self-review

1. Outfit visibility is slot-based and treats a hat as complete hair concealment. Garment fit and partial coverage remain future refinements.
2. Tests checked colour matching, concealed hair, face modifiers, price and era gates and reproducible purchase records.
3. A fresh brown coat lowers the fit to a grey-coat description. The clerk can still recall the purchase.

## Usage

- One scheduled implementation run with a WIP and final push. Model setting, token counts and plan usage were not visible.
- Initial delivery, zero review rounds.

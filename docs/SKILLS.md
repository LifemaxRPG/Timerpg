# Skills and trades

Import from `src/skills`. The game saves levels as numbers from 0 to 1. Prices are nominal cents.

- `skills` lists the authored skills. `skillAvailable("hot_wiring", 1911)` returns false. Driving starts in 1905 and hot-wiring in 1912 under the game's era rules.
- `trainingQuote("mechanics", "correspondence", 1952)` returns `{"costCents":45,"gain":0.09}` for one session. Methods include shift, correspondence, night school, driving school, library book and tutor. The last four IDs are `night_school`, `driving_school`, `library_book` and `tutor`. Quotes are null when unavailable. The caller verifies access to a school, book or teacher and charges the quote.
- `practice("mechanics", 0, "correspondence", 1952, 3)` reaches approximately 0.246. Each session closes a fraction of the remaining gap to level 1.
- `practiceShift("garage_sweeper", 0.246, 1952)` returns the associated skill and a level of about 0.280. Five shifts after three course sessions clear the mechanic helper threshold. Advance only after a completed eligible shift.
- `jobRequirements.mechanic_helper` is `{"skill":"mechanics","minimum":0.3}`. Roles absent from the table have no skill requirement in this module.
- `attempt("carpentry", 0.5, 0.4, "town", "bench-repair-2")` returns an outcome and an effect. Outcomes are success, failure and botch. Effects are null, noise or evidence. Use a stable action key per attempt so replay yields the same result. Check the skill's era before invoking this year-independent roll.
- `safeProfile(1980)` returns `{"kind":"time_lock","difficulty":0.83}`, an abstract game profile. The earlier profile is combination at difficulty 0.55.

The caller owns progression saves, job gates, payment and action consequences.

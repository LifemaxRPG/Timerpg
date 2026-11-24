# World calendar

Import from `src/calendar`. Dates are JSON objects with numeric `year`, `month` and `day`. Years 1–9999 use twelve months with Gregorian month lengths and leap rules. Weekdays retain familiar names, numbered 0 for Sunday through 6 for Saturday. Weeks start on Sunday, and week 1 contains the first day of the year. The zero-based absolute calendar starts on a Monday.

Let `start = {year:1951, month:9, day:14}`.

| Call | Result |
|---|---|
| `dateOf(0, start)` | Friday the 14th of Harvestmonth, autumn. Includes the numeric date and its week of year. |
| `dayNumberOf(start)` | Absolute day of the date. `dateOf(dayNumberOf(start))` recovers the same date. |
| `holidaysOn(dateOf(1, start), 1951)` | Lantern Festival closes banks and the post office and opens the lantern fair. |
| `isPayday(start)` | `true` |
| `isMarketDay(dateOf(1, start))` | `true` |
| `schoolTerm(start)` | `"autumn"` |
| `daylight(start)` | `{"sunrise":6.75,"sunset":18}`, in decimal local hours |
| `toShopDate(start)` | `{"month":9,"day":14}`, the shape accepted by the economy's shop-hours API |

Signed offsets work with an explicit start date as long as the resolved date remains in range. Invalid dates return null, false or an empty holiday list as appropriate. Pass calendar-relative offsets to `dateOf` with a start date, or absolute days without one.

Holiday rules have inclusive era bounds and can be fixed or based on an ordinal weekday. Fridays are weekly paydays before 1970. Later they alternate on a continuous absolute-week cycle across year boundaries. Markets run Saturday, with Wednesday added from 1960. School terms are January–May and September–December 19. Daylight is an authored temperate profile.

The caller applies world holiday closures from `holidaysOn`. The existing economy hours still include legacy fixed-date closures and Friday hours. Use `toShopDate` when those legacy date overrides are desired. Wiring exclusively fictional holiday closures and aligning late hours to alternating paydays are game integration work.

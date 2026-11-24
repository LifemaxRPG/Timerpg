# Regional weather

Import from `src/weather`. Pass the absolute day from `dayNumberOf` in `src/calendar`, a local hour in [0, 24), and a stable region seed. All results are JSON-safe.

| Call | Example result |
|---|---|
| `temperatureRange("temperate_coast", 11)` | `{"minC":0,"maxC":18}` |
| `weatherAt("temperate_coast", 712899, 18, "2")` | `{"sky":"fog","temperatureC":14,"windKph":4,"visibilityM":75}` on 8 Mistmonth 1952 |
| `weatherEffects(weather)` for that fog | `{"faceRecognitionM":6,"walkingSpeedMultiplier":0.96,"streetActivityMultiplier":0.79,"carGripMultiplier":0.9}` |

Monthly mean temperatures in °C, Frostmonth through Longnight, with the maximum deviation on either side:

| Climate | Monthly means | Deviation |
|---|---|---:|
| Temperate coast | 5, 6, 8, 11, 15, 18, 21, 21, 18, 14, 9, 6 | 9 |
| Inland plains | −5, −2, 4, 11, 17, 23, 27, 25, 19, 12, 4, −3 | 12 |
| Mountain | −9, −7, −3, 2, 8, 13, 16, 15, 10, 4, −3, −8 | 11 |
| Warm south | 14, 16, 19, 23, 26, 29, 31, 31, 28, 24, 19, 15 | 8 |

Fronts have 18–30-hour segments with keyed sky and wind. Temperature adds a day-night cycle to front variation. Snow is gated to cold winter and shoulder months. A local evening or morning fog layer can override dry skies, especially in autumn. Storm seasons are autumn on the coast, spring on the plains, and summer in the mountains and warm south. Invalid dates, hours, climates and months return null where applicable.

Apply face recognition distance alongside line of sight and lighting. Use the movement, crowd, and grip multipliers on the caller's own baseline values. This module does not locate rivers or drive the renderer.

# Life on the road

The functions in `src/road` use plain JSON. Roads have a stable ID, endpoint labels, length in miles, a road kind and terrain. Set `remote` for stretches with fewer services. Mile positions are measured from `from`; to represent trips in both directions, the caller can interpret markers in its own route coordinates.

- `roadsideAlong(road, year, seed)` returns stable stop IDs, kinds and mile positions. Stops are spaced farther apart on remote stretches. Motels enter the pool in 1940 and expressway rest areas in 1956. A stop slot can change use as the era changes. Availability is a generation rule and does not imply that a particular kind exists on every road.
- `encountersOn(road, year, hour, weather, seed, news?)` returns ordered encounter facts. Pass local decimal hours and the weather module's weather record. For daily variation pass a stable seed incorporating the day. Rolls are shared across four-hour windows. Sunlit and evening hours allow hitchhikers, with a lower chance from 1980. Fog banks need fog weather. Optional escape notices carry the road ID and last-seen coat. A matching hitchhiker references the public notice by ID, leaving identity unresolved.
- `driveTime(road, speedLimitByEra, weather)` returns moving hours, using the caller's era-specific speed limits in mph. Hills, reduced grip and fog increase the estimate. Invalid length or speed returns null.
- `driverStops(needs, hours)` projects when the current hunger or fatigue crosses 0.7 with the needs module's idle rates. An already-crossed threshold returns hour zero. Recompute after a break to reflect the driver's updated needs.

The caller places assets, schedules break durations, handles encounters and plays the news on the radio. The module does not change needs, spawn NPCs or enforce speed limits.

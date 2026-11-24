# Transit fares and service

The `src/transit` API takes and returns plain JSON. Distance is in kilometres, fares are nominal integer cents, and duration is in whole minutes.

| Call | Result |
|---|---|
| `fare("streetcar", 1925, 5)` | `5`, within the authored target of 4–6¢ |
| `fare("railway", 1925, 40, "third")` | `185` |
| `classesOf(1925)` | `["third","second","first"]` |
| `departures({id:"main",mode:"streetcar"}, 1925, 1, 23.5, "town")` | `[1420]`, the 23:40 last car |
| `departures({id:"night",mode:"railway"}, 1925, 1, 22, "town")` | `[1335]`, the 22:15 train |
| `travelTime("railway", 1925, 40)` | `59`, including modeled stops |

Modes and inclusive availability are horsecar (1880–1915), streetcar (1888–2030), motor bus (1905–1975), taxi (1907–2030), interurban (1895–1965), railway (1880–2030), city bus (1925–2030), and intercity coach (1930–2030). These are authored eligibility windows. The game supplies routes and decides whether service exists in a town.

Local buses and streetcars charge flat fares. Taxi fares include a flag drop plus distance. Interurban, railway and coach fares include base and distance components. Rail defaults to the cheapest class, with third class ending after 1955. An unavailable mode, class, or invalid distance yields `null`.

Weekday 0 is Sunday and 6 is Saturday. Pass a decimal local hour between 0 inclusive and 24 exclusive. Departures include the requested moment and stop at the end of that local day. Query tomorrow separately after the last service. The seed and route ID phase the scheduled bands. Weekend service uses midday frequency during the usual peaks. Streetcar and city bus routes have 40-minute overnight headways from 1975. Specify `trainMinutes` on a rail route to replace its fixed default trains. Taxis model an on-demand pickup at the next whole minute. Dispatch, travel animation and payment are the caller's responsibility.

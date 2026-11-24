# Car ownership

Import the JSON-safe API from `src/cars`. Prices are nominal cents, mileage is in miles, temperature is in Celsius and fuel economy is in US miles per gallon. Use stable seeds for each lot and owned car.

| Call | Example |
|---|---|
| `carsForSale(1952, "highway")` | Includes a 1946 sedan at 45000 cents. Each listing returns a car and asking price. |
| `papersFor(1952)` | Registration 1200 cents, licence 500, insurance 4200 and optional. |
| `policeStop({registration:true,licence:true,insurance:false}, 1957)` | Returns insurance missing, a fine and no impound. Two or more missing requirements trigger impound. |
| `runningCosts("sedan", 1952)` | Returns 19 mpg, the economy gasoline quote, cents per mile, and prices for oil, one tyre and a service. |
| `drive(car, 40, {year:1952, temperatureC:36, seed:"road"})` | Returns the updated car, distance actually driven and a nullable breakdown. |
| `repairCost("overheating", 1952)` | `{"repairCents":950,"roadside":false,"towRequired":true}` |
| `serviceCar(car)` | Clears a repaired breakdown and service mileage, and restores condition to at least 0.85. |

Paper requirements are authored game rules. Registration starts in 1908, licensing in 1910, optional insurance in 1925, and required insurance in 1957. The caller handles document expiry, payment, stops and towing.

Driving checks each whole odometer mile reached with a keyed roll. Wear, age, time since service and extreme temperature increase failure risk. A broken car cannot advance until serviced. Process at most 10,000 miles per call. Flat tyres and dead batteries can be handled at the roadside in this model. Call `serviceCar` after paying and completing service or repairs. The game handles fuel inventory, travel interruptions and garage locations.

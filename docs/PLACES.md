# Food and lodging places

Import from `src/places`. Keep the seed stable per venue. All prices are nominal cents.

- `menuOf("diner", 1952, "junction")` includes 10¢ coffee, a 65¢ blue-plate special and a 20¢ apple pie slice. The calibration ranges are 8–12¢, 55–75¢ and 16–24¢ respectively. Each row has an ID, label, price and C026 meal ID. Pass the meal ID to `eat` after the transaction. The menu uses catalogue prices where the dish matches, and 1952 anchors for house dishes.
- `lodgingOffers("station_hotel", 1952, "station")` returns a room class at 250 cents per night, with a washbasin and shared bath. Offers expose room count, billing period, billing rate, deposit and included meals. The nightly figure for boarding is a rounded comparison. Charge the weekly rate.
- `placeHours("automat", 1952, 2, "central")` returns `[{"open":6,"close":23}]`. Pass local weekday 0–6, Sunday first, and optionally the economy's shop date. Diner, lunch counter, automat, drive-in, bar, tavern and speakeasy use the economy's diner schedule as the base. Bakery and cafeteria use its general store schedule. Opening is inclusive and closing is exclusive. Late drinking venues close at midnight.

Food venue IDs are `diner`, `lunch_counter`, `automat`, `drive_in`, `cafeteria`, `bakery`, `bar`, `tavern`, and `speakeasy`. Automat service runs 1902–1991, drive-in service starts in 1921, and speakeasies replace legal bars and taverns during the annual 1920–1933 Prohibition window. Lodging IDs are `grand_hotel`, `commercial_hotel`, `station_hotel`, `flophouse`, `motel`, and `boarding_house`. The caller tracks room occupancy and determines whether a venue exists in town.

# Letters, telegrams and telephones

Import from `src/comms`. All amounts are nominal cents. Places contain an ID, town and continent IDs, and projected `xKm` / `yKm` coordinates on one shared plane. The caller handles geography and uses a consistent game day count.

- `collectionHours(1925)` gives `[9,16]` in local decimal hours. Collections are daily.
- `sendLetter(home, overseas, 1925, 10)` for points 4,000 km apart returns `{"costCents":8,"collectedDay":10,"deliveryDay":19,"transport":"ship"}`. The optional fifth argument accepts `{grams:56,service:"air",hour:14}`. Default mail is 28 grams, surface, submitted at 09:00. Submitting after the final collection adds a day. Air mail starts in 1918 domestically and 1939 overseas. Unsupported air service returns null.
- `sendTelegram(home, overseas, 1925, 10, {senderId:"player",recipientId:"professor",text:"One two three four five six seven eight nine ten"})` returns ten words, 75 cents and delivery on day 10. The result includes an office record with the sender, recipient, message, origin office, destination and sent day. Words are whitespace-delimited. Telegrams run through 2005.
- `phoneShares(1925)` returns shop and booth adoption shares and home shares by income bracket. The game chooses which venues and homes actually have a phone.
- `call({place:home,hasPhone:true,partyLine:true}, {place:neighbour,hasPhone:true}, 1925, 12)` within one town returns `{"costCents":5,"connects":true,"operator":true,"local":true,"eavesdropChance":0.25}`. Operator-assisted calls connect from 07:00 until 22:00. Local calls require an operator before 1940 and long-distance calls before 1970. Intercontinental calls start in 1927. Installed party lines affect calls from 1895 through 1985. Failed calls cost zero and carry zero eavesdropping probability. Prices are per call.

These are era defaults. The game handles route geography, delivery queues, office records, payments and whether a listener actually overhears a call.

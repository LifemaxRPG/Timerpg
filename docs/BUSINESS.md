# Small businesses

Import from `src/business`. Inputs and results are plain JSON, with nominal integer cents and absolute days from `dayNumberOf` in `src/calendar`. Use a stable town seed.

| Call | Example |
|---|---|
| `businessesForSale([{id:"main",kind:"five_and_dime",name:"Main Street Five-and-Dime"}], 1952, "town")` | One offer at 240000 cents with stock, or 6000 cents per 30 days on lease. Includes stock, fixtures, goodwill and four recent weekly takings estimates. |
| `openBusiness(offer, "lease", 50000, "town")` | Creates a saveable shop after acquisition with a $500 drawer, the listed stock and no employees. |
| `runDay(business, day, "town", {streetActivity:0.7, events:[{kind:"breakage",cents:3500}]})` | Returns the next shop plus sales, wholesale cost of sales, delivery spending, wages, rent, bank charges, losses, profit and creditor actions. |
| `valuation(business, 1952)` | Returns a going-concern quote in the current books' nominal cents. Fixtures, wholesale inventory and 90 days of average positive daily earnings contribute, less accrued debt. Drawer cash is excluded. |

The caller handles acquisition payments and supplies initial drawer cash after closing. On lease, acquire the opening stock separately. Optionally supply a daily bank charge as the fifth argument. Store staff as `{job:"shop_clerk",hours:8}` and set an optional same-day `{open:9,close:17}` schedule to shorten standard economy hours.

Run one day at a time and save the returned business. Repeats and gaps return null. The caller provides weather or other footfall effects as a multiplier and can pass `closed:true`. The calendar closes shops on its fictional retail holidays. Events apply before sales. Stock theft takes an item ID and quantity, cash theft takes cents up to the drawer balance, and breakage takes a repair cost in cents.

Profit includes wholesale cost of goods sold and stock theft at replacement cost. Delivery purchases affect the drawer and inventory directly. After collecting sales, cash pays rent, the bank, and operating arrears in that order. The shop skips deliveries it cannot afford. Lease rent accrues in 30 daily installments. Unpaid balances give warnings, with landlord lockout at seven days and bank repossession at fourteen. These are decisions for the caller to apply to the world. Weekly takings vary with traffic, stock and staffing.

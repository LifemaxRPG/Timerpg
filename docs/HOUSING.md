# Home ownership

Use `src/housing`. All money is in nominal cents. A home supplies ID, street, bedrooms, floor and lot square metres, construction year, and condition and neighbourhood scores from 0 to 1. The game provides the scores.

- `valuation(home, 1952)` returns 780000 for an 80 m², two-bedroom bungalow on a 400 m² lot with condition 1 and neighbourhood 0.5. The era index scales the quote. Homes cannot be sold before they are built.
- `housesForSale([home], 1952, "town")` deterministically returns zero or one listing with `home`, `askingCents` and `daysOnMarket`. The caller tracks purchases and listing changes.
- `mortgageOffer(home,{employed:true,monthlyIncomeCents:25000,reputation:0.7,availableCashCents:156000},1952,0)` approves a 20% down payment and 240 installments at 4205 cents with a 5.25% annual rate. The home serves as collateral. The payment cannot exceed 30% of monthly income.
- `payMortgage(mortgage,4205)` credits one installment through the bank ledger. Save it with the corresponding wallet change. The internal ledger uses the bank's existing secured loan shape. Use `mortgageStatus` for housing actions.
- `mortgageStatus(mortgage,120)` flags foreclosure and the home ID on a day-zero mortgage without payments. The first installment is due on day 30; collection starts 30 days overdue, default at 60 and foreclosure at 90. The caller applies foreclosure to ownership.
- `propertyTax(home,{year:1952,month:9,day:15})` returns annual and due amounts of 9600 cents. On all other dates the due amount is zero. Record tax payment separately so retries do not double-bill.
- `upkeep(home,1952)` quotes an annual maintenance reserve and a condition-based repair amount. Age increases the reserve. A fully repaired home has a zero repair quote.

Loan installments use fixed 30-day periods, consistent with the bank ledger. The game owns property transfers, work orders, tax payments and foreclosure enforcement.

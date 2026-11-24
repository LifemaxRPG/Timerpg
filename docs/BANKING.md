# Banking

Import from `src/banking`. All records are JSON, amounts are integer nominal cents, and rates are annual basis points.

- `accountTerms("savings", 1952)` returns a 500-cent minimum and a 200-basis-point rate, with cheques disabled.
- `openAccount("savings", 1952, 0, 500)` starts a passbook at $5. `deposit(account, 250)` and `withdraw(account, 100)` post at the account's current day. Invalid or unfunded operations return null.
- `accrueInterest(account, 365, 1952)` posts 10 cents on that initial balance. Advance to the transaction day before moving money. Accrual uses the supplied era and a 365-day basis, carries fractions, and compounds when interest is posted. Split intervals at year boundaries when rates change. Checking supports cheques from 1890. The caller implements cheque clearing.
- `loanOffer({monthlyIncomeCents:12000, employed:true, collateralCents:45000, reputation:0.7, cosigner:true}, "car", 40000, 1952)` approves a 24-month fixed loan at 6.5%. Payments include rounded-up amortization. A secured loan needs collateral covering the principal, and car loans before 1965 require a co-signer. Payments cannot exceed 30% of monthly income.
- `openLoan(approvedOffer, 0)` initializes a loan. `repay(loan, approvedOffer.monthlyCents)` credits one installment against the oldest balance. An overpayment returns null.
- `loanStatus(loan, 90)` on an unpaid day-zero car loan returns default and identifies the car as collateral to seize. Installments are due every 30 days from origination. Collection flags after 30 days overdue, default after 60. The caller applies defaults and handles collection, seizures and any credit reporting.
- `bankHours(1952, 5, "town")` returns `[{"open":9,"close":15}]`. Pass weekday 0–6, Sunday first. An optional calendar date enables fictional bank closures. Closing is exclusive.
- `cashOnHand("state", 1952, 5, 10, "town")` returns a drawer within 5% of $3,000, a vault estimate and game security flags. Specify an optional date matching year and weekday for actual calendar paydays and pre-holiday cash. Without a date Friday is the fallback payday. Drawer cash is zero outside opening hours. The snapshot supplies starting cash estimates and does not track events that change balances.

Save returned account or loan state with the corresponding wallet update. Bank rates, underwriting, security and enforcement are authored game rules.

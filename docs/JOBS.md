# Jobs

The `src/jobs` API uses plain JSON. Pay is in nominal cents. The game supplies the calendar year, whole nonnegative game day, town seed, place IDs and applicant reputation.

- `openings([{id:"hw",kind:"hardware"}], 1952, 0, "0")` offers a Stock boy position at 60¢ per hour, starting at 08:00 for six hours. Listings are keyed by outlet, role, year and seven-day block. The caller tracks filled openings. Hours describe the planned shift, with a 23:00 night shift continuing after midnight.
- `apply(opening, {age:23, references:[], reputation:0.6, hasPapers:false})` returns `{status:"trial", reason:"probation", job:...}` for that position. A good reference earns immediate hiring at this reputation. Applicants below the minimum age, missing required papers, or below 0.25 reputation are refused. Paperwork starts in 1987. Poor references or reputation below 0.45 lead to probation if the applicant otherwise qualifies.
- `shift(job, {day:1, outcome:"late"})` returns `{job:..., event:"warning"}` after the first strike. A second strike dismisses the worker with a poor reference. Ten consecutive on-time shifts clear prior strikes. Being caught stealing dismisses immediately. Save the returned job after each assigned shift. Submit at most one outcome per job per calendar day. Duplicate or older outcomes are ignored.

A trial can turn into a regular job on the seventh calendar day from the first shift, after five consecutive on-time shifts. A regular worker earns one 10% raise and a good reference after 15 consecutive on-time shifts spanning at least 15 calendar days from the first shift. The game handles shift scheduling, attendance evidence, payouts, reference provenance and save persistence.

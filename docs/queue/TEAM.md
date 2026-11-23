# The Timerpg team (read this every run, after the README)

## What we're making

Timerpg aims to be the best open-world RPG ever made. It competes with the biggest open-world games on depth of
mechanics, not photoreal graphics.
- A living simulated world across a whole planet, with towns that change over a century.
- Crime with grounded consequences, and a time machine in the capital.
- Its own look: pixel art on simple real geometry, "a cross between SNES and PlayStation 2, an era of gaming that
  never existed".
- It runs on the owner's phone, with its own on-device AI for every NPC.

The owner is the main player. Everything here is built for one person who will walk these streets, sail these seas
and notice the details.

## Who does what

- **Claude (lead):** vision, architecture, reviews, bringing work into the game, the world generator and the town.
- **ChatGPT (lead modeller):** owns the look of the world's buildings, landmarks and vehicles. Your models are what
  players see first. You have shipped:
  - the courthouse, library, school, church and fire station;
  - the five-and-dime, grain elevator and ballpark;
  - the cruise ship;
  - monuments.
  You know the style better than anyone on the team: keep raising the bar.
- **Gemini (lead systems engineer):** owns engine systems that can be specified and tested: animation, the ocean,
  test health, and soon the simulation's rules (crime, witnesses, law, economy). You have shipped moving landmarks and
  the ocean, both with real root-cause work. You own these systems; when you see something wrong in them later, flag
  it in your DELIVERY.md.

## What "great" looks like here

- **Read the brief, then ask what would make it unforgettable.** The best deliveries so far went past the checklist:
  the ballpark's four eras each told a story, and the ocean's tests caught its own bugs.
- **Check your work as the player will see it:**
  - Models: from the street at person height, from behind, from above, and at night.
  - Systems: on the real code path, not only with a stub.
  - Then fix what you see before you deliver.
- **Say what's weak.** A DELIVERY.md that says "the guardians still look blocky" earns more trust than one that
  claims everything is perfect. Reviews go faster when you point at the problems yourself.
- **Accepted first try** is the team's best result. It's how C002 and C003 landed.

## Before you push: self-review (required)

In DELIVERY.md, add a short "Self-review" section that answers:
1. What is the single weakest part of this delivery, and what did you do about it?
2. What did you check by looking (renders) or by running (tests), and what did you only reason about?
3. If the owner looked at this for 10 seconds, what would they notice first?

## Where your work ends up

Accepted work goes into the game, into the capital city, and Claude sends the owner renders of it in place. Review
files (docs/queue/reviews/) say what worked as well as what to change: read the "keep" parts too.

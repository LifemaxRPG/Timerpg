# Ideas backlog (the owner's ideas, kept so none are lost)

Claude turns these into design docs when their pillar comes up (see CLAUDE.md, "Direction").

## Crime and the law (pillar 3)
- Grounded crime: anything a person could do in real life, with real consequences (2026-11-23).
- Witnesses remember what they saw (how clearly, what of you); they decide by traits and relationships whether to
  report, keep quiet or tell someone; gossip spreads through real relationships and distorts; whoever reports it brings
  the police in; each jurisdiction keeps its own case file; fleeing to another continent to hide out works unless word
  travels (bulletins, extradition, newspapers by era); ports and papers matter by era.
- Forensics by era: fingerprint cards (1920s+) matched by hand within a jurisdiction; automated fingerprint databases
  (1970s-80s) link old cards across jurisdictions (cold cases close); DNA (late 1980s+) from old evidence boxes;
  cameras (2000s) and phones (2020s). Gloves, wiping down and avoiding witnesses matter.
- Time-travel twist: prints left in 1955 by someone born later stay unmatchable for decades, until the player's later
  self is printed; detectives can't explain how the suspect was at a scene before they were born.
- Outcomes: arrest, trial, fines, bail, jail (time skipped or played), a record that follows you, lost reputation.

## Prison (pillar 3)
- Prison as a playable place, inspired by the escape-from-prison genre (2026-11-23): you're not stuck if caught.
  - Daily routine by the prison's schedule (roll call, meals, work duty, yard, lights out); our schedule simulation runs
    it.
  - Guards with patrols, shifts, memories and their own traits; some can be bribed, some can't.
  - Inmates are full NPCs with memories: friends, gangs, favours, trades. Informants can snitch (the gossip system again).
  - Contraband and crafting from everyday items (a spoon, a bedsheet, soap). Cell searches.
  - Escape routes: digging, vents, disguises (a guard's uniform), the laundry truck, a riot, an inside job.
  - Legit ways out too: serve the sentence, good behaviour and parole, a lawyer and an appeal, bail.
  - Escaping has consequences: a manhunt, a longer sentence if recaptured.
  - Prisons change by era (a 1940s county jail vs a 2020s state prison with cameras).

## The professor and the time machine (pillar 5)
- The time machine is always in the capital, kept by a professor; reaching the capital is an early goal.

## Trials with a jury (the owner's idea, 2026-11-23)

If the player is caught and charged, the case goes to court: a judge, a prosecutor, a defence lawyer and a jury of 12
real townspeople.
- **The evidence is what really happened:**
  - the police case file from the witness system (G005), with each witness's actual memory, confidence and wrong
    details;
  - the era's forensics;
  - anything the player left behind.
  Nothing is scripted.
- **Jury selection:** jurors are town NPCs with traits, relationships and opinions of the player's reputation. Some
  may know the player or the victim, and selection questions can strike them.
- **Testimony comes from memory.** Witnesses answer from what they really saw, so cross-examination can expose a
  mistaken detail ("you said a red coat"). The player can speak in their own defence by voice.
- **The lawyer depends on money:** a tired public defender or an expensive lawyer. Plea deals are offered by the
  strength of the case.
- **The verdict is decided by game logic:** the strength of the evidence, the jurors' traits and the performance in
  court. The language model voices the lawyers, witnesses and deliberation and never decides facts (as with all
  dialogue). Outcomes:
  - acquittal;
  - conviction and sentence (into the prison mechanics);
  - a hung jury and retrial;
  - appeal.
- **Crimes around the trial:** witness tampering, bribing a juror and perjury, each with its own consequences. The
  time-travel twist: go back and change the evidence before it exists.
- **By era:** 1880s circuit courts, mid-century trials, and modern trials with DNA and camera footage.
- **Performance:** a trial is a set piece. Lines are pre-generated while the player waits in jail (arrest to
  arraignment) and only follow-ups are live.

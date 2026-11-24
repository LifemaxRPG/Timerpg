# Building security

`src/security` supplies deterministic game data. Call it with the same building, year and seed to reproduce the same setup. The stable building ID separates rolls for different locations. Owner wealth uses a 0–1 scale and contents value uses cents. The year must be supported by the economy and skills modules. Unsupported years and non-finite numeric inputs return null.

```ts
import { alarmResponse, entryOptions, securityOf } from '../src/security';
import { attempt } from '../src/skills';

const seed = 'town-1';
const year = 1952;
const security = securityOf({
  id: 'hardware-main', kind: 'hardware', ownerWealth: 0.4,
  neighbourhood: 'downtown', contentsValueCents: 90000,
}, year, seed);

if (security) {
  const options = entryOptions(security, { lockpicking: 0.4, safecracking: 0.2 });
  const pick = options.find(option => option.targetId === 'back' && option.method === 'pick');
  if (pick && pick.skill !== null && pick.level !== null) {
    attempt(pick.skill, pick.level, pick.difficulty, seed, 'hardware-main/back/attempt-1');
  }

  const responders = alarmResponse(security.alarm, year);
  // Each entry is a responder and the minutes after an alarm is triggered.
}
```

Each opening records the fitted lock and whether it is currently unlocked. An unlocked window can be used directly. Locked openings expose skill-based and force options. Force reports noise, damage and evidence, with difficulty available to the caller's resolution system. Safe options require interior access and use the existing safe profile. The caller triggers alarms, resolves dog encounters, applies consequences and schedules responders. The module does not change the building or the player.

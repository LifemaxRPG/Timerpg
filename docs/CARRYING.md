# Carrying and searches

An item has an instance `id` and a catalogue `itemId`. The catalogue supplies its size. A carried entry pairs the item with a container record. All public inputs and outputs are JSON values.

- `outfitContainers(outfit)` returns the player's clothing pockets. Workwear increases regular pocket space, and wool coats can hold a handheld item.
- `storageContainer(kind, id)` builds a profile for a bag, briefcase, bare hand, glovebox, trunk, room drawer or home hiding place. Supply a stable ID unique to that physical container in the inventory.
- `place(item, container, carried)` returns a proposed entry or null for an unknown catalogue item or a size or capacity conflict. Pass the current inventory to check aggregate capacity. On success the caller replaces the old entry for that item instance. Keep the prior inventory on failure. Recheck fit when the outfit changes.
- `visibility(carried)` returns `bulge` and `visibleItems`. A handheld item in a coat creates an outline. Items in hand are plainly visible. Contents of closed bags and off-person storage are outside this item-visibility result.
- `searchFinds(carried, searchKind, year, seed)` returns matching entries without removing them. A pat-down covers worn pockets and coats. A bag search names one container. Car and room searches name the targeted container IDs and carry the caller's already-resolved authority.

For Timerpg's simplified rules, car storage requires a warrant before 1970, and accepts cause or warrant from 1970. Room storage always requires a warrant. Named home hiding places have a 60% discovery roll keyed by seed, year, container and item instance. Replaying the same inputs gives the same result. Unsupported years return an empty list.

The module models size and visibility without movement penalties. The caller owns inventory persistence, accessibility, search authorization and any later confiscation.

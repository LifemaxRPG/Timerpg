# Clothes and appearance

Import from `src/clothes`. Prices are nominal integer cents.

- `wardrobeOf(1952, "clothing")` includes the brown coat at 1100 cents and felt hat at 300 cents. Omitting the shop returns all available garments. Supported outlet filters include clothing and pharmacy.
- `appearanceOf(base, {garments:[coat, hat], dyedHair:"brown"})` returns the full crime appearance, the visible description, and a face recognizability multiplier. Outerwear takes precedence over uniform and workwear. Hair dye changes the underlying tone. A hat removes the hair field from the visible description. Glasses and scarves multiply face recognizability by 0.8 and 0.65 respectively.
- `matchScore({clothingColor:"gray"}, dressed.visible)` returns 0 for the brown coat. Height, build and hair each weigh 2, coat colour weighs 3, and vehicle kind and colour weigh 1 each. A field hidden in the current appearance receives half credit. An empty description produces zero. This is a fit score for the caller, independent of the existing police recognition roll.
- `purchaseRecord(coat, 21, "main", {paidCash:true,nervous:true,publicCrimeDay:20})` returns the garment, colour, shop and day with the supplied facts and `shortlyAfterPublicCrime:true`. The window includes the public crime day and the next two days. Unspecified observations remain null.

Use the full appearance where the crime API requires ground truth and the visible projection when comparing observations. The caller supplies payment, demeanour, purchase timing and the equipment the player actually owns.

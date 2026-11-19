#!/usr/bin/env python3
"""Bakes the townspeople's character sheets from LimeZu Modern Interiors' Character Generator (docs/TOWN.md, "Townsfolk").

    python3 -I tools/make_townsfolk.py <Character_Generator dir> <assets/characters/folk out dir> [count]

The generator's layers (body with its skin tone, eyes, outfit, hairstyle, beard or moustache, glasses, hats) are cut to
the two rows the town draws (idle and walk: 4 views x 6 frames of 32 x 64, y 64..192 of the 32 px sheets) and laid one
over another into whole sheets of 768 x 128, picked from a seed per character so the set is the same every run. Each
character has traits the game picks by: sex (F/M), age band (kid, adult, elder) and whether its clothes are too modern
for an older year (`modern`). folk.json lists them; the game picks a sheet per person from their seed and traits.

Not shipped: the raw pack (only these baked sheets go into the app). Pink/blue skins, anime hair colours, cat ears,
swimwear, the ninja suit, masks and the novelty hats (the detective hat reads as a witch's at this size) are left out.
"""
import json
import os
import random
import sys

from PIL import Image

W, Y0, Y1 = 768, 64, 192

# skin tones (Bodies 1-4, 7: medium, pale, light, brown, peach); 5, 6, 8, 9 are yellow, grey, pink and blue
SKINS = [1, 2, 3, 4, 7]
SKIN_WEIGHT = [3, 2, 3, 3, 2]
# hairstyles by who wears them (the pack's numbering); 21 has cat ears, 27-29 anime colours
HAIR_F = [4, 8, 9, 10, 11, 15, 19, 23, 24, 20]
HAIR_M = [1, 2, 3, 6, 7, 12, 13, 14, 16, 17, 18, 22, 25, 26, 5, 20]
HAIR_ELDER_F = [4, 10, 8, 20, 15]
HAIR_ELDER_M = [1, 6, 12, 14, 16, 20, 22]
# hair colours 1-7: ginger, light brown, brown, dark brown, grey, dark grey, black-blue
HAIR_COLOUR = [1, 2, 3, 4, 7]
HAIR_COLOUR_WEIGHT = [1, 3, 4, 4, 3]
HAIR_ELDER = [5, 6, 5, 6, 2]
# outfits: 13 a surgeon's mask, 30 ninja, 31/32 swimwear, 33 a towel; 9 and 28 skirts/sailor (women); sporty and printed tees are "modern"
OUTFIT_ANY = [1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 14, 16, 17, 18, 20, 22, 23, 26, 27, 29]
OUTFIT_F = [9, 28, 11, 14, 1]
OUTFIT_M = [5, 6, 26, 27, 2, 18]
OUTFIT_ELDER = [2, 3, 5, 6, 10, 18, 23, 26, 27]
MODERN = {15, 16, 19, 21, 24, 25}
OUTFIT_MODERN = [15, 16, 19, 21, 24, 25]


def frames(path):
    """The two drawn rows of a layer sheet (768 x 128)."""
    im = Image.open(path).convert("RGBA")
    return im.crop((0, Y0, W, Y1))


def variants(d, stem):
    return sorted(f for f in os.listdir(d) if f.startswith(stem))


def main():
    gen, out = sys.argv[1], sys.argv[2]
    count = int(sys.argv[3]) if len(sys.argv) > 3 else 160
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        if f.startswith("folk_") and f.endswith(".png"):
            os.remove(os.path.join(out, f))
    p = lambda *a: os.path.join(gen, *a)
    cache = {}

    def layer(*a):
        k = p(*a)
        if k not in cache:
            cache[k] = frames(k)
        return cache[k]

    rnd = random.Random(1985)
    # the mix: about a tenth kids, a fifth elders, the rest adults; half women
    plan = []
    kids = max(8, count // 10)
    elders = max(8, count // 5)
    adults = count - kids - elders
    for i in range(adults):
        plan.append(("adult", "F" if i % 2 == 0 else "M"))
    for i in range(elders):
        plan.append(("elder", "F" if i % 2 == 0 else "M"))
    for i in range(kids):
        plan.append(("kid", "F" if i % 2 == 0 else "M"))

    people = []
    seen = set()
    for n, (age, sex) in enumerate(plan):
        for attempt in range(50):
            modern = False
            parts = []
            if age == "kid":
                body = rnd.randint(1, 4)
                parts.append(("Bodies_kids", "32x32", f"Body_{body}_kid_32x32.png"))
                eyes = variants(p("Eyes_kids", "32x32"), "Eyes_kids")
                parts.append(("Eyes_kids", "32x32", rnd.choice(eyes)))
                parts.append(("Outfits_kids", "32x32", f"Outfit_kid_{rnd.randint(1, 5)}_32x32.png"))
                hs = rnd.choice([1, 2, 3, 4, 5, 6] if sex == "F" else [1, 2, 4, 5, 6])
                hc = rnd.choice([1, 2, 3, 4, 5])
                parts.append(("Hairstyles_kids", "32x32", f"Hairstyle_kid_{hs}_32x32_{hc}.png"))
                key = (age, sex, body, hs, hc, parts[2][2])
            else:
                body = rnd.choices(SKINS, SKIN_WEIGHT)[0]
                parts.append(("Bodies", "32x32", f"Body_32x32_{body:02d}.png"))
                parts.append(("Eyes", "32x32", f"Eyes_32x32_{rnd.randint(1, 7):02d}.png"))
                if age == "elder":
                    o = rnd.choice(OUTFIT_ELDER)
                else:
                    pool = OUTFIT_ANY + (OUTFIT_F if sex == "F" else OUTFIT_M) * 2 + OUTFIT_MODERN
                    o = rnd.choice(pool)
                modern = o in MODERN
                ov = variants(p("Outfits", "32x32"), f"Outfit_{o:02d}_")
                parts.append(("Outfits", "32x32", rnd.choice(ov)))
                facial = None
                if sex == "M" and rnd.random() < (.35 if age == "elder" else .22):
                    kind = rnd.choice(["Accessory_13_Beard", "Accessory_12_Mustache"])
                    facial = rnd.choice(variants(p("Accessories", "32x32"), kind))
                    parts.append(("Accessories", "32x32", facial))
                if age == "elder":
                    hs = rnd.choice(HAIR_ELDER_F if sex == "F" else HAIR_ELDER_M)
                    hc = rnd.choice(HAIR_ELDER)
                else:
                    hs = rnd.choice(HAIR_F if sex == "F" else HAIR_M)
                    hc = rnd.choices(HAIR_COLOUR, HAIR_COLOUR_WEIGHT)[0]
                parts.append(("Hairstyles", "32x32", f"Hairstyle_{hs:02d}_32x32_{hc:02d}.png"))
                if rnd.random() < (.4 if age == "elder" else .12):
                    parts.append(("Accessories", "32x32", rnd.choice(variants(p("Accessories", "32x32"), "Accessory_15_Glasses"))))
                hat = rnd.random()
                if age == "adult" and hat < .08:
                    parts.append(("Accessories", "32x32", rnd.choice(variants(p("Accessories", "32x32"), "Accessory_11_Beanie"))))
                elif age == "adult" and sex == "M" and hat < .14:
                    parts.append(("Accessories", "32x32", rnd.choice(variants(p("Accessories", "32x32"), "Accessory_04_Snapback"))))
                    modern = True
                key = (age, sex, body, hs, hc, parts[2][2], facial)
            if key not in seen:
                break
        seen.add(key)
        sheet = Image.new("RGBA", (W, Y1 - Y0))
        for part in parts:
            sheet.alpha_composite(layer(*part))
        name = f"folk_{n:03d}.png"
        sheet.save(os.path.join(out, name), optimize=True)
        people.append({"file": name, "sex": sex, "age": age, "modern": modern})
    with open(os.path.join(out, "folk.json"), "w") as f:
        json.dump({"source": "LimeZu Modern Interiors, Character Generator (limezu.itch.io)", "frame": [32, 64],
                   "rows": ["idle", "walk"], "people": people}, f, indent=0)
    print(f"{len(people)} sheets: {sum(1 for x in people if x['age'] == 'adult')} adults, "
          f"{sum(1 for x in people if x['age'] == 'elder')} elders, {sum(1 for x in people if x['age'] == 'kid')} kids, "
          f"{sum(1 for x in people if x['modern'])} modern")


if __name__ == "__main__":
    main()

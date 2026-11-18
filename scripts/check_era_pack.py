#!/usr/bin/env python3
"""Checks era reference packs (docs/CHATGPT_ERA_PROMPT.md) before they are merged.

Usage: python3 scripts/check_era_pack.py FILE_OR_DIR [...]
Prints ERROR lines (the pack must be fixed or rejected) and WARN lines (a person looks at that item), then a summary.
Exit code 1 if any pack has an error. Pure standard library.
"""
import json, os, re, sys

DECADES = [1940, 1950, 1960, 1970, 1980, 1990, 2000, 2010, 2020]
TOPICS = ["tech", "speech", "money", "media", "transport", "shops", "home", "work", "fashion", "social", "events"]
AGES = {"all", "kids", "teens", "adults", "elders"}
USES = {"dialogue", "shop", "newspaper", "scenery", "guard"}
CONF = {"high", "medium"}
TONES = {"casual", "polite", "formal", "rude", "affectionate"}
NOT_YET_TOPICS = {"tech", "media", "transport"}
PRICE_TOPICS = {"money", "shops"}
PHRASE_TOPICS = {"speech", "social"}

# Brand, company and celebrity names a pack must not contain (whole words, case-insensitive). Not exhaustive: the
# capitalised-word check below catches most others as warnings.
BRANDS = """coca-cola coke pepsi 7up sprite ford chevrolet chevy cadillac buick oldsmobile pontiac chrysler dodge plymouth
jeep volkswagen vw beetle toyota honda datsun nissan tesla sears woolworth woolworths kmart walmart wal-mart target
mcdonald's mcdonalds burger king kfc wendy's starbucks ibm apple iphone ipod ipad macintosh microsoft windows google
facebook twitter instagram tiktok youtube netflix amazon nintendo atari sega playstation xbox sony walkman polaroid kodak
xerox hoover frigidaire maytag tupperware barbie lego monopoly elvis beatles sinatra kennedy nixon reagan eisenhower
truman roosevelt carter clinton obama trump disney disneyland hollywood broadway kleenex jell-o spam twinkie oreo
zenith rca motorola nokia blackberry aol yahoo myspace uber lyft zoom""".split()
# Brands that are also ordinary words only count when written with a capital (open windows, an apple, a target).
COMMON_WORDS = {"windows", "apple", "target", "sprite", "zoom", "amazon", "beetle", "spam", "coke", "jeep", "windows",
                "monopoly", "lego", "hoover", "dodge", "ford", "carter", "truman"}
BRAND_RE_I = re.compile(r"(?<![A-Za-z])(" + "|".join(re.escape(b) for b in BRANDS if b not in COMMON_WORDS) + r")(?![A-Za-z])", re.I)
BRAND_RE_CAP = re.compile(r"(?<![A-Za-z])(" + "|".join(re.escape(b.capitalize()) for b in COMMON_WORDS) + r")(?![A-Za-z])")


class _Brands:
    def search(self, s):
        return BRAND_RE_I.search(s) or BRAND_RE_CAP.search(s)


BRAND_RE = _Brands()

# Capitalised words that are fine inside a sentence.
OK_CAPS = set("""I American Americans America US U.S. TV Sunday Monday Tuesday Wednesday Thursday Friday Saturday January
February March April May June July August September October November December Christmas Easter Thanksgiving Halloween
New Year Year's Fourth July Independence Day Memorial Labor Valentine's Mother's Father's God Bible World War II Korea
Korean Vietnam Cold Depression Great Moon Main Street Mr. Mrs. Miss Ms. Dr. GI G.I. AM FM CB VCR VHS CD CDs DVD DVDs PC
PCs ATM ATMs GPS Wi-Fi Internet internet Web DJ DJs UFO JFK USA Soviet Soviets Russia Russian Russians Black White
Catholic Protestant Jewish Christian Christians Baptist Methodist Lutheran English Spanish Social Security Medicare
Prohibition Civil Rights Act Space Age Atomic Sputnik Y2K COVID-19 COVID""".split())

# Typical nominal US prices (rough, for sanity only): keyword -> {decade: usd}
PRICE_REF = {
    "gas": {1940: 0.18, 1950: 0.27, 1960: 0.31, 1970: 0.40, 1980: 1.20, 1990: 1.15, 2000: 1.50, 2010: 2.80, 2020: 3.00},
    "bread": {1940: 0.09, 1950: 0.16, 1960: 0.22, 1970: 0.25, 1980: 0.50, 1990: 0.70, 2000: 1.00, 2010: 1.40, 2020: 1.60},
    "milk": {1940: 0.50, 1950: 0.80, 1960: 1.00, 1970: 1.15, 1980: 2.00, 1990: 2.40, 2000: 2.80, 2010: 3.30, 2020: 3.50},
    "stamp": {1940: 0.03, 1950: 0.03, 1960: 0.04, 1970: 0.08, 1980: 0.15, 1990: 0.25, 2000: 0.33, 2010: 0.44, 2020: 0.55},
    "movie": {1940: 0.25, 1950: 0.48, 1960: 0.70, 1970: 1.50, 1980: 2.70, 1990: 4.20, 2000: 5.40, 2010: 7.90, 2020: 9.50},
    "coffee": {1940: 0.05, 1950: 0.10, 1960: 0.10, 1970: 0.25, 1980: 0.50, 1990: 0.75, 2000: 1.00, 2010: 1.50, 2020: 2.00},
}

errors = 0
warns = 0


def err(f, msg):
    global errors
    errors += 1
    print(f"ERROR {f}: {msg}")


def warn(f, msg):
    global warns
    warns += 1
    print(f"WARN  {f}: {msg}")


def words(s):
    return len(re.findall(r"[A-Za-z0-9'$.,%-]+", s))


def caps_inside(s):
    toks = re.findall(r"[A-Za-z][A-Za-z.'-]*", s)
    out = []
    for i, t in enumerate(toks):
        if i == 0 or not t[0].isupper():
            continue
        if t.rstrip(".,") in OK_CAPS or t.isupper():
            continue
        out.append(t)
    return out


def years_ok(a, b, decade):
    return isinstance(a, int) and isinstance(b, int) and a <= b and a <= decade + 9 and b >= decade \
        and a >= decade - 10 and b <= decade + 19


def norm(s):
    return " ".join(re.findall(r"[a-z0-9]+", s.lower()))


def check(path, covered):
    name = os.path.basename(path)
    try:
        d = json.load(open(path))
    except Exception as e:
        err(name, f"not valid JSON ({e})")
        return
    if not isinstance(d, dict):
        err(name, "top level is not an object")
        return
    for k in ("kind", "version", "decade", "topic", "facts", "not_yet", "prices", "phrases"):
        if k not in d:
            err(name, f"missing field '{k}'")
    extra = set(d) - {"kind", "version", "decade", "topic", "facts", "not_yet", "prices", "phrases"}
    if extra:
        err(name, f"unexpected fields {sorted(extra)}")
    if d.get("kind") != "era_pack" or d.get("version") != 1:
        err(name, "kind must be 'era_pack' and version 1")
    dec, top = d.get("decade"), d.get("topic")
    if dec not in DECADES:
        err(name, f"decade {dec!r} not one of {DECADES}")
        return
    if top not in TOPICS:
        err(name, f"topic {top!r} not one of {TOPICS}")
        return
    m = re.fullmatch(r"era-(\d{4})-([a-z]+)\.json", name)
    if m and (int(m.group(1)) != dec or m.group(2) != top):
        err(name, f"file name does not match decade {dec} / topic {top}")
    for k in ("facts", "not_yet", "prices", "phrases"):
        if not isinstance(d.get(k), list):
            err(name, f"'{k}' must be a list")
            return

    facts = d["facts"]
    if not 20 <= len(facts) <= 30:
        err(name, f"{len(facts)} facts (need 20-30)")
    seen = set()
    for i, f in enumerate(facts, 1):
        tag = f"fact {i}"
        if not isinstance(f, dict):
            err(name, f"{tag} is not an object")
            continue
        want = {"id", "year_from", "year_to", "text", "ages", "use", "confidence"}
        if set(f) != want:
            err(name, f"{tag} fields {sorted(f)} (need exactly {sorted(want)})")
        if f.get("id") != f"{dec}-{top}-{i:02d}":
            err(name, f"{tag} id {f.get('id')!r} (need '{dec}-{top}-{i:02d}', numbered in order)")
        if not years_ok(f.get("year_from"), f.get("year_to"), dec):
            err(name, f"{tag} years {f.get('year_from')}-{f.get('year_to')} must be whole numbers, from <= to, and overlap {dec}-{dec + 9}")
        t = f.get("text")
        if not isinstance(t, str) or not t.strip():
            err(name, f"{tag} has no text")
            continue
        if words(t) > 25:
            err(name, f"{tag} is {words(t)} words (max 25): {t}")
        if not t.rstrip().endswith((".", "!", "?")) or "\n" in t:
            err(name, f"{tag} must be one sentence ending with a full stop: {t}")
        if BRAND_RE.search(t):
            err(name, f"{tag} names a brand or person '{BRAND_RE.search(t).group(0)}': {t}")
        if re.search(r"[\"“”]", t):
            err(name, f"{tag} contains a quotation: {t}")
        c = caps_inside(t)
        if c:
            warn(name, f"{tag} capitalised words {c} (a name or brand?): {t}")
        if f.get("ages") not in AGES:
            err(name, f"{tag} ages {f.get('ages')!r} not in {sorted(AGES)}")
        u = f.get("use")
        if not isinstance(u, list) or not u or not set(u) <= USES:
            err(name, f"{tag} use {u!r} must be a non-empty list from {sorted(USES)}")
        if f.get("confidence") not in CONF:
            err(name, f"{tag} confidence {f.get('confidence')!r} not in {sorted(CONF)}")
        n = norm(t)
        if n in seen:
            err(name, f"{tag} repeats another fact in this pack: {t}")
        seen.add(n)
        for cn, ct in covered:
            a, b = set(n.split()), set(norm(ct).split())
            if a and len(a & b) / len(a | b) > 0.7:
                warn(name, f"{tag} looks like a fact in {cn}: {t}")
                break

    ny = d["not_yet"]
    if top in NOT_YET_TOPICS and len(ny) < 6:
        err(name, f"topic {top} needs at least 6 not_yet entries (has {len(ny)})")
    for i, x in enumerate(ny, 1):
        tag = f"not_yet {i}"
        if not isinstance(x, dict) or set(x) != {"thing", "first_common", "say_instead"}:
            err(name, f"{tag} needs exactly thing, first_common, say_instead")
            continue
        fc = x["first_common"]
        if fc is not None and (not isinstance(fc, int) or fc <= dec or fc > 2030):
            err(name, f"{tag} first_common {fc!r} must be a year after {dec} (or null if still not common today): {x['thing']}")
        if not str(x["thing"]).strip() or not str(x["say_instead"]).strip():
            err(name, f"{tag} thing and say_instead must not be empty")
        for s in (x["thing"], x["say_instead"]):
            if BRAND_RE.search(str(s)):
                err(name, f"{tag} names a brand '{BRAND_RE.search(str(s)).group(0)}'")

    pr = d["prices"]
    if top in PRICE_TOPICS and len(pr) < 10:
        err(name, f"topic {top} needs at least 10 prices (has {len(pr)})")
    if top not in PRICE_TOPICS and pr:
        err(name, f"prices must be empty for topic {top}")
    for i, x in enumerate(pr, 1):
        tag = f"price {i}"
        if not isinstance(x, dict) or set(x) != {"item", "usd", "year", "confidence"}:
            err(name, f"{tag} needs exactly item, usd, year, confidence")
            continue
        if not isinstance(x["usd"], (int, float)) or not 0 < x["usd"] < 1_000_000:
            err(name, f"{tag} usd {x['usd']!r} must be a positive number")
            continue
        if not isinstance(x["year"], int) or not dec <= x["year"] <= dec + 9:
            err(name, f"{tag} year {x['year']!r} must be inside {dec}-{dec + 9}")
        if x["confidence"] not in CONF:
            err(name, f"{tag} confidence {x['confidence']!r}")
        item = str(x["item"]).lower()
        for k, ref in PRICE_REF.items():
            if re.search(r"\b" + k + r"\b", item) and "gallon" in item or (k != "gas" and re.search(r"\b" + k + r"\b", item)):
                r = ref.get(dec)
                if r and not (r / 3 <= x["usd"] <= r * 3):
                    warn(name, f"{tag} {x['item']} at ${x['usd']} (typical {dec}s about ${r}; is it today's money?)")
                break

    ph = d["phrases"]
    if top == "speech" and len(ph) < 12:
        err(name, f"topic speech needs at least 12 phrases (has {len(ph)})")
    if top not in PHRASE_TOPICS and ph:
        err(name, f"phrases must be empty for topic {top}")
    for i, x in enumerate(ph, 1):
        tag = f"phrase {i}"
        if not isinstance(x, dict) or set(x) != {"phrase", "meaning", "ages", "from", "to", "tone"}:
            err(name, f"{tag} needs exactly phrase, meaning, ages, from, to, tone")
            continue
        if not years_ok(x["from"], x["to"], dec):
            err(name, f"{tag} years {x['from']}-{x['to']} must overlap {dec}-{dec + 9}")
        if x["ages"] not in AGES:
            err(name, f"{tag} ages {x['ages']!r}")
        if x["tone"] not in TONES:
            err(name, f"{tag} tone {x['tone']!r} not in {sorted(TONES)}")
        if BRAND_RE.search(str(x["phrase"])):
            err(name, f"{tag} names a brand or person")
        if len(str(x["phrase"]).split()) > 6:
            err(name, f"{tag} phrase is longer than 6 words: {x['phrase']}")


def main():
    paths = []
    for a in sys.argv[1:]:
        if os.path.isdir(a):
            paths += sorted(os.path.join(a, p) for p in os.listdir(a) if p.endswith(".json"))
        else:
            paths.append(a)
    if not paths:
        print(__doc__)
        sys.exit(2)
    # facts of all packs, so a pack is compared with the others of its decade
    by_dec = {}
    for p in paths:
        try:
            d = json.load(open(p))
            for f in d.get("facts", []):
                by_dec.setdefault(d.get("decade"), []).append((os.path.basename(p), f.get("text", "")))
        except Exception:
            pass
    bad = 0
    for p in paths:
        before = errors
        try:
            dec = json.load(open(p)).get("decade")
        except Exception:
            dec = None
        covered = [c for c in by_dec.get(dec, []) if c[0] != os.path.basename(p)]
        check(p, covered)
        bad += errors > before
    print(f"\n{len(paths)} pack(s): {len(paths) - bad} pass, {bad} with errors, {warns} warning(s)")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

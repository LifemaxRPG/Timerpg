#!/usr/bin/env python3
"""Era packs that ChatGPT pushed to the content-inbox branch, checked: prints one JSON object
{"<decade>-<topic>": {"status": "review"|"fail", "errors": [...], "warnings": [...], "sha": "..."}}
for every content/inbox/era/era-<decade>-<topic>.json on origin/content-inbox (fetch first).
Usage: python3 scripts/era_inbox_status.py [git-ref] (default origin/content-inbox)
"""
import json, os, re, subprocess, sys, tempfile

ref = sys.argv[1] if len(sys.argv) > 1 else "origin/content-inbox"
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ls = subprocess.run(["git", "-C", root, "ls-tree", "-r", ref, "content/inbox/era/"], capture_output=True, text=True)
out = {}
with tempfile.TemporaryDirectory() as tmp:
    files = []
    for line in ls.stdout.splitlines():
        meta, path = line.split("\t", 1)
        m = re.fullmatch(r"content/inbox/era/era-(\d{4})-([a-z]+)\.json", path)
        if not m:
            continue
        sha = meta.split()[2]
        body = subprocess.run(["git", "-C", root, "show", f"{ref}:{path}"], capture_output=True).stdout
        p = os.path.join(tmp, os.path.basename(path))
        open(p, "wb").write(body)
        files.append((f"{m.group(1)}-{m.group(2)}", p, sha))
    # all packs checked in one run (so repeats across a decade are seen)
    r = subprocess.run([sys.executable, os.path.join(root, "scripts", "check_era_pack.py")] + [q for _, q, _ in files],
                       capture_output=True, text=True) if files else None
    for key, p, sha in files:
        name = os.path.basename(p)
        errs = [l.split(": ", 1)[1] for l in r.stdout.splitlines() if l.startswith("ERROR " + name)]
        warns = [l.split(": ", 1)[1] for l in r.stdout.splitlines() if l.startswith("WARN  " + name)]
        out[key] = {"status": "fail" if errs else "review", "errors": errs[:8], "warnings": warns[:8], "sha": sha}
print(json.dumps(out, indent=1))

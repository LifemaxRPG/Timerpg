"""Check campaign coverage, face dimensions and the exact 2×2 art-pixel grid."""
from collections import Counter
import json
from pathlib import Path

from PIL import Image

root = Path(__file__).resolve().parent
info = json.loads((root / "model-info.json").read_text())
assert info["board_face_px"] == [256, 88]
assert info["board_face_m"] == [10, 3.44]
assert info["meters_per_texel"] == [10 / 256, 3.44 / 88]
assert info["nearest"]
assert Counter(row["kind"] for row in info["faces"]) == {
    "campaign": 9, "distance": 12, "weathered": 2,
}
seen = set()
for row in info["faces"]:
    assert row["id"] not in seen
    seen.add(row["id"])
    with Image.open(root / row["file"]) as image:
        assert image.size == (256, 88) and image.mode == "RGB"
        expected = image.resize((128, 44), Image.Resampling.NEAREST)
        expected = expected.resize(image.size, Image.Resampling.NEAREST)
        assert expected.tobytes() == image.tobytes(), row["file"]
    if row["kind"] == "weathered":
        source = next(face for face in info["faces"] if face["id"] == row["source"])
        with Image.open(root / source["file"]) as fresh, Image.open(root / row["file"]) as old:
            assert fresh.tobytes() != old.tobytes()
for era in info["campaigns"]:
    items = [row for row in info["faces"] if row["campaign"] == era]
    assert sum(row["kind"] == "campaign" for row in items) == 3
    assert {(row["miles"], row["next_exit"]) for row in items if row["kind"] == "distance"} == {
        (120, False), (45, False), (12, False), (None, True),
    }
print("23 faces validated across three campaigns")

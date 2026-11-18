"""Fixes from the final audit of every modelled building (make_buildings.py and the group modules), kept in a module of
their own so the groups' files stay as they were merged. make_buildings imports it with importlib (its name has a
hyphen)."""
import numpy as np


def ribbed_side(front, depth, corner, cols=(2, 32)):
    """A side wall for a front of upright ribs (corrugated panels, vertical boards): one rib period of the front's own
    columns repeated along the depth, so the side shows the ribs the front does instead of each row's colour smeared
    flat. The period starts at the last dark rib that leaves a whole period inside `cols`; whatever stands in front of
    the ribs there (a potted plant, which is its own card) is replaced by its column's plain colour, and its base rows
    by the base's own colour."""
    lum = front[40:120, :, :3].astype(int).mean(axis=(0, 2))
    span = lum[cols[0]:cols[1]]; light = (span.min() + np.median(span)) / 2  # a rib is darker than this, in any colour
    starts = [x for x in range(cols[0] + 1, cols[1]) if lum[x] < light <= lum[x - 1]]
    period = int(np.median(np.diff(starts))) if len(starts) > 1 else 6
    a = max(s for s in starts if s + period <= cols[1])
    win = front[:, a:a + period].copy()
    w = front.astype(int)
    plant = (w[..., 1] > w[..., 0] + 25) & (w[..., 1] > w[..., 2] + 10) & (w[..., 3] > 0)
    for x in np.where(plant.any(0))[0]:                             # its dark leaves and its pot under it too
        plant[max(0, int(np.argmax(plant[:, x])) - 3):, x] = True
    plant |= np.roll(plant, 1, 1) | np.roll(plant, -1, 1)
    h = front.shape[0]; base = h - 8
    for j in range(period):
        m = plant[:, a + j]
        if not m.any(): continue
        clean = ~plant[20:base, a + j]
        win[:base][m[:base], j] = np.median(front[20:base, a + j][clean], axis=0).astype(np.uint8)
    for r in range(base, h):                                        # the base band: its colour where nothing stands
        row = front[r, cols[0]:cols[1]]; ok = ~plant[r, cols[0]:cols[1]]
        if (plant[r, a:a + period]).any() and ok.any(): win[r] = np.median(row[ok], axis=0).astype(np.uint8)
    side = np.tile(win, (1, depth // period + 1, 1))[:, :depth].copy(); side[..., 3] = 255
    for c in (0, 1, depth - 2, depth - 1): side[:, c, :3] = corner[:3]
    return side

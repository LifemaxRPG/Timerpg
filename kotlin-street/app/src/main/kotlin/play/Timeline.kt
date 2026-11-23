package com.timerpg.street.play

/**
 * The timeline's lines (pure, for the tests): one "s" line a second from a [Second] and a [PlayState]. The field names
 * are listed in PLAYTEST.md, "What's built".
 */
object Timeline {
    /** FrameWork's part and upload names (kept here so the line does not depend on the renderer's module). */
    val PART_NAMES = arrayOf("step", "build", "occlude", "far", "buildings", "night", "yards", "green", "stores", "people", "render", "cards")
    val UP_NAMES = arrayOf("trees", "far", "buildings", "sheets", "ground")

    fun second(j: JsonLine, tMs: Long, sec: Second, s: PlayState): StringBuilder {
        j.begin().str("k", "s").long("t", tMs)
        j.int("f", sec.frames)
        if (sec.moving > 0) j.int("mv", sec.moving)
        j.pcts("ft", sec.frame, 2).pcts("cpu", sec.cpu, 2).pcts("gpu", sec.gpu, 2)
        if (sec.spikes > 0) j.int("spk", sec.spikes)
        j.intIfKnown("dr", sec.draws).intIfKnown("tri", sec.triangles).intIfKnown("triMax", sec.trianglesMax).intIfKnown("bv", sec.visible)
        if (sec.frames > 0) j.named("parts", PART_NAMES, sec.partMs, 0.05f)
        j.named("up", UP_NAMES, sec.uploads)
        if (sec.uploadBytes > 0) j.long("upKB", sec.uploadBytes shr 10)
        j.numIfKnown("touch", sec.touchMs)
        j.str("act", s.activity)
        j.strIfSet("town", s.town).strIfSet("st", s.street).strIfSet("hood", s.hood)
        if (!s.wx.isNaN()) j.num("x", s.wx, 1).num("z", s.wz, 1)
        if (!s.worldX.isNaN()) j.num("wx", s.worldX, 0).num("wz", s.worldZ, 0)
        if (s.year != 0) j.int("yr", s.year)
        j.num("hr", s.hour, 2)
        if (!s.camDist.isNaN()) {
            j.sb.append(",\"cam\":[")
            JsonLine.fixed(j.sb, s.camDist.toDouble(), 1); j.sb.append(',')
            JsonLine.fixed(j.sb, s.camPitch.toDouble(), 1); j.sb.append(',')
            JsonLine.fixed(j.sb, s.camYaw.toDouble(), 0); j.sb.append(',')
            JsonLine.fixed(j.sb, s.camHeight.toDouble(), 1); j.sb.append(']')
        }
        j.intIfKnown("ppl", s.people)
        j.strIfSet("talk", s.talkingTo).strIfSet("room", s.room).strIfSet("ai", s.ai)
        if (s.aiQueue >= 0) j.int("aiQ", s.aiQueue)
        return j.end()
    }
}

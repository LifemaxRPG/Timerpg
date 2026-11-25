package com.timerpg.street.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * An engine made live from its [EngineProfile] and [EngineState] (docs/SOUND.md "Engines"): the crank turns, each
 * cylinder fires at its point in the cycle into its bank's exhaust (a pulse whose size follows the load, uneven from
 * cycle to cycle, a lumpy cam's lope at idle, now and then a pop on the overrun); each bank's pulses ring in its pipe (a
 * delay line fed back through a damping filter: the pipe's resonances) and come out through the muffler (a low-pass and the
 * body's boom). Over it the intake's roar under throttle, the valve train's ticks, the starter motor's whine while it
 * cranks; an electric car instead has its motor's whine and the inverter's hum. Mono float at [Pcm.RATE]; [render] adds
 * frames at a gain with a [doppler] factor on every frequency (a passing car).
 */
class EngineSynth(val profile: EngineProfile, seed: Long = 3) {
    private val r = Rng(seed)
    private val rate = Pcm.RATE.toFloat()

    // the cycle and each cylinder's own strength (a real engine's cylinders are never quite equal)
    private var cycle = 0.0
    private val cylAmp = FloatArray(profile.cylinders) { 1f + r.range(-.09f, .09f) }
    private val cylNext = FloatArray(profile.cylinders) { 1f }
    // per bank: the pulse's two smoothing poles, the combustion noise's envelope, the pipe's delay line
    private val pulseA = FloatArray(2); private val pulseB = FloatArray(2); private val burst = FloatArray(2)
    private val pipeLen = IntArray(2) { (profile.pipeMs[it] / 1000f * rate).toInt().coerceIn(8, 2000) }
    private val pipe = Array(2) { FloatArray(2048) }
    private val pipePos = IntArray(2)
    private val pipeDamp = Array(2) { OnePole(2400f) }
    private val muffler = Biquad().lowpass(maxOf(200f, profile.mufflerHz), .7f)
    private val muffler2 = Biquad().lowpass(maxOf(200f, profile.mufflerHz * 1.6f), .6f)
    private val boom = Biquad().peak(maxOf(30f, profile.boomHz), 1.4f, 9f * profile.boom)
    private val dc = OnePole(22f)
    private val intakeBp = Biquad().bandpass(900f, 1.2f)
    private val intakeLp = OnePole(3000f)
    private var intakeEnv = 0f
    private var tick = 0f
    private val tickBp = Biquad().bandpass(4200f, 3f)
    private var pop = 0f
    private val popLp = OnePole(1800f)
    // the starter and the electric motor
    private var starterPh = 0f
    private var motorPh = 0f; private var motorPh2 = 0f
    private val hiss = OnePole(1500f)
    private var lastCut = -1f
    // the rev limiter (docs/SOUND.md "Engines"): a spark cut of a few dozen milliseconds, then the engine fires again
    // until the revs touch the limit once more; the revs dip a little each cut. Held there a while (flat out at the top
    // speed in top gear) it settles into a softer governor's flutter
    private var limCut = 0f
    private var limOn = 0f
    private var limNext = .03f
    private var limDip = 0f
    private var limHeld = 0f
    /** The level now (an eased gain, so starting and stopping never click). */
    private var level = 0f

    /**
     * Adds [frames] mono frames of the engine in [s] into [out] at [gain]; [doppler] scales every frequency (1 still). The
     * engine's speed is eased across the block from the last call's.
     */
    fun render(s: EngineState, out: FloatArray, frames: Int, gain: Float, doppler: Float = 1f) {
        val p = profile
        val phase = s.phase
        val on = phase != EngineState.Phase.OFF || s.rpm > 30f
        val target = if (on) 1f else 0f
        if (!on && level < 1e-4f) return
        val rpmEnd = s.rpm
        val rpm0 = if (lastCut < 0f) rpmEnd else lastCut
        lastCut = rpmEnd
        val load = s.load
        val crank = phase == EngineState.Phase.CRANK
        val running = phase == EngineState.Phase.RUN
        // a shift's dip: the throttle eased off for a moment
        val shiftDip = if (s.sinceShift < .25f) .45f + 2.2f * s.sinceShift else 1f
        if (p.electric) { renderElectric(s, out, frames, gain, doppler); return }
        val dtf = doppler / rate
        val n = p.cylinders
        // the intake's band rises with the revs
        intakeBp.bandpass(500f + 1.6f * rpmEnd * doppler * (if (p.cylinders == 4) 1.3f else 1f), 1.1f)
        val atTop = running && !p.electric && load > .3f && rpmEnd > p.redline * .985f
        limHeld = if (atTop) limHeld + frames / rate else 0f
        // (held on it over a second: shallower cuts, a governor rather than a bouncing limiter)
        val held = ((limHeld - 1f) / 1.5f).coerceIn(0f, 1f)
        for (k in 0 until frames) {
            level += (target - level) * .002f
            // the limiter: cut, fire, cut (about 10-14 times a second at the limit)
            if (limCut > 0f) limCut -= 1f / rate
            else {
                limOn += 1f / rate
                if (atTop && limOn > limNext) { limCut = r.range(.035f, .055f) * (1f - .4f * held); limOn = 0f; limNext = r.range(.028f, .045f) * (1f + held) }
            }
            val cutting = limCut > 0f
            limDip += ((if (cutting) 1f else 0f) - limDip) * (1f / (.018f * rate))
            val rpm = (rpm0 + (rpmEnd - rpm0) * (k / frames.toFloat())) * (1f - .035f * (1f - .6f * held) * limDip)
            // the cycle: two crank turns
            val prev = cycle
            cycle += rpm / 120.0 * dtf
            val wrapped = cycle >= 1.0
            if (wrapped) cycle -= 1.0
            val revs = (rpm / p.redline).coerceIn(0f, 1.2f)
            // each cylinder crossing its firing point fires (or, cranking, only compresses)
            for (c in 0 until n) {
                val f = p.firing[c].toDouble()
                val crossed = if (wrapped) (prev < f || cycle >= f) else (prev < f && cycle >= f)
                if (!crossed) continue
                val idleness = (1f - revs * 2.5f).coerceIn(0f, 1f)
                var a: Float
                if (crank) a = .22f
                else {
                    // the load sets how much burns; the overrun barely fires; a shift eases it; rough engines vary
                    a = (.28f + .72f * load) * (if (s.overrun) .35f else 1f) * shiftDip
                    a *= 1f + p.rough * (.22f + .5f * idleness) * r.noise()
                    // the cam's lope: alternate cycles stronger and weaker at idle
                    a *= 1f + p.lope * idleness * cylNext[c]
                    cylNext[c] = -cylNext[c] * (.6f + .4f * r.next())
                    // a rare miss at idle in a rough old engine
                    if (r.next() < p.rough * .02f * idleness) a *= .1f
                    // the rev limiter's cut: the spark gone (a held governor's shallower), now and then a pop of the
                    // unburnt mixture in the pipe
                    if (cutting) { a *= .05f + .45f * held; if (r.next() < p.pops * .02f + .004f) pop = .25f + .35f * r.next() }
                    // (an occasional pop, not a crackle on every cycle)
                    if (s.overrun && r.next() < p.pops * .04f) pop = .4f + .6f * r.next()
                }
                a *= cylAmp[c]
                val b = p.bank[c]
                pulseA[b] += a
                burst[b] += a * (if (crank) .2f else .55f)
            }
            // each bank: the pulse (two poles: a smooth blast) plus combustion noise, through its pipe
            val width = p.pulseMs * (1f - .55f * revs) / 1000f
            val ka = (1f / (width * rate / doppler)).coerceAtMost(.9f)
            var sum = 0f
            for (b in 0..1) {
                pulseB[b] += (pulseA[b] - pulseB[b]) * ka
                pulseA[b] -= pulseA[b] * ka
                val noise = r.noise() * burst[b]
                burst[b] *= .985f
                val x = pulseB[b] * 3f + noise * .6f
                // the pipe: what went out comes back, damped
                val len = (pipeLen[b] / doppler).toInt().coerceIn(8, 2047)
                val buf = pipe[b]
                val rd = (pipePos[b] - len + 2048) and 2047
                val back = pipeDamp[b].lp(buf[rd])
                val y = x + back * p.pipeFeedback
                buf[pipePos[b]] = y
                pipePos[b] = (pipePos[b] + 1) and 2047
                sum += y
            }
            // the muffler, the body's boom, no DC
            var e = boom.run(muffler2.run(muffler.run(sum)))
            e = dc.hp(e)
            // pops on the overrun: a crack of noise in the pipe
            if (pop > 1e-3f) { e += popLp.lp(r.noise()) * pop * 1.2f; pop *= .99f }
            // the intake: noise that breathes with the firing, louder with throttle and revs
            intakeEnv += ((pulseB[0] + pulseB[1]) - intakeEnv) * .02f
            val intake = intakeLp.lp(intakeBp.run(r.noise())) * (.2f + intakeEnv) * p.intake * load * (.3f + revs) * 1.6f * (1f - .5f * limDip)
            // the valve train: a tick each valve opening (two per cylinder a cycle), loud in old engines
            var clat = 0f
            if (p.clatter > 0f && rpm > 100f) {
                tick += rpm / 60f * n * dtf * .5f
                if (tick >= 1f) { tick -= 1f; clat = r.range(.5f, 1f) }
                clat = tickBp.run(clat * r.noise() * 4f) * p.clatter * .25f
            }
            // the starter: a motor's whine and its gear's grind while cranking
            var st = 0f
            if (crank) {
                starterPh += 2f * PI.toFloat() * (150f + rpm * .4f) * dtf
                if (starterPh > 6.2831855f) starterPh -= 6.2831855f
                // (quieter than the engine it starts: a low whirr, its pitch sagging at each compression)
                st = (sin(starterPh) * .3f + sin(starterPh * 2.01f) * .1f + hiss.lp(r.noise()) * .03f) * .3f
            }
            out[k] += soft((e * 1.2f + intake + clat + st) * p.gain) * gain * level
        }
    }

    /** The electric motor: its whine (with a harmonic), the inverter's high hum, both with the load. */
    private fun renderElectric(s: EngineState, out: FloatArray, frames: Int, gain: Float, doppler: Float) {
        val target = if (s.phase == EngineState.Phase.RUN) 1f else 0f
        val hz = (s.rpm / 60f * 4f).coerceAtMost(5200f)
        val inv = 7800f
        for (k in 0 until frames) {
            level += (target - level) * .002f
            motorPh += 2f * PI.toFloat() * hz * doppler / rate
            if (motorPh > 6.2831855f) motorPh -= 6.2831855f
            motorPh2 += 2f * PI.toFloat() * inv * doppler / rate
            if (motorPh2 > 6.2831855f) motorPh2 -= 6.2831855f
            val whine = (sin(motorPh) * .5f + sin(motorPh * 2f) * .2f + sin(motorPh * 3f) * .1f) * (.15f + .85f * s.load) * (hz / 2000f).coerceIn(0f, 1f)
            val hum = sin(motorPh2) * .05f * (.2f + s.load)
            out[k] += (whine * .9f + hum * 2f + hiss.lp(r.noise()) * .04f) * profile.gain * gain * level
        }
    }
}

/**
 * The tyres on the road and the squeal of a slide (docs/SOUND.md "Engines"): a rolling roar of band-passed noise rising
 * and brightening with the speed (gritty on gravel, soft on grass), and a squeal (a few close partials wavering, with
 * noise) as the tyres slip sideways or lock under hard braking. Mono float.
 */
class TyreSynth(seed: Long = 5) {
    private val r = Rng(seed)
    private val bp = Biquad(); private val lp = Biquad(); private val rumble = OnePole(120f)
    private val sqNoise = Biquad()
    private var sq1 = 0f; private var sq2 = 0f; private var sq3 = 0f
    private var squealLevel = 0f
    private var rollLevel = 0f
    private var crackle = 0f
    private val crackBp = Biquad().bandpass(2500f, 2f)

    /** [speed] m/s, [slip] 0..1 (sideways or locked), [surface] a [Footsteps.SURFACES] name. */
    fun render(out: FloatArray, frames: Int, gain: Float, speed: Float, slip: Float, surface: String, doppler: Float = 1f) {
        val v = kotlin.math.abs(speed)
        val rollTarget = (v / 30f).coerceIn(0f, 1.3f)
        val squealTarget = if (surface == "grass" || surface == "gravel" || surface == "snow") 0f else (slip * (v / 8f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        if (rollLevel < 1e-4f && rollTarget <= 0f && squealLevel < 1e-4f && squealTarget <= 0f) return
        val centre = (300f + v * 40f).coerceAtMost(1800f) * doppler
        bp.bandpass(centre, .7f)
        lp.lowpass((600f + v * 35f).coerceAtMost(1600f) * doppler, .6f)
        val gravel = surface == "gravel"; val grass = surface == "grass"
        val f = 1150f * doppler * (1f + .1f * slip)
        sqNoise.bandpass(f * 1.2f, 2f)
        for (k in 0 until frames) {
            rollLevel += (rollTarget - rollLevel) * .001f
            squealLevel += (squealTarget - squealLevel) * .003f
            val n = r.noise()
            var roll = lp.run(bp.run(n)) * 2.2f + rumble.lp(n) * 3f
            if (grass) roll *= .55f
            if (gravel) {
                if (r.next() < v * .02f) crackle = .4f + .6f * r.next()
                roll += crackBp.run(crackle * r.noise()) * 2f
                crackle *= .97f
            }
            var sq = 0f
            if (squealLevel > 1e-4f) {
                // a squeal's partials beat against each other and waver
                val wob = 1f + .015f * sin(sq3 * .0021f)
                sq1 += 2f * PI.toFloat() * f * wob / Pcm.RATE; if (sq1 > 6.2831855f) sq1 -= 6.2831855f
                sq2 += 2f * PI.toFloat() * f * 1.51f * wob / Pcm.RATE; if (sq2 > 6.2831855f) sq2 -= 6.2831855f
                sq3 += 1f
                sq = (sin(sq1) * .5f + sin(sq2) * .25f + sqNoise.run(n) * .3f) * squealLevel
            }
            out[k] += (roll * rollLevel * .25f + sq * .35f) * gain
        }
    }
}

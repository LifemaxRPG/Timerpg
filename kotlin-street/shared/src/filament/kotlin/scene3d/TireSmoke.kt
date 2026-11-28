package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.lifemax.rpg.feature.habits.vehicle.CarPose
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld

/** A small pool of crossed, cutout pixel puffs that rise and trail from spinning tires. */
internal class TireSmoke(kit: TownExtra.Kit) {
    private class Puff {
        var born = Double.NEGATIVE_INFINITY
        var x = 0f
        var y = 0f
        var z = 0f
        var vx = 0f
        var vz = 0f
    }

    private val layer = PropLayer(kit.engine, kit.scene, kit.lit, kit.proxy, sharedTextures = kit.textures, cull = false)
        .also { it.seeThrough = false }
    private val pool = Array(20) { Puff() }
    private val last = DoubleArray(4) { Double.NEGATIVE_INFINITY }
    private val matrix = FloatArray(16)
    private val point = FloatArray(3)
    private var clock = 0.0
    private var cursor = 0

    fun begin(seconds: Double) {
        if (seconds < clock) reset()
        clock = seconds
    }

    fun emit(pose: CarPose, car: FloatArray) {
        for (wheel in 0..3) {
            val amount = pose.tireSmoke[wheel]
            if (amount <= 0f || clock - last[wheel] < .11 + .12 * (1f - amount)) continue
            if (layer.batchCount == 0) upload()
            last[wheel] = clock
            val puff = pool[cursor]
            cursor = (cursor + 1) % pool.size
            val side = if (wheel % 2 == 0) 1f else -1f
            val spec = pose.spec
            val u = VehicleWorld.U
            val axle = if (wheel < 2) spec.frontZ else spec.rearZ
            GlbFile.point(car, side * spec.halfTrack * u,
                (spec.axleY - spec.wheelRadius + pose.rise[wheel] + .14f) * u, axle * u, point, 0)
            puff.born = clock
            puff.x = point[0]; puff.y = point[1]; puff.z = point[2]
            puff.vx = (side * pose.fz * .22f - pose.fx * .52f) * u
            puff.vz = (-side * pose.fx * .22f - pose.fz * .52f) * u
        }
    }

    fun draw(frame: TownExtra.Frame, out: IntArray) {
        if (layer.batchCount == 0) return
        var visible = false
        for ((index, puff) in pool.withIndex()) {
            val age = (clock - puff.born).toFloat()
            val alive = age >= 0f && age < 1.05f
            layer.setBatchVisible(index, alive)
            if (!alive) continue
            visible = true
            val radius = (.18f + .57f * age) * ((1.05f - age) / .3f).coerceIn(0f, 1f) * VehicleWorld.U
            matrix[0] = radius
            matrix[5] = radius * .83f
            matrix[10] = radius
            matrix[12] = puff.x + puff.vx * age
            matrix[13] = puff.y + age * .52f * VehicleWorld.U
            matrix[14] = puff.z + puff.vz * age
            matrix[15] = 1f
            layer.setBatchTransform(index, matrix)
        }
        layer.update(frame.look, frame.dark, visible, shadows = false)
        out[0] += layer.draws
        out[1] += layer.triangles
    }

    private fun upload() {
        val texture = PixImg(16, 16)
        for (y in 0..15) for (x in 0..15) {
            val dx = x / 2 - 3.5f
            val dy = y / 2 - 3.5f
            val r = dx * dx + dy * dy
            texture[x,y] = when {
                r < 5 -> Paint.c(0xD8DAD1)
                r < 9 -> Paint.c(0xBBBEBA)
                r < 13 && (x / 2 + y / 2) % 2 == 0 -> Paint.c(0x999F9D)
                else -> 0
            }
        }
        val mesh = MeshBuilder()
        mesh.quad(floatArrayOf(-1f,1f,0f), floatArrayOf(1f,1f,0f),
            floatArrayOf(1f,-1f,0f), floatArrayOf(-1f,-1f,0f), texture)
        mesh.quad(floatArrayOf(0f,1f,-1f), floatArrayOf(0f,1f,1f),
            floatArrayOf(0f,-1f,1f), floatArrayOf(0f,-1f,-1f), texture)
        val packed = PropLayer.Packing.packLocal(mesh.build()) ?: return
        repeat(pool.size) { layer.upload(packed, casts = false) }
    }

    fun reset() {
        for (puff in pool) puff.born = Double.NEGATIVE_INFINITY
        for (index in 0 until layer.batchCount) layer.setBatchVisible(index, false)
        last.fill(Double.NEGATIVE_INFINITY)
        cursor = 0
    }

    fun destroy() = layer.destroy()
}

package com.timerpg.street.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** dialogue.json as tools/export-dialogue.ts writes it: v1 (the two streets, shipped today) and v2 (`--all`, the town). */
class DialogueDataTest {
    @Test
    fun readsTheShippedV1File() {
        val d = DialogueData.parse(File("src/main/assets/dialogue.json").readText())
        assertEquals(1, d.version)
        assertTrue(d.npcs.isNotEmpty())
        for (n in d.npcs) {
            assertTrue(n.street in 0..1 && n.index >= 0)
            assertNull(n.row)
            assertEquals(-1, n.rowIndex)
            assertEquals("", n.neighbourhood)
            // looked up by the building's sprite name
            assertTrue(n in d.at(n.building))
        }
        assertEquals(d.npcs.map { it.building }.toSet(), d.buildings)
    }

    @Test
    fun readsTheWholeTownV2() {
        val topic = """{"intent":"greet","label":"Say hello","familiarity":0,"template":"Hello.","prompt":null,"must":[],"self":"X"}"""
        val text = """{"version":2,"town":"gamma","year":2025,"townName":"Gamma","streets":["Main Street","Elm Street"],"buildings":92,"rules":"",
            "npcs":[
             {"id":"a","name":"Lois Keller","first":"Lois","age":85,"sex":"F","warmth":0.7,"building":"b0","buildingName":"Keller's","street":0,"index":0,
              "row":null,"rowIndex":null,"lot":"shop:89,122","neighbourhood":"old-town","neighbourhoodName":"Old Town","role":"owner","job":"owner","topics":[$topic]},
             {"id":"b","name":"Al Moss","first":"Al","age":40,"sex":"M","warmth":0.4,"building":"b40","buildingName":"The Moss home","street":-1,"index":-1,
              "row":7,"rowIndex":3,"lot":"small:20,34","neighbourhood":"woodside","neighbourhoodName":"Woodside","role":"head","job":"baker","topics":[$topic]},
             {"id":"c","name":"Bo Moss","first":"Bo","age":38,"sex":"F","warmth":0.6,"building":"b40","buildingName":"The Moss home","street":-1,"index":-1,
              "row":7,"rowIndex":3,"lot":"small:20,34","neighbourhood":"woodside","neighbourhoodName":"Woodside","role":"resident","job":"nurse","topics":[$topic]},
             {"id":"d","name":"Cy Rho","first":"Cy","age":50,"sex":"M","warmth":0.5,"building":"b41","buildingName":"Rho House","street":-1,"index":-1,
              "row":"7","rowIndex":1,"lot":"house:2,3","neighbourhood":"woodside","neighbourhoodName":"Woodside","role":"head","job":"retired","topics":[$topic]}
            ]}"""
        val d = DialogueData.parse(text)
        assertEquals(2, d.version)
        assertEquals(4, d.npcs.size)
        assertEquals(listOf("Al Moss", "Bo Moss"), d.at("b40").map { it.name })
        val keller = d.at("b0").single()
        assertNull(keller.row)
        assertEquals(-1, keller.rowIndex)
        assertEquals("old-town", keller.neighbourhood)
        assertEquals("Old Town", keller.neighbourhoodName)
        assertEquals("shop:89,122", keller.lot)
        val moss = d.at("b40").first()
        assertEquals(-1, moss.street)
        assertEquals("7", moss.row)
        assertEquals(3, moss.rowIndex)
        // a numeric and a string row id are the same row; in order along it
        assertEquals(listOf("Cy Rho", "Al Moss", "Bo Moss"), d.inRow("7").map { it.name })
        assertEquals(setOf("b0", "b40", "b41"), d.buildings)
    }
}

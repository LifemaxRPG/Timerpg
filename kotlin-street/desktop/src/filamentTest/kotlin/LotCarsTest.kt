import com.lifemax.rpg.feature.habits.presentation.scene3d.LotCarsProbe
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreCars
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreInfo
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKind
import com.lifemax.rpg.feature.habits.presentation.scene3d.StoreKit
import com.lifemax.rpg.feature.habits.presentation.scene3d.lotCarFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The stores' parked cars are our real cars (the owner's 2026-11-25 note: "let's replace all fake cars in our models
 * with real cars"; LotCars): gamma's superstore on its busiest hour (Saturday 3 pm), its cars packed as the town packs
 * them, at the mid and the far model. They are models of the year (in 2025 no 1920s car; in 1958 nothing newer than
 * 1958), in several models and colours, each model in each colour built once and shared, and the cost stays in bounds:
 * the batches (draws), the triangles near and far, the bytes. Numbers printed are the build server's, not the S25's.
 */
class LotCarsTest {
    private val assets = listOf(File("app/src/main/assets"), File("../app/src/main/assets")).first { it.isDirectory }

    @Test fun theLotsParkRealCarsOfTheirYear() {
        com.google.android.filament.Filament.init()
        lotCarFiles { p -> File(assets, "vehicles/$p").takeIf { it.exists() }?.readBytes() }
        try {
            val big = StoreInfo.sample(StoreKind.SUPERSTORE, 2025, seed = 4).copy(people = 215, width = 3364.571f, depth = 4361f)
            val built = StoreKit.buildWithSite(big, cars = false)
            val busy = big.copy(hour = 15.5f, weekday = 6)
            val spots = StoreCars.spots(busy, built.bays)
            val at = floatArrayOf(0f, 0f, 1f, 0f, 0f, -1f, 0f)
            val mid = LotCarsProbe.pack(spots, 2025, at, far = false)!!
            val far = LotCarsProbe.pack(spots, 2025, at, far = true)!!
            val boxes = StoreCars.boxes(busy, spots)
            val boxTris = boxes.triangles
            println("superstore, Saturday 3 pm: %d cars; mid models %d triangles in %d batches, %.1f MB; far %d triangles in %d batches, %.1f MB; the old boxes %d triangles in %d batches".format(
                spots.size, mid.triangles, mid.batchCount, mid.bytes / 1e6, far.triangles, far.batchCount, far.bytes / 1e6, boxTris, boxes.parts.size))
            assertEquals(spots.size, mid.cars)
            assertTrue("a busy lot (${spots.size})", spots.size > 100)
            // the models of 2025: no 1920s or 1930s car in a supermarket car park; and of 1958: nothing built after
            val y25 = LotCarsProbe.models(spots, 2025)
            assertTrue("2025: $y25", y25.none { it.startsWith("alderwick") || it.startsWith("fenmere") } && y25.size >= 3)
            val y58 = LotCarsProbe.models(spots, 1958)
            assertTrue("1958: $y58", y58.isNotEmpty() && y58.all { it.startsWith("alderwick") || it.startsWith("fenmere") || it.startsWith("northcairn") })
            assertTrue("several colours", LotCarsProbe.colours(spots, 2025) >= 6)
            // shared: each model and colour built once, whatever the number of cars
            assertTrue("meshes shared (${LotCarsProbe.cached()})", LotCarsProbe.cached() <= 2 * y25.size * 8 * 12)
            // the cost: within what a lot near the player can afford on the S25 (a far lot is a few thousand triangles)
            assertTrue("mid triangles ${mid.triangles}", mid.triangles < 260_000)
            assertTrue("far triangles ${far.triangles}", far.triangles < 40_000)
            assertTrue("mid batches ${mid.batchCount}", mid.batchCount < 400)
            assertTrue("mid bytes", mid.bytes < 24_000_000)
        } finally { lotCarFiles(null) }
    }

    @Test fun theCinemasCarParkAndTheStoresDriveToo() {
        com.google.android.filament.Filament.init()
        lotCarFiles { p -> File(assets, "vehicles/$p").takeIf { it.exists() }?.readBytes() }
        try {
            // the theater's car park: the same places as the boxes it drew, now handed to the town as real cars
            for (year in listOf(1952, 1972, 1988, 2015)) {
                val info = com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaKit.sample(year, "LYRIC", seed = 5)
                val boxes = com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaKit.build(info)
                val real = com.lifemax.rpg.feature.habits.presentation.scene3d.CinemaKit.build(info, realCars = true)
                assertTrue("$year: no boxes handed over", boxes.parked.isEmpty())
                assertTrue("$year: ${real.parked.size} cars", real.parked.isNotEmpty())
                assertTrue("$year: the boxes gone (${boxes.triangles} -> ${real.triangles})", real.triangles < boxes.triangles)
                val packed = LotCarsProbe.pack(real.parked, year, floatArrayOf(0f, 0f, 1f, 0f, 0f, -1f, 0f), far = false)!!
                assertEquals(real.parked.size, packed.cars)
                println("cinema $year: ${real.parked.size} real cars, ${packed.triangles} triangles near; models ${LotCarsProbe.models(real.parked, year)}")
            }
            // a general store's owner's truck on its drive
            val g = StoreInfo.sample(StoreKind.GENERAL, 1962, seed = 2)
            assertTrue(StoreKit.buildWithSite(g, cars = false).parked.size == 1 && StoreKit.buildWithSite(g).parked.isEmpty())
        } finally { lotCarFiles(null) }
    }
}

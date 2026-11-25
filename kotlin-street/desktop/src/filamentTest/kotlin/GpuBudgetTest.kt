import com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownBudget
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The town's GPU memory budget (MEMORY_AUDIT.md, "The memory budget guard"): the capital (the app's gamma) built whole as
 * the open-world preview builds it (the game's own FilamentTown, yards with their far versions, farms, landmarks, the
 * rail), then every part of Filament's own account ([GpuAccount.categories], within ~2% of the phone's) against its
 * budget in [TownBudget]. A part over its budget fails, naming it, with the table and the account's owners printed: the
 * room the dialogue model's NPU copy needs on the phone is kept by this test, not found missing on the S25.
 *
 * gradle :desktop:crossroadsTest --tests GpuBudgetTest (about a minute on the build server).
 */
class GpuBudgetTest {
    @Test fun theCapitalStaysWithinItsGpuBudget() {
        // (only the build and its account: no views rendered)
        System.setProperty("gpuOnly", "1")
        // (the whole capital held at once, streaming off: the budget of what the town is made of; GpuRouteTest guards
        // what is resident with streaming on)
        System.setProperty("stream", "0")
        GpuAccount.reset()
        var rows: List<TownBudget.Row>? = null
        openBuilt = { ft -> rows = TownBudget.check(GpuAccount.categories(ft.gpuEstimate(2340, 1080))) }
        val out = File("build/gpu-budget").also { it.mkdirs() }
        try { renderFilamentOpen(out.path) } finally { openBuilt = null; System.clearProperty("gpuOnly"); System.clearProperty("stream") }
        val r = rows ?: error("the town was not built")
        println("GPU budget, the capital (desktop, Filament's own count):\n" + TownBudget.table(r))
        for (row in r) assertTrue("${row.part} was not counted", row.mb != null)
        val over = r.filter { it.over }
        if (over.isNotEmpty()) {
            println(GpuAccount.table(null))
            fail("over the GPU budget: " + over.joinToString { "%s %.1f MB (budget %.0f, over by %.1f)".format(java.util.Locale.US, it.part, it.mb, it.budget, it.mb!! - it.budget) } +
                " (TownBudget.kt: lower what grew, or raise its budget on purpose with the reason)")
        }
    }
}

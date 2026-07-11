package dev.agentic.data.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// LogNoise drops framework spam (e.g. Samsung Android 16's 92.5% setRequestedFrameRate INFO spam) before it hits the capture file.
class LogNoiseTest {

    @Test
    fun `samsung setRequestedFrameRate spam is noise`() {
        val line = "07-09 22:52:25.628 10336 10336 I View    : setRequestedFrameRate frameRate=NaN, " +
            "this=androidx.compose.ui.platform.AndroidComposeView{cccfbb9 VFED..... ........ 0,0-1968,2184}, " +
            "caller=android.view.ViewGroup.setRequestedFrameRate:10045"
        assertTrue(LogNoise.isNoise(line))
    }

    @Test
    fun `app log lines are kept`() {
        val lines = listOf(
            "07-09 22:29:12.100 10336 10336 D Nav     : wide normalize: Session(abc123) → Home",
            "07-09 22:29:10.000 10336 10336 I Auth    : login ok @ https://example.com",
            "07-09 22:29:11.000 10336 10412 I WS      : connected",
        )
        lines.forEach { assertFalse("should keep: $it", LogNoise.isNoise(it)) }
    }

    @Test
    fun `crash stack lines are kept`() {
        val lines = listOf(
            "07-09 17:11:30.000 10336 10336 E AndroidRuntime: FATAL EXCEPTION: main",
            "07-09 17:11:30.001 10336 10336 E AndroidRuntime: android.os.NetworkOnMainThreadException",
            "07-09 17:11:30.002 10336 10336 E AndroidRuntime: \tat okhttp3.internal.Util.closeQuietly(Util.kt:505)",
        )
        lines.forEach { assertFalse("should keep: $it", LogNoise.isNoise(it)) }
    }

    @Test
    fun `other View tag lines are kept`() {
        // Only the frameRate spam is filtered — a genuine View warning must survive.
        val line = "07-09 22:30:00.000 10336 10336 W View    : requestLayout() improperly called"
        assertFalse(LogNoise.isNoise(line))
    }
}

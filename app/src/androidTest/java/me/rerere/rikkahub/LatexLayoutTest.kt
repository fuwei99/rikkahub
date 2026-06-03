package me.rerere.rikkahub

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

@RunWith(AndroidJUnit4::class)
class LatexLayoutTest {
    @Test
    fun testLatexMetrics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        JLatexMathAndroid.init(context)

        val testFormulas = listOf("x", "\\beta", "\\lim_{n \\to \\infty}", "\\frac{a}{b}")
        val fontSize = 48.0f // Large font size to make metrics easy to read
        val deltaRatio = 0.35f // Correct visual center ratio for standard Android fonts

        Log.d("LatexTest", "----------------------------------------")
        for (formula in testFormulas) {
            val drawable = JLatexMathDrawable.builder(formula)
                .textSize(fontSize)
                .padding(0)
                .build()
            
            val icon = drawable.icon()
            val depth = icon.iconDepth.toFloat()
            val height = icon.iconHeight.toFloat()
            val ascent = height - depth
            
            val delta = deltaRatio * fontSize
            val hBox = 2 * maxOf(ascent - delta, depth + delta)
            val yOffset = 0.5f * hBox + delta - ascent
            
            // Validate: formula's baseline must align perfectly with text baseline
            val textBaseline = 0.5f * hBox + delta
            val formulaBaseline = yOffset + ascent
            val diff = formulaBaseline - textBaseline
            
            Log.d("LatexTest", "Formula: $formula")
            Log.d("LatexTest", "  ascent = $ascent, depth = $depth, totalHeight = $height")
            Log.d("LatexTest", "  hBox   = $hBox, yOffset = $yOffset")
            Log.d("LatexTest", "  Formula Baseline = $formulaBaseline, Text Baseline = $textBaseline (Diff = $diff)")
            Log.d("LatexTest", "----------------------------------------")
        }
    }
}

package com.example.cameratranslator

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/** Yazının küçülebileceği en küçük boyut (sp): bundan sonra sığmasa da okunur kalsın. */
private const val MIN_FIT_FONT_SIZE = 14f

/**
 * Verilen alana sığan altyazı: önce [maxFontSize] denenir, satırlar alanın yüksekliğini aşıyorsa
 * yazı sığana kadar küçültülür. Alan telefonun ekranına ve kontrollere göre belirlendiği için
 * her telefonda (S23 Ultra, S21 FE …) yazı ekrandan ya da kontrollerin altına taşmaz.
 */
@Composable
fun AutoFitText(
    text: String,
    maxFontSize: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    lineHeightFactor: Float = 1.15f,
    fontWeight: FontWeight = FontWeight.Bold,
    contentAlignment: Alignment = Alignment.Center,
    textModifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier, contentAlignment = contentAlignment) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val fontSize = remember(text, maxFontSize, widthPx, heightPx, lineHeightFactor, fontWeight) {
            if (widthPx == Constraints.Infinity || heightPx == Constraints.Infinity) return@remember maxFontSize
            var size = maxFontSize
            while (size > MIN_FIT_FONT_SIZE) {
                val result = measurer.measure(
                    text = text,
                    style = subtitleStyle(size, lineHeightFactor, fontWeight),
                    constraints = Constraints(maxWidth = widthPx)
                )
                if (result.size.height <= heightPx) break
                size -= 1f
            }
            size.coerceAtLeast(MIN_FIT_FONT_SIZE)
        }
        Text(
            text = text,
            color = color,
            style = subtitleStyle(fontSize, lineHeightFactor, fontWeight),
            modifier = textModifier
        )
    }
}

private fun subtitleStyle(size: Float, lineHeightFactor: Float, fontWeight: FontWeight) = TextStyle(
    fontSize = size.sp,
    fontWeight = fontWeight,
    textAlign = TextAlign.Center,
    lineHeight = (size * lineHeightFactor).sp
)

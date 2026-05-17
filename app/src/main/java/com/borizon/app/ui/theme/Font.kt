package com.borizon.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.borizon.app.R

/**
 * Borizon font family — DM Sans.
 * Warm geometric humanist typeface (SIL Open Font License).
 * Creates a contemplative, intimate feel that matches the Warm Dusk palette.
 */
val BorizonFontFamily = FontFamily(
    Font(R.font.dm_sans_light, FontWeight.Light),
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
    Font(R.font.dm_sans_semi_bold, FontWeight.SemiBold),
    Font(R.font.dm_sans_bold, FontWeight.Bold),
)

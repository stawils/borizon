package com.borizon.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText

@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    RichText(modifier = modifier) {
        Markdown(markdown)
    }
}

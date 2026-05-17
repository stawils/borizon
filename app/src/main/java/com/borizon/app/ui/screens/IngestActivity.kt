package com.borizon.app.ui.screens

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity that receives shared content from other apps via Android Share Sheet.
 * Handles text/plain and image MIME types.
 *
 * It passes content to MainActivity which opens a chat conversation
 * with the content pre-loaded for LLM analysis.
 */
class IngestActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INGEST_TEXT = "ingest_text"
        const val EXTRA_INGEST_SOURCE = "ingest_source"
        const val EXTRA_INGEST_IMAGE_URI = "ingest_image_uri"
        private const val MAX_TEXT_LENGTH = 50_000
        private const val MAX_IMAGE_BYTES = 20_000_000L // 20 MB
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSend()
            else -> {
                Toast.makeText(this, "Unsupported action", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleSend() {
        when (intent.type) {
            "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Shared text"
                if (text.isNotBlank() && text.length <= MAX_TEXT_LENGTH) {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra(EXTRA_INGEST_TEXT, text)
                        putExtra(EXTRA_INGEST_SOURCE, subject)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else if (text.length > MAX_TEXT_LENGTH) {
                    Toast.makeText(this, "Text too large to process", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "No text content received", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            "image/png", "image/jpeg" -> {
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    val size = try {
                        contentResolver.openInputStream(uri)?.use { it.available().toLong() } ?: 0L
                    } catch (_: Exception) { 0L }
                    if (size > MAX_IMAGE_BYTES) {
                        Toast.makeText(this, "Image too large to process", Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra(EXTRA_INGEST_IMAGE_URI, uri.toString())
                        putExtra(EXTRA_INGEST_SOURCE, "Shared image")
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "No image content received", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            else -> {
                Toast.makeText(this, "Unsupported content type: ${intent.type}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}

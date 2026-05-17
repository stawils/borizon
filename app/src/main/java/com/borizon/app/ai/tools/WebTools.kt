package com.borizon.app.ai.tools

import android.util.Log
import com.borizon.app.BuildConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit
import com.borizon.app.ai.tools.ToolCallTracker

/**
 * WebTools — combined web search and page reading.
 *
 * readWebPage strategy (2-tier):
 * 1. Direct HTTP fetch → regex-based HTML→text extraction (fast, works for most sites)
 * 2. If blocked or empty → Jina Reader API (headless Chrome rendering, free 20 RPM)
 *
 * Includes in-memory URL cache to avoid re-fetching the same page in a conversation.
 */
class WebTools(
    private val actionChannel: Channel<BorizonAction>,
    private val apiKeyProvider: () -> String,
) : ToolSet {

    companion object {
        private const val TAG = "WebTools"
        private const val BRAVE_BASE_URL = "https://api.search.brave.com/res/v1/web/search"
        private const val MAX_CONTENT_LENGTH = 1500

        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.113 Mobile Safari/537.36"

        /** Jina Reader: prepend to any URL to get clean markdown. Free, 20 RPM, no key needed. */
        private const val JINA_READER_PREFIX = "https://r.jina.ai/"

        /** In-memory cache: URL → fetched content. Evicted on clearCache(). */
        private val pageCache = java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 16
            }
        )

        fun clearCache() = pageCache.clear()

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /** Longer-timeout client for Jina Reader (renders JS via headless Chrome). */
        private val jinaClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val apiKey: String get() = apiKeyProvider()

    // ── Web Search ──────────────────────────────────────────────────

    @Tool(description = "Search the web.")
    fun webSearch(
        @ToolParam(description = "Search query") query: String,
    ): Map<String, String> {
        ToolCallTracker.increment()
        if (BuildConfig.DEBUG) Log.d(TAG, "webSearch called: $query")
        if (apiKey.isBlank()) {
            return mapOf("error" to "Web search not configured. No API key set.")
        }

        actionChannel.trySend(BorizonAction.Progress(
            label = "Searching: \"$query\"",
            isInProgress = true,
            toolType = ToolType.WEB_SEARCH,
        ))

        return try {
            val cleanQuery = query
                .replace(Regex("<think[\\s\\S]*?</think\\s*>"), "")
                .replace(Regex("[\\{\\}\\[\\]\\n\\r]+"), " ")
                .trim()
                .take(200)
                .takeIf { it.isNotBlank() } ?: query.trim().take(200)

            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "$BRAVE_BASE_URL?q=$encodedQuery&count=3&search_lang=en"
            val request = Request.Builder()
                .url(url)
                .header("X-Subscription-Token", apiKey)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val code = response.code
                if (BuildConfig.DEBUG) Log.d(TAG, "Search API error $code")
                actionChannel.trySend(BorizonAction.Progress(
                    label = "Search failed (HTTP $code)",
                    isInProgress = false,
                    toolType = ToolType.WEB_SEARCH,
                ))
                return mapOf("error" to "Search failed (HTTP $code).")
            }

            val body = response.body?.string() ?: run {
                actionChannel.trySend(BorizonAction.Progress(
                    label = "Empty search response",
                    isInProgress = false,
                    toolType = ToolType.WEB_SEARCH,
                ))
                return mapOf("error" to "Empty response from search API.")
            }

            val (resultCount, formattedResults) = parseSearchResults(body)

            if (resultCount == 0) {
                actionChannel.trySend(BorizonAction.Progress(
                    label = "No results",
                    isInProgress = false,
                    toolType = ToolType.WEB_SEARCH,
                ))
                return mapOf("results" to "No results for \"$query\".")
            }

            actionChannel.trySend(BorizonAction.Progress(
                label = "Found $resultCount results",
                isInProgress = false,
                toolType = ToolType.WEB_SEARCH,
            ))

            mapOf("results" to formattedResults)
        } catch (e: Exception) {
            Log.e(TAG, "webSearch failed", e)
            actionChannel.trySend(BorizonAction.Progress(
                label = "Search failed",
                isInProgress = false,
                toolType = ToolType.WEB_SEARCH,
            ))
            mapOf("error" to "Search failed: ${e.message}")
        }
    }

    // ── Read Web Page ───────────────────────────────────────────────

    @Tool(description = "Read a web page.")
    fun readWebPage(
        @ToolParam(description = "URL to read") url: String,
    ): Map<String, String> {
        ToolCallTracker.increment()
        if (BuildConfig.DEBUG) Log.d(TAG, "readWebPage called: $url")

        // Validate scheme
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme !in listOf("http", "https")) {
            return mapOf("error" to "Only http/https URLs supported.")
        }

        // Validate URL & block private networks
        try {
            val parsed = java.net.URL(url)
            val host = parsed.host ?: return mapOf("error" to "Invalid URL")
            val resolved = InetAddress.getByName(host)
            if (resolved.isLoopbackAddress || resolved.isLinkLocalAddress ||
                resolved.isSiteLocalAddress || resolved.isAnyLocalAddress
            ) {
                return mapOf("error" to "Private URLs not allowed.")
            }
        } catch (_: Exception) {
            return mapOf("error" to "Invalid URL format.")
        }

        // Check cache — avoid re-fetching the same URL
        pageCache[url]?.let { cached ->
            if (BuildConfig.DEBUG) Log.d(TAG, "readWebPage cache HIT: $url (${cached.length} chars)")
            actionChannel.trySend(BorizonAction.Progress(
                label = "Read ${cached.length} chars (cached)",
                isInProgress = false,
                toolType = ToolType.WEB_READ,
            ))
            return mapOf("content" to cached, "url" to url)
        }

        actionChannel.trySend(BorizonAction.Progress(
            label = "Reading page...",
            isInProgress = true,
            toolType = ToolType.WEB_READ,
            detailDescription = url.take(80),
        ))

        return try {
            fetchAndExtract(url)
        } catch (e: Exception) {
            Log.e(TAG, "readWebPage failed for $url", e)
            actionChannel.trySend(BorizonAction.Progress(
                label = "Page read failed",
                isInProgress = false,
                toolType = ToolType.WEB_READ,
            ))
            mapOf("error" to "Failed to read page: ${e.message}")
        }
    }

    /**
     * Single entry point for fetching + extracting content.
     * Tries direct fetch first, falls back to Jina Reader if needed.
     */
    private fun fetchAndExtract(url: String): Map<String, String> {
        // For known JS-heavy sites, skip direct fetch and go straight to Jina Reader.
        // Direct fetch wastes 15+ seconds on these sites and always produces empty extraction.
        val jsHeavyHosts = listOf("twitter.com", "x.com", "instagram.com", "facebook.com",
            "reddit.com", "cnn.com", "bbc.com", "nytimes.com", "theguardian.com",
            "bloomberg.com", "washingtonpost.com", "reuters.com")
        val host = try { java.net.URL(url).host?.lowercase() } catch (_: Exception) { "" }
        val isJsHeavy = jsHeavyHosts.any { host == it || host?.endsWith(".$it") == true }

        if (isJsHeavy) {
            if (BuildConfig.DEBUG) Log.d(TAG, "readWebPage: skipping direct fetch for JS-heavy site $host, using Jina Reader")
            return tryFetchViaJina(url)
        }

        // Tier 1: Direct HTTP fetch
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        var response = httpClient.newCall(request).execute()

        // Retry once on 5xx
        if (response.code >= 500) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Retrying after ${response.code}")
            response.close()
            Thread.sleep(500)
            response = httpClient.newCall(request).execute()
        }

        // 4xx or still 5xx → try Jina
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (BuildConfig.DEBUG) Log.d(TAG, "Direct fetch failed ($code), trying Jina Reader")
            return tryFetchViaJina(url)
        }

        // Skip non-text content types
        val contentType = response.header("Content-Type", "")?.lowercase() ?: ""
        if (contentType.contains("application/pdf") || contentType.contains("image/") ||
            contentType.contains("video/") || contentType.contains("audio/")) {
            response.close()
            actionChannel.trySend(BorizonAction.Progress(
                label = "Unsupported content type",
                isInProgress = false,
                toolType = ToolType.WEB_READ,
            ))
            return mapOf("error" to "Unsupported content type. Only text pages can be read.")
        }

        val rawBody = response.body?.string()?.takeIf { it.isNotBlank() }
        response.close()

        if (rawBody == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Empty body, trying Jina Reader")
            return tryFetchViaJina(url)
        }

        val content = extractTextContent(rawBody)
        if (BuildConfig.DEBUG) Log.d(TAG, "readWebPage: direct extraction = ${content.length} chars from ${rawBody.length} byte HTML")

        // If extraction produced very little, the page is likely JS-rendered → Jina fallback
        if (content.length < 50 && rawBody.length > 500) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Extraction too short (${content.length} chars), trying Jina Reader")
            return tryFetchViaJina(url)
        }

        // Cache and return
        pageCache[url] = content
        actionChannel.trySend(BorizonAction.Progress(
            label = "Read ${content.length} chars",
            isInProgress = false,
            toolType = ToolType.WEB_READ,
        ))
        return mapOf("content" to content, "url" to url)
    }

    /**
     * Fetch page content via Jina Reader API.
     * Uses headless Chrome to render JS, returns clean markdown.
     * Free tier: 20 RPM, no API key needed.
     */
    private fun tryFetchViaJina(url: String): Map<String, String> {
        // No separate progress event — this is a continuation of readWebPage, not a new tool call

        return try {
            val jinaUrl = JINA_READER_PREFIX + url
            val request = Request.Builder()
                .url(jinaUrl)
                .header("Accept", "text/plain")
                .header("x-max-tokens", "2000")
                .header("x-retain-images", "none")
                .header("x-retain-links", "text")
                .build()

            val response = jinaClient.newCall(request).execute()

            if (!response.isSuccessful) {
                response.close()
                if (BuildConfig.DEBUG) Log.d(TAG, "Jina Reader failed: ${response.code}")
                actionChannel.trySend(BorizonAction.Progress(
                    label = "Page read failed (${response.code})",
                    isInProgress = false,
                    toolType = ToolType.WEB_READ,
                ))
                return mapOf("error" to "Could not read page (HTTP ${response.code}).")
            }

            val body = response.body?.string()?.takeIf { it.isNotBlank() }
            response.close()

            if (body == null) {
                actionChannel.trySend(BorizonAction.Progress(
                    label = "Empty page",
                    isInProgress = false,
                    toolType = ToolType.WEB_READ,
                ))
                return mapOf("error" to "Page returned empty content.")
            }

            // Jina returns markdown with a Title/URL header — extract content section
            val content = extractJinaContent(body)

            // Cache and return
            pageCache[url] = content
            actionChannel.trySend(BorizonAction.Progress(
                label = "Read ${content.length} chars",
                isInProgress = false,
                toolType = ToolType.WEB_READ,
            ))
            mapOf("content" to content, "url" to url)
        } catch (e: Exception) {
            Log.e(TAG, "Jina Reader failed for $url", e)
            actionChannel.trySend(BorizonAction.Progress(
                label = "Page read failed",
                isInProgress = false,
                toolType = ToolType.WEB_READ,
            ))
            mapOf("error" to "Failed to read page: ${e.message}")
        }
    }

    // ── HTML Content Extraction ─────────────────────────────────────

    /**
     * Extract readable text from raw HTML using regex-based pipeline.
     * Tries to preserve some structure (headings, lists) for the small model.
     */
    private fun extractTextContent(html: String): String {
        // 1. Remove invisible / boilerplate elements
        var text = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<nav[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<footer[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<aside[\\s\\S]*?</aside>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<header[\\s\\S]*?</header>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<!--[\\s\\S]*?-->", RegexOption.IGNORE_CASE), "")
            // Cookie banners, consent dialogs, ad containers
            .replace(Regex("<[^>]*(?:cookie|consent|banner|popup|modal|overlay|ad[-_]?(?:container|wrapper)|social|share|newsletter)[^>]*>[\\s\\S]*?</\\w+\\s*>", RegexOption.IGNORE_CASE), "")

        // 2. Extract main content area if available
        val mainContent = extractTagContent(text, "article")
            ?: extractTagContent(text, "main")
            ?: extractDivByRole(text, "main")
            ?: extractDivByClass(text, "post", "article", "content", "entry")
            ?: text

        // 3. Convert semantic HTML to lightweight structured text
        val structured = mainContent
            // Headings → uppercase markers
            .replace(Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE)) { m ->
                "\n# ${m.groupValues[1].stripTags().trim()}\n"
            }
            .replace(Regex("<h2[^>]*>([\\s\\S]*?)</h2>", RegexOption.IGNORE_CASE)) { m ->
                "\n## ${m.groupValues[1].stripTags().trim()}\n"
            }
            .replace(Regex("<h3[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE)) { m ->
                "\n### ${m.groupValues[1].stripTags().trim()}\n"
            }
            .replace(Regex("<h[4-6][^>]*>([\\s\\S]*?)</h[4-6]>", RegexOption.IGNORE_CASE)) { m ->
                "\n${m.groupValues[1].stripTags().trim()}\n"
            }
            // List items → bullet points
            .replace(Regex("<li[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)) { m ->
                "\n- ${m.groupValues[1].stripTags().trim()}"
            }
            // Paragraphs / divs → newlines
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(p|div|blockquote|section|ul|ol|dl|table|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            // Links → keep link text
            .replace(Regex("<a[^>]*href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)) { m ->
                val linkText = m.groupValues[2].stripTags().trim()
                linkText
            }

        // 4. Strip remaining tags and decode entities
        val stripped = structured
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;|&#160;"), " ")
            .replace(Regex("&amp;|&#38;"), "&")
            .replace(Regex("&lt;|&#60;"), "<")
            .replace(Regex("&gt;|&#62;"), ">")
            .replace(Regex("&quot;|&#34;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("&#x[0-9a-fA-F]+;"), "")
            .replace(Regex("&#\\d+;"), "")
            // Normalize whitespace
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n[ \\t]+\\n"), "\n\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        return truncateAtBoundary(stripped, MAX_CONTENT_LENGTH)
    }

    /**
     * Extract content from Jina Reader markdown response.
     * Strips the "Title:" and "URL Source:" header, returns body.
     */
    private fun extractJinaContent(markdown: String): String {
        var content = markdown
            .replace(Regex("^Title:.*\\n", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^URL Source:.*\\n", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^Published Time:.*\\n", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^Markdown Content:\\s*\\n?", RegexOption.IGNORE_CASE), "")
            .trim()

        return truncateAtBoundary(content, MAX_CONTENT_LENGTH)
    }

    /** Truncate at sentence or word boundary near the limit. */
    private fun truncateAtBoundary(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val truncated = text.take(maxLen)
        val searchStart = (maxLen * 0.7).toInt()
        val lastPeriod = truncated.lastIndexOf('.', searchStart)
        val breakAt = if (lastPeriod > searchStart) {
            lastPeriod + 1
        } else {
            truncated.lastIndexOf(' ').takeIf { it > searchStart } ?: maxLen
        }
        return truncated.take(breakAt).trimEnd() + "..."
    }

    // ── HTML Tag Extractors ─────────────────────────────────────────

    private fun extractTagContent(html: String, tag: String): String? {
        val regex = Regex("<$tag[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
    }

    private fun extractDivByRole(html: String, role: String): String? {
        val regex = Regex("<div[^>]*role\\s*=\\s*[\"']$role[\"'][^>]*>([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
    }

    private fun extractDivByClass(html: String, vararg classKeywords: String): String? {
        val pattern = classKeywords.joinToString("|")
        val regex = Regex("<div[^>]*class\\s*=\\s*[\"'][^\"']*\\b(?:$pattern)\\b[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
    }

    // ── Search Result Parsing ───────────────────────────────────────

    private fun parseSearchResults(jsonBody: String): Pair<Int, String> {
        val json = JSONObject(jsonBody)
        val webResults = json.optJSONObject("web") ?: return Pair(0, "")
        val results = webResults.optJSONArray("results") ?: return Pair(0, "")

        val sb = StringBuilder()
        var count = 0

        for (i in 0 until minOf(results.length(), 3)) {
            val result = results.getJSONObject(i)
            val title = result.optString("title", "")
            val url = result.optString("url", "")
            val description = result.optString("description", "")

            if (title.isNotBlank()) {
                count++
                sb.append("$count. **${title.take(80)}**\n")
                if (description.isNotBlank()) sb.append("   ${description.take(120)}\n")
                if (url.isNotBlank()) sb.append("   $url\n")
                sb.append("\n")
            }
        }

        return Pair(count, sb.toString().trim())
    }

    private fun String.stripTags(): String = replace(Regex("<[^>]+>"), "")
}

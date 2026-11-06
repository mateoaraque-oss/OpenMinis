package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * `web_search` — the tool OpenMinis does not ship.
 *
 * The app can already drive a WebView (`browser_use`), but making the agent
 * navigate to a search engine, wait for the page, scrape the result list and
 * then open each candidate is slow, fragile (consent walls, captchas) and
 * expensive in tokens. This tool does the one thing the agent actually wants:
 * give it ranked results as text.
 *
 * Backend: DuckDuckGo's HTML endpoint. Chosen because it needs no API key and
 * no account, which matters for an app whose whole model is BYO-everything —
 * asking the user to register for a search API before their assistant can
 * search would be a worse product. The parser is deliberately tolerant: if
 * DuckDuckGo changes its markup the tool returns a clear failure instead of
 * silently inventing results.
 *
 * If you later want higher-quality results, swap [searchDuckDuckGo] for
 * Brave / Tavily / SearXNG and keep the schema. The model contract is the
 * stable part.
 */
object WebSearchTool {

    const val NAME = "web_search"

    private const val MAX_RESULTS = 10
    private const val TIMEOUT_SECONDS = 20L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the public web and get ranked results (title, URL, snippet) without opening a browser. " +
            "Use this FIRST for any question about current events, facts you are unsure about, prices, docs, " +
            "releases, or anything that may have changed after your training cutoff. " +
            "Then use `browser_use` with `action=navigate` + `get_readable` on the most promising URL when you " +
            "need the full page. Prefer specific queries (\"nubia neo 3 5g chipset\") over vague ones.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Search Nubia Neo 3 specs'). Use the same language as the user.",
            ),
            "query" to AgentToolParam(
                "string",
                "The search query. Write it the way a person would type it into a search box.",
            ),
            "count" to AgentToolParam(
                "integer",
                "How many results to return, 1-$MAX_RESULTS. Default 5.",
            ),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "count"),
    )

    suspend fun execute(argsJson: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return@withContext ToolExecutionResult(
                "web_search: arguments are not valid JSON (${it.message}).",
                false,
            )
        }
        val query = args.optString("query", "").trim()
        if (query.isEmpty()) {
            return@withContext ToolExecutionResult(
                "web_search: `query` is required and must not be empty.",
                false,
            )
        }
        val count = args.optInt("count", 5).coerceIn(1, MAX_RESULTS)

        runCatching { searchDuckDuckGo(query, count) }
            .fold(
                onSuccess = { hits ->
                    if (hits.isEmpty()) {
                        ToolExecutionResult(
                            "No results for \"$query\". Try a shorter or differently worded query.",
                            true,
                        )
                    } else {
                        val body = buildString {
                            append("Results for \"").append(query).append("\":\n\n")
                            hits.forEachIndexed { i, hit ->
                                append(i + 1).append(". ").append(hit.title).append('\n')
                                append("   ").append(hit.url).append('\n')
                                if (hit.snippet.isNotBlank()) {
                                    append("   ").append(hit.snippet).append('\n')
                                }
                                append('\n')
                            }
                        }
                        ToolExecutionResult(body, true)
                    }
                },
                onFailure = { e ->
                    ToolExecutionResult(
                        "web_search failed: ${e.message ?: e.javaClass.simpleName}. " +
                            "Check the connection, or fall back to browser_use.",
                        false,
                    )
                },
            )
    }

    private data class Hit(val title: String, val url: String, val snippet: String)

    private fun searchDuckDuckGo(query: String, count: Int): List<Hit> {
        // POST /html/ is the shape DuckDuckGo's own no-JS page uses; GET works
        // too but some exit nodes get a consent page on GET only.
        val body = "q=${java.net.URLEncoder.encode(query, "UTF-8")}&kl=wt-wt"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} from DuckDuckGo")
            }
            val html = response.body?.string().orEmpty()
            val parsed = parseHtmlResults(html, count)
            if (parsed.isNotEmpty()) return parsed
            // Different markup on some regional edges: try the lite page.
            return parseLiteResults(fetchLite(query), count)
        }
    }

    private fun fetchLite(query: String): String {
        val body = "q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder()
            .url("https://lite.duckduckgo.com/lite/")
            .header("User-Agent", USER_AGENT)
            .post(body)
            .build()
        return client.newCall(request).execute().use { it.body?.string().orEmpty() }
    }

    private val anchorRegex = Regex(
        """<a[^>]+class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val snippetRegex = Regex(
        """<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val liteLinkRegex = Regex(
        """<a[^>]+class="result-link"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val liteSnippetRegex = Regex(
        """<td[^>]+class="result-snippet"[^>]*>(.*?)</td>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private fun parseHtmlResults(html: String, count: Int): List<Hit> {
        val links = anchorRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.take(count).mapIndexed { i, m ->
            Hit(
                title = clean(m.groupValues[2]).ifBlank { "(untitled)" },
                url = decodeDuckDuckGoUrl(m.groupValues[1]),
                snippet = snippets.getOrNull(i).orEmpty(),
            )
        }.filter { it.url.startsWith("http") }
    }

    private fun parseLiteResults(html: String, count: Int): List<Hit> {
        val links = liteLinkRegex.findAll(html).toList()
        val snippets = liteSnippetRegex.findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.take(count).mapIndexed { i, m ->
            Hit(
                title = clean(m.groupValues[2]).ifBlank { "(untitled)" },
                url = decodeDuckDuckGoUrl(m.groupValues[1]),
                snippet = snippets.getOrNull(i).orEmpty(),
            )
        }.filter { it.url.startsWith("http") }
    }

    /**
     * DuckDuckGo wraps every outbound hit in `/l/?uddg=<percent-encoded URL>`.
     * Return the real destination so the model can pass it straight to
     * browser_use.
     */
    private fun decodeDuckDuckGoUrl(raw: String): String {
        val absolute = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://duckduckgo.com$raw"
            else -> raw
        }
        val marker = "uddg="
        val i = absolute.indexOf(marker)
        if (i < 0) return absolute
        val encoded = absolute.substring(i + marker.length).substringBefore('&')
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(absolute)
    }

    /** Strip tags and decode the handful of entities search snippets use. */
    private fun clean(raw: String): String = raw
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
}

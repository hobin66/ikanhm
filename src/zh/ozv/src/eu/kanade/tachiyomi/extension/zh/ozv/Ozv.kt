package eu.kanade.tachiyomi.extension.zh.ozv

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Ozv : HttpSource() {
    override val name = "\u7f8e\u5973\u79c1\u623f\u9986"
    override val baseUrl = "https://ozv.me"
    override val lang = "zh"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", DESKTOP_USER_AGENT)

    // Keep popular mapped to the site's "Guess You Like" section.
    override fun popularMangaRequest(page: Int): Request = GET(buildListUrl(ListMode.GUESS, page), headers)

    override fun popularMangaParse(response: Response): MangasPage = parseListPage(response)

    override fun latestUpdatesRequest(page: Int): Request = GET(buildListUrl(ListMode.LATEST, page), headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseListPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val mode = filters.filterIsInstance<SectionFilter>()
            .firstOrNull()
            ?.selectedMode()
            ?: ListMode.LATEST

        val requestUrl = buildListUrl(mode, page)

        val requestHeaders = headers.newBuilder().apply {
            if (query.isNotBlank()) {
                set(SEARCH_QUERY_HEADER, query.trim())
            }
        }.build()

        return GET(requestUrl, requestHeaders)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseListPage(response)

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("\u53ef\u9009\u5207\u6362\u9996\u9875\u5206\u533a\uff1a\u6700\u65b0 / \u731c\u4f60\u559c\u6b22 / \u5168\u7ad9\u70ed\u95e8 / \u7ad9\u53cb\u63a8\u8350"),
        Filter.Separator(),
        SectionFilter(),
        Filter.Separator(),
        Filter.Header("\u5173\u952e\u8bcd\u4ec5\u5728\u5f53\u524d\u5206\u533a\u6807\u9898\u4e2d\u5339\u914d"),
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val pagePath = response.request.url.encodedPath
        val dateHint = parseDateHint(document)

        val parsedTitle = parseTitle(document)
        val title = parsedTitle.ifBlank { fallbackTitle(pagePath, dateHint) }

        val description = document.selectFirst("meta[name=description]")
            ?.attr("content")
            ?.trim()
            .orEmpty()
            .ifBlank { null }

        val genres = document.select("a[href*='/tag/'], a[href*='/category/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val pages = extractPageImages(document)

        return SManga.create().apply {
            this.title = title
            this.description = description
            this.thumbnail_url = pages.firstOrNull()
            this.genre = genres.joinToString(", ").ifBlank { null }
            this.status = SManga.COMPLETED
            this.update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            this.initialized = true
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val dateHint = parseDateHint(document)

        return listOf(
            SChapter.create().apply {
                name = if (dateHint.isNotBlank()) "Gallery ($dateHint)" else "Gallery"
                setUrlWithoutDomain(response.request.url.encodedPath)
            },
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val pages = extractPageImages(response.asJsoup())
        return pages.mapIndexed { index, imageUrl ->
            Page(index, "", imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    private fun parseListPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val query = response.request.header(SEARCH_QUERY_HEADER).orEmpty().trim()

        val mangas = document.select(CARD_SELECTOR)
            .mapNotNull(::mangaFromCard)
            .let { items ->
                if (query.isBlank()) {
                    items
                } else {
                    items.filter { it.title.contains(query, ignoreCase = true) }
                }
            }
            .distinctBy { it.url }

        val hasNextPage = hasNextPage(document, response.request.url.toString())
        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromCard(cardLink: Element): SManga? {
        val path = normalizeUrlPath(cardLink.attr("href"))
        if (path.isBlank()) return null

        val dateHint = cardLink.parent()?.let(::parseDateHint)
            .orEmpty()

        val title = cleanTitle(cardLink.attr("title"))
            .ifBlank { fallbackTitle(path, dateHint) }

        val thumbCandidate = cardLink.selectFirst("img[data-src], img[data-original], img[src]")
        val thumbnail = parseThumbnailUrl(thumbCandidate)

        return SManga.create().apply {
            setUrlWithoutDomain(path)
            this.title = title
            this.thumbnail_url = thumbnail
            this.status = SManga.COMPLETED
            this.update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }

    private fun parseTitle(document: Document): String {
        val ogTitle = cleanTitle(
            document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                .orEmpty(),
        )
        if (ogTitle.isNotBlank()) return ogTitle

        return cleanTitle(document.title())
    }

    private fun cleanTitle(raw: String): String {
        return raw.trim()
            .removeSuffix(SITE_TITLE_SUFFIX)
            .removeSuffix(SITE_TITLE_SUFFIX.removePrefix(" "))
            .trim()
    }

    private fun parseDateHint(node: Element): String {
        return node.selectFirst(".post-inner-item-meta-msg h3")
            ?.text()
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .substringBefore(" ")
            .trim()
    }

    private fun fallbackTitle(urlOrPath: String, dateHint: String): String {
        val id = POST_ID_REGEX.find(urlOrPath)?.groupValues?.getOrNull(1)

        return when {
            id != null && dateHint.isNotBlank() -> "$dateHint #$id"
            id != null -> "Post #$id"
            dateHint.isNotBlank() -> dateHint
            else -> "Untitled"
        }
    }

    private fun extractPageImages(document: Document): List<String> {
        return document.select(PAGE_IMAGE_SELECTOR)
            .mapNotNull { img ->
                val raw = img.attr("data-src")
                    .ifBlank { img.attr("src") }
                    .trim()
                toAbsoluteUrl(raw)
                    .takeIf { it.isNotBlank() }
                    ?.takeUnless { it.contains("loading", ignoreCase = true) }
            }
            .distinct()
    }

    private fun parseThumbnailUrl(image: Element?): String? {
        val raw = image?.attr("data-src")
            .orEmpty()
            .ifBlank { image?.attr("data-original").orEmpty() }
            .ifBlank { image?.attr("src").orEmpty() }
            .trim()

        if (raw.isBlank()) return null

        val absolute = toAbsoluteUrl(raw)
        if (absolute.isBlank()) return null

        val parsed = absolute.toHttpUrlOrNull()
        val original = if (parsed != null && parsed.encodedPath.endsWith("/timthumb.php")) {
            parsed.queryParameter("src").orEmpty()
        } else {
            ""
        }

        return if (original.isNotBlank()) toAbsoluteUrl(original) else absolute
    }

    private fun normalizeUrlPath(url: String): String {
        val raw = url.trim().substringBefore("#")
        if (raw.isEmpty() || raw.startsWith("javascript", ignoreCase = true)) return ""

        val noDomain = raw.removePrefix(baseUrl)
        return if (noDomain.startsWith("/")) noDomain else "/$noDomain"
    }

    private fun toAbsoluteUrl(url: String): String {
        val raw = url.trim()
        if (raw.isBlank()) return ""

        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> "$baseUrl/$raw"
        }
    }

    private fun hasNextPage(document: Document, currentUrl: String): Boolean {
        val currentPage = extractPageNumber(currentUrl) ?: 1

        val pageNumbers = document.select("a[href]")
            .mapNotNull { link ->
                val href = link.attr("href")
                if (href.isBlank()) return@mapNotNull null

                val absolute = toAbsoluteUrl(href)
                extractPageNumber(absolute)
            }

        return pageNumbers.any { it > currentPage }
    }

    private fun extractPageNumber(url: String): Int? {
        return PAGE_NUMBER_REGEX.find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun buildListUrl(mode: ListMode, page: Int): String {
        val normalizedPage = page.coerceAtLeast(1)
        val builder = baseUrl.toHttpUrl().newBuilder()

        if (normalizedPage > 1) {
            builder.addPathSegment("page")
            builder.addPathSegment(normalizedPage.toString())
        }

        when (mode) {
            ListMode.LATEST -> Unit
            ListMode.GUESS -> builder.addQueryParameter("orderby", "rand")
            ListMode.HOT -> builder.addQueryParameter("orderby", "hot")
            ListMode.RECOMMEND -> builder.addQueryParameter("orderby", "like")
        }

        return builder.build().toString()
    }

    private enum class ListMode {
        LATEST,
        GUESS,
        HOT,
        RECOMMEND,
    }

    private class SectionFilter : Filter.Select<String>(
        "\u9996\u9875\u5206\u533a",
        SECTION_OPTIONS.map { it.first }.toTypedArray(),
    ) {
        fun selectedMode(): ListMode = SECTION_OPTIONS.getOrNull(state)?.second ?: ListMode.LATEST
    }

    companion object {
        private const val SEARCH_QUERY_HEADER = "X-Ozv-Search-Query"
        private const val SITE_TITLE_SUFFIX = " - \u7f8e\u5973\u79c1\u623f\u83dc"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

        private const val CARD_SELECTOR =
            ".post-inner-item > a[data-id][href*='.html'], .post-inner-item > a[href*='.html']"

        private const val PAGE_IMAGE_SELECTOR = ".swiper.vertical .swiper-slide img.swiper-lazy"

        private val SECTION_OPTIONS = listOf(
            "\u6700\u65b0" to ListMode.LATEST,
            "\u731c\u4f60\u559c\u6b22" to ListMode.GUESS,
            "\u5168\u7ad9\u70ed\u95e8" to ListMode.HOT,
            "\u7ad9\u53cb\u63a8\u8350" to ListMode.RECOMMEND,
        )

        private val PAGE_NUMBER_REGEX = "/page/(\\d+)".toRegex()
        private val POST_ID_REGEX = "/(\\d+)\\.html".toRegex()
    }
}

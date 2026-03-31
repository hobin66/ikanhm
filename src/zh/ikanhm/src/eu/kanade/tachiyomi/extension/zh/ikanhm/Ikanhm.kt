package eu.kanade.tachiyomi.extension.zh.ikanhm

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Ikanhm : ParsedHttpSource() {
    override val name = "漫小肆韩漫 (Ikanhm)"
    override val baseUrl = "https://www.ikanhm.top"
    override val lang = "zh"
    override val supportsLatest = true

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/update?page=$page", headers)

    override fun latestUpdatesSelector() = "ul.mh-list li .mh-item, ul.manga-list-2 li"

    override fun latestUpdatesFromElement(element: Element): SManga = mangaFromElement(element)

    override fun latestUpdatesNextPageSelector() =
        "a#nextPage, a[title=下一页], a.page-next, a[rel=next], li.next a, a.paginate-btn[title*=下一]"

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/rank?page=$page", headers)

    override fun popularMangaSelector() =
        "ul.mh-list.top-cat .mh-item.horizontal, ul.mh-list.top-cat .mh-itme-top > a[href*='book/'], " +
            "ul.rank-list > a, ul.index-rank-list li"

    override fun popularMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?keyword=$encodedQuery&page=$page", headers)
    }

    override fun searchMangaSelector() = "ul.mh-list li .mh-item, ul.book-list li"

    override fun searchMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        manga.title = document.selectFirst("h1, .detail-main-info-title")?.text()?.trim() ?: ""
        manga.author = extractLabeledText(document, "作者", ".detail-main-info-author, p.subtitle, p, li, span, div")
        manga.description = document.selectFirst(
            "p.content, .detail-info p.intro, .comic-desc, .detail-desc, #detail-desc, .BookIntro",
        )?.text()?.trim()

        val statusText = document.body().text()
        manga.status = when {
            statusText.contains("连载中") || statusText.contains("連載中") || statusText.contains("連載") -> SManga.ONGOING
            statusText.contains("完结") || statusText.contains("完結") || statusText.contains("已完结") || statusText.contains("已完結") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        val tags = document.select("a[href*='tag='], .tag-item a, span.block a, .detail-main-info-class a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        manga.genre = tags.joinToString(", ")

        manga.thumbnail_url = extractImageUrl(
            document.selectFirst(
                ".banner_detail_form img, .detail-main-cover img, .detail-main-bg, .detail-cover img, " +
                    ".book-cover img, .bookdetail img, .detail img, img.cover",
            ),
        ).ifBlank { null }

        return manga
    }

    override fun chapterListSelector() =
        "ul#detail-list-select li a, ul.detail-list-select li a, ul.detail-list-1 li a, " +
            "ul.chapter-list li a, #chapterList li a, .chapter-list a, a.chapteritem"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.name = element.text().trim()
        normalizeUrlPath(element.attr("href"))
            .takeIf { it.isNotEmpty() }
            ?.let { chapter.setUrlWithoutDomain(it) }
        return chapter
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        val images = document.select(
            "#cp_img img, .view-main-1 img, div.comicpage img, div.comiclist img, .read-content img, #ChapterContenter img",
        )
        images.forEachIndexed { i, element ->
            val url = extractImageUrl(element)
            if (url.isNotEmpty() && !url.contains("loading")) {
                pages.add(Page(i, "", url))
            }
        }

        if (pages.isEmpty()) {
            val scriptContent = document.select("script").joinToString("\n") { it.html() }
            val listRegexes = listOf(
                Regex("""newimgs\s*=\s*\[([^\]]+)]"""),
                Regex("""imgArr\s*=\s*\[([^\]]+)]"""),
            )
            val itemRegex = Regex(""""([^"]+)"""")

            for (listRegex in listRegexes) {
                val match = listRegex.find(scriptContent) ?: continue
                itemRegex.findAll(match.groupValues[1]).forEachIndexed { i, m ->
                    val url = toAbsoluteUrl(m.groupValues[1])
                    if (url.isNotEmpty()) {
                        pages.add(Page(i, "", url))
                    }
                }
                if (pages.isNotEmpty()) break
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    private fun mangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = findBookLink(element)

        manga.title = extractTitle(element, link)
        normalizeUrlPath(link?.attr("href") ?: "")
            .takeIf { it.isNotEmpty() }
            ?.let { manga.setUrlWithoutDomain(it) }
        manga.thumbnail_url = extractImageUrl(element).ifBlank { null }

        return manga
    }

    private fun findBookLink(element: Element): Element? {
        if (element.tagName().equals("a", ignoreCase = true) && element.attr("href").contains("book/")) {
            return element
        }

        val links = element.select("a[href*='book/']")
        return links.firstOrNull {
            it.text().isNotBlank() && !it.text().contains("查看详情")
        } ?: links.firstOrNull()
    }

    private fun extractTitle(element: Element, link: Element?): String {
        return element.selectFirst(
            ".mh-item-detali .title a, .manga-list-2-title a, .book-list-info-title, " +
                ".rank-list-info-right-title, h2.title a, .title a",
        )?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: link?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: link?.text()?.trim().orEmpty()
    }

    private fun extractLabeledText(document: Document, label: String, selector: String): String? {
        return document.select(selector)
            .map { it.text().trim() }
            .firstOrNull { it.contains(label) }
            ?.substringAfter(label)
            ?.trim('：', ':', ' ')
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractImageUrl(element: Element?): String {
        if (element == null) return ""

        val direct = element.attr("data-original")
            .ifEmpty { element.attr("data-src") }
            .ifEmpty { element.attr("src") }
        if (direct.isNotEmpty()) return toAbsoluteUrl(direct)

        val nested = element.selectFirst("img[data-original], img[data-src], img[src]")
        val nestedUrl = nested?.attr("data-original").orEmpty()
            .ifEmpty { nested?.attr("data-src").orEmpty() }
            .ifEmpty { nested?.attr("src").orEmpty() }
        if (nestedUrl.isNotEmpty()) return toAbsoluteUrl(nestedUrl)

        val styleUrl = element.selectFirst("[style*='url('], .mh-cover")
            ?.attr("style")
            ?.substringAfter("url(")
            ?.substringBefore(")")
            ?.trim('\'', '"', ' ')
            .orEmpty()
        return toAbsoluteUrl(styleUrl)
    }

    private fun normalizeUrlPath(url: String): String {
        val raw = url.trim().substringBefore("#")
        if (raw.isEmpty() || raw.startsWith("javascript", ignoreCase = true)) return ""

        val noDomain = raw.removePrefix(baseUrl)
        return if (noDomain.startsWith("/")) noDomain else "/$noDomain"
    }

    private fun toAbsoluteUrl(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""

        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> "$baseUrl/$raw"
        }
    }
}

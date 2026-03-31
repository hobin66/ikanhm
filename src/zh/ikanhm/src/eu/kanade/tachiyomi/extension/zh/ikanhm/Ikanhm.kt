package eu.kanade.tachiyomi.extension.zh.ikanhm

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Ikanhm : ParsedHttpSource() {
    override val name = "\u6f2b\u5c0f\u8086"
    override val baseUrl = "https://www.ikanhm.top"
    override val lang = "zh"
    override val supportsLatest = true

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/rank?page=$page", headers)

    override fun popularMangaSelector() =
        "ul.mh-list.top-cat .mh-item.horizontal, ul.mh-list.top-cat .mh-itme-top > a[href*='book/'], " +
            "ul.rank-list > a, ul.index-rank-list li"

    override fun popularMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun popularMangaNextPageSelector() =
        "a#nextPage, a[title*=\u4e0b\u4e00], a.page-next, a[rel=next], li.next a, a.paginate-btn"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/update?page=$page", headers)

    override fun latestUpdatesSelector() = "ul.mh-list li .mh-item, ul.manga-list-2 li"

    override fun latestUpdatesFromElement(element: Element): SManga = mangaFromElement(element)

    override fun latestUpdatesNextPageSelector() =
        "a#nextPage, a[title*=\u4e0b\u4e00], a.page-next, a[rel=next], li.next a, a.paginate-btn"

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addPathSegment("search")
                addQueryParameter("keyword", query.trim())
                addQueryParameter("page", page.toString())
            } else {
                val tag = filters.filterIsInstance<TagFilter>().firstOrNull()?.toUriPart() ?: "\u5168\u90e8"
                val area = filters.filterIsInstance<AreaFilter>().firstOrNull()?.toUriPart() ?: "-1"
                val end = filters.filterIsInstance<EndFilter>().firstOrNull()?.toUriPart() ?: "-1"

                addPathSegment("booklist")
                addQueryParameter("tag", tag)
                addQueryParameter("area", area)
                addQueryParameter("end", end)
                addQueryParameter("page", page.toString())
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaSelector() = "ul.mh-list li .mh-item, ul.book-list li, ul.manga-list-2 li"

    override fun searchMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun searchMangaNextPageSelector() =
        "a#nextPage, a[title*=\u4e0b\u4e00], a.page-next, a[rel=next], li.next a, a.paginate-btn"

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("\u7b5b\u9009\u4ec5\u5728\u4e0d\u8f93\u5165\u641c\u7d22\u8bcd\u65f6\u751f\u6548"),
        Filter.Separator(),
        TagFilter(),
        AreaFilter(),
        EndFilter(),
    )

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        manga.title = document.selectFirst("h1, .detail-main-info-title")?.text()?.trim() ?: ""
        manga.author = extractLabeledText(document, "\u4f5c\u8005", ".detail-main-info-author, p.subtitle, p, li, span, div")
        manga.description = document.selectFirst(
            "p.content, .detail-info p.intro, .comic-desc, .detail-desc, #detail-desc, .BookIntro",
        )?.text()?.trim()

        val bodyText = document.body().text()
        manga.status = when {
            bodyText.contains("\u8fde\u8f7d\u4e2d") || bodyText.contains("\u9023\u8f09\u4e2d") || bodyText.contains("\u9023\u8f09") -> SManga.ONGOING
            bodyText.contains("\u5b8c\u7ed3") || bodyText.contains("\u5b8c\u7d50") || bodyText.contains("\u5df2\u5b8c\u7ed3") || bodyText.contains("\u5df2\u5b8c\u7d50") -> SManga.COMPLETED
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

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = response.asJsoup()
            .select(chapterListSelector())
            .mapNotNull { element ->
                val url = normalizeUrlPath(element.attr("href"))
                if (url.isEmpty()) return@mapNotNull null
                SChapter.create().apply {
                    name = element.text().trim()
                    setUrlWithoutDomain(url)
                }
            }
            .distinctBy { it.url }

        if (chapters.size < 2) return chapters

        val firstId = extractChapterId(chapters.first().url)
        val lastId = extractChapterId(chapters.last().url)

        return if (firstId != null && lastId != null && firstId < lastId) {
            chapters.reversed()
        } else {
            chapters
        }
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
            it.text().isNotBlank() && !it.text().contains("\u67e5\u770b\u8be6\u60c5")
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
            ?.trim('\uff1a', ':', ' ')
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

    private fun extractChapterId(url: String): Long? {
        return """/chapter/(\d+)""".toRegex()
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    }

    private open class UriPartFilter(
        displayName: String,
        private val options: Array<Pair<String, String>>,
    ) : Filter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart(): String = options[state].second
    }

    private class TagFilter : UriPartFilter("\u9898\u6750", TAG_OPTIONS)

    private class AreaFilter : UriPartFilter("\u5730\u533a", AREA_OPTIONS)

    private class EndFilter : UriPartFilter("\u8fdb\u5ea6", END_OPTIONS)

    companion object {
        private val TAG_OPTIONS = arrayOf(
            "\u5168\u90e8" to "\u5168\u90e8",
            "\u9752\u6625" to "\u9752\u6625",
            "\u6027\u611f" to "\u6027\u611f",
            "\u957f\u817f" to "\u957f\u817f",
            "\u591a\u4eba" to "\u591a\u4eba",
            "\u5fa1\u59d0" to "\u5fa1\u59d0",
            "\u5de8\u4e73" to "\u5de8\u4e73",
            "\u65b0\u5a5a" to "\u65b0\u5a5a",
            "\u5ab3\u5987" to "\u5ab3\u5987",
            "\u66a7\u6627" to "\u66a7\u6627",
            "\u6e05\u7eaf" to "\u6e05\u7eaf",
            "\u8c03\u6559" to "\u8c03\u6559",
            "\u5c11\u5987" to "\u5c11\u5987",
            "\u98ce\u9a9a" to "\u98ce\u9a9a",
            "\u540c\u5c45" to "\u540c\u5c45",
            "\u6deb\u4e71" to "\u6deb\u4e71",
            "\u597d\u53cb" to "\u597d\u53cb",
            "\u5973\u795e" to "\u5973\u795e",
            "\u8bf1\u60d1" to "\u8bf1\u60d1",
            "\u5077\u60c5" to "\u5077\u60c5",
            "\u51fa\u8f68" to "\u51fa\u8f68",
            "\u6b63\u59b9" to "\u6b63\u59b9",
            "\u5bb6\u6559" to "\u5bb6\u6559",
        )

        private val AREA_OPTIONS = arrayOf(
            "\u5168\u90e8" to "-1",
            "\u97e9\u56fd" to "1",
            "\u65e5\u672c" to "2",
            "\u53f0\u6e7e" to "3",
        )

        private val END_OPTIONS = arrayOf(
            "\u5168\u90e8" to "-1",
            "\u8fde\u8f7d" to "0",
            "\u5b8c\u7ed3" to "1",
        )
    }
}

package eu.kanade.tachiyomi.extension.zh.se8

import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.SourceUrlConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Se8 : ParsedHttpSource() {
    private val sourceUrlConfig = SourceUrlConfig(
        baseUrl = BuildConfig.SOURCE_BASE_URL,
        aliases = listOf(
            "http://se8.us",
            "https://www.se8.us",
        ),
    )
    override val name = "韩漫库"
    override val baseUrl = sourceUrlConfig.baseUrl
    override val lang = "zh"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", DESKTOP_USER_AGENT)
        .set("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/index.php/custom/top", headers)

    override fun popularMangaSelector() =
        ".top-list .top-list__item--first, .top-list .top-list__item"

    override fun popularMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun popularMangaNextPageSelector() = null

    override fun popularMangaParse(response: Response): MangasPage {
        val mangas = parseMangaElements(response.asJsoup().select(popularMangaSelector()))
        return MangasPage(mangas, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/index.php/custom/update", headers)

    override fun latestUpdatesSelector() = ".update-list .common-comic-item"

    override fun latestUpdatesFromElement(element: Element): SManga = mangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    override fun latestUpdatesParse(response: Response): MangasPage {
        val mangas = parseMangaElements(response.asJsoup().select(latestUpdatesSelector()))
        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            urlBuilder.addEncodedPathSegments("index.php/search")
            urlBuilder.addPathSegment(query.trim())
            urlBuilder.addPathSegment(page.toString())
        } else {
            val segments = mutableListOf("index.php", "category")
            listOf(
                filters.filterIsInstance<TypeFilter>().firstOrNull()?.toUriPart().orEmpty(),
                filters.filterIsInstance<TagFilter>().firstOrNull()?.toUriPart().orEmpty(),
                filters.filterIsInstance<ThemeFilter>().firstOrNull()?.toUriPart().orEmpty(),
                filters.filterIsInstance<QualityFilter>().firstOrNull()?.toUriPart().orEmpty(),
                filters.filterIsInstance<AreaFilter>().firstOrNull()?.toUriPart().orEmpty(),
                filters.filterIsInstance<PayFilter>().firstOrNull()?.toUriPart().orEmpty(),
                filters.filterIsInstance<ProgressFilter>().firstOrNull()?.toUriPart().orEmpty(),
                filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty(),
            ).forEach { part ->
                if (part.isNotBlank()) {
                    segments.addAll(part.split("/"))
                }
            }
            segments.add("page")
            segments.add(page.toString())
            segments.forEach(urlBuilder::addPathSegment)
        }
        return GET(urlBuilder.build(), headers)
    }

    override fun searchMangaSelector() =
        ".search-comic-list .common-comic-item, .cate-comic-list .common-comic-item, .update-list .common-comic-item"

    override fun searchMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun searchMangaNextPageSelector() = "#Pagination a.next"

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val hint = document.selectFirst(".search_head")?.text().orEmpty()
        if (hint.contains("很遗憾")) {
            return MangasPage(emptyList(), false)
        }

        val mangas = parseMangaElements(document.select(searchMangaSelector()))
        val nextPath = normalizeUrlPath(document.selectFirst(searchMangaNextPageSelector())?.attr("href").orEmpty())
        val hasNextPage = nextPath.isNotEmpty() && nextPath != response.request.url.encodedPath
        return MangasPage(mangas, hasNextPage)
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("筛选仅在不输入搜索词时生效"),
        Filter.Separator(),
        TypeFilter(),
        TagFilter(),
        ThemeFilter(),
        QualityFilter(),
        AreaFilter(),
        PayFilter(),
        ProgressFilter(),
        SortFilter(),
    )

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        manga.title = document.selectFirst(".de-info__box .comic-title")?.text()?.trim().orEmpty()
        manga.author = document.selectFirst(".comic-author .name a, .comic-author .name")?.text()?.trim()
        manga.description = document.selectFirst(".comic-intro .intro-total, .comic-intro .intro")?.text()?.trim()
        manga.thumbnail_url = extractImageUrl(document.selectFirst(".de-info__cover img, .de-info__bg")).ifBlank { null }

        val statusText = document.selectFirst(".de-chapter__title")?.text().orEmpty() + " " + document.body().text()
        manga.status = when {
            statusText.contains("连载") || statusText.contains("連載") -> SManga.ONGOING
            statusText.contains("完结") || statusText.contains("完結") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        val tags = document.select(".comic-status a[href*='/category/tags/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        manga.genre = tags.joinToString(", ")

        return manga
    }

    override fun chapterListSelector() =
        ".chapter__list-box .j-chapter-link, .chapter__list-box a[href*='/chapter/']"

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

        val hasIds = chapters.any { extractChapterId(it.url) != null }
        return if (!hasIds) {
            chapters.asReversed()
        } else {
            chapters.sortedByDescending { extractChapterId(it.url) ?: Long.MIN_VALUE }
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = document.select(
            ".rd-article__pic img, img.lazy-read, .comic-list img",
        ).mapIndexedNotNull { index, element ->
            val imageUrl = extractImageUrl(element)
            if (imageUrl.isBlank() || imageUrl.contains("lazyload_img")) {
                null
            } else {
                Page(index, "", imageUrl)
            }
        }
        return pages.distinctBy { it.imageUrl }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    private fun parseMangaElements(elements: Iterable<Element>): List<SManga> {
        return elements.map(::mangaFromElement)
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .distinctBy { it.url }
    }

    private fun mangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = findBookLink(element)

        manga.title = element.selectFirst(".comic__title a, .comic-title a")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: link?.attr("title")?.trim().orEmpty()

        normalizeUrlPath(link?.attr("href").orEmpty())
            .takeIf { it.isNotEmpty() }
            ?.let { manga.setUrlWithoutDomain(it) }

        manga.thumbnail_url = extractImageUrl(
            element.selectFirst("img[data-original], img[data-src], img[src], .cover img"),
        ).ifBlank { null }

        return manga
    }

    private fun findBookLink(element: Element): Element? {
        if (element.tagName().equals("a", ignoreCase = true) && element.attr("href").contains("/comic/")) {
            return element
        }
        return element.selectFirst(
            ".comic__title a[href*='/comic/'], .comic-title a[href*='/comic/'], a.cover[href*='/comic/'], a[href*='/comic/']",
        )
    }

    private fun extractImageUrl(element: Element?): String {
        if (element == null) return ""

        val direct = element.attr("data-original")
            .ifEmpty { element.attr("data-src") }
            .ifEmpty { element.attr("src") }
            .replace(Regex("\\s+"), "")
        if (direct.isNotEmpty()) return toAbsoluteUrl(direct)

        val nested = element.selectFirst("img[data-original], img[data-src], img[src]")
        val nestedUrl = nested?.attr("data-original").orEmpty()
            .ifEmpty { nested?.attr("data-src").orEmpty() }
            .ifEmpty { nested?.attr("src").orEmpty() }
            .replace(Regex("\\s+"), "")
        if (nestedUrl.isNotEmpty()) return toAbsoluteUrl(nestedUrl)

        val styleUrl = element.attr("style")
            .substringAfter("url(", "")
            .substringBefore(")")
            .trim('\'', '"', ' ')
        return toAbsoluteUrl(styleUrl)
    }

    private fun normalizeUrlPath(url: String): String {
        return sourceUrlConfig.normalizeUrlPath(
            url.trim().removeSuffix("]"),
        )
    }

    private fun toAbsoluteUrl(url: String): String {
        return sourceUrlConfig.toAbsoluteUrl(url.replace(Regex("\\s+"), ""))
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

    private class TypeFilter : UriPartFilter("漫画类型", TYPE_OPTIONS)
    private class TagFilter : UriPartFilter("题材", TAG_OPTIONS)
    private class ThemeFilter : UriPartFilter("形式", THEME_OPTIONS)
    private class QualityFilter : UriPartFilter("品质", QUALITY_OPTIONS)
    private class AreaFilter : UriPartFilter("地区", AREA_OPTIONS)
    private class PayFilter : UriPartFilter("付费", PAY_OPTIONS)
    private class ProgressFilter : UriPartFilter("进度", PROGRESS_OPTIONS)
    private class SortFilter : UriPartFilter("排序", SORT_OPTIONS)

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0"

        private val TYPE_OPTIONS = arrayOf(
            "全部" to "",
            "成人漫画" to "list/5",
            "青年漫画" to "list/3",
        )

        private val TAG_OPTIONS = arrayOf(
            "全部" to "",
            "巨乳" to "tags/61",
            "偷情" to "tags/63",
            "青春" to "tags/62",
            "乱伦" to "tags/64",
            "校园" to "tags/11",
            "后宫" to "tags/15",
            "都市" to "tags/31",
            "多人" to "tags/75",
            "人妻" to "tags/78",
            "女神" to "tags/81",
            "性感" to "tags/91",
            "清纯" to "tags/95",
            "好友" to "tags/97",
            "暧昧" to "tags/98",
            "正妹" to "tags/114",
            "同居" to "tags/117",
            "家教" to "tags/142",
            "调教" to "tags/153",
            "御姐" to "tags/154",
            "新婚" to "tags/221",
            "媳妇" to "tags/222",
            "风骚" to "tags/323",
            "出轨" to "tags/414",
            "少妇" to "tags/432",
        )

        private val THEME_OPTIONS = arrayOf(
            "全部" to "",
            "故事漫画" to "theme/32",
            "条漫" to "theme/37",
        )

        private val QUALITY_OPTIONS = arrayOf(
            "全部" to "",
            "独家" to "quality/442",
            "精品" to "quality/39",
            "热门" to "quality/40",
        )

        private val AREA_OPTIONS = arrayOf(
            "全部" to "",
            "韩国" to "city/44",
        )

        private val PAY_OPTIONS = arrayOf(
            "全部" to "",
            "免费" to "pay/1",
            "付费" to "pay/2",
            "VIP" to "pay/3",
        )

        private val PROGRESS_OPTIONS = arrayOf(
            "全部" to "",
            "连载" to "finish/1",
            "完结" to "finish/2",
        )

        private val SORT_OPTIONS = arrayOf(
            "热门人气" to "order/hits",
            "更新时间" to "order/addtime",
            "评分" to "order/score",
        )
    }
}

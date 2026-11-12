package eu.kanade.tachiyomi.extension.zh.gal18

import eu.kanade.tachiyomi.extension.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 18GAL（https://18gallery.hm.bhb6.top）图集站。
 *
 * 站点结构：
 * - 图集：/gallery/<slug>/，每页预览 10 张，完整图片位于 /page/N/
 * - 图片 CDN：https://img.18gallery.com/<slug>/g-mhcom_001.webp 起连续编号
 * - 列表：/popular/、/latest/、/cat/<分类>/page/N/
 * - 站内搜索（/s/?q=）目前失效，搜索用分类名匹配兜底
 */
class Gal18 : ParsedHttpSource() {

    private val sourceUrlConfig = SourceUrlConfig(BuildConfig.SOURCE_BASE_URL)

    override val name = "18GAL"
    override val baseUrl = sourceUrlConfig.baseUrl
    override val lang = "zh"
    override val supportsLatest = true

    // 列表页的卡片与翻页
    private val galleryCardSelector = "article:has(a[href*=\"/gallery/\"])"
    private val nextPageSelector = "a[aria-label=\"Next\"]:not([href=\"#\"])"

    // ============================ 热门 / 最新 ============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/?page=$page", headers)

    override fun popularMangaSelector(): String = galleryCardSelector

    override fun popularMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun popularMangaNextPageSelector(): String = nextPageSelector

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest/?page=$page", headers)

    override fun latestUpdatesSelector(): String = galleryCardSelector

    override fun latestUpdatesFromElement(element: Element): SManga = mangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = nextPageSelector

    // ============================== 搜索 ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) {
            filters.filterIsInstance<CategoryFilter>().firstOrNull()
                ?.takeIf { it.state > 0 }
                ?.let { return GET(categoryPageUrl(it.toUriPart(), page), headers) }
            return GET("$baseUrl/latest/?page=$page", headers)
        }

        val keyword = query.trim()
        val matchedCategory = CATEGORIES.asSequence()
            .filter { it.first != ALL_CATEGORY }
            .firstOrNull { (label, slug) ->
                label.contains(keyword, ignoreCase = true) ||
                    keyword.contains(label, ignoreCase = true) ||
                    slug.equals(keyword, ignoreCase = true) ||
                    slug.contains(keyword.lowercase())
            }
        if (matchedCategory != null) {
            return GET(categoryPageUrl(matchedCategory.second, page), headers)
        }

        // 站内搜索已失效（任何关键词都返回空），保留原搜索地址以便站点修复后自动可用
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("s/")
            .addQueryParameter("q", keyword)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaSelector(): String = galleryCardSelector

    override fun searchMangaFromElement(element: Element): SManga = mangaFromElement(element)

    override fun searchMangaNextPageSelector(): String = nextPageSelector

    // ============================== 筛选 ==============================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("分类筛选（简体中文）"),
        Filter.Separator(),
        CategoryFilter(CATEGORIES),
        Filter.Header("提示：站内搜索已失效，搜索框输入分类名（如：国模、日本、cosplay）可按分类筛选"),
    )

    private class CategoryFilter(
        private val categories: List<Pair<String, String>>,
    ) : Filter.Select<String>("分类", categories.map { it.first }.toTypedArray()) {
        fun toUriPart(): String = categories[state].second
    }

    private fun categoryPageUrl(slug: String, page: Int): String = "$baseUrl/cat/$slug/page/$page/"

    // ============================== 详情 ==============================

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        manga.title = document.selectFirst("h1.article-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.title().removeSuffix(" - 18GAL").trim()

        // 封面直接用图集首图（站点的 cover CDN 地址由标题拼音规则生成，无法从 URL 反推）
        manga.thumbnail_url = previewImages(document).firstOrNull()
        manga.author = null
        manga.status = SManga.COMPLETED

        val tags = parseTags(document)
        manga.genre = tags.joinToString(", ").ifBlank { null }
        manga.description = buildString {
            append("共 ").append(parseImageCount(document)).append(" 张图片")
            parseDateText(document)?.let { append("\n发布于 ").append(it) }
            if (tags.isNotEmpty()) {
                append("\n标签：").append(tags.joinToString("、"))
            }
        }

        return manga
    }

    // ============================== 章节 ==============================
    // 每个图集只有一个「章节」，章节内包含全部图片

    override fun chapterListSelector(): String = "article#article"

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return listOf(
            SChapter.create().apply {
                name = "共 ${parseImageCount(document)} 张"
                setUrlWithoutDomain(response.request.url.toString())
                date_upload = parseDateUpload(document)
            },
        )
    }

    // ============================== 图片 ==============================
    // 图片地址连续编号（g-mhcom_001.webp、g-mhcom_002.webp ...），
    // 由详情页总张数直接生成，无需逐页请求预览页

    override fun pageListParse(document: Document): List<Page> {
        val count = parseImageCount(document)
        val previewImages = previewImages(document)

        val pattern = previewImages.firstOrNull()?.let { IMAGE_URL_REGEX.find(it) }
        if (pattern == null || count <= 0) {
            return previewImages.mapIndexed { index, url -> Page(index, "", url) }
        }

        val prefix = pattern.groupValues[1]
        val padding = pattern.groupValues[2].length
        val extension = pattern.groupValues[3]

        return (1..count).map { index ->
            val imageUrl = prefix + index.toString().padStart(padding, '0') + extension
            Page(index - 1, "", imageUrl)
        }
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    // ============================== 工具方法 ==============================

    private fun mangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val link = element.selectFirst("a[href*=\"/gallery/\"]") ?: return manga

        val href = link.absUrl("href").ifBlank { link.attr("href") }
        manga.setUrlWithoutDomain(href)
        manga.title = element.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: link.attr("title").ifBlank { link.text().trim() }
        manga.thumbnail_url = element.selectFirst("img")?.let { image ->
            image.absUrl("src").ifBlank { image.attr("src") }.ifBlank { null }
        }

        return manga
    }

    /** 详情页 article 内的图片就是该图集的前若干张预览图 */
    private fun previewImages(document: Document): List<String> = document.select("article#article img").mapNotNull { image ->
        image.absUrl("src").ifBlank { image.attr("src") }.ifBlank { null }
    }

    private fun parseImageCount(document: Document): Int {
        val metaText = document.select("li.meta-item").joinToString(" ") { it.text() }
        IMAGE_COUNT_REGEX.find(metaText)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        return document.select("article#article img").size
    }

    private fun parseTags(document: Document): List<String> = document.select("a[href^=\"/tags/\"]")
        .map { it.text().trim().removePrefix("#").trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    private fun parseDateText(document: Document): String? = document.select("li.meta-item")
        .map { it.text().trim() }
        .firstOrNull { META_DATE_REGEX.matches(it) }

    private fun parseDateUpload(document: Document): Long {
        val raw = parseDateText(document) ?: return 0L
        return runCatching {
            SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).parse(raw)?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val ALL_CATEGORY = "全部分类"

        private val IMAGE_COUNT_REGEX = Regex("""(\d+)\s*images?""", RegexOption.IGNORE_CASE)
        private val IMAGE_URL_REGEX = Regex("""^(.*?)(\d+)(\.(?:webp|jpe?g|png))$""", RegexOption.IGNORE_CASE)
        private val META_DATE_REGEX = Regex("""[A-Z][a-z]{2} \d{1,2}, \d{4}""")

        /** 站点中有内容的分类（其余分类当前无图集，不列入筛选） */
        private val CATEGORIES = listOf(
            ALL_CATEGORY to "",
            "国模套图" to "guo-mo-tao-tu",
            "台模套图" to "tai-mo-tao-tu",
            "韩模套图" to "han-mo-tao-tu",
            "各国其他套图" to "ge-guo-qi-ta-tao-tu",
            "中国" to "china",
            "日本" to "japan",
            "韩国" to "korean",
            "角色扮演" to "cosplay",
            "写真集" to "album",
            "中国工作室" to "zhong-guo-gong-zuo-shi",
            "日本工作室" to "ri-ben-gong-zuo-shi",
            "台湾工作室" to "tai-wan-gong-zuo-shi",
            "风吟鸟唱" to "feng-yin-niao-chang",
            "怀旧" to "huai-jiu",
            "新版" to "xin-ban",
            "PANS" to "pans",
            "ED Mosaic" to "ed-mosaic",
            "Super Pose Book" to "super-pose-book",
            "Urabon" to "urabon",
        )
    }
}

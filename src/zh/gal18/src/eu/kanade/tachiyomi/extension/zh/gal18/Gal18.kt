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
 *
 * 图片 CDN（img/cover.18gallery.com）在部分网络下无法直连，统一改走反代域名的
 * `/img/`、`/cover/` 路径，需要在反代服务器上把这两个路径转发到对应 CDN 域名。
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
            .filter { it.second.isNotEmpty() }
            .firstOrNull { (label, slug) ->
                label.equals(keyword, ignoreCase = true) ||
                    slug.equals(keyword, ignoreCase = true) ||
                    (keyword.length >= 2 && label.contains(keyword, ignoreCase = true))
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

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(CATEGORIES))

    private class CategoryFilter(
        private val categories: List<Pair<String, String>>,
    ) : Filter.Select<String>("Category", categories.map { it.first }.toTypedArray()) {
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
        manga.thumbnail_url = previewImages(document).firstOrNull()?.let(::proxiedImageUrl)
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
            return previewImages.mapIndexed { index, url -> Page(index, "", proxiedImageUrl(url)) }
        }

        val prefix = pattern.groupValues[1]
        val padding = pattern.groupValues[2].length
        val extension = pattern.groupValues[3]

        return (1..count).map { index ->
            val imageUrl = prefix + index.toString().padStart(padding, '0') + extension
            Page(index - 1, "", proxiedImageUrl(imageUrl))
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
            image.absUrl("src").ifBlank { image.attr("src") }.ifBlank { null }?.let(::proxiedImageUrl)
        }

        return manga
    }

    /** 图片 CDN 地址改为走反代域名（/img/、/cover/），避免直连 CDN 被墙 */
    private fun proxiedImageUrl(url: String): String = when {
        url.startsWith(IMG_CDN_PREFIX) -> "$baseUrl/img/" + url.removePrefix(IMG_CDN_PREFIX)
        url.startsWith(COVER_CDN_PREFIX) -> "$baseUrl/cover/" + url.removePrefix(COVER_CDN_PREFIX)
        else -> url
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
        private const val DEFAULT_CATEGORY = "All"
        private const val IMG_CDN_PREFIX = "https://img.18gallery.com/"
        private const val COVER_CDN_PREFIX = "https://cover.18gallery.com/"

        /**
         * Native category list taken from the site's /cat/ pages.
         * Names are kept exactly as the site displays them.
         */
        private val CATEGORIES = listOf(
            DEFAULT_CATEGORY to "",
            "10musume" to "10musume",
            "91茄子" to "91qie-zi",
            "爱豆传媒" to "ai-dou-chuan-mei",
            "album" to "album",
            "AV帝王" to "avdi-wang",
            "北京天使" to "bei-jing-tian-shi",
            "Caribbean" to "caribbean",
            "CCAV" to "ccav",
            "China" to "china",
            "cosplay" to "cosplay",
            "代理出品" to "dai-li-chu-pin",
            "抖阴" to "dou-yin",
            "渡边传媒" to "du-bian-chuan-mei",
            "独立创作者" to "du-li-chuang-zuo-zhe",
            "ED Mosaic" to "ed-mosaic",
            "Europe" to "eu-girls",
            "FC2" to "fc2",
            "疯狂传媒" to "feng-kuang-chuan-mei",
            "风吟鸟唱作品" to "feng-yin-niao-chang-zuo-pin",
            "风吟鸟唱" to "feng-yin-niao-chang",
            "各国其他套图" to "ge-guo-qi-ta-tao-tu",
            "果冻传媒" to "guo-dong-chuan-mei",
            "果哥作品" to "guo-ge-zuo-pin",
            "国模套图" to "guo-mo-tao-tu",
            "韩模套图" to "han-mo-tao-tu",
            "HEYZO" to "heyzo",
            "红斯灯影像" to "hong-si-deng-ying-xiang",
            "葫芦影业" to "hu-lu-ying-ye",
            "华语电影" to "hua-yu-dian-ying",
            "怀旧" to "huai-jiu",
            "皇家华人" to "huang-jia-hua-ren",
            "IBiZa Media" to "ibiza-media",
            "Japan" to "japan",
            "精东影业" to "jing-dong-ying-ye",
            "绝对领域" to "jue-dui-ling-yu",
            "蝌蚪传媒" to "ke-dou-chuan-mei",
            "Korean" to "korean",
            "扣扣传媒" to "kou-kou-chuan-mei",
            "辣椒原创" to "la-jiao-yuan-chuang",
            "乐播传媒" to "le-bo-chuan-mei",
            "萝莉社" to "luo-li-she",
            "裸体系列" to "luo-ti-xi-lie",
            "麻豆传媒" to "ma-dou-chuan-mei",
            "麻豆达人秀" to "ma-dou-da-ren-xiu",
            "麻豆官方" to "ma-dou-guan-fang",
            "麻麻传媒" to "ma-ma-chuan-mei",
            "猫爪影像" to "mao-zhua-ying-xiang",
            "门事件" to "men-shi-jian",
            "蜜桃传媒" to "mi-tao-chuan-mei",
            "模特私拍" to "mo-te-si-pai",
            "Model US" to "model-us",
            "欧美电影" to "ou-mei-dian-ying",
            "Pacopacomama" to "pacopacomama",
            "PANS" to "pans",
            "PANS视频" to "pansshi-pin",
            "Pussy Hunted" to "pussy-hunted",
            "其他模特私拍" to "qi-ta-mo-te-si-pai",
            "其他欧美影片" to "qi-ta-ou-mei-ying-pian",
            "其他亚洲影片" to "qi-ta-ya-zhou-ying-pian",
            "其他影片" to "qi-ta-ying-pian",
            "其他中文AV" to "qi-ta-zhong-wen-av",
            "千禧传媒" to "qian-xi-chuan-mei",
            "情色电影" to "qing-se-dian-ying",
            "热舞" to "re-wu",
            "日本AV" to "ri-ben-av",
            "日本工作室" to "ri-ben-gong-zuo-shi",
            "日韩电影" to "ri-han-dian-ying",
            "肉肉传媒" to "rou-rou-chuan-mei",
            "SA国际传媒" to "saguo-ji-chuan-mei",
            "色控" to "se-kong",
            "Super Pose Book" to "super-pose-book",
            "SweatGirl" to "sweatgirl",
            "台模套图" to "tai-mo-tao-tu",
            "台湾工作室" to "tai-wan-gong-zuo-shi",
            "探花现场" to "tan-hua-xian-chang",
            "糖心Vlog" to "tang-xin-vlog",
            "桃花源" to "tao-hua-yuan",
            "天美传媒" to "tian-mei-chuan-mei",
            "兔子先生" to "tu-zi-xian-sheng",
            "TWAV" to "twav",
            "Urabon" to "urabon",
            "维拉舞团" to "wei-la-wu-tuan",
            "无码AV" to "wu-ma-av",
            "无码流出" to "wu-ma-liu-chu",
            "乌托邦" to "wu-tuo-bang",
            "香蕉视频" to "xiang-jiao-shi-pin",
            "相约中国" to "xiang-yue-zhong-guo",
            "新版" to "xin-ban",
            "杏吧原版" to "xing-ba-yuan-ban",
            "星空传媒" to "xing-kong-chuan-mei",
            "行色" to "xing-se",
            "性视界" to "xing-shi-jie",
            "业余拍摄" to "ye-yu-pai-she",
            "有码AV" to "you-ma-av",
            "直播回放" to "zhi-bo-hui-fang",
            "中国工作室" to "zhong-guo-gong-zuo-shi",
            "中文AV" to "zhong-wen-av",
            "中文字幕" to "zhong-wen-zi-mu",
            "主播现场" to "zhu-bo-xian-chang",
        )

        private val IMAGE_COUNT_REGEX = Regex("""(\d+)\s*images?""", RegexOption.IGNORE_CASE)
        private val IMAGE_URL_REGEX = Regex("""^(.*?)(\d+)(\.(?:webp|jpe?g|png))$""", RegexOption.IGNORE_CASE)
        private val META_DATE_REGEX = Regex("""[A-Z][a-z]{2} \d{1,2}, \d{4}""")
    }
}

package eu.kanade.tachiyomi.extension.zh.ikanhm

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.Request
import java.net.URLEncoder

class Ikanhm : ParsedHttpSource() {
    override val name = "漫小肆韩漫 (Ikanhm)"
    override val baseUrl = "https://www.ikanhm.top"
    override val lang = "zh"
    override val supportsLatest = true

    // ===========================
    // 最新更新 (Latest)
    // ===========================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/update?page=$page", headers)
    }

    // 更新页与首页共用相同的漫画列表结构
    override fun latestUpdatesSelector() = "ul.mh-list li .mh-item, ul.mh-list li"

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()

        // 尝试多种可能的标题选择器
        val titleElement = element.selectFirst("a.mh-item-detali") 
            ?: element.selectFirst(".title a")
            ?: element.selectFirst("h2.title a")
            ?: element.selectFirst("a[href*='/book/']")
        
        manga.title = titleElement?.text()?.trim() ?: element.selectFirst("a")?.attr("title")?.trim() ?: ""
        manga.setUrlWithoutDomain(titleElement?.attr("href") ?: element.selectFirst("a[href*='/book/']")?.attr("href") ?: "")

        // 封面图（优先 img 标签，其次 style 背景图）
        val img = element.selectFirst("img")
        if (img != null) {
            manga.thumbnail_url = img.attr("data-src").ifEmpty { img.attr("src") }
        } else {
            val styleAttr = element.selectFirst("[style*='url(']")?.attr("style")
            if (styleAttr != null) {
                manga.thumbnail_url = styleAttr
                    .substringAfter("url(").substringBefore(")")
                    .trim('\'', '"')
            }
        }
        return manga
    }

    override fun latestUpdatesNextPageSelector() = "a.page-next, a[rel=next], li.next a, a:contains(下一页)"

    // ===========================
    // 热门排行 (Popular)
    // ===========================
    override fun popularMangaRequest(page: Int): Request {
        // 注意：此处不要使用 \$ ，否则会变成字面字符串 "$baseUrl"
        return GET("$baseUrl/rank?page=$page", headers)
    }

    // 排行页结构: ul.index-rank-list > li，每个 li 内有 .type_2 展开区块
    override fun popularMangaSelector() = "ul.index-rank-list li"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        // 优先取展开区块 .type_2 中的信息
        val expandedBlock = element.selectFirst(".type_2, .rank-list-item-info")
        val titleLink = expandedBlock?.selectFirst("a[href*='/book/']")
            ?: element.selectFirst("a[href*='/book/']")

        manga.title = titleLink?.text()?.trim()
            ?: titleLink?.attr("title")?.trim()
            ?: element.selectFirst("a")?.text()?.trim()
            ?: ""
        manga.setUrlWithoutDomain(titleLink?.attr("href") ?: "")

        // 封面图在 .rank-list-item-img img 中
        val coverImg = element.selectFirst(".rank-list-item-img img, .type_2 img")
        manga.thumbnail_url = coverImg?.attr("src")?.ifEmpty { coverImg.attr("data-src") }

        return manga
    }

    override fun popularMangaNextPageSelector() = "a.page-next, a[rel=next], li.next a, a:contains(下一页)"

    // ===========================
    // 搜索 (Search)
    // ===========================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?keyword=$encodedQuery&page=$page", headers)
    }

    // 搜索页通常与更新页相同布局
    override fun searchMangaSelector() = latestUpdatesSelector()

    override fun searchMangaFromElement(element: Element): SManga = latestUpdatesFromElement(element)

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    // ===========================
    // 详情页 (Manga Details)
    // ===========================
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()

        // 标题
        manga.title = document.selectFirst("h1")?.text()?.trim() ?: ""

        // 作者：文本包含"作者："
        manga.author = document.body().getElementsContainingText("作者")
            .firstOrNull { it.tagName() in listOf("p", "li", "span", "div") && it.ownText().trim().isNotEmpty() }
            ?.ownText()?.replace(Regex("^作者[：:]\\.?"), "")?.trim()
            ?: document.selectFirst("p:contains(作者), span:contains(作者)")
                ?.ownText()?.replace(Regex("^作者[：:]\\s*"), "")?.trim()

        // 简介
        manga.description = document.selectFirst("p.content, .detail-info p.intro, .comic-desc")
            ?.text()?.trim()
            ?: document.selectFirst(".detail-desc, #detail-desc, .BookIntro")?.text()?.trim()

        // 状态
        val statusText = document.body().text()
        manga.status = when {
            statusText.contains("连载中") || statusText.contains("連載") -> SManga.ONGOING
            statusText.contains("完结") || statusText.contains("完結") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        // 标签/分类
        val tags = document.select("a[href*='tag='], .tag-item a, span.block a")
            .map { it.text().trim() }.filter { it.isNotEmpty() }
        manga.genre = tags.joinToString(", ")

        // 封面
        manga.thumbnail_url = document.selectFirst(
            ".banner_detail_form img, .detail-cover img, .book-cover img, " +
            ".bookdetail img, .detail img, img.cover"
        )?.attr("src")

        return manga
    }

    // ===========================
    // 章节列表 (Chapter List)
    // ===========================
    override fun chapterListSelector() =
        "ul#detail-list-select li a, ul.chapter-list li a, #chapterList li a, .chapter-list a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.name = element.text().trim()
        chapter.setUrlWithoutDomain(element.attr("href"))
        return chapter
    }

    // ===========================
    // 章节内页图片 (Page List)
    // ===========================
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        // 方法1：直接从 DOM 中获取 img 标签（静态加载时有效）
        val images = document.select(
            "div.comicpage img, div.comiclist img, .read-content img, #ChapterContenter img"
        )
        images.forEachIndexed { i, element ->
            val url = element.attr("data-original")
                .ifEmpty { element.attr("data-src") }
                .ifEmpty { element.attr("src") }
            if (url.isNotEmpty() && !url.contains("loading")) {
                pages.add(Page(i, "", url))
            }
        }

        // 方法2：如果 DOM 中没有图片（JS 动态加载），从 script 中提取 newimgs 数组
        if (pages.isEmpty()) {
            val scriptContent = document.select("script").joinToString("\n") { it.html() }

            // 匹配 var newimgs = ["url1", "url2", ...]
            val newimgsRegex = Regex("""newimgs\s*=\s*\[([^\]]+)]""")
            val match = newimgsRegex.find(scriptContent)
            if (match != null) {
                val urlsRaw = match.groupValues[1]
                val urlRegex = Regex(""""([^"]+)"""")
                urlRegex.findAll(urlsRaw).forEachIndexed { i, m ->
                    val url = m.groupValues[1]
                    if (url.isNotEmpty()) pages.add(Page(i, "", url))
                }
            }

            // 匹配 imgArr = ["url1", "url2", ...] 备用
            if (pages.isEmpty()) {
                val imgArrRegex = Regex("""imgArr\s*=\s*\[([^\]]+)]""")
                val match2 = imgArrRegex.find(scriptContent)
                if (match2 != null) {
                    val urlsRaw = match2.groupValues[1]
                    val urlRegex = Regex(""""([^"]+)"""")
                    urlRegex.findAll(urlsRaw).forEachIndexed { i, m ->
                        val url = m.groupValues[1]
                        if (url.isNotEmpty()) pages.add(Page(i, "", url))
                    }
                }
            }
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")
}

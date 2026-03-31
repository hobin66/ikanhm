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
    // 最新连载 (Latest)
    // ===========================
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/update?page=$page", headers)
    }

    override fun latestUpdatesSelector() = "ul.mh-list li .mh-item"

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst(".title a")
        manga.title = titleElement?.text()?.trim() ?: ""
        manga.setUrlWithoutDomain(titleElement?.attr("href") ?: "")
        
        val styleAttr = element.selectFirst(".mh-cover")?.attr("style")
        if (styleAttr != null && styleAttr.contains("url(")) {
            val url = styleAttr.substringAfter("url(").substringBefore(")").trim('\'', '"')
            manga.thumbnail_url = url
        }
        return manga
    }

    override fun latestUpdatesNextPageSelector() = "a#nextPage, a[title=下一页]"

    // ===========================
    // 热门排行 (Popular)
    // ===========================
    override fun popularMangaRequest(page: Int): Request {
        return GET("\$baseUrl/rank?page=\$page", headers)
    }

    override fun popularMangaSelector() = "ul.index-rank-list li"

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        val titleElement = element.selectFirst(".title a")
        manga.title = titleElement?.text()?.trim() ?: ""
        manga.setUrlWithoutDomain(titleElement?.attr("href") ?: "")
        manga.thumbnail_url = element.selectFirst(".cover img")?.attr("src")
        return manga
    }

    override fun popularMangaNextPageSelector() = "a#nextPage, a[title=下一页]"

    // ===========================
    // 搜索 (Search)
    // ===========================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search?keyword=$encodedQuery&page=$page", headers)
    }

    override fun searchMangaSelector() = latestUpdatesSelector()

    override fun searchMangaFromElement(element: Element): SManga = latestUpdatesFromElement(element)

    override fun searchMangaNextPageSelector() = latestUpdatesNextPageSelector()

    // ===========================
    // 详情页 (Manga Details)
    // ===========================
    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        val infoElement = document.selectFirst(".info")
        
        manga.title = infoElement?.selectFirst("h1")?.text()?.trim() ?: ""
        manga.author = infoElement?.selectFirst("p.subtitle:contains(作者)")?.text()?.replace("作者：", "")?.trim()
        manga.description = infoElement?.selectFirst("p.content")?.text()?.trim()
        
        val statusText = infoElement?.selectFirst("span.block:contains(状态)")?.text() ?: ""
        manga.status = when {
            statusText.contains("连载") -> SManga.ONGOING
            statusText.contains("完结") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        val tags = document.select("span.block:contains(标签) a").map { it.text() }
        manga.genre = tags.joinToString(", ")
        
        manga.thumbnail_url = document.selectFirst(".banner_detail_form .cover img")?.attr("src")
        
        return manga
    }

    // ===========================
    // 章节列表 (Chapter List)
    // ===========================
    override fun chapterListSelector() = "ul#detail-list-select li a"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        chapter.name = element.text().trim()
        chapter.setUrlWithoutDomain(element.attr("href"))
        return chapter
    }

    // ===========================
    // 章节内页 (Page List)
    // ===========================
    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        
        // It uses img.lazy inside .comicpage
        val images = document.select(".comicpage img.lazy")
        images.forEachIndexed { i, element ->
            val url = element.attr("data-original").ifEmpty { element.attr("src") }
            if (url.isNotEmpty()) {
                pages.add(Page(i, "", url))
            }
        }
        
        return pages
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")
}

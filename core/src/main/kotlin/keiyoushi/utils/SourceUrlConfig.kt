package keiyoushi.utils

class SourceUrlConfig(
    baseUrl: String,
    aliases: Iterable<String> = emptyList(),
) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    private val knownBaseUrls = linkedSetOf(this.baseUrl).apply {
        aliases
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .forEach(::add)
    }.toList()

    fun normalizeUrlPath(url: String): String {
        val raw = url.trim().substringBefore("#")
        if (raw.isEmpty() || raw.startsWith("javascript", ignoreCase = true)) return ""

        val noDomain = knownBaseUrls.fold(raw) { current, knownBaseUrl ->
            current.removePrefix(knownBaseUrl)
        }

        return if (noDomain.startsWith("/")) noDomain else "/$noDomain"
    }

    fun toAbsoluteUrl(url: String): String {
        val raw = url.trim()
        if (raw.isBlank()) return ""

        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> "$baseUrl/$raw"
        }
    }
}

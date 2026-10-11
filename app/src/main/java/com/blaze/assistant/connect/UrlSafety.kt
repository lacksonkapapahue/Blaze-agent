package com.blaze.assistant.connect

/**
 * Blaze allows plain HTTP so it can reach the on-device runner, OAuth loopback and gateways or MCP servers
 * on the user's own network (see res/xml/network_security_config.xml). This flags the ones that send
 * keys and chats unencrypted over the internet, so the self-check can warn about them.
 */
object UrlSafety {
    private val ipv4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun host(url: String): String {
        val rest = url.trim().substringAfter("://", "")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        return if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']').lowercase()
        else authority.substringBefore(':').lowercase()
    }

    /** Loopback, private LAN, link-local, Tailscale (100.64/10), *.local / *.lan / *.home.arpa / *.ts.net. */
    fun isLocalHost(h: String): Boolean {
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h == "::1") return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".ts.net")) return true
        if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return h.contains(':')
        val m = ipv4.matchEntire(h) ?: return false
        val (a, b) = m.destructured.toList().map { it.toInt() }.let { it[0] to it[1] }
        return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }

    /** True for an http:// URL whose host is on the public internet. */
    fun isRiskyCleartext(url: String): Boolean {
        val u = url.trim()
        if (!u.regionMatches(0, "http://", 0, 7, ignoreCase = true)) return false
        return !isLocalHost(host(u))
    }
}

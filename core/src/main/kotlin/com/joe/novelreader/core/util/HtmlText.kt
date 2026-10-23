package com.joe.novelreader.core.util

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.text.Normalizer

object HtmlText {
    private val BLOCKS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote",
        "section", "article", "hr", "tr", "pre", "center", "table", "ul", "ol",
    )

    private val SPACES = Regex("[ \\t\\r\\f\\u00A0\\u200B\\u200C\\u200D\\uFEFF]+")

    /** Turns a chapter's HTML into a clean list of paragraphs. */
    fun paragraphs(root: Element, junk: List<Regex> = emptyList()): List<String> {
        val el = root.clone()
        el.select("script, style, noscript, iframe, ins, button, form, input, select, svg, sub, .ads, [class*=adsbygoogle]").remove()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() {
            val t = sb.toString().replace(SPACES, " ").trim()
            sb.setLength(0)
            if (t.isEmpty()) return
            if (junk.any { it.containsMatchIn(t) }) return
            out += t
        }
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> sb.append(node.wholeText.replace('\n', ' '))
                    is Element -> {
                        val tag = node.normalName()
                        if (tag == "br" || tag in BLOCKS) flush()
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in BLOCKS) flush()
            }
        }, el)
        flush()
        return out
    }

    /** Plain text with paragraph breaks, for descriptions. */
    fun plain(root: Element?): String? {
        if (root == null) return null
        return paragraphs(root).joinToString("\n\n").ifBlank { null }
    }

    /**
     * Some sites hide their name inside the text using look-alike letters (𝐟𝐫𝐞𝐞𝐰𝐞𝐛𝐧𝐨𝐯𝐞𝐥).
     * Folds those letters back to plain ones and keeps only letters, for watermark checks.
     */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC)
            .lowercase()
            .filter { it in 'a'..'z' || it in '0'..'9' }

    /** Drops short lines that only advertise a site, e.g. "Read the latest chapters at novelbin.com". */
    fun dropWatermarks(paragraphs: List<String>, siteWords: List<String>): List<String> {
        if (siteWords.isEmpty()) return paragraphs
        val words = siteWords.map { fold(it) }
        return paragraphs.filterNot { p ->
            if (p.length > 220) return@filterNot false
            val f = fold(p)
            words.any { w -> f.contains(w) }
        }
    }

    val COMMON_JUNK = listOf(
        Regex("^\\s*(?:if you find any errors|please report us|report chapter)", RegexOption.IGNORE_CASE),
        Regex("non-standard content", RegexOption.IGNORE_CASE),
        Regex("^\\s*(?:read|find) (?:the )?(?:latest|original|more|new).{0,40}(?:chapters?|novels?) (?:at|on)\\b", RegexOption.IGNORE_CASE),
        Regex("^\\s*tip: you can use left, right", RegexOption.IGNORE_CASE),
        Regex("^\\s*use arrow keys", RegexOption.IGNORE_CASE),
        Regex("^\\s*(?:advertisement|sponsored content)\\s*$", RegexOption.IGNORE_CASE),
    )
}

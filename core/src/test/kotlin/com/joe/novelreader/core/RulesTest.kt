package com.joe.novelreader.core

import com.joe.novelreader.core.model.ChapterInfo
import com.joe.novelreader.core.model.ChapterText
import com.joe.novelreader.core.model.Listing
import com.joe.novelreader.core.model.NovelDetails
import com.joe.novelreader.core.model.PageResult
import com.joe.novelreader.core.source.HttpEnv
import com.joe.novelreader.core.source.NovelSource
import com.joe.novelreader.core.source.RulesFile
import com.joe.novelreader.core.source.SourceDisabledException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RulesTest {
    private val pages = mapOf(
        "https://new.example.com/popular?p=1" to """<div class="book"><a href="/b/one" title="Book One"><img data-src="/img/one_small.jpg"></a><span class="au">Ann</span></div>
            <div class="book"><a href="https://new.example.com/b/two">Book Two</a></div><a class="nx" href="?p=2">next</a>""",
        "https://new.example.com/b/one" to """<h1 class="t">Book One</h1><div class="sum"><p>Hello.</p></div><a class="g">Action</a><a class="g">Drama</a>
            <ul class="ch"><li><a href="/b/one/3">Ch 3</a></li><li><a href="/b/one/2">Ch 2</a></li><li><a href="/b/one/1">Ch 1</a></li></ul>""",
        "https://new.example.com/b/one/1" to """<div id="c"><p>First line.</p><div class="ad">BUY</div><p>Read at example dot com</p><p>Second line.</p></div>""",
    )

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url.toString()
        val body = pages[url] ?: ""
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if (body.isEmpty()) 404 else 200).message("x")
            .body(body.toResponseBody("text/html".toMediaType())).build()
    }.build()

    private class Fake(env: HttpEnv) : NovelSource(env) {
        override val id = "fake"
        override val name = "Fake"
        override val defaultBaseUrl = "https://old.example.com"
        override val listings = listOf(Listing("popular", "Popular"))
        override suspend fun builtInListing(listing: Listing, page: Int): PageResult = error("built-in broken")
        override suspend fun builtInSearch(query: String, page: Int): PageResult = error("built-in broken")
        override suspend fun builtInDetails(path: String): NovelDetails = error("built-in broken")
        override suspend fun builtInChapters(path: String): List<ChapterInfo> = error("built-in broken")
        override suspend fun builtInChapterText(path: String): ChapterText = error("built-in broken")
    }

    private val rulesJson = """
        { "version": 1, "sources": { "fake": {
          "baseUrl": "https://new.example.com/",
          "urls": { "listing": "{base}/{listing}?p={page}" },
          "list": { "item": "div.book", "subtitle": ".au", "next": "a.nx" },
          "details": { "title": "h1.t", "description": ".sum", "genres": "a.g" },
          "chapters": { "item": "ul.ch a", "reversed": true },
          "chapter": { "content": "#c", "remove": [".ad"], "dropParagraphs": ["example dot com"] },
          "coverRewrite": [ { "from": "_small", "to": "" } ],
          "someFutureField": 3
        } } }
    """.trimIndent()

    @Test
    fun rulesReplaceBrokenBuiltInCode() = runBlocking {
        val src = Fake(HttpEnv({ client }))
        src.rules = RulesFile.parse(rulesJson).sources.getValue("fake")
        assertEquals("https://new.example.com", src.baseUrl)

        val list = src.getListing(src.listings[0], 1)
        assertEquals(listOf("Book One", "Book Two"), list.novels.map { it.title })
        assertEquals("/b/one", list.novels[0].path)
        assertEquals("https://new.example.com/img/one.jpg", list.novels[0].cover)
        assertEquals("Ann", list.novels[0].subtitle)
        assertTrue(list.hasNextPage)

        val d = src.getDetails("/b/one")
        assertEquals("Book One", d.title)
        assertEquals(listOf("Action", "Drama"), d.genres)
        assertEquals("Hello.", d.description)

        val ch = src.getChapters("/b/one")
        assertEquals(listOf("/b/one/1", "/b/one/2", "/b/one/3"), ch.map { it.path })

        val t = src.getChapterText("/b/one/1")
        assertEquals(listOf("First line.", "Second line."), t.paragraphs)

        src.userBaseUrl = "https://mine.example.com"
        assertEquals("https://mine.example.com", src.baseUrl)
    }

    @Test
    fun disabledSourceExplainsWhy() = runBlocking {
        val src = Fake(HttpEnv({ client }))
        src.rules = RulesFile.parse("""{"sources":{"fake":{"disabled":true,"notice":"Site closed"}}}""").sources.getValue("fake")
        try {
            src.getListing(src.listings[0], 1)
            fail()
        } catch (e: SourceDisabledException) {
            assertEquals("Site closed", e.message)
        }
    }
}

class RulesFileInRepoTest {
    @Test
    fun repoRulesFileParses() {
        val f = java.io.File(System.getProperty("user.dir"), "../sources/rules.json")
        val parsed = RulesFile.parse(f.readText())
        assertTrue(parsed.sources.isNotEmpty())
    }
}

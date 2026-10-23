package com.joe.novelreader.ui.reader

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joe.novelreader.AppContainer
import com.joe.novelreader.core.model.ChapterText
import com.joe.novelreader.data.BookmarkEntity
import com.joe.novelreader.data.ChapterEntity
import com.joe.novelreader.data.NovelEntity
import com.joe.novelreader.ui.discover.Load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class Segment(val chapter: ChapterEntity, val state: Load<ChapterText>)

/** One row in the reader's scrolling list. */
sealed interface ReaderItem {
    val key: String
    val segment: Int

    data class Header(override val segment: Int, val chapter: ChapterEntity, val title: String) : ReaderItem {
        override val key get() = "${chapter.id}#h"
    }

    data class Para(override val segment: Int, val chapter: ChapterEntity, val index: Int, val text: String) : ReaderItem {
        override val key get() = "${chapter.id}#$index"
    }

    data class Status(override val segment: Int, val chapter: ChapterEntity, val state: Load<ChapterText>) : ReaderItem {
        override val key get() = "${chapter.id}#s"
    }

    data class Footer(override val segment: Int, val chapter: ChapterEntity, val hasNext: Boolean) : ReaderItem {
        override val key get() = "${chapter.id}#f"
    }
}

class ReaderViewModel(
    private val c: AppContainer,
    private val appContext: Context,
    val novelId: String,
    startChapterId: String?,
    startParagraph: Int?,
) : ViewModel() {

    var novel by mutableStateOf<NovelEntity?>(null)
        private set
    var chapters by mutableStateOf<List<ChapterEntity>>(emptyList())
        private set
    var segments by mutableStateOf<List<Segment>>(emptyList())
        private set
    var items by mutableStateOf<List<ReaderItem>>(emptyList())
        private set

    /** Which list row to jump to; the screen scrolls there and then clears it. */
    var pendingScroll by mutableStateOf<Int?>(null)
    var currentSegment by mutableIntStateOf(0)
        private set
    var currentParagraph by mutableIntStateOf(0)
        private set
    var hasNextChapter by mutableStateOf(false)
        private set

    private var loadingNext = false
    private var lastSaved = 0L

    val currentChapter: ChapterEntity? get() = segments.getOrNull(currentSegment)?.chapter

    /** Clears everything and shows [chapter], scrolled to the saved place or [paragraph]. */
    fun openChapter(chapter: ChapterEntity, paragraph: Int? = null) {
        stopTts()
        segments = listOf(Segment(chapter, Load.Loading))
        currentSegment = 0
        rebuild()
        viewModelScope.launch {
            markOpened(chapter)
            val state = load(chapter)
            setSegment(chapter.id, state)
            val target = paragraph ?: if (chapter.progress in 0.01f..0.98f) chapter.position else null
            pendingScroll = if (target != null && target > 0) indexOfParagraph(0, target) else 0
            prefetchNext(chapter)
        }
    }

    fun retry(chapter: ChapterEntity) {
        setSegment(chapter.id, Load.Loading)
        viewModelScope.launch { setSegment(chapter.id, load(chapter, force = true)) }
    }

    private suspend fun load(chapter: ChapterEntity, force: Boolean = false): Load<ChapterText> = try {
        Load.Ok(c.repository.chapterText(chapter, forceNetwork = force))
    } catch (e: Exception) {
        Load.Failed(e)
    }

    private fun setSegment(chapterId: String, state: Load<ChapterText>) {
        segments = segments.map { if (it.chapter.id == chapterId) it.copy(state = state) else it }
        rebuild()
    }

    private fun rebuild() {
        val out = mutableListOf<ReaderItem>()
        segments.forEachIndexed { si, seg ->
            val st = seg.state
            if (st is Load.Ok) {
                out += ReaderItem.Header(si, seg.chapter, seg.chapter.name)
                // Drop a first paragraph that just repeats the chapter title.
                val paras = st.value.paragraphs
                paras.forEachIndexed { pi, t ->
                    if (pi == 0 && similarTitle(t, seg.chapter.name)) return@forEachIndexed
                    out += ReaderItem.Para(si, seg.chapter, pi, t)
                }
                out += ReaderItem.Footer(si, seg.chapter, hasNext = seg.chapter.idx < (chapters.lastOrNull()?.idx ?: 0))
            } else {
                out += ReaderItem.Header(si, seg.chapter, seg.chapter.name)
                out += ReaderItem.Status(si, seg.chapter, st)
            }
        }
        items = out
        hasNextChapter = (currentChapter?.idx ?: 0) < (chapters.lastOrNull()?.idx ?: 0)
    }

    private fun similarTitle(a: String, b: String): Boolean {
        val x = a.lowercase().filter { it.isLetterOrDigit() }
        val y = b.lowercase().filter { it.isLetterOrDigit() }
        return x.isNotEmpty() && x.length < 120 && (x == y || y.contains(x) || x.contains(y))
    }

    fun indexOfParagraph(segment: Int, paragraph: Int): Int {
        val i = items.indexOfFirst { it is ReaderItem.Para && it.segment == segment && it.index >= paragraph }
        return if (i >= 0) i else items.indexOfFirst { it.segment == segment }.coerceAtLeast(0)
    }

    /** Called by the screen as the reader scrolls. */
    fun onScrolled(firstVisible: Int, lastVisible: Int, continuous: Boolean) {
        val item = items.getOrNull(firstVisible) ?: return
        val seg = item.segment
        val para = when (item) {
            is ReaderItem.Para -> item.index
            else -> if (item is ReaderItem.Footer) Int.MAX_VALUE else 0
        }
        if (seg != currentSegment) {
            // Moved into another chapter in continuous mode.
            val prev = segments.getOrNull(currentSegment)?.chapter
            currentSegment = seg
            val now = segments.getOrNull(seg)?.chapter
            if (prev != null && now != null && now.idx > prev.idx) {
                viewModelScope.launch { c.repository.saveProgress(prev, 0, 1f, true) }
            }
            now?.let { viewModelScope.launch { markOpened(it) } }
            hasNextChapter = (now?.idx ?: 0) < (chapters.lastOrNull()?.idx ?: 0)
        }
        currentParagraph = if (para == Int.MAX_VALUE) paragraphCount(seg) else para

        val last = items.getOrNull(lastVisible)
        val seenEnd = last is ReaderItem.Footer && last.segment == seg
        saveProgressThrottled(seenEnd)

        if (continuous && lastVisible >= items.size - 8) appendNext()
    }

    fun paragraphCount(segment: Int): Int =
        (segments.getOrNull(segment)?.state as? Load.Ok)?.value?.paragraphs?.size ?: 0

    val progressInChapter: Float
        get() {
            val total = paragraphCount(currentSegment)
            return if (total <= 1) 0f else (currentParagraph.toFloat() / (total - 1)).coerceIn(0f, 1f)
        }

    private fun saveProgressThrottled(finished: Boolean) {
        val now = System.currentTimeMillis()
        if (!finished && now - lastSaved < 1500) return
        lastSaved = now
        saveNow(finished)
    }

    fun saveNow(finished: Boolean = false) {
        val ch = currentChapter ?: return
        val p = progressInChapter
        val pos = currentParagraph
        viewModelScope.launch(Dispatchers.IO) {
            c.repository.saveProgress(ch, pos, if (finished) 1f else p, finished || p >= 0.98f)
        }
    }

    private suspend fun markOpened(ch: ChapterEntity) {
        c.repository.markOpened(ch)
        novel = c.repository.getNovel(novelId)
    }

    private fun appendNext() {
        if (loadingNext) return
        val last = segments.lastOrNull()?.chapter ?: return
        if (segments.last().state !is Load.Ok) return
        loadingNext = true
        viewModelScope.launch {
            try {
                val next = c.repository.next(last) ?: return@launch
                if (segments.any { it.chapter.id == next.id }) return@launch
                segments = (segments + Segment(next, Load.Loading)).let { if (it.size > 4) dropFirst(it) else it }
                rebuild()
                setSegment(next.id, load(next))
                prefetchNext(next)
            } finally {
                loadingNext = false
            }
        }
    }

    /** Keep memory small: forget chapters well above the current one. */
    private fun dropFirst(list: List<Segment>): List<Segment> {
        if (currentSegment <= 0) return list
        // The list keeps its place by row keys, so removing rows above doesn't jump the text.
        currentSegment -= 1
        return list.drop(1)
    }

    private fun prefetchNext(ch: ChapterEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val next = c.repository.next(ch) ?: return@launch
            runCatching { c.repository.chapterText(next) }
        }
    }

    fun goNext() {
        val ch = currentChapter ?: return
        viewModelScope.launch {
            c.repository.saveProgress(ch, 0, 1f, true)
            c.repository.next(ch)?.let { openChapter(it) }
        }
    }

    fun goPrevious() {
        val ch = currentChapter ?: return
        viewModelScope.launch { c.repository.previous(ch)?.let { openChapter(it) } }
    }

    fun jumpWithinChapter(fraction: Float) {
        val total = paragraphCount(currentSegment)
        if (total == 0) return
        val p = (fraction * (total - 1)).toInt()
        pendingScroll = indexOfParagraph(currentSegment, p)
    }

    fun addBookmark() {
        val ch = currentChapter ?: return
        val n = novel ?: return
        val text = (segments.getOrNull(currentSegment)?.state as? Load.Ok)?.value?.paragraphs?.getOrNull(currentParagraph).orEmpty()
        viewModelScope.launch {
            c.repository.addBookmark(
                BookmarkEntity(
                    novelId = n.id,
                    chapterId = ch.id,
                    chapterName = ch.name,
                    novelTitle = n.title,
                    cover = n.cover,
                    paragraph = currentParagraph,
                    snippet = text.take(160),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteBookmark(id: Long) = viewModelScope.launch { c.repository.deleteBookmark(id) }

    fun bookmarksForChapter(chapterId: String) = c.repository.observeChapterBookmarks(chapterId)

    fun download(count: Int) {
        val ch = currentChapter ?: return
        val n = novel ?: return
        viewModelScope.launch {
            val list = chapters.filter { it.idx >= ch.idx }.take(count)
            c.downloads.enqueue(n.title, list)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Text to speech

    var ttsActive by mutableStateOf(false)
        private set
    var ttsSpeaking by mutableStateOf<String?>(null)
        private set
    var ttsError by mutableStateOf<String?>(null)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsQueue: List<ReaderItem.Para> = emptyList()
    private var ttsPos = 0
    private var ttsJob: Job? = null

    fun toggleTts() {
        if (ttsActive) stopTts() else startTts()
    }

    private fun startTts() {
        ttsActive = true
        ttsError = null
        if (tts == null) {
            tts = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault().takeIf { tts?.isLanguageAvailable(it) ?: -1 >= 0 } ?: Locale.US
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            viewModelScope.launch { advanceTts() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            viewModelScope.launch { advanceTts() }
                        }
                    })
                    viewModelScope.launch { beginTtsAt(currentSegment, currentParagraph) }
                } else {
                    viewModelScope.launch {
                        ttsError = "Text-to-speech isn't available on this phone."
                        ttsActive = false
                    }
                }
            }
        } else if (ttsReady) {
            beginTtsAt(currentSegment, currentParagraph)
        }
    }

    private fun beginTtsAt(segment: Int, paragraph: Int) {
        ttsQueue = items.filterIsInstance<ReaderItem.Para>().filter { it.segment > segment || (it.segment == segment && it.index >= paragraph) }
        ttsPos = 0
        speakCurrent()
    }

    private fun speakCurrent() {
        val engine = tts ?: return
        val item = ttsQueue.getOrNull(ttsPos)
        if (item == null) {
            // Ran out of loaded text: load the next chapter and keep going.
            val lastSeg = segments.lastOrNull()?.chapter ?: return stopTts()
            ttsJob = viewModelScope.launch {
                val next = c.repository.next(lastSeg) ?: return@launch stopTts()
                if (segments.none { it.chapter.id == next.id }) {
                    segments = segments + Segment(next, Load.Loading)
                    rebuild()
                    setSegment(next.id, load(next))
                }
                val si = segments.indexOfFirst { it.chapter.id == next.id }
                if (ttsActive && si >= 0) beginTtsAt(si, 0)
            }
            return
        }
        ttsSpeaking = item.key
        pendingScroll = items.indexOfFirst { it.key == item.key }.takeIf { it >= 0 }
        val s = c.settings.current
        engine.setSpeechRate(s.ttsRate)
        engine.setPitch(s.ttsPitch)
        val max = (TextToSpeech.getMaxSpeechInputLength() - 10).coerceAtLeast(500)
        val text = if (item.text.length > max) item.text.take(max) else item.text
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), item.key)
    }

    private fun advanceTts() {
        if (!ttsActive) return
        ttsPos++
        speakCurrent()
    }

    fun stopTts() {
        ttsActive = false
        ttsSpeaking = null
        ttsJob?.cancel()
        tts?.stop()
    }

    override fun onCleared() {
        saveNow()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }

    suspend fun refreshChapters() {
        withContext(Dispatchers.IO) { runCatching { c.repository.refreshChapters(novelId) } }
        chapters = c.repository.chapterList(novelId)
        rebuild()
    }

    init {
        viewModelScope.launch {
            novel = c.repository.getNovel(novelId)
            chapters = c.repository.chapterList(novelId)
            val start = startChapterId?.let { c.repository.getChapter(it) } ?: c.repository.resumeChapter(novelId)
            if (start != null) openChapter(start, startParagraph)
        }
    }
}

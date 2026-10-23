package com.joe.novelreader.data

import android.content.Context
import com.joe.novelreader.core.model.ChapterText
import com.joe.novelreader.core.model.NovelDetails
import com.joe.novelreader.core.model.NovelItem
import com.joe.novelreader.core.source.NovelSource
import com.joe.novelreader.core.source.SourceCatalog
import com.joe.novelreader.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class SourceManager(network: Network, private val settings: SettingsRepository) {
    val all: List<NovelSource> = SourceCatalog.create(network.env)
    private val byId = all.associateBy { it.id }

    init {
        applyBaseUrls(settings.current)
    }

    fun applyBaseUrls(s: AppSettings) {
        all.forEach { it.baseUrl = s.baseUrls[it.id].orEmpty() }
    }

    fun get(id: String): NovelSource? = byId[id]
    fun require(id: String): NovelSource = byId[id] ?: error("Unknown source $id")

    val enabled: List<NovelSource> get() = all.filter { it.id !in settings.current.disabledSources }
}

/** Saves chapter text on the phone, either as a download (kept) or as a short-term cache. */
class ChapterStore(context: Context) {
    private val downloads = File(context.filesDir, "chapters")
    private val cache = File(context.cacheDir, "chapters")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(val title: String? = null, val paragraphs: List<String>)

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun file(root: File, novelId: String, chapterId: String) =
        File(File(root, hash(novelId)), hash(chapterId) + ".json")

    fun readDownloaded(novelId: String, chapterId: String): ChapterText? = read(file(downloads, novelId, chapterId))
    fun readCached(novelId: String, chapterId: String): ChapterText? = read(file(cache, novelId, chapterId))

    private fun read(f: File): ChapterText? = try {
        if (!f.exists()) null else json.decodeFromString<Stored>(f.readText()).let { ChapterText(it.title, it.paragraphs) }
    } catch (_: Exception) {
        null
    }

    fun saveDownloaded(novelId: String, chapterId: String, text: ChapterText) = write(file(downloads, novelId, chapterId), text)
    fun saveCached(novelId: String, chapterId: String, text: ChapterText) = write(file(cache, novelId, chapterId), text)

    private fun write(f: File, text: ChapterText) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(Stored(text.title, text.paragraphs)))
        tmp.renameTo(f)
    }

    fun deleteDownloaded(novelId: String, chapterId: String) {
        file(downloads, novelId, chapterId).delete()
    }

    fun deleteNovel(novelId: String) {
        File(downloads, hash(novelId)).deleteRecursively()
    }

    fun downloadedBytes(): Long = downloads.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun cacheBytes(): Long = cache.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clearCache() {
        cache.deleteRecursively()
    }
}

class NovelRepository(
    private val db: AppDatabase,
    private val sources: SourceManager,
    private val store: ChapterStore,
) {
    private val novels = db.novels()
    private val chapters = db.chapters()

    fun observeNovel(id: String): Flow<NovelEntity?> = novels.observe(id)
    fun observeChapters(novelId: String): Flow<List<ChapterEntity>> = chapters.observe(novelId)
    fun observeLibrary() = novels.observeLibrary()
    fun observeHistory() = novels.observeHistory()

    suspend fun getNovel(id: String) = novels.get(id)
    suspend fun getChapter(id: String) = chapters.get(id)
    suspend fun chapterList(novelId: String) = chapters.list(novelId)

    /** Makes sure a novel exists locally (from a list item) so its page can show something straight away. */
    suspend fun ensureNovel(item: NovelItem): String {
        val id = NovelEntity.makeId(item.sourceId, item.path)
        if (novels.get(id) == null) {
            novels.put(NovelEntity(id = id, sourceId = item.sourceId, path = item.path, title = item.title, cover = item.cover))
        }
        return id
    }

    suspend fun ensureNovel(sourceId: String, path: String, title: String = "", cover: String? = null): String =
        ensureNovel(NovelItem(sourceId, path, title, cover))

    /** Downloads the book's info and chapter list from the site and saves them. Returns how many chapters are new. */
    suspend fun refresh(novelId: String): Int = withContext(Dispatchers.IO) {
        val local = novels.get(novelId) ?: error("Novel not found")
        val source = sources.require(local.sourceId)
        val details = source.getDetails(local.path)
        saveDetails(local, details)
        syncChapters(novelId, source)
    }

    suspend fun refreshChapters(novelId: String): Int = withContext(Dispatchers.IO) {
        val local = novels.get(novelId) ?: error("Novel not found")
        syncChapters(novelId, sources.require(local.sourceId))
    }

    private suspend fun saveDetails(local: NovelEntity, d: NovelDetails) {
        val current = novels.get(local.id) ?: local
        novels.put(
            current.copy(
                title = d.title.ifBlank { current.title },
                author = d.author ?: current.author,
                cover = d.cover ?: current.cover,
                description = d.description ?: current.description,
                genres = d.genres.joinToString("|").ifBlank { current.genres },
                status = d.status ?: current.status,
                rating = d.rating ?: current.rating,
                detailsUpdatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun syncChapters(novelId: String, source: NovelSource): Int {
        val local = novels.get(novelId) ?: return 0
        val remote = source.getChapters(local.path)
        if (remote.isEmpty()) return 0
        val existing = chapters.list(novelId).associateBy { it.path }
        val toInsert = mutableListOf<ChapterEntity>()
        val toUpdate = mutableListOf<ChapterEntity>()
        remote.forEachIndexed { i, c ->
            val old = existing[c.path]
            if (old == null) {
                toInsert += ChapterEntity(ChapterEntity.makeId(novelId, c.path), novelId, c.path, c.name, i, c.date)
            } else if (old.idx != i || old.name != c.name) {
                toUpdate += old.copy(idx = i, name = c.name, date = c.date ?: old.date)
            }
        }
        val remotePaths = remote.map { it.path }.toHashSet()
        val gone = existing.values.filter { it.path !in remotePaths && !it.downloaded }.map { it.id }
        chapters.insertAll(toInsert)
        if (toUpdate.isNotEmpty()) chapters.updateAll(toUpdate)
        if (gone.isNotEmpty() && gone.size < remote.size) gone.chunked(500).forEach { chapters.delete(it) }
        val newCount = if (existing.isEmpty()) 0 else toInsert.size
        val fresh = novels.get(novelId) ?: return newCount
        novels.put(
            fresh.copy(
                chapterCount = remote.size,
                newChapters = if (fresh.inLibrary) fresh.newChapters + newCount else 0,
            ),
        )
        return newCount
    }

    suspend fun setInLibrary(novelId: String, inLibrary: Boolean) {
        novels.setInLibrary(novelId, inLibrary, if (inLibrary) System.currentTimeMillis() else 0)
        if (!inLibrary) novels.setNewChapters(novelId, 0)
    }

    suspend fun clearNew(novelId: String) = novels.setNewChapters(novelId, 0)

    /** Gets a chapter's text: from downloads first, then the cache, then the website. */
    suspend fun chapterText(chapter: ChapterEntity, forceNetwork: Boolean = false): ChapterText = withContext(Dispatchers.IO) {
        if (!forceNetwork) {
            store.readDownloaded(chapter.novelId, chapter.id)?.let { return@withContext it }
            store.readCached(chapter.novelId, chapter.id)?.let { return@withContext it }
        }
        val novel = novels.get(chapter.novelId) ?: error("Novel not found")
        val text = sources.require(novel.sourceId).getChapterText(chapter.path)
        if (chapter.downloaded) store.saveDownloaded(chapter.novelId, chapter.id, text)
        else store.saveCached(chapter.novelId, chapter.id, text)
        text
    }

    suspend fun downloadChapter(chapter: ChapterEntity) = withContext(Dispatchers.IO) {
        val existing = store.readDownloaded(chapter.novelId, chapter.id)
        if (existing == null) {
            val cached = store.readCached(chapter.novelId, chapter.id)
            val text = cached ?: run {
                val novel = novels.get(chapter.novelId) ?: error("Novel not found")
                sources.require(novel.sourceId).getChapterText(chapter.path)
            }
            store.saveDownloaded(chapter.novelId, chapter.id, text)
        }
        chapters.setDownloaded(chapter.id, true)
    }

    suspend fun deleteDownload(chapter: ChapterEntity) {
        store.deleteDownloaded(chapter.novelId, chapter.id)
        chapters.setDownloaded(chapter.id, false)
    }

    suspend fun deleteAllDownloads(novelId: String) {
        store.deleteNovel(novelId)
        chapters.clearDownloaded(novelId)
        db.downloads().clearNovel(novelId)
    }

    suspend fun saveProgress(chapter: ChapterEntity, position: Int, progress: Float, finished: Boolean) {
        chapters.setProgress(chapter.id, position, progress, finished)
    }

    suspend fun markOpened(chapter: ChapterEntity) {
        novels.setLastRead(chapter.novelId, chapter.id, chapter.name, System.currentTimeMillis())
    }

    suspend fun setRead(ids: List<String>, read: Boolean) = ids.chunked(500).forEach { chapters.setRead(it, read) }
    suspend fun markReadBefore(novelId: String, idx: Int) = chapters.markReadBefore(novelId, idx)

    suspend fun next(chapter: ChapterEntity) = chapters.next(chapter.novelId, chapter.idx)
    suspend fun previous(chapter: ChapterEntity) = chapters.previous(chapter.novelId, chapter.idx)

    /** The chapter to open when pressing "Read" / "Continue". */
    suspend fun resumeChapter(novelId: String): ChapterEntity? {
        val novel = novels.get(novelId)
        novel?.lastReadChapterId?.let { chapters.get(it) }?.let { last ->
            return if (last.read && last.progress >= 0.98f) chapters.next(novelId, last.idx) ?: last else last
        }
        return chapters.firstUnread(novelId) ?: chapters.first(novelId)
    }

    suspend fun clearHistory(novelId: String) = novels.clearHistory(novelId)
    suspend fun clearAllHistory() = novels.clearAllHistory()

    suspend fun readGenres(): List<String> = novels.readGenres().flatMap { it.split('|') }.map { it.trim() }.filter { it.isNotEmpty() }
    suspend fun knownPaths(): Set<String> = novels.knownPaths().toHashSet()

    fun observeBookmarks() = db.bookmarks().observeAll()
    fun observeChapterBookmarks(chapterId: String) = db.bookmarks().observeForChapter(chapterId)
    suspend fun addBookmark(b: BookmarkEntity) = db.bookmarks().add(b)
    suspend fun deleteBookmark(id: Long) = db.bookmarks().delete(id)
}

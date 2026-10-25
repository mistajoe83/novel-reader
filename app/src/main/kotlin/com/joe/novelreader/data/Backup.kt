package com.joe.novelreader.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupNovel(
    val sourceId: String,
    val path: String,
    val title: String,
    val cover: String? = null,
    val inLibrary: Boolean = true,
    val addedAt: Long = 0,
    val lastReadAt: Long = 0,
    val lastReadChapterPath: String? = null,
    val readChapters: List<String> = emptyList(),
)

@Serializable
data class BackupBookmark(
    val sourceId: String,
    val novelPath: String,
    val chapterPath: String,
    val chapterName: String,
    val novelTitle: String,
    val cover: String? = null,
    val paragraph: Int,
    val snippet: String,
    val createdAt: Long,
)

@Serializable
data class BackupFile(
    val version: Int = 2,
    val createdAt: Long,
    val novels: List<BackupNovel>,
    val bookmarks: List<BackupBookmark> = emptyList(),
    /** App settings (reader, theme, DNS, sources). Missing in version 1 backups. */
    val settings: AppSettings? = null,
)

class RestoreResult(val novels: Int, val bookmarks: Int, val settingsRestored: Boolean)

class BackupManager(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }

    suspend fun export(): String {
        val novels = db.novels().libraryNovels()
        val items = novels.map { n ->
            val chapters = db.chapters().list(n.id)
            BackupNovel(
                sourceId = n.sourceId,
                path = n.path,
                title = n.title,
                cover = n.cover,
                inLibrary = n.inLibrary,
                addedAt = n.addedAt,
                lastReadAt = n.lastReadAt,
                lastReadChapterPath = chapters.firstOrNull { it.id == n.lastReadChapterId }?.path,
                readChapters = chapters.filter { it.read }.map { it.path },
            )
        }
        val marks = db.bookmarks().all().mapNotNull { b ->
            val n = db.novels().get(b.novelId) ?: return@mapNotNull null
            val ch = db.chapters().get(b.chapterId) ?: return@mapNotNull null
            BackupBookmark(n.sourceId, n.path, ch.path, b.chapterName, b.novelTitle, b.cover, b.paragraph, b.snippet, b.createdAt)
        }
        return json.encodeToString(BackupFile(createdAt = System.currentTimeMillis(), novels = items, bookmarks = marks, settings = settings.current))
    }

    /** Restores books into the library. Chapter lists are fetched again when each book is opened. */
    suspend fun import(text: String): RestoreResult {
        val file = json.decodeFromString<BackupFile>(text)
        for (b in file.novels) {
            val id = NovelEntity.makeId(b.sourceId, b.path)
            val existing = db.novels().get(id)
            val lastId = b.lastReadChapterPath?.let { ChapterEntity.makeId(id, it) }
            db.novels().put(
                (existing ?: NovelEntity(id = id, sourceId = b.sourceId, path = b.path, title = b.title, cover = b.cover)).copy(
                    inLibrary = b.inLibrary || existing?.inLibrary == true,
                    addedAt = if (b.addedAt > 0) b.addedAt else System.currentTimeMillis(),
                    lastReadAt = maxOf(b.lastReadAt, existing?.lastReadAt ?: 0),
                    lastReadChapterId = existing?.lastReadChapterId ?: lastId,
                ),
            )
            // Chapters we know were read are stored now; names fill in after the next refresh.
            val read = b.readChapters.mapIndexed { i, p -> ChapterEntity(ChapterEntity.makeId(id, p), id, p, p.substringAfterLast('/'), i, read = true) }
            db.chapters().insertAll(read)
            read.map { it.id }.chunked(500).forEach { db.chapters().setRead(it, true) }
        }
        var addedMarks = 0
        for (m in file.bookmarks) {
            val nid = NovelEntity.makeId(m.sourceId, m.novelPath)
            val chapterId = ChapterEntity.makeId(nid, m.chapterPath)
            // Restoring the same backup twice must not duplicate bookmarks.
            if (db.bookmarks().forNovel(nid).any { it.chapterId == chapterId && it.paragraph == m.paragraph }) continue
            addedMarks++
            db.bookmarks().add(
                BookmarkEntity(
                    novelId = nid,
                    chapterId = chapterId,
                    chapterName = m.chapterName,
                    novelTitle = m.novelTitle,
                    cover = m.cover,
                    paragraph = m.paragraph,
                    snippet = m.snippet,
                    createdAt = m.createdAt,
                ),
            )
        }
        file.settings?.let { restored -> settings.update { restored.copy(onboarded = true) } }
        return RestoreResult(file.novels.size, addedMarks, file.settings != null)
    }
}

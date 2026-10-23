package com.joe.novelreader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "novels", indices = [Index("inLibrary"), Index("lastReadAt")])
data class NovelEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val path: String,
    val title: String,
    val author: String? = null,
    val cover: String? = null,
    val description: String? = null,
    val genres: String = "",
    val status: String? = null,
    val rating: Float? = null,
    val inLibrary: Boolean = false,
    val addedAt: Long = 0,
    val lastReadChapterId: String? = null,
    val lastReadChapterName: String? = null,
    val lastReadAt: Long = 0,
    val chapterCount: Int = 0,
    val newChapters: Int = 0,
    val detailsUpdatedAt: Long = 0,
) {
    val genreList: List<String> get() = genres.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun makeId(sourceId: String, path: String) = "$sourceId:$path"
    }
}

@Entity(tableName = "chapters", indices = [Index("novelId"), Index(value = ["novelId", "idx"])])
data class ChapterEntity(
    @PrimaryKey val id: String,
    val novelId: String,
    val path: String,
    val name: String,
    val idx: Int,
    val date: String? = null,
    val read: Boolean = false,
    /** Paragraph the reader was on. */
    val position: Int = 0,
    /** 0..1 how far through the chapter. */
    val progress: Float = 0f,
    val downloaded: Boolean = false,
) {
    companion object {
        fun makeId(novelId: String, path: String) = "$novelId|$path"
    }
}

@Entity(tableName = "bookmarks", indices = [Index("novelId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: String,
    val chapterId: String,
    val chapterName: String,
    val novelTitle: String,
    val cover: String?,
    val paragraph: Int,
    val snippet: String,
    val createdAt: Long,
)

@Entity(tableName = "downloads", indices = [Index("novelId")])
data class DownloadEntity(
    @PrimaryKey val chapterId: String,
    val novelId: String,
    val novelTitle: String,
    val chapterName: String,
    val idx: Int,
    val addedAt: Long,
    val error: String? = null,
)

data class LibraryNovel(
    @Embedded val novel: NovelEntity,
    val unread: Int,
    val downloadedCount: Int,
    val total: Int,
)

data class DownloadSummary(
    val novelId: String,
    val novelTitle: String,
    val count: Int,
    val failed: Int,
)

@Dao
interface NovelDao {
    @Query(
        """
        SELECT n.*,
          (SELECT COUNT(*) FROM chapters c WHERE c.novelId = n.id AND c.read = 0) AS unread,
          (SELECT COUNT(*) FROM chapters c WHERE c.novelId = n.id AND c.downloaded = 1) AS downloadedCount,
          (SELECT COUNT(*) FROM chapters c WHERE c.novelId = n.id) AS total
        FROM novels n WHERE n.inLibrary = 1
        """,
    )
    fun observeLibrary(): Flow<List<LibraryNovel>>

    @Query("SELECT * FROM novels WHERE inLibrary = 1")
    suspend fun libraryNovels(): List<NovelEntity>

    @Query("SELECT * FROM novels WHERE lastReadAt > 0 ORDER BY lastReadAt DESC LIMIT 300")
    fun observeHistory(): Flow<List<NovelEntity>>

    @Query("SELECT * FROM novels WHERE id = :id")
    fun observe(id: String): Flow<NovelEntity?>

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun get(id: String): NovelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(novel: NovelEntity)

    @Query("UPDATE novels SET inLibrary = :inLibrary, addedAt = :addedAt WHERE id = :id")
    suspend fun setInLibrary(id: String, inLibrary: Boolean, addedAt: Long)

    @Query("UPDATE novels SET lastReadChapterId = :chapterId, lastReadChapterName = :chapterName, lastReadAt = :at WHERE id = :id")
    suspend fun setLastRead(id: String, chapterId: String, chapterName: String, at: Long)

    @Query("UPDATE novels SET lastReadAt = 0 WHERE id = :id")
    suspend fun clearHistory(id: String)

    @Query("UPDATE novels SET lastReadAt = 0")
    suspend fun clearAllHistory()

    @Query("UPDATE novels SET newChapters = :count WHERE id = :id")
    suspend fun setNewChapters(id: String, count: Int)

    @Query("SELECT genres FROM novels WHERE inLibrary = 1 OR lastReadAt > 0")
    suspend fun readGenres(): List<String>

    @Query("SELECT path FROM novels WHERE inLibrary = 1 OR lastReadAt > 0")
    suspend fun knownPaths(): List<String>
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY idx")
    fun observe(novelId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY idx")
    suspend fun list(novelId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE id = :id")
    fun observeOne(id: String): Flow<ChapterEntity?>

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND idx > :idx ORDER BY idx LIMIT 1")
    suspend fun next(novelId: String, idx: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND idx < :idx ORDER BY idx DESC LIMIT 1")
    suspend fun previous(novelId: String, idx: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId AND read = 0 ORDER BY idx LIMIT 1")
    suspend fun firstUnread(novelId: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY idx LIMIT 1")
    suspend fun first(novelId: String): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Update
    suspend fun updateAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("UPDATE chapters SET read = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: List<String>, read: Boolean)

    @Query("UPDATE chapters SET read = 1 WHERE novelId = :novelId AND idx < :idx")
    suspend fun markReadBefore(novelId: String, idx: Int)

    @Query("UPDATE chapters SET position = :position, progress = :progress, read = CASE WHEN :read THEN 1 ELSE read END WHERE id = :id")
    suspend fun setProgress(id: String, position: Int, progress: Float, read: Boolean)

    @Query("UPDATE chapters SET downloaded = :downloaded WHERE id = :id")
    suspend fun setDownloaded(id: String, downloaded: Boolean)

    @Query("UPDATE chapters SET downloaded = 0 WHERE novelId = :novelId")
    suspend fun clearDownloaded(novelId: String)

    @Query("SELECT COUNT(*) FROM chapters WHERE novelId = :novelId")
    suspend fun count(novelId: String): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE downloaded = 1")
    fun observeDownloadedCount(): Flow<Int>
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE chapterId = :chapterId")
    fun observeForChapter(chapterId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE novelId = :novelId ORDER BY createdAt DESC")
    fun observeForNovel(novelId: String): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun add(b: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM bookmarks")
    suspend fun all(): List<BookmarkEntity>
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY addedAt, idx")
    fun observeQueue(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE error IS NULL ORDER BY addedAt, idx LIMIT 1")
    suspend fun nextPending(): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE error IS NULL")
    suspend fun pendingCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(items: List<DownloadEntity>)

    @Query("DELETE FROM downloads WHERE chapterId = :chapterId")
    suspend fun remove(chapterId: String)

    @Query("UPDATE downloads SET error = :error WHERE chapterId = :chapterId")
    suspend fun fail(chapterId: String, error: String)

    @Query("UPDATE downloads SET error = NULL")
    suspend fun retryFailed()

    @Query("DELETE FROM downloads")
    suspend fun clear()

    @Query("DELETE FROM downloads WHERE novelId = :novelId")
    suspend fun clearNovel(novelId: String)
}

@Database(
    entities = [NovelEntity::class, ChapterEntity::class, BookmarkEntity::class, DownloadEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun novels(): NovelDao
    abstract fun chapters(): ChapterDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "novelnest.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

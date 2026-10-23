package com.joe.novelreader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.joe.novelreader.data.AppDatabase
import com.joe.novelreader.data.ChapterStore
import com.joe.novelreader.data.NovelRepository
import com.joe.novelreader.data.SettingsRepository
import com.joe.novelreader.data.SourceManager
import com.joe.novelreader.net.Network
import com.joe.novelreader.work.DownloadManager
import com.joe.novelreader.work.Notifications
import com.joe.novelreader.work.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(app, scope)
    val network = Network(app) { settings.current.dnsConfig }
    val db = AppDatabase.build(app)
    val sources = SourceManager(network, settings)
    val store = ChapterStore(app)
    val repository = NovelRepository(db, sources, store)
    val downloads = DownloadManager(app)
    val rules = com.joe.novelreader.data.RulesManager(app, network, sources)

    init {
        scope.launch {
            if (rules.shouldAutoCheck()) rules.refresh()
        }
        scope.launch {
            settings.flow.map { it.dnsConfig }.distinctUntilChanged().drop(1).collect { network.onDnsChanged() }
        }
        scope.launch {
            settings.flow.map { it.baseUrls }.distinctUntilChanged().collect { sources.applyBaseUrls(settings.current) }
        }
        scope.launch {
            settings.flow.map { it.autoUpdate }.distinctUntilChanged().collect { UpdateWorker.schedule(app, it) }
        }
    }

    /** Looks for new chapters in every library book. Returns (title, new chapter count) for books that have some. */
    suspend fun checkLibraryUpdates(): List<Pair<String, Int>> {
        val lib = db.novels().libraryNovels()
        val gate = Semaphore(3)
        return kotlinx.coroutines.coroutineScope {
            lib.map { n ->
                async {
                    gate.withPermit {
                        runCatching { repository.refreshChapters(n.id) }.getOrDefault(0).let { n.title to it }
                    }
                }
            }.awaitAll().filter { it.second > 0 }
        }
    }
}

class NovelApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.scope.launch {
            if (container.db.downloads().pendingCount() > 0) container.downloads.start()
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { container.network.imageClient }
        .crossfade(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(150L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()
}

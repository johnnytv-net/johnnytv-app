package com.johnnytv.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class JohnnyTvApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Whatever logo overrides were downloaded last time, ready before any screen draws.
        LogoPack.load(this)
        // Alarms do not survive an app being force-stopped or updated, and a
        // recording somebody set for tonight has to happen tonight - so they are
        // all set again from the file every time the app starts.
        runCatching { RecordScheduler.armAll(this) }
    }

    /**
     * A catalogue of thousands of posters can push the image cache into the app's
     * whole heap and get it killed, so cap what it may hold in memory and let the
     * disk cache do the heavy lifting instead.
     *
     * The memory cap used to be tight enough that film posters - seven to a row and
     * far heavier than a channel logo - were evicted within a screen or two of
     * scrolling. Every eviction is another fetch, and a fetch that fails leaves the
     * JohnnyTV mark sitting where the poster was. A quarter of the heap holds a
     * couple of screens of artwork, and the bigger disk cache means a poster that
     * has been seen once never needs the portal again.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("artwork"))
                .maxSizeBytes(256L * 1024L * 1024L)
                .build()
        }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}

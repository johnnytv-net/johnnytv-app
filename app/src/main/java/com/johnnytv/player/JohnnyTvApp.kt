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

        /*
         * And then, quietly, the current ones.
         *
         * Until now the logo pack was only fetched on the sign-in screen, which
         * sounds reasonable until you remember that a customer signs in once and
         * never again. Every correction made to the pack after that day never
         * reached them. So it is fetched on every start as well: off the main
         * thread, ignored if it fails, and it replaces nothing until it has
         * arrived in full. Screens already drawn keep the old artwork until the
         * next time they are opened, which is exactly how nobody notices.
         */
        Thread {
            runCatching { LogoPack.refresh(applicationContext, null) }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
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

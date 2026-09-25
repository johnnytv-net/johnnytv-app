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
         * Until now the pack was only fetched on the sign-in screen, which sounds
         * reasonable until you remember that a customer signs in once and never
         * again: every correction made after that day never reached them. So it
         * is fetched on every start as well - off the main thread, ignored if it
         * fails, and it replaces nothing until it has arrived in full. Screens
         * already drawn keep the old artwork until they are next opened, which is
         * exactly how nobody notices.
         */
        Thread {
            runCatching { LogoPack.refresh(applicationContext, null) }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        // Alarms do not survive an app being force-stopped or updated, and a
        // recording somebody set for tonight has to happen tonight - so they are
        // all set again from the file every time the app starts.
        runCatching { RecordScheduler.armAll(this) }
        // And go looking for anything set from a phone while this box was off.
        runCatching { Postman.start(this) }
        // Sharing, if it is switched on, comes back with the app.
        runCatching { ShareService.apply(this) }

        /*
         * FOLLOWING A CUSTOMER WHO HAS BEEN MOVED - CAREFULLY.
         *
         * The first version of this took the site at its word: whatever line
         * and address came back were saved on the spot. That stranded a box
         * whose entry named a host that no longer resolved - the catalogue was
         * cleared, nothing could be fetched, and no amount of restarting helped
         * because the same bad address was read again every time. Only a
         * reinstall cleared it. My fault, and the sort that reaches customers
         * rather than me.
         *
         * So nothing is saved until it has been proved: the new line is signed
         * into first, and only a success is written down. A wrong address, a
         * lapsed line or a site having a bad morning all leave the box exactly
         * as it was - still working on what it had.
         */
        Thread {
            runCatching {
                val prefs = Prefs(applicationContext)
                val name = prefs.friendlyName
                if (name.isBlank()) return@runCatching

                val real = FriendlyLogin.lookUp(name, Config.CUSTOMER_PASSWORD) ?: return@runCatching
                if (real.username.isBlank() || real.password.isBlank()) return@runCatching

                val moved = real.username != prefs.username || real.password != prefs.password
                if (!moved) return@runCatching

                // Where to try: what the site named first, then the usual list,
                // so a mistake in one entry cannot cut a box off from the rest.
                val candidates = ArrayList<String>()
                if (real.server.isNotBlank()) candidates.add(RemoteConfigLoader.resolve(real.server))
                candidates.add(prefs.server)
                for (fallback in Config.SERVERS) {
                    val resolved = RemoteConfigLoader.resolve(fallback)
                    if (!candidates.contains(resolved)) candidates.add(resolved)
                }

                for (address in candidates) {
                    if (address.isBlank()) continue
                    val client = XtreamClient(address, real.username, real.password)
                    val worked = runCatching { client.login() }.isSuccess
                    if (worked) {
                        prefs.saveCredentials(address, real.username, real.password)
                        prefs.rememberLogin(address, real.username, real.password)
                        // The catalogue belonged to the old line, so it goes -
                        // but only now that there is a working one to replace it.
                        runCatching { Catalog.clear(applicationContext) }
                        break
                    }
                }
            }
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

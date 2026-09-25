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
         * FOLLOWING A CUSTOMER WHO HAS BEEN MOVED.
         *
         * Anybody signed in with a name rather than a code has that name kept
         * beside their real line. At every start the site is asked again, and
         * if the answer has changed - because they were moved to another panel
         * - the box quietly picks up the new line. Nobody is told anything and
         * nothing is reinstalled.
         *
         * Off the main thread, ignored if the site cannot be reached, and it
         * changes nothing unless it has a complete answer in hand.
         */
        Thread {
            runCatching {
                val prefs = Prefs(applicationContext)
                val name = prefs.friendlyName
                if (name.isNotBlank()) {
                    val real = FriendlyLogin.lookUp(name, Config.CUSTOMER_PASSWORD)
                    if (real != null && real.username.isNotBlank()) {
                        val moved = real.username != prefs.username ||
                            real.password != prefs.password
                        if (moved) {
                            val server = if (real.server.isNotBlank()) {
                                RemoteConfigLoader.resolve(real.server)
                            } else {
                                prefs.server
                            }
                            prefs.saveCredentials(server, real.username, real.password)
                            prefs.rememberLogin(server, real.username, real.password)
                            // The catalogue belongs to the old line; drop it so the
                            // next screen fetches the new one rather than showing
                            // channels this customer may no longer have.
                            runCatching { Catalog.clear(applicationContext) }
                        }
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

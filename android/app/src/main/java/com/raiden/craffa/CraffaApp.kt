package com.raiden.craffa

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.raiden.craffa.data.api.CraffaApi
import com.raiden.craffa.data.api.OkHttpTransport
import com.raiden.craffa.data.settings.SettingsStore
import com.raiden.craffa.reminders.Notifications
import com.raiden.craffa.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual DI: one of each, created once. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = SettingsStore(appContext)

    /** For work that must outlive a screen (e.g. the last save on leaving the editor). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Image loads (Coil) share this client: add the token for our own server only.
        .addInterceptor { chain ->
            val req = chain.request()
            val cfg = settings.config
            val serverHost = cfg.normalizedBase.toHttpUrlOrNull()
            val needsAuth = serverHost != null && req.url.host == serverHost.host &&
                req.url.port == serverHost.port && req.header("Authorization") == null && cfg.token.isNotBlank()
            chain.proceed(if (needsAuth) req.newBuilder().header("Authorization", "Bearer ${cfg.token}").build() else req)
        }
        .build()

    val api = CraffaApi({ settings.config }, OkHttpTransport(okHttp))
}

/** Run blocking API work off the main thread. */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

class CraffaApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannel(this)
        ReminderScheduler.schedulePeriodic(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.okHttp })) }
            .crossfade(true)
            .build()
}

val Context.container: AppContainer get() = (applicationContext as CraffaApp).container

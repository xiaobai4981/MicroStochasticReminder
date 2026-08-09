package cc.polysfaer.stochapop

import android.app.Application
import cc.polysfaer.stochapop.ads.AdManager
import cc.polysfaer.stochapop.data.AppContainer
import cc.polysfaer.stochapop.data.AppDataContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainApplication : Application() {
    lateinit var container: AppContainer
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        container = AppDataContainer(this)

        AdManager.init(this)

        appScope.launch {
            val enabled = fetchRemoteConfig()

            withContext(Dispatchers.Main) {
                AdManager.setEnabled(enabled)
            }
        }
    }

    private suspend fun fetchRemoteConfig(): Boolean {
        return try {
            val url = URL("https://lyhd-7e893.web.app/appText/xiaobia4861@gmail.com/MicroStochasticReminder.txt")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000

            val text = conn.inputStream.bufferedReader().readText().trim()
            val map = text.split("=")
                .map { it.trim() }

            if (map.size == 2 && map[0] == "enableAd") {
                map[1].toBoolean()
            } else {
                false
            }
        } catch (e: Exception) {
            false // 默认关闭
        }
    }
}
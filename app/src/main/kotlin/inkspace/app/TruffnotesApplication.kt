package inkspace.app

import android.app.Application
import android.content.ComponentCallbacks2
import inkspace.core.outcome.Outcome
import inkspace.core.runtime.MemoryPressureLevel
import inkspace.core.runtime.PlatformPorts
import inkspace.core.runtime.RuntimeConfig
import java.io.File

class TruffnotesApplication : Application() {

    @Volatile
    var appContainer: TruffnotesAppContainer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        val storageDir = File(filesDir, "truffnotes-store")
        val started = TruffnotesAppContainer.create(
            config = RuntimeConfig(storageRootPath = storageDir.absolutePath),
            ports = PlatformPorts()
        )
        if (started is Outcome.Success) {
            started.value.runtime.configureAndroid(this)
            appContainer = started.value
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val pressure = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> MemoryPressureLevel.CRITICAL
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryPressureLevel.MODERATE
            else -> MemoryPressureLevel.LOW
        }
        appContainer?.onTrimMemory(pressure)
    }
}

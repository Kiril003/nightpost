package local.phantom.companion.core.voice

object VoskRuntime {
    val shipped: Boolean by lazy {
        runCatching { Class.forName("org.vosk.LibVosk", false, VoskRuntime::class.java.classLoader) }.isSuccess
    }
}

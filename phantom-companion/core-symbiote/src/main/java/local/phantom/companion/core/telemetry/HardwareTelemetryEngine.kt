package local.phantom.companion.core.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * PSS і вільна RAM для екрана діагностики.
 */
class HardwareTelemetryEngine(
    private val context: Context
) {
    data class TelemetrySnapshot(
        val pssMemoryMb: Int,
        val availableRamMb: Int,
        val isLowMemory: Boolean,
    )

    fun streamTelemetry(): Flow<TelemetrySnapshot> = flow {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        val debugMemInfo = Debug.MemoryInfo()
        
        while (true) {
            am.getMemoryInfo(memoryInfo)
            Debug.getMemoryInfo(debugMemInfo)
            
            // Extract PSS memory footprint
            val pssMb = debugMemInfo.totalPss / 1024
            val availMb = (memoryInfo.availMem / (1024 * 1024)).toInt()
            
            emit(TelemetrySnapshot(
                pssMemoryMb = pssMb,
                availableRamMb = availMb,
                isLowMemory = memoryInfo.lowMemory,
            ))
            delay(1000)
        }
    }
}

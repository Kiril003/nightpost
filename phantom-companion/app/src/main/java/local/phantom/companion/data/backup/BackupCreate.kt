package local.phantom.companion.data.backup

import java.io.OutputStream

internal fun writeFirstBackup(open: () -> OutputStream?, seal: (OutputStream) -> Unit, discard: () -> Unit): Boolean {
    val ok = runCatching { (open() ?: error("no stream")).use(seal) }.isSuccess
    if (!ok) runCatching(discard)
    return ok
}

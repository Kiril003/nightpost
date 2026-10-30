package local.phantom.companion

import android.os.Process
import android.os.SystemClock
import android.util.Log

internal object StartMarks {
    private const val TAG = "PhantomStart"

    fun mark(step: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "+${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()}ms $step")
    }

    /**
     * Paint timings of the page, reported by the page itself into logcat —
     * plus `home`: the moment the real first screen (PhantomHome, its ask field
     * live) replaced the skeleton. Times are page-relative (performance.now(),
     * zero at loadUrl); add `web:loadUrl` for process-relative.
     */
    const val PAINT_PROBE = """
        try { new PerformanceObserver(function (l) {
            l.getEntries().forEach(function (e) {
                console.info('[PhantomStart] ' + e.entryType + ' ' + (e.name || '') + ' ' + Math.round(e.startTime));
            });
        }).observe({ type: 'paint', buffered: true }); } catch (e) {}
        try { new PerformanceObserver(function (l) {
            var e = l.getEntries().pop();
            console.info('[PhantomStart] lcp ' + Math.round(e.startTime) + ' ' + (e.element ? e.element.tagName : ''));
        }).observe({ type: 'largest-contentful-paint', buffered: true }); } catch (e) {}
        try { document.addEventListener('DOMContentLoaded', function () {
            var seen = function () {
                if (!document.querySelector('[data-testid="phantom-home"]')) return false;
                console.info('[PhantomStart] home ' + Math.round(performance.now()));
                return true;
            };
            if (seen()) return;
            var mo = new MutationObserver(function () { if (seen()) mo.disconnect(); });
            mo.observe(document.body, { childList: true, subtree: true });
        }); } catch (e) {}
    """
}

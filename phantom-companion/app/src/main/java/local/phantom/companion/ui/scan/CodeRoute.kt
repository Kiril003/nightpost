package local.phantom.companion.ui.scan

import java.net.URI
import java.net.URLDecoder
import local.phantom.companion.core.geo.link.MapLink
import local.phantom.companion.core.net.auth.WebAuthQr
import local.phantom.companion.core.net.pair.PairingQr
import local.phantom.companion.core.net.pair.SelfHandoverClient
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerSafety
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.PayLink
import local.phantom.companion.data.PcPairing

/** Куди веде прочитаний QR-код; `null` — код не наш, камера шукає далі. */
internal sealed interface CodeRoute {
    data class Person(val invite: PeerInvite.Invite) : CodeRoute
    data class OldInvite(val raw: String) : CodeRoute
    data class SafetyNumber(val text: String) : CodeRoute
    data class Computer(val json: String) : CodeRoute
    data object OwnDevice : CodeRoute
    data class SiteSignIn(val payload: String, val viaLink: Boolean) : CodeRoute
    data class Pay(val link: PayLink) : CodeRoute
    data class Place(val point: MapLink.Point) : CodeRoute

    companion object {
        const val MAX = 4096

        fun classify(raw: String, cluster: Cluster): CodeRoute? {
            val t = raw.trim()
            if (t.isEmpty() || t.length > MAX) return null
            if (t.startsWith(WebAuthQr.PREFIX + "|")) return SiteSignIn(t, false)
            signInLink(t)?.let { return SiteSignIn(it, true) }
            if (t.startsWith(PayLink.SCHEME)) return PayLink.parse(t, cluster)?.let(::Pay)
            if (PeerSafety.read(t) != null) return SafetyNumber(t)
            if (t.startsWith("{")) {
                if (PcPairing.parse(t) is PcPairing.Input.Qr) return Computer(t)
                if (runCatching { PairingQr.fromJson(t) }.getOrNull()?.purpose == SelfHandoverClient.PURPOSE_SELF) {
                    return OwnDevice
                }
            }
            when (val d = PeerInvite.decode(PeerInvite.codeInLink(t) ?: t)) {
                is PeerInvite.Decoded.Ph2 -> return Person(d.invite)
                PeerInvite.Decoded.LegacyPh1 -> return OldInvite(t)
                else -> Unit
            }
            MapLink.parse(t)?.let { return Place(it) }
            return PayLink.parse(t, cluster)?.let(::Pay)
        }

        private fun signInLink(t: String): String? {
            val uri = runCatching { URI(t) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true)) return null
            if (!uri.rawAuthority.equals(PeerInvite.WEB_HOST, ignoreCase = true)) return null
            if (uri.rawPath != "/s") return null
            val rawQuery = uri.rawQuery ?: return null
            val rawP = rawQuery.split('&').firstOrNull { it.startsWith("p=") }?.removePrefix("p=") ?: return null
            return runCatching { URLDecoder.decode(rawP, "UTF-8") }.getOrDefault(rawP).trim().takeIf { it.isNotEmpty() }
        }
    }
}

internal fun matchSafety(text: String, mine: String, people: List<Pair<String, String>>): String? =
    people.firstOrNull { PeerSafety.compare(text, mine, it.second) == PeerSafety.Outcome.MATCH }?.first

package local.phantom.companion.core.bus

import kotlinx.serialization.Serializable

/**
 * Core event definition for the PHANTOM Symbiote.
 * Represents a single unified node in the append-only event-sourced nervous stream.
 */
@Serializable
data class PhantomEvent(
    val id: String,
    val timestampMs: Long,
    val sourceOrgan: String,
    val type: String,
    val payloadJson: String,
    val contextFrameRef: String? = null,
    val causalParent: String? = null,
    val confidence: Float = 1.0f,
    val deviceOrigin: String,
    val privacy: String // public | trusted | vault
)

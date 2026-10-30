package local.phantom.companion.core.design.component

/**
 * Push-to-talk lifecycle as the shell reports it.
 *
 * Lives in core-design because both sides of the seam read it: the app's
 * `PhantomViewModel.PttRuntime` carries it as state, and
 * `core-design.theme.pttRingScale` maps it to a ring envelope. The composable
 * that originally declared it (`PttButton`) was deleted on 2026-09-05 as
 * unreachable — zero call sites — so the enum now stands on its own.
 */
enum class PttState { Idle, Capturing, AwaitingFinal, Speaking }

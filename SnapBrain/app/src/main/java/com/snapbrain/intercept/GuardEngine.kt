package com.snapbrain.intercept

import com.snapbrain.domain.InterceptPolicy

/**
 * Decides when to cover the foreground app. Fed with "app X came to the foreground" events and a
 * periodic tick; pure Kotlin so it can be unit tested without a device.
 */
class GuardEngine(private val ownPkg: String) {

    /** Last non-transient app seen in the foreground; null right after we covered it. */
    var current: String? = null
        private set

    /** The current app was open on free time (pass/ticket), so an intercept now means "time's up". */
    private var wasFree = false

    data class Intercept(val pkg: String, val expired: Boolean)

    fun onForeground(pkg: String) {
        if (InterceptPolicy.isTransient(pkg, ownPkg, emptySet())) return
        if (pkg == current) return
        current = pkg
        wasFree = false
    }

    fun tick(now: Long, blocked: Set<String>, freeUntil: Long, coverVisible: Boolean): Intercept? {
        val pkg = current ?: return null
        return when (InterceptPolicy.decide(pkg, now, blocked, freeUntil)) {
            InterceptPolicy.Decision.Allow -> null
            is InterceptPolicy.Decision.AllowUntil -> {
                wasFree = true
                null
            }
            InterceptPolicy.Decision.Intercept -> {
                if (coverVisible) return null
                val expired = wasFree
                // Forget the app so returning to it (Back, Recents) is a new foreground event.
                current = null
                wasFree = false
                Intercept(pkg, expired)
            }
        }
    }
}

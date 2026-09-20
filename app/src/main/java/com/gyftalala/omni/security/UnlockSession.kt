package com.gyftalala.omni.security

/** In-memory authentication only. A new process always starts locked. */
class UnlockSession {
    var unlocked = false
        private set
    private var externalResultPending = false
    private var stoppedForExternalFlow = false
    private var externalResultReturned = false

    fun authenticated() { unlocked = true }

    fun beginExternalFlow() {
        check(unlocked) { "Unlock Omni before opening another screen." }
        externalResultPending = true
        externalResultReturned = false
    }

    fun externalResult() {
        if (externalResultPending) {
            externalResultPending = false
            externalResultReturned = true
        }
    }

    fun stopped() {
        if (unlocked && externalResultPending) stoppedForExternalFlow = true else lock()
    }

    fun resumed(deviceLocked: Boolean) {
        // Returning directly to Omni without the requested result is an ordinary app return.
        if (deviceLocked || (stoppedForExternalFlow && !externalResultReturned)) lock()
        stoppedForExternalFlow = false
        externalResultReturned = false
    }

    fun lock() {
        unlocked = false
        externalResultPending = false
        stoppedForExternalFlow = false
        externalResultReturned = false
    }
}

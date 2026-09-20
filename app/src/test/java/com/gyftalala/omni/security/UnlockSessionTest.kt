package com.gyftalala.omni.security

import org.junit.Assert.*
import org.junit.Test

class UnlockSessionTest {
    @Test fun freshAppAndNormalBackgroundRequireAuthentication() {
        val session = UnlockSession()
        assertFalse(session.unlocked)
        session.authenticated()
        session.stopped()
        session.resumed(false)
        assertFalse(session.unlocked)
    }

    @Test fun selectingOrCancelingTrustedPickerKeepsAuthenticatedSession() {
        val session = UnlockSession()
        session.authenticated()
        repeat(3) { // Selection, back cancellation, then another attachment/export.
            session.beginExternalFlow()
            session.stopped()
            session.externalResult()
            session.resumed(false)
            assertTrue(session.unlocked)
        }
        session.stopped()
        assertFalse(session.unlocked)
    }

    @Test fun returningWithoutPickerResultLocksAgain() {
        val session = UnlockSession()
        session.authenticated()
        session.beginExternalFlow()
        session.stopped()
        session.resumed(false)
        assertFalse(session.unlocked)
        session.externalResult()
        assertFalse(session.unlocked)
    }

    @Test fun screenLockInvalidatesPendingPickerAndLateResultCannotUnlock() {
        val session = UnlockSession()
        session.authenticated()
        session.beginExternalFlow()
        session.stopped()
        session.lock() // Screen-off broadcast or explicit Lock now.
        session.externalResult()
        session.resumed(false)
        assertFalse(session.unlocked)
    }

    @Test fun lockedDeviceNeverResumesTrustedSession() {
        val session = UnlockSession()
        session.authenticated()
        session.beginExternalFlow()
        session.stopped()
        session.externalResult()
        session.resumed(true)
        assertFalse(session.unlocked)
    }

    @Test fun processRecreationDoesNotRestoreAuthentication() {
        val old = UnlockSession()
        old.authenticated()
        old.beginExternalFlow()
        val recreated = UnlockSession()
        recreated.externalResult()
        recreated.resumed(false)
        assertFalse(recreated.unlocked)
    }

    @Test fun failedPickerLaunchDoesNotLeaveFutureBackgroundUnlocked() {
        val session = UnlockSession()
        session.authenticated()
        session.beginExternalFlow()
        session.externalResult() // Launcher throws before starting an Activity.
        assertTrue(session.unlocked)
        session.stopped()
        session.resumed(false)
        assertFalse(session.unlocked)
    }

    @Test fun permissionDialogResultWithoutStopDoesNotExemptNextAppExit() {
        val session = UnlockSession()
        session.authenticated()
        session.beginExternalFlow()
        // A runtime permission dialog can pause the Activity without stopping it.
        session.externalResult()
        session.resumed(false)
        assertTrue(session.unlocked)
        session.stopped()
        session.resumed(false)
        assertFalse(session.unlocked)
    }
}

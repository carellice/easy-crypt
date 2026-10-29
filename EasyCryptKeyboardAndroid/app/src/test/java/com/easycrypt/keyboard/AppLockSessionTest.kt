package com.easycrypt.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSessionTest {
    @Test fun newProcessRequiresAuthentication() {
        assertFalse(AppLockSession().authenticated)
    }

    @Test fun backgroundRequiresAuthenticationAgain() {
        val session = AppLockSession()
        session.started()
        session.authenticate()
        session.stopped(changingConfiguration = false)
        session.started()
        assertFalse(session.authenticated)
    }

    @Test fun internalNavigationKeepsAuthenticatedSession() {
        val session = AppLockSession()
        session.started()
        session.authenticate()
        session.started()
        session.stopped(changingConfiguration = false)
        assertTrue(session.authenticated)
        session.stopped(changingConfiguration = false)
        assertFalse(session.authenticated)
    }

    @Test fun rotationKeepsAuthenticationButSubsequentBackgroundLocks() {
        val session = AppLockSession()
        session.started()
        session.authenticate()
        session.stopped(changingConfiguration = true)
        session.started()
        assertTrue(session.authenticated)
        session.stopped(changingConfiguration = false)
        assertFalse(session.authenticated)
    }

    @Test fun evenBriefExternalPauseLocksBeforeOnStop() {
        val session = AppLockSession()
        session.started()
        session.authenticate()
        session.paused(stayingInApp = false)
        assertFalse(session.authenticated)
    }

    @Test fun internalPauseKeepsSessionUntilAppLeavesForeground() {
        val session = AppLockSession()
        session.authenticate()
        session.paused(stayingInApp = true)
        assertTrue(session.authenticated)
        session.paused(stayingInApp = false)
        assertFalse(session.authenticated)
    }
}

package com.gyftalala.omni.ui

import org.junit.Assert.*
import org.junit.Test

class WalletPhysicsTest {
    @Test fun dragTracksDistanceInBothDirectionsAndClamps() {
        assertEquals(.5f, WalletPhysics.progress(0f, 90f), .001f)
        assertEquals(.25f, WalletPhysics.progress(.75f, -90f), .001f)
        assertEquals(1f, WalletPhysics.progress(.8f, 900f), 0f)
        assertEquals(0f, WalletPhysics.progress(.2f, -900f), 0f)
    }
    @Test fun FastFlickOverridesDistanceInBothDirections() {
        assertEquals(1f, WalletPhysics.target(.1f, 601f), 0f)
        assertEquals(0f, WalletPhysics.target(.9f, -601f), 0f)
        assertEquals(0f, WalletPhysics.target(.49f, 599f), 0f)
        assertEquals(1f, WalletPhysics.target(.51f, -599f), 0f)
    }
    @Test fun DepthResolvesToFullSizeWhenExpanded() {
        assertEquals(1f, WalletPhysics.scale(0, 0f), 0f)
        assertEquals(.94f, WalletPhysics.scale(4, 0f), .001f)
        assertEquals(.97f, WalletPhysics.scale(4, .5f), .001f)
        assertEquals(1f, WalletPhysics.scale(4, 1f), 0f)
    }
}

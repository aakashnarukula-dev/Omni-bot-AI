package com.gyftalala.omni

import android.graphics.Bitmap
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.data.*
import com.gyftalala.omni.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WalletInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()
    private fun cards(count: Int) = (1..count).map { i -> Memory("c$i", "Bank $i", "", Category.CARD, createdAt = i.toLong(),
        card = CardDetails("Bank $i", "TEST OWNER", "4111111111111111", "09/29", "123", "Debit card", "VISA")) }
    private fun progress() = compose.onNodeWithTag("wallet-stack").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(TestSupport.context.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun followsFingerBeforeReleaseAndReversesWithoutJump() {
        val vm = TestSupport.vm()
        compose.setContent { OmniTheme { WalletScreen(cards(3), ImagePreviews(vm.vault), {}, {}) } }
        val density = TestSupport.context.resources.displayMetrics.density
        compose.onNodeWithTag("wallet-stack").performTouchInput { down(Offset(width / 2f, height * .75f)); moveBy(Offset(0f, 75f * density), 400) }
        val first = progress(); assertTrue("Finger moved but stack did not follow: $first", first > .2f && first < .6f)
        compose.onNodeWithTag("wallet-stack").performTouchInput { moveBy(Offset(0f, -25f * density), 300) }
        assertTrue(progress() < first)
        compose.onNodeWithTag("wallet-stack").performTouchInput { advanceEventTime(300); up() }
        compose.waitForIdle(); assertEquals(0f, progress(), .01f)
        screenshot("wallet-compact.png")
    }

    @Test fun fastShortFlickOpensAndTopEdgeCloses() {
        val vm = TestSupport.vm()
        compose.setContent { OmniTheme { WalletScreen(cards(3), ImagePreviews(vm.vault), {}, {}) } }
        val density = TestSupport.context.resources.displayMetrics.density
        compose.onNodeWithTag("wallet-stack").performTouchInput {
            val origin = Offset(width / 2f, height * .6f)
            swipe(origin, origin + Offset(0f, 65f * density), 65)
        }
        compose.waitForIdle(); assertEquals(1f, progress(), .01f)
        screenshot("wallet-expanded.png")
        // Persistent handle stays reachable even when the expanded stack fills the viewport.
        compose.onNodeWithTag("wallet-drag-handle").performTouchInput {
            val origin = Offset(12f, height / 2f)
            swipe(origin, origin - Offset(0f, 65f * density), 65)
        }
        compose.waitForIdle(); assertEquals(0f, progress(), .01f)
    }

    @Test fun expandedCardEdgeTracksUpwardDragUntilRelease() {
        val vm = TestSupport.vm()
        compose.setContent { OmniTheme { WalletScreen(cards(3), ImagePreviews(vm.vault), {}, {}) } }
        compose.onNodeWithTag("wallet-expand").performClick()
        val density = TestSupport.context.resources.displayMetrics.density
        compose.onNodeWithTag("wallet-edge-c1", useUnmergedTree = true).performTouchInput {
            down(center); moveBy(Offset(0f, -55f * density), 300)
        }
        val first = progress(); assertTrue(first < .9f && first > .5f)
        compose.onNodeWithTag("wallet-edge-c1", useUnmergedTree = true).performTouchInput { moveBy(Offset(0f, -65f * density), 300) }
        assertTrue("Edge drag stopped following after first move", progress() < first - .2f)
        compose.onNodeWithTag("wallet-edge-c1", useUnmergedTree = true).performTouchInput { advanceEventTime(300); up() }
        compose.waitForIdle(); assertEquals(0f, progress(), .01f)
    }

    @Test fun tapTogglesShareWithoutOpeningDetailsAndAddStaysFloating() {
        val vm = TestSupport.vm(); var opened = ""; var shared = ""; var added = 0
        compose.setContent { OmniTheme { WalletScreen(cards(1), ImagePreviews(vm.vault), { opened = it }, { added++ }, { shared = it.id }) } }
        compose.onNodeWithText("4111 1111 1111 1111").performClick()
        assertEquals("", opened)
        compose.onNodeWithTag("wallet-share-c1").assertIsDisplayed().performClick(); assertEquals("c1", shared)
        compose.onNodeWithTag("wallet-edge-c1", useUnmergedTree = true).performTouchInput { click(center) }
        compose.onNodeWithTag("wallet-actions-c1").assertDoesNotExist()
        compose.onNodeWithTag("wallet-edge-c1", useUnmergedTree = true).performTouchInput { click(center) }
        compose.onNodeWithTag("wallet-details-c1").performClick(); assertEquals("c1", opened)
        compose.onNodeWithTag("wallet-add").assertIsDisplayed().performClick(); assertEquals(1, added)
        screenshot("wallet-share-actions.png")
    }

    @Test fun largeWalletKeepsOldestAndNewestReachableAndSearchWorks() {
        val vm = TestSupport.vm()
        compose.setContent { OmniTheme { WalletScreen(cards(60), ImagePreviews(vm.vault), {}, {}) } }
        compose.onNodeWithTag("wallet-expand").performClick()
        compose.onNodeWithTag("wallet-list").performScrollToNode(hasText("BANK 1"))
        compose.onNodeWithText("BANK 1").assertIsDisplayed()
        compose.onNodeWithTag("wallet-add").assertIsDisplayed()
        val density = TestSupport.context.resources.displayMetrics.density
        compose.onNodeWithTag("wallet-edge-c1", useUnmergedTree = true).performTouchInput {
            swipe(center, center - Offset(0f, 200f * density), 350)
        }
        compose.waitForIdle(); assertEquals(0f, progress(), .01f)
        compose.onNodeWithTag("wallet-expand").performClick()
        compose.onNodeWithTag("wallet-list").performScrollToNode(hasText("BANK 60"))
        compose.onNodeWithText("BANK 60", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Search wallet").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Bank 27")
        compose.onNodeWithText("BANK 27").assertIsDisplayed()
        compose.onNodeWithText("BANK 60").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close wallet search").performClick()
        repeat(3) {
            compose.onNodeWithTag("wallet-expand").performClick()
            compose.onNodeWithTag("wallet-expand").performClick()
        }
        compose.onNodeWithTag("wallet-add").assertIsDisplayed()
    }

    @Test fun cardsHaveSamePhysicalProportionsAndDepthInCompactStack() {
        val vm = TestSupport.vm()
        val identity = Memory("id", "PAN", "", Category.DOCUMENT, createdAt = 0, identity = IdentityDetails(IdKind.PAN, "ABCDE1234F", "TEST OWNER"))
        compose.setContent { OmniTheme { WalletScreen(cards(2) + identity, ImagePreviews(vm.vault), {}, {}) } }
        val front = compose.onNodeWithTag("wallet-card-c2").getUnclippedBoundsInRoot()
        val rear = compose.onNodeWithTag("wallet-card-id").getUnclippedBoundsInRoot()
        assertEquals(WALLET_CARD_ASPECT, front.width.value / front.height.value, .02f)
        assertTrue("Rear card should be narrower", compose.onNodeWithTag("wallet-card-id").fetchSemanticsNode().boundsInRoot.width < compose.onNodeWithTag("wallet-card-c2").fetchSemanticsNode().boundsInRoot.width)
        compose.onNodeWithTag("wallet-expand").performClick()
        val expandedFront = compose.onNodeWithTag("wallet-card-c2").getUnclippedBoundsInRoot()
        val expandedRear = compose.onNodeWithTag("wallet-card-id").getUnclippedBoundsInRoot()
        assertEquals(expandedFront.width.value, expandedRear.width.value, .1f)
        assertEquals(expandedFront.height.value, expandedRear.height.value, .1f)
    }
}

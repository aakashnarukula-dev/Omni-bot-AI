package com.gyftalala.omni

import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gyftalala.omni.data.*
import com.gyftalala.omni.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class InteractionUiTest {
    @get:Rule val compose = createAndroidComposeRule<VerificationActivity>()
    @Before fun reset() = TestSupport.reset()
    private fun seed(vararg memories: Memory): OmniViewModel {
        OmniStore(TestSupport.context).use { store -> memories.forEach(store::save) }
        return TestSupport.vm()
    }
    private fun render(vm: OmniViewModel, initial: String? = null, font: Float = 1f) {
        compose.setContent { OmniTheme {
            val state by vm.state.collectAsState()
            var selected by remember { mutableStateOf(initial) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font)) {
                OmniApp(state, vm, selected, { selected = it }, { _, _, _ -> }, {}, {}, {}, {})
            }
        } }
    }
    private fun memory(id: String = "task", category: Category = Category.REMINDER) =
        Memory(id, if (id == "task") "Repair nord phone" else "Saved $id", "repair not phone", category, priority = TaskPriority.URGENT)
    private fun library() = compose.onNodeWithContentDescription("Library").performClick()
    private fun row(id: String = "task") = compose.onNodeWithTag("swipe-item-$id")
    private fun pageLeft() = compose.onNodeWithTag("main-pages").performTouchInput { swipe(Offset(width * .85f, height * .88f), Offset(width * .15f, height * .88f), 400) }
    private fun pageRight() = compose.onNodeWithTag("main-pages").performTouchInput { swipe(Offset(width * .15f, height * .88f), Offset(width * .85f, height * .88f), 400) }
    private fun shot(name: String, sheet: Boolean = false) {
        val node = if (sheet) compose.onNodeWithTag("item-detail") else compose.onRoot()
        File(TestSupport.context.getExternalFilesDir(null), name).outputStream().use {
            node.captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun leftSwipeCompletesAndStaysInLibraryEvenWhenRepeated() {
        val vm = seed(memory())
        render(vm); library()
        row().performTouchInput { swipeLeft() }
        compose.waitUntil { vm.state.value.memories.single().status == "Done" }
        compose.onNodeWithContentDescription("Library").assertIsSelected()
        val replies = vm.state.value.messages.size
        row().performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("Library").assertIsSelected()
        assertEquals(replies, vm.state.value.messages.size)
        assertEquals("Done", TestSupport.vm().state.value.memories.single().status)
    }

    @Test fun rightSwipeRequiresDeleteConfirmationAndKeepRestoresCard() {
        val vm = seed(memory())
        render(vm); library()
        row().performTouchInput { swipeRight() }
        compose.onNodeWithText("Delete this item?").assertIsDisplayed()
        assertEquals(1, vm.state.value.memories.size)
        compose.onNodeWithText("Cancel").performClick()
        row().assertIsDisplayed()
        compose.onNodeWithContentDescription("Library").assertIsSelected()
        row().performTouchInput { swipeRight() }
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil { vm.state.value.memories.isEmpty() }
        compose.onNodeWithText("All 0").assertIsDisplayed()
        compose.onNodeWithContentDescription("Library").assertIsSelected()
    }

    @Test fun shortCancelledAndReversedDragsHaveNoSideEffects() {
        val vm = seed(memory())
        render(vm); library()
        row().performTouchInput { swipe(center, center + Offset(width * .12f, 0f), 400) }
        row().performTouchInput { down(center); moveBy(Offset(width * .6f, 0f)); cancel() }
        row().performTouchInput {
            down(center); moveBy(Offset(-width * .45f, 0f)); moveBy(Offset(width * .45f, 0f)); up()
        }
        compose.onNodeWithText("Delete this item?").assertDoesNotExist()
        assertEquals("Saved", vm.state.value.memories.single().status)
        assertTrue(vm.state.value.messages.isEmpty())
        compose.onNodeWithContentDescription("Library").assertIsSelected()
        row().performTouchInput { down(Offset(width * .15f, height / 2f)); moveBy(Offset(width * .45f, 0f)) }
        shot("swipe-delete.png")
        row().performTouchInput { cancel() }
        row().performTouchInput { down(Offset(width * .85f, height / 2f)); moveBy(Offset(-width * .45f, 0f)) }
        shot("swipe-done.png")
        row().performTouchInput { cancel() }
    }

    @Test fun verticalAndDiagonalListDragsNeverChangePageOrDeleteItems() {
        val vm = seed(*(0..24).map { memory("task$it") }.toTypedArray())
        render(vm); library()
        val before = vm.state.value.memories.map { it.status }
        compose.onNodeWithTag("library-items").performTouchInput {
            swipe(Offset(width * .5f, height * .85f), Offset(width * .6f, height * .2f), 500)
        }
        compose.onNodeWithContentDescription("Library").assertIsSelected()
        compose.onNodeWithText("Delete this item?").assertDoesNotExist()
        assertEquals(before, vm.state.value.memories.map { it.status })
        compose.onNodeWithTag("library-items").performScrollToIndex(24)
        compose.onNodeWithText(MemorySearch.find(vm.state.value.memories, "").last().title).assertIsDisplayed()
    }

    @Test fun pageSwipesNavigateBothDirectionsAndRestoreDraft() {
        val vm = seed(memory())
        render(vm)
        compose.onNode(hasSetTextAction()).performTextInput("Keep my draft")
        // Hide the keyboard without submitting: clearing focus is enough via dock.
        library(); compose.onNodeWithContentDescription("Chat").performClick()
        pageLeft(); compose.onNodeWithContentDescription("Library").assertIsSelected()
        pageLeft(); compose.onNodeWithContentDescription("Cards").assertIsSelected()
        pageRight(); compose.onNodeWithContentDescription("Library").assertIsSelected()
        pageRight(); compose.onNodeWithContentDescription("Chat").assertIsSelected()
        compose.onNode(hasSetTextAction()).assertTextContains("Keep my draft")
        repeat(3) { pageLeft(); pageRight() }
        compose.onNodeWithContentDescription("Chat").assertIsSelected()
    }

    @Test fun categoryCountsSortDescendingAndStripsDoNotTurnPages() {
        val vm = seed(memory("r"), memory("p1", Category.PRODUCT), memory("p2", Category.PRODUCT),
            memory("n1", Category.NOTE), memory("n2", Category.NOTE), memory("n3", Category.NOTE))
        render(vm); library()
        val all = compose.onNodeWithText("All 6").fetchSemanticsNode().boundsInRoot
        val note = compose.onNodeWithText("Note 3").fetchSemanticsNode().boundsInRoot
        val product = compose.onNodeWithText("Want to buy 2").fetchSemanticsNode().boundsInRoot
        assertTrue(all.left < note.left && note.left < product.left)
        compose.onNodeWithText("Note 3").performClick()
        TestSupport.await(vm.categorize("n1", Category.PRODUCT))
        TestSupport.await(vm.categorize("n2", Category.PRODUCT))
        compose.onNodeWithText("Note 1").assertIsSelected()
        compose.onNodeWithText("Saved n3").assertIsDisplayed()
        compose.onNodeWithTag("category-pills").performTouchInput { swipeRight() }
        val p = compose.onNodeWithText("Want to buy 4").fetchSemanticsNode().boundsInRoot
        val n = compose.onNodeWithText("Note 1").fetchSemanticsNode().boundsInRoot
        assertTrue(p.left < n.left)
        repeat(5) { compose.onNodeWithTag("category-pills").performTouchInput { swipeLeft() } }
        repeat(5) { compose.onNodeWithTag("category-pills").performTouchInput { swipeRight() } }
        compose.onNodeWithContentDescription("Library").assertIsSelected()
    }

    @Test fun detailPlacesCategoryAboveEditableTitleAndActionsAtBottom() {
        val vm = seed(memory())
        render(vm, "task")
        compose.onNodeWithText("Topic").assertDoesNotExist()
        compose.onNodeWithContentDescription("Edit details").assertDoesNotExist()
        val category = compose.onNodeWithTag("detail-category").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("detail-title").fetchSemanticsNode().boundsInRoot
        val schedule = compose.onNodeWithTag("reminder-schedule").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("detail-actions").fetchSemanticsNode().boundsInRoot
        assertTrue(category.bottom <= title.top)
        assertTrue(schedule.bottom < actions.top)
        compose.onNodeWithTag("reminder-repeat").assertIsOff().assertIsNotEnabled()
        shot("detail-reminder.png", true)
        compose.onNodeWithTag("detail-title").performClick()
        compose.onNodeWithTag("detail-title-input").performTextReplacement("Repair Nord phone")
        compose.onNodeWithText("Save name").performClick()
        compose.waitUntil { vm.state.value.memories.single().title == "Repair Nord phone" }
        compose.onNodeWithTag("detail-title").performClick()
        compose.onNodeWithTag("detail-title-input").performTextReplacement("Discard this")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("Repair Nord phone", vm.state.value.memories.single().title)
        compose.onNodeWithTag("detail-category").performClick()
        compose.onNode(hasText("Want to buy") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil { vm.state.value.memories.single().category == Category.PRODUCT }
        assertTrue(vm.state.value.memories.single().categoryConfirmedAt > 0)
        compose.onNodeWithTag("reminder-schedule").assertDoesNotExist()
    }

    @Test fun nativeScheduleStartsFromSavedTimeAndOnlySavesAfterBothSteps() {
        val vm = seed(memory())
        val at = ZonedDateTime.now().plusDays(4).withHour(17).withMinute(25).withSecond(0).withNano(0)
        TestSupport.await(vm.schedule("task", at.toInstant().toEpochMilli(), "daily"))
        render(vm, "task")
        compose.onNodeWithTag("reminder-repeat").assertIsOn()
        compose.onNodeWithTag("reminder-schedule").performClick()
        onView(isAssignableFrom(DatePicker::class.java)).check { view, _ ->
            val picker = view as DatePicker
            assertEquals(at.year, picker.year); assertEquals(at.monthValue - 1, picker.month); assertEquals(at.dayOfMonth, picker.dayOfMonth)
        }
        onView(withText("Cancel")).perform(click())
        assertEquals(at.toInstant().toEpochMilli(), vm.state.value.reminders.single().triggerAt)
        compose.onNodeWithTag("reminder-schedule").performClick()
        onView(withText("Next")).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).check { view, _ ->
            val picker = view as TimePicker
            assertEquals(17, picker.hour); assertEquals(25, picker.minute)
        }
        onView(withText("Cancel")).perform(click())
        assertEquals(at.toInstant().toEpochMilli(), vm.state.value.reminders.single().triggerAt)
        compose.onNodeWithTag("reminder-schedule").performClick()
        val changed = at.plusDays(2).withHour(10).withMinute(40)
        onView(isAssignableFrom(DatePicker::class.java)).check { view, _ -> (view as DatePicker).updateDate(changed.year, changed.monthValue - 1, changed.dayOfMonth) }
        onView(withText("Next")).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).check { view, _ -> (view as TimePicker).apply { hour = 10; minute = 40 } }
        onView(withText("Save")).perform(click())
        compose.waitUntil { vm.state.value.reminders.single().triggerAt == changed.toInstant().toEpochMilli() }
        compose.onNodeWithTag("reminder-repeat").performClick()
        compose.waitUntil { vm.state.value.reminders.single().repeat == "none" }
        compose.onNodeWithTag("reminder-complete").performClick()
        compose.waitUntil { vm.state.value.reminders.single().completed }
        compose.onNodeWithTag("reminder-complete").assertIsNotEnabled()
    }

    @Test fun largeTextKeepsCompletionVisibleAndTitleEditable() {
        val vm = seed(memory().copy(title = "Repair Nord phone and replace its damaged screen"))
        render(vm, "task", 1.5f)
        compose.onNodeWithTag("reminder-complete").assertIsDisplayed()
        compose.onNodeWithTag("detail-delete").assertIsDisplayed()
        compose.onNodeWithTag("detail-title").performScrollTo().assertHasClickAction()
        shot("detail-large-text.png", true)
    }

    @Test fun walletVerticalStackDragKeepsCardsPageAndHorizontalSwipeLeavesIt() {
        val vm = seed(*(1..3).map { Memory("c$it", "Bank $it", "", Category.CARD, createdAt = it.toLong(),
            card = CardDetails("Bank $it", "TEST OWNER", "4111111111111111", "09/29", "123")) }.toTypedArray())
        render(vm)
        compose.onNodeWithContentDescription("Cards").performClick()
        val density = TestSupport.context.resources.displayMetrics.density
        compose.onNodeWithTag("wallet-stack").performTouchInput {
            val start = Offset(width / 2f, height * .6f)
            swipe(start, start + Offset(0f, 65f * density), 65)
        }
        compose.onNodeWithContentDescription("Cards").assertIsSelected()
        assertEquals(1f, compose.onNodeWithTag("wallet-stack").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current, .01f)
        pageRight()
        compose.onNodeWithContentDescription("Library").assertIsSelected()
    }

    @Test fun pastTimeStaysInPickerWithoutReplacingExistingSchedule() {
        val vm = seed(memory())
        val at = ZonedDateTime.now().plusDays(2).withSecond(0).withNano(0)
        TestSupport.await(vm.schedule("task", at.toInstant().toEpochMilli()))
        render(vm, "task")
        compose.onNodeWithTag("reminder-schedule").performClick()
        val today = ZonedDateTime.now()
        onView(isAssignableFrom(DatePicker::class.java)).check { view, _ -> (view as DatePicker).updateDate(today.year, today.monthValue - 1, today.dayOfMonth) }
        onView(withText("Next")).perform(click())
        onView(isAssignableFrom(TimePicker::class.java)).check { view, _ -> (view as TimePicker).apply { hour = 0; minute = 0 } }
        onView(withText("Save")).perform(click())
        onView(withText("Choose a future time")).check { view, _ -> assertNotNull(view) }
        assertEquals(at.toInstant().toEpochMilli(), vm.state.value.reminders.single().triggerAt)
        onView(withText("Cancel")).perform(click())
        compose.onNodeWithTag("reminder-schedule").assertIsDisplayed()
    }
}

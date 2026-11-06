package com.adsbexchange.feeder.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Scrolling that doesn't need a mouse wheel: each page's scrolling area has a scrollbar to drag, and
 * the window's keys scroll it (Page Up/Down, Home/End, the arrows). Before, a trackball without a
 * wheel couldn't reach the bottom of a page at all.
 */

/**
 * The window's scrolling keys, for the scrolling area the window shows. The window passes it the
 * keys nothing else used (Main.kt, Window's onKeyEvent), so a text field still gets its arrows, Home
 * and End; the page's [ScrollingColumn] or [ScrollingLazyColumn] registers itself.
 */
class PageKeys {
    private class Area(val state: ScrollableState, val viewportPx: () -> Int)

    private var area: Area? = null

    internal fun register(state: ScrollableState, viewportPx: () -> Int): () -> Unit {
        val a = Area(state, viewportPx)
        area = a
        return { if (area === a) area = null }
    }

    /** Scrolls the page for a scrolling key; true if [event] was one. */
    fun handle(event: KeyEvent, scope: CoroutineScope): Boolean {
        val a = area ?: return false
        if (event.type != KeyEventType.KeyDown) return false
        val page = a.viewportPx() * 0.9f
        val by = when (event.key) {
            Key.PageDown -> page
            Key.PageUp -> -page
            Key.DirectionDown -> ARROW_STEP_PX
            Key.DirectionUp -> -ARROW_STEP_PX
            Key.MoveEnd -> TO_THE_END_PX
            Key.MoveHome -> -TO_THE_END_PX
            else -> return false
        }
        scope.launch { a.state.scrollBy(by) }
        return true
    }

    private companion object {
        const val ARROW_STEP_PX = 60f
        /** Further than any page: scrolling stops at its end. */
        const val TO_THE_END_PX = 1_000_000f
    }
}

val LocalPageKeys = staticCompositionLocalOf { PageKeys() }

@Composable
private fun RegisterPageKeys(state: ScrollableState, viewportPx: () -> Int) {
    val keys = LocalPageKeys.current
    DisposableEffect(state, keys) {
        val unregister = keys.register(state, viewportPx)
        onDispose { unregister() }
    }
}

/** A page's scrolling column, with a scrollbar beside it and the window's scrolling keys ([PageKeys]). */
@Composable
fun ScrollingColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberScrollState()
    RegisterPageKeys(state) { state.viewportSize }
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(state).padding(end = SCROLLBAR_ROOM),
            verticalArrangement = verticalArrangement, content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = scrollbarStyle())
    }
}

/** As [ScrollingColumn], for a long list built as it scrolls (the aircraft list). */
@Composable
fun ScrollingLazyColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    RegisterPageKeys(state) { state.layoutInfo.viewportSize.height }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize().padding(end = SCROLLBAR_ROOM), state, verticalArrangement = verticalArrangement,
            content = content)
        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = scrollbarStyle())
    }
}

/** Room at the right of a scrolling area's content for its scrollbar. */
private val SCROLLBAR_ROOM = 14.dp

/**
 * Compose's scrollbar is a faint black, made for light backgrounds, so on the app's black it
 * couldn't be seen: grey here, brighter under the pointer.
 */
@Composable
private fun scrollbarStyle() = LocalScrollbarStyle.current.copy(
    thickness = 8.dp,
    unhoverColor = Brand.TextSecondary.copy(alpha = 0.45f),
    hoverColor = Brand.Text.copy(alpha = 0.75f),
)

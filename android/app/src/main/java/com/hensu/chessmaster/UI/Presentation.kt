package com.hensu.chessmaster

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * .sheet(isPresented:onDismiss:) { content }. Call it where the Swift view attaches the sheet:
 * `if (show) Sheet(onDismiss = { show = false }) { … }`. `dismissable = false` is
 * .interactiveDismissDisabled(). `large = false` is .presentationDetents([.medium, .large]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(
    onDismiss: () -> Unit,
    dismissable: Boolean = true,
    large: Boolean = true,
    content: @Composable () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = large, confirmValueChange = { dismissable })
    ModalBottomSheet(
        onDismissRequest = { if (dismissable) onDismiss() },
        sheetState = state,
        containerColor = Sys.groupedBackground,
        properties = androidx.compose.material3.ModalBottomSheetProperties(shouldDismissOnBackPress = dismissable),
    ) { content() }
}

/** .fullScreenCover(isPresented:) { content }: covers the whole screen; back closes it when `dismissable`. */
@Composable
fun FullScreenCover(onDismiss: () -> Unit, dismissable: Boolean = false, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = { if (dismissable) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = dismissable,
                                      dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Sys.groupedBackground)) { content() }
    }
}

fun TextStyle.bold(): TextStyle = copy(fontWeight = FontWeight.Bold)
fun TextStyle.weight(w: FontWeight): TextStyle = copy(fontWeight = w)

/** UIImpactFeedbackGenerator / UINotificationFeedbackGenerator. Get one with `rememberHaptics()`. */
class Haptics(private val view: android.view.View) {
    fun impact() { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    fun light() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun success() {
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }
    fun error() {
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}

/** @Environment(\.accessibilityReduceMotion): the system "remove animations" setting. */
fun reduceMotion(): Boolean =
    Settings.Global.getFloat(App.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** @AppStorage("key") var flag = false: a remembered state that saves to Defaults.standard on every write. */
@Composable
fun appStorageBool(key: String, default: Boolean = false): MutableState<Boolean> = remember(key) {
    object : MutableState<Boolean> {
        private val inner = mutableStateOf(Defaults.standard.boolOrNull(key) ?: default)
        override var value: Boolean
            get() = inner.value
            set(v) { inner.value = v; Defaults.standard.set(key, v) }
        override fun component1() = value
        override fun component2(): (Boolean) -> Unit = { value = it }
    }
}

@Composable
fun appStorageString(key: String, default: String = ""): MutableState<String> = remember(key) {
    object : MutableState<String> {
        private val inner = mutableStateOf(Defaults.standard.string(key) ?: default)
        override var value: String
            get() = inner.value
            set(v) { inner.value = v; Defaults.standard.set(key, v) }
        override fun component1() = value
        override fun component2(): (String) -> Unit = { value = it }
    }
}

@Composable
fun appStorageInt(key: String, default: Int = 0): MutableState<Int> = remember(key) {
    object : MutableState<Int> {
        private val inner = mutableStateOf(Defaults.standard.intOrNull(key) ?: default)
        override var value: Int
            get() = inner.value
            set(v) { inner.value = v; Defaults.standard.set(key, v) }
        override fun component1() = value
        override fun component2(): (Int) -> Unit = { value = it }
    }
}

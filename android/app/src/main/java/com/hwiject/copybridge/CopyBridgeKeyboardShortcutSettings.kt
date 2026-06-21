package com.hwiject.copybridge

import android.content.Context
import android.view.KeyEvent

data class CopyBridgeKeyboardShortcutSpec(
 val keyCode: Int,
 val ctrl: Boolean,
 val shift: Boolean,
 val alt: Boolean,
 val meta: Boolean
)

object CopyBridgeKeyboardShortcutSettings {
 private const val PREFS_NAME = "copybridge_keyboard_shortcuts"
 private const val KEY_CAPTURE_ACTION = "capture_action"

 private fun keyCodeKey(action: String) = "shortcut_${action}_keyCode"
 private fun ctrlKey(action: String) = "shortcut_${action}_ctrl"
 private fun shiftKey(action: String) = "shortcut_${action}_shift"
 private fun altKey(action: String) = "shortcut_${action}_alt"
 private fun metaKey(action: String) = "shortcut_${action}_meta"

 fun isSupportedAction(action: String): Boolean {
 return action == CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT ||
 action == CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM
 }

 fun defaultSpec(action: String): CopyBridgeKeyboardShortcutSpec {
 return when (action) {
 CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM -> CopyBridgeKeyboardShortcutSpec(
 keyCode = KeyEvent.KEYCODE_ENTER,
 ctrl = true,
 shift = true,
 alt = false,
 meta = false
 )
 else -> CopyBridgeKeyboardShortcutSpec(
 keyCode = KeyEvent.KEYCODE_ENTER,
 ctrl = true,
 shift = false,
 alt = false,
 meta = false
 )
 }
 }

 fun getSpec(context: Context, action: String): CopyBridgeKeyboardShortcutSpec {
 val default = defaultSpec(action)
 val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
 return CopyBridgeKeyboardShortcutSpec(
 keyCode = prefs.getInt(keyCodeKey(action), default.keyCode),
 ctrl = prefs.getBoolean(ctrlKey(action), default.ctrl),
 shift = prefs.getBoolean(shiftKey(action), default.shift),
 alt = prefs.getBoolean(altKey(action), default.alt),
 meta = prefs.getBoolean(metaKey(action), default.meta)
 )
 }

 fun saveSpec(context: Context, action: String, spec: CopyBridgeKeyboardShortcutSpec) {
 if (!isSupportedAction(action)) return
 context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
 .edit()
 .putInt(keyCodeKey(action), spec.keyCode)
 .putBoolean(ctrlKey(action), spec.ctrl)
 .putBoolean(shiftKey(action), spec.shift)
 .putBoolean(altKey(action), spec.alt)
 .putBoolean(metaKey(action), spec.meta)
 .apply()
 }

 fun reset(context: Context) {
 context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
 .edit()
 .remove(keyCodeKey(CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT))
 .remove(ctrlKey(CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT))
 .remove(shiftKey(CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT))
 .remove(altKey(CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT))
 .remove(metaKey(CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT))
 .remove(keyCodeKey(CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM))
 .remove(ctrlKey(CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM))
 .remove(shiftKey(CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM))
 .remove(altKey(CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM))
 .remove(metaKey(CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM))
 .remove(KEY_CAPTURE_ACTION)
 .apply()
 }

 fun startCapture(context: Context, action: String) {
 if (!isSupportedAction(action)) return
 context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
 .edit()
 .putString(KEY_CAPTURE_ACTION, action)
 .apply()
 }

 fun cancelCapture(context: Context) {
 context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
 .edit()
 .remove(KEY_CAPTURE_ACTION)
 .apply()
 }

 fun getCaptureAction(context: Context): String {
 val action = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
 .getString(KEY_CAPTURE_ACTION, "") ?: ""
 return if (isSupportedAction(action)) action else ""
 }

 fun specFromEvent(event: KeyEvent): CopyBridgeKeyboardShortcutSpec? {
 if (event.action != KeyEvent.ACTION_DOWN) return null
 if (event.repeatCount > 0) return null

 val keyCode = event.keyCode
 if (!isAllowedShortcutKey(keyCode)) return null

 val ctrl = event.isCtrlPressed
 val shift = event.isShiftPressed
 val alt = event.isAltPressed
 val meta = event.isMetaPressed

 if (!ctrl && !alt && !meta) return null

 return CopyBridgeKeyboardShortcutSpec(
 keyCode = keyCode,
 ctrl = ctrl,
 shift = shift,
 alt = alt,
 meta = meta
 )
 }

 fun findMatchingAction(context: Context, event: KeyEvent): String? {
 if (event.action != KeyEvent.ACTION_DOWN) return null
 if (event.repeatCount > 0) return null

 val telegramToGptSpec = getSpec(context, CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT)
 if (matches(event, telegramToGptSpec)) {
 return CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT
 }

 val gptToTelegramSpec = getSpec(context, CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM)
 if (matches(event, gptToTelegramSpec)) {
 return CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM
 }

 return null
 }

 fun conflictAction(context: Context, action: String, spec: CopyBridgeKeyboardShortcutSpec): String {
 val otherAction = when (action) {
 CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT -> CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM
 CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM -> CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT
 else -> ""
 }

 if (otherAction.isBlank()) return ""

 val otherSpec = getSpec(context, otherAction)
 return if (sameSpec(spec, otherSpec)) otherAction else ""
 }

 fun actionLabel(action: String): String {
 return when (action) {
 CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT -> "GPT로 보내기"
 CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM -> "텔레그램으로 보내기"
 else -> action
 }
 }

 fun labelForSpec(spec: CopyBridgeKeyboardShortcutSpec): String {
 val parts = mutableListOf<String>()
 if (spec.ctrl) parts.add("Ctrl")
 if (spec.shift) parts.add("Shift")
 if (spec.alt) parts.add("Alt")
 if (spec.meta) parts.add("Meta")
 parts.add(keyLabel(spec.keyCode))
 return parts.joinToString(" + ")
 }

 private fun matches(event: KeyEvent, spec: CopyBridgeKeyboardShortcutSpec): Boolean {
 return event.keyCode == spec.keyCode &&
 event.isCtrlPressed == spec.ctrl &&
 event.isShiftPressed == spec.shift &&
 event.isAltPressed == spec.alt &&
 event.isMetaPressed == spec.meta
 }

 private fun sameSpec(
 first: CopyBridgeKeyboardShortcutSpec,
 second: CopyBridgeKeyboardShortcutSpec
 ): Boolean {
 return first.keyCode == second.keyCode &&
 first.ctrl == second.ctrl &&
 first.shift == second.shift &&
 first.alt == second.alt &&
 first.meta == second.meta
 }

 private fun isAllowedShortcutKey(keyCode: Int): Boolean {
 return when (keyCode) {
 KeyEvent.KEYCODE_UNKNOWN,
 KeyEvent.KEYCODE_BACK,
 KeyEvent.KEYCODE_HOME,
 KeyEvent.KEYCODE_POWER,
 KeyEvent.KEYCODE_VOLUME_UP,
 KeyEvent.KEYCODE_VOLUME_DOWN,
 KeyEvent.KEYCODE_VOLUME_MUTE,
 KeyEvent.KEYCODE_MENU,
 KeyEvent.KEYCODE_APP_SWITCH,
 KeyEvent.KEYCODE_CTRL_LEFT,
 KeyEvent.KEYCODE_CTRL_RIGHT,
 KeyEvent.KEYCODE_SHIFT_LEFT,
 KeyEvent.KEYCODE_SHIFT_RIGHT,
 KeyEvent.KEYCODE_ALT_LEFT,
 KeyEvent.KEYCODE_ALT_RIGHT,
 KeyEvent.KEYCODE_META_LEFT,
 KeyEvent.KEYCODE_META_RIGHT -> false
 else -> true
 }
 }

 private fun keyLabel(keyCode: Int): String {
 return when (keyCode) {
 KeyEvent.KEYCODE_ENTER -> "Enter"
 KeyEvent.KEYCODE_NUMPAD_ENTER -> "Numpad Enter"
 KeyEvent.KEYCODE_SPACE -> "Space"
 KeyEvent.KEYCODE_TAB -> "Tab"
 KeyEvent.KEYCODE_DEL -> "Backspace"
 KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
 KeyEvent.KEYCODE_ESCAPE -> "Esc"
 else -> {
 val raw = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
 if (raw.length == 1) {
 raw
 } else {
 raw.lowercase()
 .split("_")
 .filter { it.isNotBlank() }
 .joinToString(" ") { word -> word.replaceFirstChar { char -> char.uppercase() } }
 }
 }
 }
 }
}

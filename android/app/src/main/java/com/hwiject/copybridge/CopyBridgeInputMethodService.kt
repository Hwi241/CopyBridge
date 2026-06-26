package com.hwiject.copybridge

import android.inputmethodservice.InputMethodService
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class CopyBridgeInputMethodService : InputMethodService() {
 companion object {
 private const val TAG = "CopyBridgeIME"
 private const val PREF_NAME = "copybridge_ime_prefs"

 private const val KEY_PENDING_ID = "pending_id"
 private const val KEY_PENDING_TEXT = "pending_text"
 private const val KEY_PENDING_AUTO_SEND = "pending_auto_send"
 private const val KEY_PENDING_REASON = "pending_reason"

 private const val KEY_CONSUMED_ID = "consumed_id"
 private const val KEY_COMMITTED_ID = "committed_id"
 private const val KEY_COMMIT_OK = "commit_ok"
 private const val KEY_COMMITTED_AT = "committed_at"
 private const val KEY_EDITOR_ACTION_OK = "editor_action_ok"
 private const val KEY_EDITOR_ACTION_AT = "editor_action_at"

 private const val KEY_IME_ALIVE = "ime_alive"
 private const val KEY_IME_LAST_EVENT = "ime_last_event"
 private const val KEY_IME_LAST_TRIGGER = "ime_last_trigger"
 private const val KEY_IME_LAST_STATUS = "ime_last_status"
 private const val KEY_IME_LAST_HAS_CONNECTION = "ime_last_has_connection"
 private const val KEY_IME_LAST_PENDING_ID = "ime_last_pending_id"
 private const val KEY_IME_LAST_TEXT_LENGTH = "ime_last_text_length"
 private const val KEY_IME_LAST_AT = "ime_last_at"
 private const val KEY_IME_LAST_ERROR = "ime_last_error"

 @Volatile
 private var currentInstance: CopyBridgeInputMethodService? = null

 fun requestCommitNow(trigger: String): Boolean {
 val instance = currentInstance ?: return false
 return instance.postCommitPendingText("accessibility:$trigger")
 }
 }

 private var latestEditorInfo: EditorInfo? = null
 private val switchBackHandler = Handler(Looper.getMainLooper())
 private var pendingSwitchBackRunnable: Runnable? = null

 private fun scheduleSwitchBackToPreviousInputMethod(reason: String, delayMs: Long = 700L) {
 pendingSwitchBackRunnable?.let { switchBackHandler.removeCallbacks(it) }

 val runnable = Runnable {
 var result = false
 var error = ""

 try {
 if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
 result = switchToPreviousInputMethod()
 } else {
 requestHideSelf(0)
 error = "UNSUPPORTED_API_${Build.VERSION.SDK_INT}_HIDE_SELF"
 }
 } catch (throwable: Throwable) {
 error = "${throwable.javaClass.simpleName}:${throwable.message ?: ""}"
 }

 Log.d(
 "CopyBridgeKeyboard",
 "SWITCH_BACK_RESULT result=$result reason=$reason delayMs=$delayMs error=$error"
 )

 pendingSwitchBackRunnable = null
 }

 pendingSwitchBackRunnable = runnable

 Log.d(
 "CopyBridgeKeyboard",
 "SWITCH_BACK_SCHEDULED reason=$reason delayMs=$delayMs"
 )

 switchBackHandler.postDelayed(runnable, delayMs)
 }

 override fun onCreate() {
 super.onCreate(); currentInstance = this
 writeImeState("onCreate", "lifecycle", "created", currentInputConnection != null, 0L, 0, "")
 }

 override fun onDestroy() {
 writeImeState("onDestroy", "lifecycle", "destroyed", currentInputConnection != null, 0L, 0, "")
 if (currentInstance === this) { currentInstance = null }
 super.onDestroy()
 }

 override fun onCreateInputView(): View {
 writeImeState("onCreateInputView", "view", "view_created_active", currentInputConnection != null, 0L, 0, "")
 return LinearLayout(this).apply {
 orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
 setPadding(28, 22, 28, 22); setBackgroundColor(0xFF101820.toInt())
 addView(TextView(this@CopyBridgeInputMethodService).apply {
 text = "CopyBridge Keyboard ACTIVE"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
 gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
 })
 addView(TextView(this@CopyBridgeInputMethodService).apply {
 text = "이 화면이 보이면 CopyBridge IME가 실제 입력기로 실행된 상태입니다.\n이 상태에서 GPT로 보내기를 다시 누르세요."
 textSize = 14f; gravity = Gravity.CENTER; setTextColor(0xFFE0E0E0.toInt()); setPadding(0, 14, 0, 0)
 })
 }
 }

 override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
 super.onStartInput(attribute, restarting); latestEditorInfo = attribute
 writeImeState("onStartInput", "system", "started restarting=$restarting", currentInputConnection != null, 0L, 0, "")
 tryCommitPendingText("onStartInput", attribute)
 }

 override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
 super.onStartInputView(info, restarting); latestEditorInfo = info
 writeImeState("onStartInputView", "system", "view_started restarting=$restarting", currentInputConnection != null, 0L, 0, "")
 tryCommitPendingText("onStartInputView", info)
 }

 override fun onFinishInput() {
 writeImeState("onFinishInput", "system", "finished", currentInputConnection != null, 0L, 0, "")
 super.onFinishInput()
 }

 private fun postCommitPendingText(trigger: String): Boolean {
 writeImeState("postCommitPendingText", trigger, "posted", currentInputConnection != null, 0L, 0, "")
 android.os.Handler(android.os.Looper.getMainLooper()).post { tryCommitPendingText(trigger, latestEditorInfo) }
 return true
 }

 private fun tryCommitPendingText(trigger: String, editorInfo: EditorInfo?) {
 val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
 val pendingId = prefs.getLong(KEY_PENDING_ID, 0L)
 val consumedId = prefs.getLong(KEY_CONSUMED_ID, 0L)
 val text = prefs.getString(KEY_PENDING_TEXT, "").orEmpty()
 val autoSend = prefs.getBoolean(KEY_PENDING_AUTO_SEND, false)
 val reason = prefs.getString(KEY_PENDING_REASON, "").orEmpty()

 if (pendingId <= 0L || pendingId == consumedId || text.isBlank()) {
 writeImeState("tryCommitPendingText", trigger, "no_pending pendingId=$pendingId consumedId=$consumedId", currentInputConnection != null, pendingId, text.length, "")
 return
 }

 val inputConnection = currentInputConnection
 if (inputConnection == null) {
 writeImeState("tryCommitPendingText", trigger, "no_input_connection", false, pendingId, text.length, "")
 return
 }

 writeImeState("tryCommitPendingText", trigger, "commit_attempt", true, pendingId, text.length, "")
 val commitOk = try { inputConnection.commitText(text, 1)
 } catch (error: Exception) { writeImeState("tryCommitPendingText", trigger, "commit_error", true, pendingId, text.length, "${error::class.java.simpleName}: ${error.message.orEmpty()}"); false }

 Log.d(
 "CopyBridgeKeyboard",
 "KEYBOARD_COMMIT_RESULT result=$commitOk length=${text.length} trigger=$trigger pendingId=$pendingId"
 )

 val now = System.currentTimeMillis()
 prefs.edit().putLong(KEY_CONSUMED_ID, pendingId).putLong(KEY_COMMITTED_ID, pendingId).putBoolean(KEY_COMMIT_OK, commitOk).putLong(KEY_COMMITTED_AT, now).apply()
 writeImeState("tryCommitPendingText", trigger, "commit_result ok=$commitOk", true, pendingId, text.length, "")
 Toast.makeText(this, if (commitOk) "CopyBridge IME 입력 완료" else "CopyBridge IME 입력 실패", Toast.LENGTH_SHORT).show()

 if (commitOk && autoSend) {
 android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
 val editorActionOk = try { currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_SEND) ?: false
 } catch (error: Exception) { writeImeState("performEditorAction", trigger, "editor_action_error", currentInputConnection != null, pendingId, text.length, "${error::class.java.simpleName}: ${error.message.orEmpty()}"); false }
 prefs.edit().putBoolean(KEY_EDITOR_ACTION_OK, editorActionOk).putLong(KEY_EDITOR_ACTION_AT, System.currentTimeMillis()).apply()
 writeImeState("performEditorAction", trigger, "editor_action_send ok=$editorActionOk", currentInputConnection != null, pendingId, text.length, "")
 }, 350L)
 }

 if (commitOk) {
 scheduleSwitchBackToPreviousInputMethod("commitText_success")
 }
 }

 private fun writeImeState(event: String, trigger: String, status: String, hasConnection: Boolean, pendingId: Long, textLength: Int, error: String) {
 getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_IME_ALIVE, true).putString(KEY_IME_LAST_EVENT, event).putString(KEY_IME_LAST_TRIGGER, trigger).putString(KEY_IME_LAST_STATUS, status).putBoolean(KEY_IME_LAST_HAS_CONNECTION, hasConnection).putLong(KEY_IME_LAST_PENDING_ID, pendingId).putInt(KEY_IME_LAST_TEXT_LENGTH, textLength).putLong(KEY_IME_LAST_AT, System.currentTimeMillis()).putString(KEY_IME_LAST_ERROR, error).apply()
 Log.d(TAG, "IME_STATE event=$event trigger=$trigger status=$status hasConnection=$hasConnection pendingId=$pendingId textLength=$textLength error=$error")
 }
}

package com.hwiject.copybridge

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class FloatingWidgetService : Service() {
  private var windowManager: WindowManager? = null
  private var floatingView: View? = null
  private var layoutParams: WindowManager.LayoutParams? = null
  private var preferences: SharedPreferences? = null

  private var initialX = 0
  private var initialY = 0
  private var initialTouchX = 0f
  private var initialTouchY = 0f

  private var widgetSize = WidgetSize.SMALL
  private var autoSendEnabled = false
  private var replyCopyModeString: String = "FULL"
  private var gptOutputModeString: String = "CODE"
  private var isCollapsed = false
  private var widgetOpacity = 1f
  private var collapsedOpacity = 0.85f

  private enum class WidgetSize(
    val label: String,
    val panelPaddingHorizontal: Int,
    val panelPaddingTop: Int,
    val panelPaddingBottom: Int,
    val contentWidth: Int,
    val buttonHeight: Int,
    val toggleHeight: Int,
    val buttonTextSize: Float,
    val titleTextSize: Float,
    val headerButtonSize: Int
  ) {
    SMALL("S", 22, 18, 20, 260, 74, 46, 12f, 12f, 48),
    MEDIUM("M", 24, 20, 22, 300, 84, 50, 13f, 13f, 52),
    LARGE("L", 26, 22, 24, 340, 94, 54, 14f, 14f, 56),
    EXTRA_LARGE("XL", 28, 24, 26, 380, 104, 58, 15f, 15f, 60)
  }

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    loadWidgetPreferences()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_REFRESH_WIDGET) {
      refreshWidgetAtSamePosition()
      return START_STICKY
    }

    if (intent?.action == ACTION_RESTORE_WIDGET) {
      restoreExpandedWidget()
      return START_STICKY
    }

    if (!canDrawOverlay()) {
      Toast.makeText(this, "오버레이 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
      stopSelf()
      return START_NOT_STICKY
    }

    showFloatingWidget()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? {
    return null
  }

  override fun onDestroy() {
    removeFloatingWidget()
    super.onDestroy()
  }

  private fun loadWidgetPreferences() {
    val prefs = preferences ?: return
    val savedSizeLabel = prefs.getString(KEY_WIDGET_SIZE, WidgetSize.SMALL.label)
    widgetSize = WidgetSize.entries.firstOrNull { it.label == savedSizeLabel } ?: WidgetSize.SMALL
    autoSendEnabled = prefs.getBoolean(KEY_AUTO_SEND_ENABLED, false)
    replyCopyModeString = prefs.getString(KEY_REPLY_COPY_MODE, "FULL") ?: "FULL"
    gptOutputModeString = prefs.getString(KEY_GPT_OUTPUT_MODE, "CODE") ?: "CODE"
    isCollapsed = prefs.getBoolean(KEY_WIDGET_COLLAPSED, false)
    widgetOpacity = prefs.getFloat("widget_opacity", 1f)
    collapsedOpacity = prefs.getFloat("collapsed_opacity", 0.85f)
  }

  private fun saveWidgetPreferences() {
    val prefs = preferences ?: return
    prefs.edit()
      .putString(KEY_WIDGET_SIZE, widgetSize.label)
      .putBoolean(KEY_AUTO_SEND_ENABLED, autoSendEnabled)
      .putString(KEY_REPLY_COPY_MODE, replyCopyModeString)
      .putString(KEY_GPT_OUTPUT_MODE, gptOutputModeString)
      .putBoolean(KEY_WIDGET_COLLAPSED, isCollapsed)
      .apply()
  }

  private fun getSavedWidgetX(): Int {
    return preferences?.getInt(KEY_WIDGET_X, 40) ?: 40
  }

  private fun getSavedWidgetY(): Int {
    return preferences?.getInt(KEY_WIDGET_Y, 320) ?: 320
  }

  private fun saveWidgetPosition(x: Int, y: Int) {
    val prefs = preferences ?: return
    prefs.edit()
      .putInt(KEY_WIDGET_X, x)
      .putInt(KEY_WIDGET_Y, y)
      .apply()
  }

  private fun canDrawOverlay(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Settings.canDrawOverlays(this)
    } else {
      true
    }
  }

  private fun showFloatingWidget() {
    val prefs = getSharedPreferences("copybridge_floating_widget", MODE_PRIVATE)
    widgetOpacity = prefs.getFloat("widget_opacity", 1f).coerceIn(0.35f, 1.0f)
    collapsedOpacity = prefs.getFloat("collapsed_opacity", 0.85f).coerceIn(0.35f, 1.0f)
    if (floatingView != null) {
      Toast.makeText(this, "CopyBridge 위젯이 이미 실행 중입니다.", Toast.LENGTH_SHORT).show()
      return
    }

    val widgetView = createWidgetView()

    val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION")
      WindowManager.LayoutParams.TYPE_PHONE
    }

    val params = WindowManager.LayoutParams(
      WindowManager.LayoutParams.WRAP_CONTENT,
      WindowManager.LayoutParams.WRAP_CONTENT,
      overlayType,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      PixelFormat.TRANSLUCENT
    ).apply {
      gravity = Gravity.TOP or Gravity.START
      x = getSavedWidgetX()
      y = getSavedWidgetY()
    }

    layoutParams = params
    floatingView = widgetView

    try {
      windowManager?.addView(widgetView, params)
      Toast.makeText(this, "CopyBridge 위젯을 시작했습니다.", Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
      floatingView = null
      layoutParams = null
      Toast.makeText(this, "위젯을 표시하지 못했습니다.", Toast.LENGTH_SHORT).show()
      stopSelf()
    }
  }

  private fun removeFloatingWidget() {
    val view = floatingView ?: return

    try {
      windowManager?.removeView(view)
    } catch (_: Exception) {
      // 이미 제거된 경우 무시한다.
    } finally {
      floatingView = null
      layoutParams = null
    }
  }

  private fun cycleWidgetSize() {
    val currentX = layoutParams?.x ?: 40
    val currentY = layoutParams?.y ?: 320

    widgetSize = when (widgetSize) {
      WidgetSize.SMALL -> WidgetSize.MEDIUM
      WidgetSize.MEDIUM -> WidgetSize.LARGE
      WidgetSize.LARGE -> WidgetSize.EXTRA_LARGE
      WidgetSize.EXTRA_LARGE -> WidgetSize.SMALL
    }

    removeFloatingWidget()
    showFloatingWidget()

    layoutParams?.let { newParams ->
      newParams.x = currentX
      newParams.y = currentY
      windowManager?.updateViewLayout(floatingView, newParams)
    }

    saveWidgetPreferences()
    Toast.makeText(this, "위젯 크기: ${widgetSize.label}", Toast.LENGTH_SHORT).show()
  }

  private fun createWidgetView(): View {
    val (_, _, bridgeStatusText) = loadBridgeStatusForWidget()
    val size = widgetSize

    if (isCollapsed) return createCollapsedView()

    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(size.panelPaddingHorizontal, size.panelPaddingTop, size.panelPaddingHorizontal, size.panelPaddingBottom)
      background = roundedBackground(Color.parseColor("#171717"), 18f)
      elevation = 12f
    }

    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }

    val collapseButton = createHeaderButton("−") {
      isCollapsed = true; saveWidgetPreferences(); refreshWidgetAtSamePosition()
    }

    val title = TextView(this).apply {
      text = "Bridge"; setTextColor(Color.WHITE); textSize = size.titleTextSize
      typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL
    }

    val sizeButton = createHeaderButton(size.label) { cycleWidgetSize() }
    val closeButton = createHeaderButton("×") {
      Toast.makeText(this, "CopyBridge 위젯을 종료합니다.", Toast.LENGTH_SHORT).show(); stopSelf()
    }.apply { textSize = 18f }

    header.addView(collapseButton, LinearLayout.LayoutParams(size.headerButtonSize, size.headerButtonSize).apply { rightMargin = 8 })
    header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(sizeButton, LinearLayout.LayoutParams(size.headerButtonSize, size.headerButtonSize).apply { rightMargin = 8 })
    header.addView(closeButton, LinearLayout.LayoutParams(size.headerButtonSize, size.headerButtonSize))

    val replyCopyLabel = if (replyCopyModeString == "LAST") "답변: 마지막" else "답변: 전체"
    val replyCopyButton = createDarkButton(replyCopyLabel, size.buttonTextSize) {
      replyCopyModeString = if (replyCopyModeString == "LAST") "FULL" else "LAST"
      saveWidgetPreferences(); refreshWidgetAtSamePosition()
    }

    val gptOutputLabel = if (gptOutputModeString == "FULL") "GPT: 전체" else "GPT: 코드"
    val gptOutputButton = createDarkButton(gptOutputLabel, size.buttonTextSize) {
      gptOutputModeString = if (gptOutputModeString == "FULL") "CODE" else "FULL"
      saveWidgetPreferences(); refreshWidgetAtSamePosition()
    }

    val autoSendLabel = if (autoSendEnabled) "전송: 켬" else "전송: 끔"
    val autoSendButton = TextView(this).apply {
      text = autoSendLabel
      setTextColor(Color.WHITE)
      textSize = size.buttonTextSize
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      background = roundedBackground(Color.parseColor("#242424"), 12f)
      setPadding(8, 0, 8, 0)
      setOnClickListener {
        autoSendEnabled = !autoSendEnabled; saveWidgetPreferences(); refreshWidgetAtSamePosition()
      }
      setOnTouchListener { view, event ->
        applyPressFeedback(view, event)
        false
      }
      minHeight = 0
      minimumHeight = 0
      includeFontPadding = false
      isAllCaps = false
    }

    val gptButton = createWhiteButton("GPT로 보내기", size.buttonTextSize) {
            appendDebugLog("WIDGET", "tap GPT로 보내기 replyMode=$replyCopyModeString autoSend=$autoSendEnabled")
      CopyBridgeAccessibilityService.requestTelegramToGpt(this, replyCopyModeString, autoSendEnabled)
    }

    val tgButton = createTelegramBlueButton("텔레그램으로 보내기", size.buttonTextSize) {
            appendDebugLog("WIDGET", "tap 텔레그램으로 보내기 gptMode=$gptOutputModeString autoSend=$autoSendEnabled")
      CopyBridgeAccessibilityService.requestGptToTelegram(this, gptOutputModeString, autoSendEnabled)
    }

    panel.addView(header, LinearLayout.LayoutParams(size.contentWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
    panel.addView(gptButton, LinearLayout.LayoutParams(size.contentWidth, size.buttonHeight).apply { bottomMargin = 10 })
    panel.addView(replyCopyButton, LinearLayout.LayoutParams(size.contentWidth, size.toggleHeight).apply { bottomMargin = 10 })
    panel.addView(tgButton, LinearLayout.LayoutParams(size.contentWidth, size.buttonHeight).apply { bottomMargin = 10 })
    panel.addView(gptOutputButton, LinearLayout.LayoutParams(size.contentWidth, size.toggleHeight).apply { bottomMargin = 10 })
    panel.addView(autoSendButton, LinearLayout.LayoutParams(size.contentWidth, size.toggleHeight))
    panel.addView(createBridgeStatusTextView(bridgeStatusText))

    panel.alpha = widgetOpacity
    panel.setOnTouchListener { _, event -> handleDrag(event); true }
    return panel
  }

  private fun appendDebugLog(category: String, message: String) {
    val prefs = getSharedPreferences("copybridge_debug_logs", MODE_PRIVATE)
    val oldLogs = prefs.getString("logs", "").orEmpty()
    val entries = oldLogs.split("\n---\n").filter { it.isNotBlank() }.toMutableList()
    val time = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
    entries.add("[$time][$category] $message")
    val trimmed = entries.takeLast(50).joinToString("\n---\n")
    prefs.edit().putString("logs", trimmed).apply()
  }

  private fun createCollapsedView(): View {
    return TextView(this).apply {
      alpha = collapsedOpacity
      text = "B"
      setTextColor(Color.WHITE)
      textSize = 20f
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      background = roundedBackground(Color.parseColor("#171717"), 48f)
      setPadding(24, 24, 24, 24)
      elevation = 12f

      setOnTouchListener { view, event ->
        val params = this@FloatingWidgetService.layoutParams ?: return@setOnTouchListener true

        when (event.action) {
          MotionEvent.ACTION_DOWN -> {
            view.alpha = 0.55f
            initialX = params.x
            initialY = params.y
            initialTouchX = event.rawX
            initialTouchY = event.rawY
            true
          }

          MotionEvent.ACTION_MOVE -> {
            val dx = event.rawX - initialTouchX
            val dy = event.rawY - initialTouchY
            val isDrag = dx < -COLLAPSED_DRAG_SLOP ||
              dx > COLLAPSED_DRAG_SLOP ||
              dy < -COLLAPSED_DRAG_SLOP ||
              dy > COLLAPSED_DRAG_SLOP

            if (isDrag) {
              params.x = initialX + dx.toInt()
              params.y = initialY + dy.toInt()
              windowManager?.updateViewLayout(floatingView, params)
              saveWidgetPosition(params.x, params.y)
            }

            true
          }

          MotionEvent.ACTION_UP -> {
            view.alpha = 1f

            val dx = event.rawX - initialTouchX
            val dy = event.rawY - initialTouchY
            val isTap = dx >= -COLLAPSED_DRAG_SLOP &&
              dx <= COLLAPSED_DRAG_SLOP &&
              dy >= -COLLAPSED_DRAG_SLOP &&
              dy <= COLLAPSED_DRAG_SLOP

            if (isTap) {
              isCollapsed = false
              saveWidgetPreferences()
              refreshWidgetAtSamePosition()
            }

            true
          }

          MotionEvent.ACTION_CANCEL -> {
            view.alpha = 1f
            true
          }

          else -> true
        }
      }
    }
  }

  private fun refreshWidgetAtSamePosition() {
    val currentX = layoutParams?.x ?: 40
    val currentY = layoutParams?.y ?: 320

    removeFloatingWidget()
    showFloatingWidget()

    layoutParams?.let { newParams ->
      newParams.x = currentX
      newParams.y = currentY
      windowManager?.updateViewLayout(floatingView, newParams)
    }
  }

  private fun restoreExpandedWidget() {
    isCollapsed = false
    saveWidgetPreferences()

    val x = layoutParams?.x ?: 40
    val y = layoutParams?.y ?: 320

    if (floatingView != null) {
      refreshWidgetAtSamePosition()
    } else {
      showFloatingWidget()
    }

    layoutParams?.let { params ->
      params.x = x
      params.y = y
      windowManager?.updateViewLayout(floatingView, params)
    }

    Toast.makeText(this, "위젯을 복원했습니다.", Toast.LENGTH_SHORT).show()
  }

  private fun createHeaderButton(label: String, onClick: () -> Unit): TextView {
    return TextView(this).apply {
      text = label
      setTextColor(Color.WHITE)
      textSize = 13f
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      background = roundedBackground(Color.parseColor("#333333"), 12f)
      setOnClickListener { onClick() }
      setOnTouchListener { view, event ->
        applyPressFeedback(view, event)
        false
      }
    }
  }

  private fun loadBridgeStatusForWidget(): Triple<Boolean, Boolean, String> {
    val prefs = getSharedPreferences("copybridge_busy_state", MODE_PRIVATE)
    val gptBusy = prefs.getBoolean("gpt_busy", false)
    val telegramTyping = prefs.getBoolean("telegram_typing", false)

    val statusText = when {
      gptBusy && telegramTyping -> "\u23F3 \uC791\uC131 \uC911..."
      gptBusy -> "\u23F3 GPT \uC791\uC131 \uC911..."
      telegramTyping -> "\u23F3 OpenClaw \uC791\uC131 \uC911..."
      else -> "\u2705 \uB300\uAE30 \uC911"
    }

    appendDebugLog(
      "WIDGET",
      "WIDGET_STATUS_STATE gptBusy=$gptBusy telegramTyping=$telegramTyping text=$statusText"
    )

    return Triple(gptBusy, telegramTyping, statusText)
  }

  private fun createBridgeStatusTextView(statusText: String): TextView {
    val isReady = statusText[0] == '\u2705'

    return TextView(this).apply {
      text = statusText
      textSize = 11f
      gravity = android.view.Gravity.CENTER
      setTextColor(
        if (isReady) {
          android.graphics.Color.parseColor("#4CAF50")
        } else {
          android.graphics.Color.parseColor("#D0A85A")
        }
      )
      alpha = 0.92f
      setPadding(0, 4, 0, 0)
    }
  }

  private fun createDarkButton(
    label: String,
    textSizeValue: Float,
    onClick: () -> Unit
  ): Button {
    return Button(this).apply {
      text = label
      textSize = textSizeValue
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.WHITE)
      background = roundedBackground(Color.parseColor("#333333"), 12f)
      setPadding(8, 0, 8, 0)
      setOnClickListener { onClick() }
      setOnTouchListener { view, event ->
        applyPressFeedback(view, event)
        false
      }
      minHeight = 0
      minimumHeight = 0
      includeFontPadding = false
      isAllCaps = false
    }
  }

  private fun createTelegramBlueButton(
    label: String,
    textSizeValue: Float,
    onClick: () -> Unit
  ): Button {
    return Button(this).apply {
      text = label
      textSize = textSizeValue
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.WHITE)
      background = roundedBackground(Color.parseColor("#2AABEE"), 12f)
      setPadding(8, 0, 8, 0)
      setOnClickListener { onClick() }
      setOnTouchListener { view, event ->
        applyPressFeedback(view, event)
        false
      }
      minHeight = 0
      minimumHeight = 0
      includeFontPadding = false
      isAllCaps = false
    }
  }

  private fun createWhiteButton(
    label: String,
    textSizeValue: Float,
    onClick: () -> Unit
  ): Button {
    return Button(this).apply {
      text = label
      textSize = textSizeValue
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.parseColor("#171717"))
      background = roundedBackground(Color.WHITE, 12f)
      setPadding(8, 0, 8, 0)
      setOnClickListener { onClick() }
      setOnTouchListener { view, event ->
        applyPressFeedback(view, event)
        false
      }
      minHeight = 0
      minimumHeight = 0
      includeFontPadding = false
      isAllCaps = false
    }
  }

  private fun applyPressFeedback(view: View, event: MotionEvent) {
    when (event.action) {
      MotionEvent.ACTION_DOWN -> view.alpha = 0.55f
      MotionEvent.ACTION_UP,
      MotionEvent.ACTION_CANCEL -> view.alpha = 1f
    }
  }

  private fun handleDrag(event: MotionEvent) {
    val params = layoutParams ?: return

    when (event.action) {
      MotionEvent.ACTION_DOWN -> {
        initialX = params.x
        initialY = params.y
        initialTouchX = event.rawX
        initialTouchY = event.rawY
      }

      MotionEvent.ACTION_MOVE -> {
        params.x = initialX + (event.rawX - initialTouchX).toInt()
        params.y = initialY + (event.rawY - initialTouchY).toInt()
        windowManager?.updateViewLayout(floatingView, params)
        saveWidgetPosition(params.x, params.y)
      }
    }
  }

  private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
    return GradientDrawable().apply {
      setColor(color)
      cornerRadius = radius
    }
  }

  companion object {
    private const val PREFS_NAME = "copybridge_floating_widget"
    private const val KEY_WIDGET_SIZE = "widget_size"
    private const val KEY_AUTO_SEND_ENABLED = "auto_send_enabled"
    private const val KEY_REPLY_COPY_MODE = "reply_copy_mode"
    private const val KEY_GPT_OUTPUT_MODE = "gpt_output_mode"
    private const val KEY_WIDGET_COLLAPSED = "widget_collapsed"
    const val ACTION_RESTORE_WIDGET = "com.hwiject.copybridge.RESTORE_WIDGET"
    const val ACTION_REFRESH_WIDGET = "com.hwiject.copybridge.REFRESH_WIDGET"
    private const val KEY_WIDGET_X = "widget_x"
    private const val KEY_WIDGET_Y = "widget_y"
    private const val COLLAPSED_TAP_SLOP = 96f
    private const val COLLAPSED_DRAG_SLOP = 18f
  }
}

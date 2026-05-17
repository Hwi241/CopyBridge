package com.hwiject.copybridge

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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

  private var initialX = 0
  private var initialY = 0
  private var initialTouchX = 0f
  private var initialTouchY = 0f

  private var widgetSize = WidgetSize.MEDIUM
  private var copyMode = CopyMode.ALL
  private var autoSendEnabled = false

  private enum class WidgetSize(
    val label: String,
    val panelPaddingHorizontal: Int,
    val panelPaddingTop: Int,
    val panelPaddingBottom: Int,
    val contentWidth: Int,
    val buttonHeight: Int,
    val buttonTextSize: Float,
    val titleTextSize: Float,
    val headerButtonSize: Int
  ) {
    SMALL("S", 14, 10, 12, 190, 56, 10f, 10f, 36),
    MEDIUM("M", 18, 14, 16, 220, 64, 11f, 11f, 42),
    LARGE("L", 22, 18, 20, 260, 74, 12f, 12f, 48)
  }

  private enum class CopyMode(
    val key: String,
    val label: String
  ) {
    ALL("ALL", "복사: 전체"),
    LAST("LAST", "복사: 마지막")
  }

  override fun onCreate() {
    super.onCreate()
    windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

  private fun canDrawOverlay(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      Settings.canDrawOverlays(this)
    } else {
      true
    }
  }

  private fun showFloatingWidget() {
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
      x = 40
      y = 320
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
    val currentParams = layoutParams
    val currentX = currentParams?.x ?: 40
    val currentY = currentParams?.y ?: 320

    widgetSize = when (widgetSize) {
      WidgetSize.SMALL -> WidgetSize.MEDIUM
      WidgetSize.MEDIUM -> WidgetSize.LARGE
      WidgetSize.LARGE -> WidgetSize.SMALL
    }

    removeFloatingWidget()
    showFloatingWidget()

    layoutParams?.let { newParams ->
      newParams.x = currentX
      newParams.y = currentY
      windowManager?.updateViewLayout(floatingView, newParams)
    }

    Toast.makeText(this, "위젯 크기: ${widgetSize.label}", Toast.LENGTH_SHORT).show()
  }

  private fun toggleCopyMode(labelView: TextView) {
    copyMode = when (copyMode) {
      CopyMode.ALL -> CopyMode.LAST
      CopyMode.LAST -> CopyMode.ALL
    }

    labelView.text = copyMode.label
    Toast.makeText(this, copyMode.label, Toast.LENGTH_SHORT).show()
  }

  private fun toggleAutoSend(labelView: TextView) {
    autoSendEnabled = !autoSendEnabled
    labelView.text = if (autoSendEnabled) "전송: 켬" else "전송: 끔"

    Toast.makeText(
      this,
      if (autoSendEnabled) "자동 전송: 켬" else "자동 전송: 끔",
      Toast.LENGTH_SHORT
    ).show()
  }

  private fun createWidgetView(): View {
    val size = widgetSize

    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(
        size.panelPaddingHorizontal,
        size.panelPaddingTop,
        size.panelPaddingHorizontal,
        size.panelPaddingBottom
      )
      background = roundedBackground(Color.parseColor("#171717"), 18f)
      elevation = 12f
    }

    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }

    val title = TextView(this).apply {
      text = "Bridge"
      setTextColor(Color.WHITE)
      textSize = size.titleTextSize
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER_VERTICAL
    }

    val sizeButton = createHeaderButton(size.label) {
      cycleWidgetSize()
    }

    val closeButton = createHeaderButton("×") {
      Toast.makeText(this, "CopyBridge 위젯을 종료합니다.", Toast.LENGTH_SHORT).show()
      stopSelf()
    }.apply {
      textSize = 18f
    }

    header.addView(
      title,
      LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    )

    header.addView(
      sizeButton,
      LinearLayout.LayoutParams(size.headerButtonSize, size.headerButtonSize).apply {
        rightMargin = 8
      }
    )

    header.addView(
      closeButton,
      LinearLayout.LayoutParams(size.headerButtonSize, size.headerButtonSize)
    )

    val copyModeButton = createToggleTextButton(copyMode.label, size.buttonTextSize) { view ->
      toggleCopyMode(view)
    }

    val autoSendButton = createToggleTextButton(
      if (autoSendEnabled) "전송: 켬" else "전송: 끔",
      size.buttonTextSize
    ) { view ->
      toggleAutoSend(view)
    }

    val copyButton = createWidgetButton("TG → AI 복사", size.buttonTextSize) {
      CopyBridgeAccessibilityService.requestCopyTelegramToAi(this, copyMode.key)
    }

    val pasteButton = createWidgetButton("AI → TG 붙여넣기", size.buttonTextSize) {
      CopyBridgeAccessibilityService.requestPasteAiToTelegram(this, autoSendEnabled)
    }

    panel.addView(
      header,
      LinearLayout.LayoutParams(size.contentWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = 10
      }
    )

    panel.addView(
      copyModeButton,
      LinearLayout.LayoutParams(size.contentWidth, 42).apply {
        bottomMargin = 10
      }
    )

    panel.addView(
      autoSendButton,
      LinearLayout.LayoutParams(size.contentWidth, 42).apply {
        bottomMargin = 10
      }
    )

    panel.addView(
      copyButton,
      LinearLayout.LayoutParams(size.contentWidth, size.buttonHeight).apply {
        bottomMargin = 10
      }
    )

    panel.addView(
      pasteButton,
      LinearLayout.LayoutParams(size.contentWidth, size.buttonHeight)
    )

    panel.setOnTouchListener { _, event ->
      handleDrag(event)
      true
    }

    return panel
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

  private fun createToggleTextButton(
    label: String,
    textSizeValue: Float,
    onClick: (TextView) -> Unit
  ): TextView {
    return TextView(this).apply {
      text = label
      setTextColor(Color.WHITE)
      textSize = textSizeValue
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      background = roundedBackground(Color.parseColor("#333333"), 12f)
      setOnClickListener { onClick(this) }
      setOnTouchListener { view, event ->
        applyPressFeedback(view, event)
        false
      }
    }
  }

  private fun createWidgetButton(
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
      }
    }
  }

  private fun roundedBackground(color: Int, radius: Float): GradientDrawable {
    return GradientDrawable().apply {
      setColor(color)
      cornerRadius = radius
    }
  }
}

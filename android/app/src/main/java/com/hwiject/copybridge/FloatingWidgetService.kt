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
    } catch (error: Exception) {
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

  private fun createWidgetView(): View {
    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(18, 14, 18, 16)
      background = roundedBackground(Color.parseColor("#171717"), 18f)
      elevation = 12f
    }

    val title = TextView(this).apply {
      text = "Bridge"
      setTextColor(Color.WHITE)
      textSize = 11f
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
    }

    val copyButton = createWidgetButton("TG → AI 복사") {
      CopyBridgeAccessibilityService.requestCopyTelegramToAi(this)
    }

    val pasteButton = createWidgetButton("AI → TG 붙여넣기") {
      CopyBridgeAccessibilityService.requestPasteAiToTelegram(this)
    }

    panel.addView(
      title,
      LinearLayout.LayoutParams(220, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = 10
      }
    )

    panel.addView(
      copyButton,
      LinearLayout.LayoutParams(220, 64).apply {
        bottomMargin = 10
      }
    )

    panel.addView(
      pasteButton,
      LinearLayout.LayoutParams(220, 64)
    )

    panel.setOnTouchListener { _, event ->
      handleDrag(event)
      true
    }

    return panel
  }

  private fun createWidgetButton(label: String, onClick: () -> Unit): Button {
    return Button(this).apply {
      text = label
      textSize = 11f
      typeface = Typeface.DEFAULT_BOLD
      setTextColor(Color.parseColor("#171717"))
      background = roundedBackground(Color.WHITE, 12f)
      setPadding(8, 0, 8, 0)
      setOnClickListener { onClick() }
      minHeight = 0
      minimumHeight = 0
      includeFontPadding = false
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

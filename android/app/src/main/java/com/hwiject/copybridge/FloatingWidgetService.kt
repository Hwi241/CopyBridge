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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread
import org.json.JSONObject

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

  private val apiBalanceHandler = Handler(Looper.getMainLooper())
  private var apiBalanceTextView: TextView? = null
  private var apiBalanceBoxView: TextView? = null
  private var apiBalanceText = "$" + "KEY"
  private var apiBalanceRefreshing = false
  private val apiBalanceHistory = mutableListOf<Pair<Long, Double>>()
  private val apiBalanceRunnable = object : Runnable {
    override fun run() {
      refreshDeepSeekBalance()
      apiBalanceHandler.postDelayed(this, API_BALANCE_REFRESH_INTERVAL_MS)
    }
  }

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
    COMPACT("SS", 8, 10, 10, 124, 128, 32, 11f, 10f, 36),
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
    apiBalanceHandler.removeCallbacks(apiBalanceRunnable)
    removeFloatingWidget()
    super.onDestroy()
  }

  private fun loadWidgetPreferences() {
    val prefs = preferences ?: return
    val savedSizeLabel = prefs.getString(KEY_WIDGET_SIZE, WidgetSize.SMALL.label)
    widgetSize = WidgetSize.entries.firstOrNull { it.label == savedSizeLabel } ?: WidgetSize.SMALL
    autoSendEnabled = prefs.getBoolean(KEY_AUTO_SEND_ENABLED, false)
    replyCopyModeString = prefs.getString(KEY_REPLY_COPY_MODE, "FULL") ?: "FULL"
    gptOutputModeString = "CODE"
    if (prefs.getString(KEY_GPT_OUTPUT_MODE, "CODE") != "CODE") {
      prefs.edit().putString(KEY_GPT_OUTPUT_MODE, "CODE").apply()
    }
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
      .putString(KEY_GPT_OUTPUT_MODE, "CODE")
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
      startDeepSeekBalanceAutoRefresh()
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
      apiBalanceTextView = null
      apiBalanceBoxView = null
    }
  }

  private fun runTelegramToGptWithTemporaryWidgetHide(
    source: String
  ) {
    val currentParams = layoutParams
    val currentX = currentParams?.x ?: getSavedWidgetX()
    val currentY = currentParams?.y ?: getSavedWidgetY()

    appendDebugLog(
      "WIDGET",
      "TG_TO_GPT_HIDE_WIDGET_START source=$source x=$currentX y=$currentY"
    )

    try {
      saveWidgetPosition(currentX, currentY)
    } catch (_: Exception) {}

    try {
      floatingView?.let { view ->
        windowManager?.removeView(view)
      }
      floatingView = null
    } catch (e: Exception) {
      appendDebugLog(
        "WIDGET",
        "TG_TO_GPT_HIDE_WIDGET_REMOVE_ERROR ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
      )
      floatingView = null
    }

    Handler(Looper.getMainLooper()).postDelayed({
      try {
        appendDebugLog(
          "WIDGET",
          "TG_TO_GPT_HIDE_WIDGET_RUN source=$source replyMode=$replyCopyModeString autoSend=$autoSendEnabled"
        )

        CopyBridgeAccessibilityService.requestTelegramToGpt(
          this,
          replyCopyModeString,
          autoSendEnabled
        )
      } finally {
        Handler(Looper.getMainLooper()).postDelayed({
          try {
            if (floatingView == null) {
              showFloatingWidget()
              layoutParams?.x = currentX
              layoutParams?.y = currentY
              floatingView?.let { view ->
                windowManager?.updateViewLayout(view, layoutParams)
              }
              appendDebugLog(
                "WIDGET",
                "TG_TO_GPT_HIDE_WIDGET_RESTORE source=$source x=$currentX y=$currentY"
              )
            }
          } catch (e: Exception) {
            appendDebugLog(
              "WIDGET",
              "TG_TO_GPT_HIDE_WIDGET_RESTORE_ERROR ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
            )
          }
        }, 250L)
      }
    }, 350L)
  }

  private fun cycleWidgetSize() {
    val currentX = layoutParams?.x ?: 40
    val currentY = layoutParams?.y ?: 320

    widgetSize = when (widgetSize) {
      WidgetSize.COMPACT -> WidgetSize.SMALL
      WidgetSize.SMALL -> WidgetSize.MEDIUM
      WidgetSize.MEDIUM -> WidgetSize.LARGE
      WidgetSize.LARGE -> WidgetSize.EXTRA_LARGE
      WidgetSize.EXTRA_LARGE -> WidgetSize.COMPACT
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
    val (gptBusy, telegramTyping, bridgeStatusText) = loadBridgeStatusForWidget()
    val isBridgeBusy = gptBusy || telegramTyping
    val size = widgetSize

    if (isCollapsed) return createCollapsedView()
    if (size == WidgetSize.COMPACT) return createCompactWidgetView(isBridgeBusy, bridgeStatusText)

    val widgetBackgroundColor = if (isBridgeBusy) { Color.WHITE } else { Color.parseColor("#171717") }
    val widgetPrimaryTextColor = if (isBridgeBusy) { Color.parseColor("#171717") } else { Color.WHITE }
    val widgetSecondaryTextColor = if (isBridgeBusy) { Color.parseColor("#585858") } else { Color.argb(220, 255, 255, 255) }

    appendDebugLog("WIDGET", "WIDGET_STYLE_STATE busy=$isBridgeBusy background=${if (isBridgeBusy) "light" else "dark"}")

    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(size.panelPaddingHorizontal, size.panelPaddingTop, size.panelPaddingHorizontal, size.panelPaddingBottom)
      background = roundedBackground(widgetBackgroundColor, 18f)
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
      text = "Bridge"; setTextColor(widgetPrimaryTextColor); textSize = size.titleTextSize
      typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL
    }

    val refreshButton = createHeaderButton("↻") {
      refreshDeepSeekBalance(force = true)
    }

    val sizeButton = createHeaderButton(size.label) { cycleWidgetSize() }
    val closeButton = createHeaderButton("×") {
      Toast.makeText(this, "CopyBridge 위젯을 종료합니다.", Toast.LENGTH_SHORT).show(); stopSelf()
    }.apply { textSize = 18f }

    header.addView(collapseButton, LinearLayout.LayoutParams(size.headerButtonSize, size.headerButtonSize).apply { rightMargin = 8 })
    header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(refreshButton, LinearLayout.LayoutParams(size.headerButtonSize, size.headerButtonSize).apply { rightMargin = 8 })
    header.addView(sizeButton, LinearLayout.LayoutParams(size.headerButtonSize, size.headerButtonSize).apply { rightMargin = 8 })
    header.addView(closeButton, LinearLayout.LayoutParams(size.headerButtonSize, size.headerButtonSize))

    val replyCopyLabel = if (replyCopyModeString == "LAST") "답변: 마지막" else "답변: 전체"
    val replyCopyButton = createDarkButton(replyCopyLabel, size.buttonTextSize) {
      replyCopyModeString = if (replyCopyModeString == "LAST") "FULL" else "LAST"
      saveWidgetPreferences(); refreshWidgetAtSamePosition()
    }

    val gptOutputLabel = "GPT: 코드"
    val gptOutputButton = createDarkButton(gptOutputLabel, size.buttonTextSize) {
      gptOutputModeString = "CODE"
      saveWidgetPreferences()
      Toast.makeText(this, "GPT 전체 모드는 비활성화되었습니다. 코드 모드로 전송합니다.", Toast.LENGTH_SHORT).show()
      refreshWidgetAtSamePosition()
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
      runTelegramToGptWithTemporaryWidgetHide("normal")
    }

    val apiBalanceBox = createApiBalanceBox(size.buttonTextSize)
    apiBalanceBoxView = apiBalanceBox
    apiBalanceTextView = apiBalanceBox
    updateApiBalanceBoxText()

    val tgButton = createTelegramBlueButton("텔레그램으로 보내기", size.buttonTextSize) {
      gptOutputModeString = "CODE"
      saveWidgetPreferences()
      appendDebugLog("WIDGET", "tap 텔레그램으로 보내기 gptMode=CODE autoSend=$autoSendEnabled")
      CopyBridgeAccessibilityService.requestGptToTelegram(this, "CODE", autoSendEnabled)
    }

    panel.addView(header, LinearLayout.LayoutParams(size.contentWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
    panel.addView(apiBalanceBox, LinearLayout.LayoutParams(size.contentWidth, size.toggleHeight).apply { bottomMargin = 10 })
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

  private fun createCompactWidgetView(
    isBridgeBusy: Boolean,
    bridgeStatusText: String
  ): View {
    val size = WidgetSize.COMPACT
    val compactBackgroundColor = if (isBridgeBusy) {
      Color.WHITE
    } else {
      Color.parseColor("#171717")
    }

    val panel = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(size.panelPaddingHorizontal, size.panelPaddingTop, size.panelPaddingHorizontal, size.panelPaddingBottom)
      background = roundedBackground(compactBackgroundColor, 12f)
      elevation = 12f
    }

    val header = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
    }

    val collapseButton = createHeaderButton("−") {
      isCollapsed = true
      saveWidgetPreferences()
      refreshWidgetAtSamePosition()
    }

    val sizeButton = createHeaderButton(size.label) {
      cycleWidgetSize()
    }

    header.addView(
      collapseButton,
      LinearLayout.LayoutParams(0, size.headerButtonSize, 1f).apply { rightMargin = 6 }
    )
    header.addView(
      sizeButton,
      LinearLayout.LayoutParams(0, size.headerButtonSize, 1f)
    )

    val apiBalanceBox = createApiBalanceBox(size.buttonTextSize)
    apiBalanceBoxView = apiBalanceBox
    apiBalanceTextView = apiBalanceBox
    updateApiBalanceBoxText()

    val gptButton = createWhiteButton("G", 22f) {
      appendDebugLog("WIDGET", "tap SS G replyMode=$replyCopyModeString autoSend=$autoSendEnabled")
      runTelegramToGptWithTemporaryWidgetHide("ss")
    }

    val tgButton = createTelegramBlueButton("T", 22f) {
      gptOutputModeString = "CODE"
      saveWidgetPreferences()
      appendDebugLog("WIDGET", "tap SS T gptMode=CODE autoSend=$autoSendEnabled")
      CopyBridgeAccessibilityService.requestGptToTelegram(this, "CODE", autoSendEnabled)
    }

    panel.addView(
      header,
      LinearLayout.LayoutParams(size.contentWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 6 }
    )
    panel.addView(
      apiBalanceBox,
      LinearLayout.LayoutParams(size.contentWidth, size.toggleHeight).apply { bottomMargin = 6 }
    )
    panel.addView(
      gptButton,
      LinearLayout.LayoutParams(size.contentWidth, size.buttonHeight).apply { bottomMargin = 6 }
    )
    panel.addView(
      tgButton,
      LinearLayout.LayoutParams(size.contentWidth, size.buttonHeight).apply { bottomMargin = 6 }
    )
    panel.addView(
      createBridgeStatusTextView(bridgeStatusText),
      LinearLayout.LayoutParams(size.contentWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
    )

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


  private fun createApiBalanceBox(
    textSizeValue: Float
  ): TextView {
    return TextView(this).apply {
      text = apiBalanceText
      setTextColor(Color.parseColor("#FACC15"))
      textSize = textSizeValue
      typeface = Typeface.DEFAULT_BOLD
      gravity = Gravity.CENTER
      background = roundedBackground(Color.parseColor("#242424"), 12f)
      setPadding(8, 0, 8, 0)
      minHeight = 0
      minimumHeight = 0
      includeFontPadding = false
    }
  }

  private fun updateApiBalanceBoxText() {
    apiBalanceTextView?.text = apiBalanceText
  }

  private fun startDeepSeekBalanceAutoRefresh() {
    apiBalanceHandler.removeCallbacks(apiBalanceRunnable)
    refreshDeepSeekBalance()
    apiBalanceHandler.postDelayed(apiBalanceRunnable, API_BALANCE_REFRESH_INTERVAL_MS)
  }

  private fun refreshDeepSeekBalance(force: Boolean = false) {
    if (apiBalanceRefreshing && !force) return

    val prefs = getSharedPreferences("copybridge_deepseek_settings", MODE_PRIVATE)
    val apiKey = prefs.getString("deepseek_api_key", "") ?: ""

    if (apiKey.isBlank()) {
      apiBalanceText = "$" + "KEY"
      apiBalanceHistory.clear()
      updateApiBalanceBoxText()
      return
    }

    apiBalanceRefreshing = true
    apiBalanceText = "$" + "..."
    updateApiBalanceBoxText()

    thread {
      var connection: HttpURLConnection? = null

      try {
        val url = URL("https://api.deepseek.com/user/balance")
        connection = (url.openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = 10000
          readTimeout = 10000
          setRequestProperty("Accept", "application/json")
          setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
          connection.inputStream
        } else {
          connection.errorStream
        }

        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (responseCode !in 200..299) {
          apiBalanceHandler.post {
            apiBalanceRefreshing = false
            apiBalanceText = "$" + "ERR"
            updateApiBalanceBoxText()
          }
          return@thread
        }

        val balance = parseDeepSeekUsdBalance(body)
        val displayText = String.format(Locale.US, "%.2f", balance)

        apiBalanceHandler.post {
          apiBalanceRefreshing = false
          val shouldBlink = shouldBlinkApiBalanceWarning(balance)
          apiBalanceText = "$" + displayText
          updateApiBalanceBoxText()
          if (shouldBlink) blinkApiBalanceBox()
        }
      } catch (_: Exception) {
        apiBalanceHandler.post {
          apiBalanceRefreshing = false
          apiBalanceText = "$" + "ERR"
          updateApiBalanceBoxText()
        }
      } finally {
        connection?.disconnect()
      }
    }
  }

  private fun parseDeepSeekUsdBalance(body: String): Double {
    val json = JSONObject(body)
    val balanceInfos = json.optJSONArray("balance_infos") ?: return 0.0

    var fallbackBalance = 0.0

    for (index in 0 until balanceInfos.length()) {
      val item = balanceInfos.optJSONObject(index) ?: continue
      val currency = item.optString("currency", "")
      val totalBalanceString = item.optString("total_balance", "")
      val balance = totalBalanceString.toDoubleOrNull() ?: 0.0

      if (index == 0) fallbackBalance = balance

      if (currency.uppercase(Locale.US) == "USD") {
        return balance
      }
    }

    return fallbackBalance
  }

  private fun shouldBlinkApiBalanceWarning(currentBalance: Double): Boolean {
    val now = System.currentTimeMillis()
    apiBalanceHistory.add(now to currentBalance)
    apiBalanceHistory.removeAll { now - it.first > API_BALANCE_HISTORY_WINDOW_MS }

    val current = apiBalanceHistory.lastOrNull() ?: return false
    val fiveMinutesAgo = apiBalanceHistory.lastOrNull { now - it.first >= API_BALANCE_RECENT_WINDOW_MS } ?: return false
    val tenMinutesAgo = apiBalanceHistory.lastOrNull { now - it.first >= API_BALANCE_HISTORY_WINDOW_MS } ?: return false

    val recentUsage = (fiveMinutesAgo.second - current.second).coerceAtLeast(0.0)
    val previousUsage = (tenMinutesAgo.second - fiveMinutesAgo.second).coerceAtLeast(0.0)

    if (previousUsage <= 0.0) return false
    return recentUsage > previousUsage * API_BALANCE_WARNING_MULTIPLIER
  }

  private fun blinkApiBalanceBox() {
    val box = apiBalanceBoxView ?: return
    val normalBackground = roundedBackground(Color.parseColor("#242424"), 12f)
    val warningBackground = roundedBackground(Color.parseColor("#B91C1C"), 12f)

    var count = 0
    val blinkRunnable = object : Runnable {
      override fun run() {
        if (apiBalanceBoxView == null) return

        box.background = if (count % 2 == 0) warningBackground else normalBackground
        count += 1

        if (count < API_BALANCE_BLINK_COUNT) {
          apiBalanceHandler.postDelayed(this, API_BALANCE_BLINK_INTERVAL_MS)
        } else {
          box.background = normalBackground
        }
      }
    }

    apiBalanceHandler.post(blinkRunnable)
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
    private const val API_BALANCE_REFRESH_INTERVAL_MS = 60_000L
    private const val API_BALANCE_RECENT_WINDOW_MS = 5 * 60_000L
    private const val API_BALANCE_HISTORY_WINDOW_MS = 10 * 60_000L
    private const val API_BALANCE_WARNING_MULTIPLIER = 1.5
    private const val API_BALANCE_BLINK_INTERVAL_MS = 500L
    private const val API_BALANCE_BLINK_COUNT = 6
  }
}

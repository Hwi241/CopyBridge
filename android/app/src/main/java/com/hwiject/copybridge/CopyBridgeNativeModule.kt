package com.hwiject.copybridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.concurrent.thread
import org.json.JSONObject

class CopyBridgeNativeModule(
 private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

 override fun getName(): String {
 return "CopyBridgeNativeModule"
 }

 private fun sanitizeOpacity(value: Double): Float {
 val clamped = value.coerceIn(OPACITY_MIN_VALUE.toDouble(), OPACITY_MAX_VALUE.toDouble())
 val stepIndex = ((clamped - OPACITY_MIN_VALUE) / OPACITY_STEP_VALUE).roundToInt()
 return (OPACITY_MIN_VALUE + stepIndex * OPACITY_STEP_VALUE).toFloat().coerceIn(OPACITY_MIN_VALUE, OPACITY_MAX_VALUE)
 }

 private fun refreshFloatingWidget() {
 val intent = Intent(reactContext, FloatingWidgetService::class.java).apply {
 action = FloatingWidgetService.ACTION_REFRESH_WIDGET
 }
 reactContext.startService(intent)
 }

 @ReactMethod
 fun openOverlaySettings(promise: Promise) {
 try {
 val intent = Intent(
 Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
 Uri.parse("package:${reactContext.packageName}")
 ).apply {
 addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 }

 reactContext.startActivity(intent)
 promise.resolve(true)
 } catch (error: Exception) {
 promise.reject("OPEN_OVERLAY_SETTINGS_FAILED", error)
 }
 }

 @ReactMethod
 fun openAccessibilitySettings(promise: Promise) {
 try {
 val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
 addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 }

 reactContext.startActivity(intent)
 promise.resolve(true)
 } catch (error: Exception) {
 promise.reject("OPEN_ACCESSIBILITY_SETTINGS_FAILED", error)
 }
 }

 @ReactMethod
 fun startFloatingWidget(promise: Promise) {
 try {
 val intent = Intent(reactContext, FloatingWidgetService::class.java)
 reactContext.startService(intent)
 Toast.makeText(reactContext, "CopyBridge 위젯 시작 요청", Toast.LENGTH_SHORT).show()
 promise.resolve(true)
 } catch (error: Exception) {
 promise.reject("START_FLOATING_WIDGET_FAILED", error)
 }
 }

 @ReactMethod
 fun stopFloatingWidget(promise: Promise) {
 try {
 val intent = Intent(reactContext, FloatingWidgetService::class.java)
 reactContext.stopService(intent)
 Toast.makeText(reactContext, "CopyBridge 위젯을 종료했습니다.", Toast.LENGTH_SHORT).show()
 promise.resolve(true)
 } catch (error: Exception) {
 promise.reject("STOP_FLOATING_WIDGET_FAILED", error)
 }
 }

 @ReactMethod
 fun restoreFloatingWidget(promise: Promise) {
 try {
 val intent = Intent(reactContext, FloatingWidgetService::class.java).apply {
 action = FloatingWidgetService.ACTION_RESTORE_WIDGET
 }
 reactContext.startService(intent)
 Toast.makeText(reactContext, "CopyBridge 위젯 복원 요청", Toast.LENGTH_SHORT).show()
 promise.resolve(true)
 } catch (error: Exception) {
 promise.reject("RESTORE_FLOATING_WIDGET_FAILED", error)
 }
 }

  @ReactMethod
  fun saveDeepSeekApiKey(apiKey: String, promise: Promise) {
    try {
      val trimmedKey = apiKey.trim()
      if (trimmedKey.isBlank()) {
        promise.reject("DEEPSEEK_API_KEY_EMPTY", "DeepSeek API Key is empty")
        return
      }
      val prefs = reactContext.getSharedPreferences("copybridge_deepseek_settings", Context.MODE_PRIVATE)
      prefs.edit().putString("deepseek_api_key", trimmedKey).apply()
      Toast.makeText(reactContext, "DeepSeek API Key를 저장했습니다.", Toast.LENGTH_SHORT).show()
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("SAVE_DEEPSEEK_API_KEY_FAILED", error)
    }
  }

  @ReactMethod
  fun getDeepSeekApiKeyStatus(promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences("copybridge_deepseek_settings", Context.MODE_PRIVATE)
      val apiKey = prefs.getString("deepseek_api_key", "") ?: ""
      val map = Arguments.createMap()

      if (apiKey.isBlank()) {
        map.putBoolean("hasKey", false)
        map.putString("maskedKey", "$" + "KEY")
      } else {
        map.putBoolean("hasKey", true)
        map.putString("maskedKey", maskDeepSeekApiKey(apiKey))
      }

      promise.resolve(map)
    } catch (error: Exception) {
      promise.reject("GET_DEEPSEEK_API_KEY_STATUS_FAILED", error)
    }
  }

  @ReactMethod
  fun fetchDeepSeekBalance(promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences("copybridge_deepseek_settings", Context.MODE_PRIVATE)
      val apiKey = prefs.getString("deepseek_api_key", "") ?: ""

      if (apiKey.isBlank()) {
        promise.reject("DEEPSEEK_API_KEY_MISSING", "DeepSeek API Key is missing")
        return
      }

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
            promise.reject("DEEPSEEK_BALANCE_HTTP_ERROR", "HTTP $responseCode")
            return@thread
          }

          val json = JSONObject(body)
          val balanceInfos = json.optJSONArray("balance_infos")

          if (balanceInfos == null || balanceInfos.length() == 0) {
            promise.reject("DEEPSEEK_BALANCE_EMPTY", "balance_infos is empty")
            return@thread
          }

          var selectedBalance = 0.0
          var selectedCurrency = "USD"

          for (index in 0 until balanceInfos.length()) {
            val item = balanceInfos.optJSONObject(index) ?: continue
            val currency = item.optString("currency", "")
            val totalBalanceString = item.optString("total_balance", "")

            if (currency.uppercase(Locale.US) == "USD") {
              selectedCurrency = "USD"
              selectedBalance = totalBalanceString.toDoubleOrNull() ?: 0.0
              break
            }

            if (index == 0) {
              selectedCurrency = currency.ifBlank { "USD" }
              selectedBalance = totalBalanceString.toDoubleOrNull() ?: 0.0
            }
          }

          val map = Arguments.createMap()
          map.putBoolean("ok", true)
          map.putDouble("balance", selectedBalance)
          map.putString("currency", selectedCurrency)
          promise.resolve(map)
        } catch (error: Exception) {
          promise.reject("FETCH_DEEPSEEK_BALANCE_FAILED", error)
        } finally {
          connection?.disconnect()
        }
      }
    } catch (error: Exception) {
      promise.reject("FETCH_DEEPSEEK_BALANCE_FAILED", error)
    }
  }

  private fun maskDeepSeekApiKey(apiKey: String): String {
    val trimmed = apiKey.trim()
    if (trimmed.length <= 8) return "저장됨"
    return trimmed.take(4) + "..." + trimmed.takeLast(4)
  }

  @ReactMethod
  fun getDebugLogs(promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences("copybridge_debug_logs", Context.MODE_PRIVATE)
      promise.resolve(prefs.getString("logs", "") ?: "")
    } catch (error: Exception) {
      promise.reject("GET_DEBUG_LOGS_FAILED", error)
    }
  }

  @ReactMethod
  fun clearDebugLogs(promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences("copybridge_debug_logs", Context.MODE_PRIVATE)
      prefs.edit().remove("logs").apply()
      Toast.makeText(reactContext, "CopyBridge 로그를 비웠습니다.", Toast.LENGTH_SHORT).show()
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("CLEAR_DEBUG_LOGS_FAILED", error)
    }
  }

  @ReactMethod
  fun copyDebugLogs(promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences("copybridge_debug_logs", Context.MODE_PRIVATE)
      val logs = prefs.getString("logs", "") ?: ""
      val clipboard = reactContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
      clipboard.setPrimaryClip(ClipData.newPlainText("CopyBridge Debug Logs", logs))
      Toast.makeText(reactContext, "CopyBridge 로그를 복사했습니다.", Toast.LENGTH_SHORT).show()
      promise.resolve(true)
    } catch (error: Exception) {
      promise.reject("COPY_DEBUG_LOGS_FAILED", error)
    }
  }


  @ReactMethod
  fun getOpacitySettings(promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences("copybridge_floating_widget", Context.MODE_PRIVATE)
      val widgetOpacity = sanitizeOpacity(prefs.getFloat("widget_opacity", OPACITY_DEFAULT_WIDGET).toDouble())
      val collapsedOpacity = sanitizeOpacity(prefs.getFloat("collapsed_opacity", OPACITY_DEFAULT_COLLAPSED).toDouble())
      val map = Arguments.createMap()
      map.putDouble("widgetOpacity", widgetOpacity.toDouble())
      map.putDouble("collapsedOpacity", collapsedOpacity.toDouble())
      promise.resolve(map)
    } catch (error: Exception) {
      promise.reject("GET_OPACITY_FAILED", error)
    }
  }

  @ReactMethod
  fun setWidgetOpacity(value: Double, promise: Promise) {
    try {
      val safeOpacity = sanitizeOpacity(value)
      val prefs = reactContext.getSharedPreferences("copybridge_floating_widget", Context.MODE_PRIVATE)
      prefs.edit().putFloat("widget_opacity", safeOpacity).apply()
      refreshFloatingWidget()
      promise.resolve(safeOpacity.toDouble())
    } catch (error: Exception) {
      promise.reject("SET_OPACITY_FAILED", error)
    }
  }

  @ReactMethod
  fun setCollapsedOpacity(value: Double, promise: Promise) {
    try {
      val safeOpacity = sanitizeOpacity(value)
      val prefs = reactContext.getSharedPreferences("copybridge_floating_widget", Context.MODE_PRIVATE)
      prefs.edit().putFloat("collapsed_opacity", safeOpacity).apply()
      refreshFloatingWidget()
      promise.resolve(safeOpacity.toDouble())
    } catch (error: Exception) {
      promise.reject("SET_COLLAPSED_OPACITY_FAILED", error)
    }
  }

  @ReactMethod
  fun getApiUsageMinutes(promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences(
        "copybridge_api_usage_minutes",
        Context.MODE_PRIVATE
      )
      val records = prefs.getString("minute_usage_records", "[]") ?: "[]"
      promise.resolve(records)
    } catch (error: Exception) {
      promise.reject("API_USAGE_READ_FAILED", "Failed to read API usage minutes", error)
    }
  }

    companion object {
        private const val OPACITY_MIN_VALUE = 0.10f
        private const val OPACITY_MAX_VALUE = 1.0f
        private const val OPACITY_STEP_VALUE = 0.05
        private const val OPACITY_DEFAULT_WIDGET = 1.0f
        private const val OPACITY_DEFAULT_COLLAPSED = 0.85f
    }
}

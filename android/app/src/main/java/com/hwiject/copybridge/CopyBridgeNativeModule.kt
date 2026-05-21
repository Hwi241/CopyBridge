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

class CopyBridgeNativeModule(
 private val reactContext: ReactApplicationContext
) : ReactContextBaseJavaModule(reactContext) {

 override fun getName(): String {
 return "CopyBridgeNativeModule"
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
      val widgetOpacity = prefs.getFloat("widget_opacity", 1f)
      val collapsedOpacity = prefs.getFloat("collapsed_opacity", 0.85f)
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
      val prefs = reactContext.getSharedPreferences("copybridge_floating_widget", Context.MODE_PRIVATE)
      prefs.edit().putFloat("widget_opacity", value.toFloat()).apply()
      val intent = Intent(reactContext, FloatingWidgetService::class.java).apply {
        action = FloatingWidgetService.ACTION_REFRESH_WIDGET
      }
      reactContext.startService(intent)
      promise.resolve(value)
    } catch (error: Exception) {
      promise.reject("SET_OPACITY_FAILED", error)
    }
  }

  @ReactMethod
  fun setCollapsedOpacity(value: Double, promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences("copybridge_floating_widget", Context.MODE_PRIVATE)
      prefs.edit().putFloat("collapsed_opacity", value.toFloat()).apply()
      val intent = Intent(reactContext, FloatingWidgetService::class.java).apply {
        action = FloatingWidgetService.ACTION_REFRESH_WIDGET
      }
      reactContext.startService(intent)
      promise.resolve(value)
    } catch (error: Exception) {
      promise.reject("SET_COLLAPSED_OPACITY_FAILED", error)
    }
  }
}

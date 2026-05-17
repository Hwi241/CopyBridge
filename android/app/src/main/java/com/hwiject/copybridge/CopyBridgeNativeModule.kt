package com.hwiject.copybridge

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.facebook.react.bridge.Promise
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
}

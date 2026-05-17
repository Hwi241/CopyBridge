package com.hwiject.copybridge

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class CopyBridgeAccessibilityService : AccessibilityService() {
  private var lastPackageName: String? = null

  override fun onServiceConnected() {
    super.onServiceConnected()
    activeService = this
    Toast.makeText(this, "CopyBridge 접근성 서비스가 연결되었습니다.", Toast.LENGTH_SHORT).show()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val packageName = event?.packageName?.toString()
    if (!packageName.isNullOrBlank()) {
      lastPackageName = packageName
    }
  }

  override fun onInterrupt() {
    Log.d(TAG, "CopyBridge accessibility service interrupted")
  }

  override fun onDestroy() {
    if (activeService === this) {
      activeService = null
    }
    super.onDestroy()
  }

  private fun handleCopyTelegramToAiRequest() {
    val root = rootInActiveWindow
    val packageName = lastPackageName ?: root?.packageName?.toString() ?: "unknown"

    if (root == null) {
      Toast.makeText(this, "현재 화면 내용을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
      return
    }

    Toast.makeText(
      this,
      "TG → AI 복사 준비됨: $packageName",
      Toast.LENGTH_SHORT
    ).show()
  }

  private fun handlePasteAiToTelegramRequest() {
    val root = rootInActiveWindow
    val packageName = lastPackageName ?: root?.packageName?.toString() ?: "unknown"

    if (root == null) {
      Toast.makeText(this, "현재 입력창을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
      return
    }

    Toast.makeText(
      this,
      "AI → TG 붙여넣기 준비됨: $packageName",
      Toast.LENGTH_SHORT
    ).show()
  }

  companion object {
    private const val TAG = "CopyBridgeA11y"
    private var activeService: CopyBridgeAccessibilityService? = null

    fun isServiceActive(): Boolean {
      return activeService != null
    }

    fun requestCopyTelegramToAi(context: Context): Boolean {
      val service = activeService

      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

      service.handleCopyTelegramToAiRequest()
      return true
    }

    fun requestPasteAiToTelegram(context: Context): Boolean {
      val service = activeService

      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

      service.handlePasteAiToTelegramRequest()
      return true
    }
  }
}

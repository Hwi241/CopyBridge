package com.hwiject.copybridge

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    if (root == null) {
      Toast.makeText(this, "현재 화면 내용을 읽을 수 없습니다.", Toast.LENGTH_SHORT).show()
      return
    }

    val rawTexts = mutableListOf<String>()
    collectVisibleTexts(root, rawTexts)

    val cleanedTexts = cleanTextLines(rawTexts)
    if (cleanedTexts.isEmpty()) {
      Toast.makeText(this, "복사할 텍스트를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
      return
    }

    val result = buildCopyText(cleanedTexts)
    copyToClipboard(result)

    Toast.makeText(
      this,
      "TG → AI 복사 완료: ${cleanedTexts.size}줄",
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

  private fun collectVisibleTexts(node: AccessibilityNodeInfo?, output: MutableList<String>) {
    if (node == null) return

    val text = node.text?.toString()?.trim()
    if (!text.isNullOrBlank()) {
      output.add(text)
    }

    for (index in 0 until node.childCount) {
      collectVisibleTexts(node.getChild(index), output)
    }
  }

  private fun cleanTextLines(rawTexts: List<String>): List<String> {
    val result = mutableListOf<String>()
    var previous = ""

    rawTexts.forEach { raw ->
      val cleaned = raw
        .replace(Regex("\\s+"), " ")
        .trim()

      if (cleaned.isBlank()) return@forEach
      if (cleaned == previous) return@forEach
      if (shouldIgnoreText(cleaned)) return@forEach

      result.add(cleaned)
      previous = cleaned
    }

    return result.take(MAX_COPY_LINES)
  }

  private fun shouldIgnoreText(text: String): Boolean {
    val lower = text.lowercase()

    if (lower == "telegram") return true
    if (lower == "copybridge") return true
    if (lower == "bridge") return true
    if (text.length > MAX_SINGLE_LINE_LENGTH) return true

    return false
  }

  private fun buildCopyText(lines: List<String>): String {
    val body = lines.joinToString("\n")
    val limitedBody = if (body.length > MAX_COPY_CHARS) {
      body.take(MAX_COPY_CHARS) + "\n...(길이 제한으로 일부만 복사됨)"
    } else {
      body
    }

    return "[Telegram 화면에서 복사한 텍스트]\n\n$limitedBody"
  }

  private fun copyToClipboard(text: String) {
    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("CopyBridge Telegram Text", text)
    clipboard.setPrimaryClip(clip)
  }

  companion object {
    private const val TAG = "CopyBridgeA11y"
    private const val MAX_COPY_LINES = 80
    private const val MAX_COPY_CHARS = 8000
    private const val MAX_SINGLE_LINE_LENGTH = 600

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

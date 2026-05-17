package com.hwiject.copybridge

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
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

  private fun handleCopyTelegramToAiRequest(copyMode: String) {
    val telegramRoots = getTelegramRoots()

    if (telegramRoots.isEmpty()) {
      Toast.makeText(this, "Telegram 채팅방을 화면에 열어주세요.", Toast.LENGTH_SHORT).show()
      return
    }

    val rawTexts = mutableListOf<String>()
    telegramRoots.forEach { root ->
      collectVisibleTexts(root, rawTexts)
    }

    val cleanedTexts = cleanTextLines(rawTexts)
    if (cleanedTexts.isEmpty()) {
      Toast.makeText(this, "Telegram에서 복사할 텍스트를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
      return
    }

    val selectedTexts = if (copyMode == COPY_MODE_LAST) {
      cleanedTexts.takeLast(1)
    } else {
      cleanedTexts
    }

    val result = buildCopyText(selectedTexts)
    copyToClipboard(result)

    val modeLabel = if (copyMode == COPY_MODE_LAST) "마지막" else "전체"
    Toast.makeText(
      this,
      "TG → AI 복사 완료($modeLabel): ${selectedTexts.size}줄",
      Toast.LENGTH_SHORT
    ).show()
  }

  private fun handlePasteAiToTelegramRequest(autoSend: Boolean) {
    val telegramRoots = getTelegramRoots()

    if (telegramRoots.isEmpty()) {
      Toast.makeText(this, "Telegram 채팅방을 화면에 열어주세요.", Toast.LENGTH_SHORT).show()
      return
    }

    val editableNode = findEditableNodeFromRoots(telegramRoots)
    if (editableNode == null) {
      Toast.makeText(this, "Telegram 입력창을 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
      return
    }

    val clipboardText = readClipboardText()

    if (clipboardText.isNotBlank()) {
      val setTextSuccess = setTextToNode(editableNode, clipboardText)
      if (setTextSuccess) {
        handlePasteSuccess("SET_TEXT", autoSend, telegramRoots)
        return
      }
    }

    val pasteSuccess = pasteClipboardToNode(editableNode)
    if (pasteSuccess) {
      val mode = if (clipboardText.isBlank()) "ACTION_PASTE" else "ACTION_PASTE fallback"
      handlePasteSuccess(mode, autoSend, telegramRoots)
      return
    }

    if (clipboardText.isBlank()) {
      Toast.makeText(this, "클립보드 텍스트를 읽지 못했고, 시스템 붙여넣기도 실패했습니다.", Toast.LENGTH_SHORT).show()
    } else {
      Toast.makeText(this, "Telegram 입력창에 텍스트를 넣지 못했습니다.", Toast.LENGTH_SHORT).show()
    }
  }

  private fun handlePasteSuccess(mode: String, autoSend: Boolean, telegramRoots: List<AccessibilityNodeInfo>) {
    if (!autoSend) {
      Toast.makeText(this, "AI → TG 붙여넣기 완료: $mode", Toast.LENGTH_SHORT).show()
      return
    }

    val sent = clickSendButton(telegramRoots)
    if (sent) {
      Toast.makeText(this, "AI → TG 붙여넣기 완료: $mode + 전송", Toast.LENGTH_SHORT).show()
    } else {
      Toast.makeText(this, "붙여넣기는 완료, Telegram 전송 버튼은 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
    }
  }

  private fun getTelegramRoots(): List<AccessibilityNodeInfo> {
    val candidates = mutableListOf<AccessibilityNodeInfo>()

    rootInActiveWindow?.let { root ->
      if (isTelegramPackage(root.packageName?.toString().orEmpty())) {
        candidates.add(root)
      }
    }

    windows.forEach { window ->
      val root = window.root
      val packageName = root?.packageName?.toString().orEmpty()
      if (root != null && isTelegramPackage(packageName) && candidates.none { it === root }) {
        candidates.add(root)
      }
    }

    return candidates
  }

  private fun isTelegramPackage(packageName: String): Boolean {
    val lower = packageName.lowercase()
    return lower.contains("telegram")
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
    if (lower == "복사: 전체") return true
    if (lower == "복사: 마지막") return true
    if (lower == "전송: 켬") return true
    if (lower == "전송: 끔") return true
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

  private fun readClipboardText(): String {
    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return ""
    if (clip.itemCount <= 0) return ""

    return clip.getItemAt(0)
      ?.coerceToText(this)
      ?.toString()
      ?.trim()
      ?: ""
  }

  private fun findEditableNodeFromRoots(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
    roots.forEach { root ->
      val found = findEditableNode(root)
      if (found != null) return found
    }

    return null
  }

  private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (node == null) return null

    val candidates = mutableListOf<AccessibilityNodeInfo>()
    collectEditableNodes(node, candidates)

    return candidates.firstOrNull { it.isFocused }
      ?: candidates.firstOrNull { it.isAccessibilityFocused }
      ?: candidates.lastOrNull()
  }

  private fun collectEditableNodes(
    node: AccessibilityNodeInfo?,
    output: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return

    if (isEditableNode(node)) {
      output.add(node)
    }

    for (index in 0 until node.childCount) {
      collectEditableNodes(node.getChild(index), output)
    }
  }

  private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
    if (!node.isEnabled) return false

    val className = node.className?.toString().orEmpty()
    val hasSetTextAction = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    val hasPasteAction = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }

    if (node.isEditable) return true
    if (className.contains("EditText", ignoreCase = true)) return true
    if (hasSetTextAction) return true
    if (hasPasteAction) return true

    return false
  }

  private fun setTextToNode(node: AccessibilityNodeInfo, text: String): Boolean {
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    val arguments = Bundle().apply {
      putCharSequence(
        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
        text
      )
    }

    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
  }

  private fun pasteClipboardToNode(node: AccessibilityNodeInfo): Boolean {
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
  }

  private fun clickSendButton(roots: List<AccessibilityNodeInfo>): Boolean {
    roots.forEach { root ->
      val sendButton = findSendButton(root)
      if (sendButton != null) {
        return sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      }
    }

    return false
  }

  private fun findSendButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (node == null) return null

    if (isSendButton(node)) {
      return node
    }

    for (index in 0 until node.childCount) {
      val found = findSendButton(node.getChild(index))
      if (found != null) return found
    }

    return null
  }

  private fun isSendButton(node: AccessibilityNodeInfo): Boolean {
    if (!node.isEnabled) return false

    val textValue = node.text?.toString().orEmpty()
    val descriptionValue = node.contentDescription?.toString().orEmpty()
    val viewIdValue = node.viewIdResourceName.orEmpty()

    val combined = "$textValue $descriptionValue $viewIdValue".lowercase()

    val looksLikeSend =
      combined.contains("send") ||
      combined.contains("전송") ||
      combined.contains("보내기")

    if (!looksLikeSend) return false

    return node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
  }

  companion object {
    private const val TAG = "CopyBridgeA11y"
    private const val MAX_COPY_LINES = 80
    private const val MAX_COPY_CHARS = 8000
    private const val MAX_SINGLE_LINE_LENGTH = 600
    private const val COPY_MODE_ALL = "ALL"
    private const val COPY_MODE_LAST = "LAST"

    private var activeService: CopyBridgeAccessibilityService? = null

    fun isServiceActive(): Boolean {
      return activeService != null
    }

    fun requestCopyTelegramToAi(context: Context): Boolean {
      return requestCopyTelegramToAi(context, COPY_MODE_ALL)
    }

    fun requestCopyTelegramToAi(context: Context, copyMode: String): Boolean {
      val service = activeService

      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

      service.handleCopyTelegramToAiRequest(copyMode)
      return true
    }

    fun requestPasteAiToTelegram(context: Context): Boolean {
      return requestPasteAiToTelegram(context, false)
    }

    fun requestPasteAiToTelegram(context: Context, autoSend: Boolean): Boolean {
      val service = activeService

      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

      service.handlePasteAiToTelegramRequest(autoSend)
      return true
    }
  }
}

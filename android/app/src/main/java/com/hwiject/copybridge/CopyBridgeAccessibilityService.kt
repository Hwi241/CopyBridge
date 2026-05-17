package com.hwiject.copybridge

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
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

  private fun handleCopyTelegramToAiRequest() {
    val telegramRoots = getTelegramRoots()

    if (telegramRoots.isEmpty()) {
      Toast.makeText(this, "Telegram 채팅방을 화면에 열어주세요.", Toast.LENGTH_SHORT).show()
      return
    }

    val candidates = collectTelegramMessageCandidates(telegramRoots)

    if (candidates.isEmpty()) {
      val debugText = buildTelegramCopyDebugInfo(telegramRoots)
      copyToClipboard(debugText)
      Toast.makeText(
        this,
        "Telegram 메시지를 찾지 못했습니다. 진단 정보가 복사되었습니다.",
        Toast.LENGTH_SHORT
      ).show()
      return
    }

    val selectedTexts = candidates.map { it.text }
    val result = buildCopyText(selectedTexts)
    copyToClipboard(result)

    Toast.makeText(
      this,
      "텔레그램 답변복사 완료: ${selectedTexts.size}개",
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

  private fun handlePasteSuccess(
    mode: String,
    autoSend: Boolean,
    telegramRoots: List<AccessibilityNodeInfo>
  ) {
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
      val packageName = root.packageName?.toString().orEmpty()
      if (isTelegramPackage(packageName)) {
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
    return packageName.lowercase().contains("telegram")
  }

  private data class TextCandidate(
    val text: String,
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int
  )

  private fun collectTelegramMessageCandidates(
    roots: List<AccessibilityNodeInfo>
  ): List<TextCandidate> {
    val rawCandidates = mutableListOf<TextCandidate>()

    roots.forEach { root ->
      val rootRect = Rect()
      root.getBoundsInScreen(rootRect)
      if (!rootRect.isEmpty()) {
        collectVisibleMessageTextCandidates(root, rootRect, rawCandidates)
      }
    }

    val sorted = rawCandidates
      .mapNotNull { candidate ->
        val cleaned = normalizeCandidateText(candidate.text)
        if (cleaned.isBlank()) return@mapNotNull null
        if (shouldIgnoreText(cleaned)) return@mapNotNull null
        candidate.copy(text = cleaned)
      }
      .sortedWith(compareBy<TextCandidate> { it.top }.thenBy { it.left })

    val result = mutableListOf<TextCandidate>()
    var previousText = ""

    sorted.forEach { candidate ->
      if (candidate.text == previousText) return@forEach
      result.add(candidate)
      previousText = candidate.text
    }

    return result.takeLast(MAX_COPY_LINES)
  }

  private fun collectVisibleMessageTextCandidates(
    node: AccessibilityNodeInfo?,
    rootRect: Rect,
    output: MutableList<TextCandidate>
  ) {
    if (node == null) return

    val rect = Rect()
    node.getBoundsInScreen(rect)

    if (!rect.isEmpty() && isInsideMessageArea(rect, rootRect)) {
      val textValue = node.text?.toString()?.trim()
      if (!textValue.isNullOrBlank()) {
        output.add(
          TextCandidate(
            text = textValue,
            top = rect.top,
            bottom = rect.bottom,
            left = rect.left,
            right = rect.right
          )
        )
      }
    }

    for (index in 0 until node.childCount) {
      collectVisibleMessageTextCandidates(node.getChild(index), rootRect, output)
    }
  }

  private fun isInsideMessageArea(rect: Rect, rootRect: Rect): Boolean {
    val rootHeight = rootRect.height()
    if (rootHeight <= 0) return false
    val centerY = (rect.top + rect.bottom) / 2
    val topLimit = rootRect.top + (rootHeight * 0.18f).toInt()
    val bottomLimit = rootRect.bottom - (rootHeight * 0.22f).toInt()
    return centerY in topLimit..bottomLimit
  }

  private fun normalizeCandidateText(raw: String): String {
    return raw
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  private fun shouldIgnoreText(text: String): Boolean {
    val lower = text.lowercase()

    val exactIgnores = setOf(
      "telegram", "copybridge", "bridge",
      "답장", "복사", "전달", "고정", "수정", "삭제", "검색",
      "메시지", "프로필", "프로필 사진 설정", "icon 프로필 사진 설정",
      "온라인", "알림", "뒤로", "통화", "비디오", "plain text",
      "복사: 전체", "복사: 마지막", "전송: 켬", "전송: 끔",
      "텔레그램 답변복사", "텔레그램으로 전송",
      "tg → ai 복사", "ai → tg 붙여넣기"
    )
    if (lower in exactIgnores) return true

    val partialIgnores = listOf(
      "저장한 메시지", "자세히 보기", "태그로 더 빠르게",
      "메시지를 입력", "메시지 입력", "chatgpt에 답장",
      "프로필 사진", "마지막으로", "입력",
      "검색", "첨부", "이모티콘", "키보드",
      "보내기", "전송", "더보기", "더 보기",
      "menu", "reply", "copy", "forward", "pin", "edit", "delete",
      "saved messages", "profile photo", "last seen", "type a message"
    )
    if (partialIgnores.any { lower.contains(it) }) return true

    if (text.length <= 1) return true

    return false
  }

  private fun buildTelegramCopyDebugInfo(
    roots: List<AccessibilityNodeInfo>
  ): String {
    val builder = StringBuilder()

    builder.appendLine("[CopyBridge Telegram 복사 진단]")
    builder.appendLine("reason=Telegram 메시지 후보 0개")
    builder.appendLine("telegramRoots=${roots.size}")

    roots.forEachIndexed { rootIndex, root ->
      val rootRect = Rect()
      root.getBoundsInScreen(rootRect)
      val rootHeight = rootRect.height()
      val topLimit = rootRect.top + (rootHeight * 0.18f).toInt()
      val bottomLimit = rootRect.bottom - (rootHeight * 0.22f).toInt()

      builder.appendLine("")
      builder.appendLine("[root $rootIndex]")
      builder.appendLine("package=${root.packageName}")
      builder.appendLine("class=${root.className}")
      builder.appendLine("rootBounds=${formatRect(rootRect)}")
      builder.appendLine("messageAreaY=$topLimit..$bottomLimit")

      val debugLines = mutableListOf<String>()
      collectDebugTextLines(root, rootRect, debugLines)

      if (debugLines.isEmpty()) {
        builder.appendLine("rawTextCandidates=0")
      } else {
        builder.appendLine("rawTextCandidates=${debugLines.size}")
        debugLines.take(MAX_DEBUG_LINES).forEach { line -> builder.appendLine(line) }
        if (debugLines.size > MAX_DEBUG_LINES) {
          builder.appendLine("...(debug lines limited: ${debugLines.size} -> $MAX_DEBUG_LINES)")
        }
      }
    }

    val result = builder.toString()
    return if (result.length > MAX_DEBUG_CHARS) {
      result.take(MAX_DEBUG_CHARS) + "\n...(debug chars limited)"
    } else {
      result
    }
  }

  private fun collectDebugTextLines(
    node: AccessibilityNodeInfo?,
    rootRect: Rect,
    output: MutableList<String>
  ) {
    if (node == null) return
    if (output.size >= MAX_DEBUG_LINES) return

    val rect = Rect()
    node.getBoundsInScreen(rect)
    val textValue = node.text?.toString()?.trim().orEmpty()
    val descriptionValue = node.contentDescription?.toString()?.trim().orEmpty()

    if (!rect.isEmpty() && (textValue.isNotBlank() || descriptionValue.isNotBlank())) {
      val normalizedText = normalizeCandidateText(textValue)
      val ignored = if (normalizedText.isBlank()) false else shouldIgnoreText(normalizedText)

      output.add(
        "#${output.size} " +
        "bounds=${formatRect(rect)} " +
        "insideMessageArea=${isInsideMessageArea(rect, rootRect)} " +
        "ignoredText=$ignored " +
        "class=${node.className} " +
        "viewId=${node.viewIdResourceName} " +
        "clickable=${node.isClickable} " +
        "enabled=${node.isEnabled} " +
        "text=\"${escapeDebugValue(textValue)}\" " +
        "desc=\"${escapeDebugValue(descriptionValue)}\""
      )
    }

    for (index in 0 until node.childCount) {
      collectDebugTextLines(node.getChild(index), rootRect, output)
    }
  }

  private fun formatRect(rect: Rect): String {
    return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
  }

  private fun escapeDebugValue(value: String): String {
    return value.replace("\n", " ").replace("\r", " ").take(120)
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
    return clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim() ?: ""
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

  private fun collectEditableNodes(node: AccessibilityNodeInfo?, output: MutableList<AccessibilityNodeInfo>) {
    if (node == null) return
    if (isEditableNode(node)) output.add(node)
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
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
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
    if (isSendButton(node)) return node
    for (index in 0 until node.childCount) {
      val found = findSendButton(node.getChild(index))
      if (found != null) return found
    }
    return null
  }

  private fun isSendButton(node: AccessibilityNodeInfo): Boolean {
    if (!node.isEnabled) return false
    val textValue = node.text?.toString()?.trim().orEmpty()
    val descriptionValue = node.contentDescription?.toString()?.trim().orEmpty()
    val viewIdValue = node.viewIdResourceName.orEmpty()
    val lowerText = textValue.lowercase()
    val lowerDescription = descriptionValue.lowercase()
    val lowerViewId = viewIdValue.lowercase()
    val textLooksLikeSend = lowerText == "send" || lowerText == "전송" || lowerText == "보내기"
    val descriptionLooksLikeSend = lowerDescription == "send" ||
      lowerDescription.contains("send message") || lowerDescription.contains("전송") || lowerDescription.contains("보내기")
    val viewIdLooksLikeSend = lowerViewId.contains("send") ||
      lowerViewId.contains("chat_message_send") || lowerViewId.contains("button_send")
    val looksLikeSend = textLooksLikeSend || descriptionLooksLikeSend || viewIdLooksLikeSend
    if (!looksLikeSend) return false
    return node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
  }

  companion object {
    private const val TAG = "CopyBridgeA11y"
    private const val MAX_COPY_LINES = 80
    private const val MAX_COPY_CHARS = 8000
    private const val MAX_SINGLE_LINE_LENGTH = 600
    private const val MAX_DEBUG_LINES = 60
    private const val MAX_DEBUG_CHARS = 6000
    private var activeService: CopyBridgeAccessibilityService? = null

    fun isServiceActive(): Boolean = activeService != null

    fun requestCopyTelegramToAi(context: Context): Boolean {
      val service = activeService
      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }
      service.handleCopyTelegramToAiRequest()
      return true
    }

    fun requestPasteAiToTelegram(context: Context): Boolean =
      requestPasteAiToTelegram(context, false)

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

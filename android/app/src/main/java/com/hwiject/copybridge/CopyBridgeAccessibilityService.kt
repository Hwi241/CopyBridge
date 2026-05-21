package com.hwiject.copybridge

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
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

  private fun appendDebugLog(category: String, message: String) {
    val prefs = getSharedPreferences(DEBUG_LOG_PREFS_NAME, MODE_PRIVATE)
    val oldLogs = prefs.getString(DEBUG_LOG_KEY, "").orEmpty()
    val entries = oldLogs
      .split("\n---\n")
      .filter { it.isNotBlank() }
      .toMutableList()

    val time = android.text.format.DateFormat
      .format("HH:mm:ss", System.currentTimeMillis())
      .toString()

    entries.add("[$time][$category] $message")

    val trimmed = entries.takeLast(MAX_DEBUG_LOG_ENTRIES).joinToString("\n---\n")
    prefs.edit().putString(DEBUG_LOG_KEY, trimmed).apply()
  }

  private fun compactLogPreview(text: String): String {
    return text
      .replace("\n", " ")
      .replace(Regex("\\s+"), " ")
      .trim()
      .take(1200)
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

    finishTelegramCopyWithCandidates(candidates)
  }

  private fun handleCopyTelegramToAiRequest(copyMode: String) {
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

    val selectedTexts = if (copyMode == COPY_MODE_LAST) {
      candidates.takeLast(1).map { it.text }
    } else {
      candidates.map { it.text }
    }
    val result = buildCopyText(selectedTexts)
    copyToClipboard(result)
    val modeLabel = if (copyMode == COPY_MODE_LAST) "마지막" else "전체"
    Toast.makeText(
      this,
      "텔레그램 답변복사 완료($modeLabel): ${selectedTexts.size}개",
      Toast.LENGTH_SHORT
    ).show()
  }

  private fun finishTelegramCopyWithCandidates(candidates: List<TextCandidate>) {
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
        handlePasteSuccess("SET_TEXT", autoSend, editableNode)
        return
      }
    }

    val pasteSuccess = pasteClipboardToNode(editableNode)
    if (pasteSuccess) {
      val mode = if (clipboardText.isBlank()) "ACTION_PASTE" else "ACTION_PASTE fallback"
      handlePasteSuccess(mode, autoSend, editableNode)
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
    editableNode: AccessibilityNodeInfo
  ) {
    if (!autoSend) {
      Toast.makeText(this, "AI → TG 붙여넣기 완료: $mode", Toast.LENGTH_SHORT).show()
      return
    }

    scheduleAutoSendAfterPaste(mode, editableNode)
  }

  private fun scheduleAutoSendAfterPaste(
    mode: String,
    editableNode: AccessibilityNodeInfo
  ) {
    Handler(Looper.getMainLooper()).postDelayed({
      val firstRoots = getTelegramRoots()
      val firstSent = clickSendButton(firstRoots)

      if (firstSent) {
        Toast.makeText(this, "AI → TG 붙여넣기 완료: $mode + 전송", Toast.LENGTH_SHORT).show()
        return@postDelayed
      }

      val firstEdit = findEditableNodeFromRoots(firstRoots) ?: editableNode
      firstEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      firstEdit.performAction(AccessibilityNodeInfo.ACTION_CLICK)

      Handler(Looper.getMainLooper()).postDelayed({
        val secondRoots = getTelegramRoots()
        val secondSent = clickSendButton(secondRoots)

        if (secondSent) {
          Toast.makeText(this, "AI → TG 붙여넣기 완료: $mode + 전송", Toast.LENGTH_SHORT).show()
          return@postDelayed
        }

        val secondEdit = findEditableNodeFromRoots(secondRoots) ?: firstEdit
        secondEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        secondEdit.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        Handler(Looper.getMainLooper()).postDelayed({
          val thirdRoots = getTelegramRoots()
          val thirdSent = clickSendButton(thirdRoots)

          if (thirdSent) {
            Toast.makeText(this, "AI → TG 붙여넣기 완료: $mode + 전송", Toast.LENGTH_SHORT).show()
          } else {
            val debug = buildTelegramCopyDebugInfo(thirdRoots)
            copyToClipboard(debug)
            Toast.makeText(
              this,
              "붙여넣기는 완료, Telegram 전송 버튼은 찾지 못했습니다. 진단 정보가 복사되었습니다.",
              Toast.LENGTH_SHORT
            ).show()
          }
        }, 350L)
      }, 350L)
    }, 450L)
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

  private data class AiTextCandidate(
    val text: String,
    val top: Int,
    val left: Int
  )

  private data class CopyButtonCandidate(
    val node: AccessibilityNodeInfo,
    val label: String,
    val top: Int,
    val left: Int,
    val isCodeCopy: Boolean
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
        if (shouldIgnoreMessageText(cleaned)) return@mapNotNull null
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

  private fun preferBottomTelegramClusterTexts(
    texts: List<String>
  ): List<String> {
    if (texts.size <= 2) return texts
    val normalized = texts.map { it.trim() }.filter { it.isNotBlank() }
    if (normalized.size <= 2) return normalized
    val selected = normalized.takeLast(2)
    appendDebugLog("TG\u2192GPT", "TG_FULL_BOTTOM_CLUSTER before=${normalized.size} after=${selected.size} preview=${compactLogPreview(selected.joinToString("\\n"))}")
    return selected
  }

  private fun collectTelegramFullModeTexts(
    roots: List<AccessibilityNodeInfo>
  ): List<String> {
    val primaryCandidates = mutableListOf<TextCandidate>()
    val broadCandidates = mutableListOf<TextCandidate>()

    roots.forEach { root ->
      val rootRect = Rect()
      root.getBoundsInScreen(rootRect)
      if (!rootRect.isEmpty()) {
        collectVisibleMessageTextCandidates(root, rootRect, primaryCandidates)
        collectTelegramBroadTextCandidates(root, rootRect, broadCandidates)
      }
    }

    val primaryTexts = normalizeTelegramTextCandidates(primaryCandidates)
    val broadTexts = normalizeTelegramTextCandidates(broadCandidates)

    val chosenTexts = if (primaryTexts.size >= 2) {
      primaryTexts
    } else if (broadTexts.size >= primaryTexts.size) {
      broadTexts
    } else {
      primaryTexts
    }

    val result = mutableListOf<String>()
    chosenTexts.forEach { text ->
      addUniqueTelegramText(result, text)
    }

    val finalResult = result.takeLast(MAX_COPY_LINES)

    appendDebugLog(
      "TG\u2192GPT",
      "fullCollect primary=${primaryTexts.size} broad=${broadTexts.size} final=${finalResult.size} preview=${compactLogPreview(finalResult.joinToString("\\n"))}"
    )

    return finalResult
  }

  private fun normalizeTelegramTextCandidates(
    candidates: List<TextCandidate>
  ): List<String> {
    return candidates
      .mapNotNull { candidate ->
        val cleaned = normalizeCandidateText(candidate.text)
        if (cleaned.isBlank()) return@mapNotNull null
        if (shouldIgnoreMessageText(cleaned)) return@mapNotNull null
        candidate.copy(text = cleaned)
      }
      .sortedWith(compareBy<TextCandidate> { it.top }.thenBy { it.left })
      .map { it.text }
  }

  private fun collectTelegramBroadTextCandidates(
    node: AccessibilityNodeInfo?,
    rootRect: Rect,
    output: MutableList<TextCandidate>
  ) {
    if (node == null) return

    val rect = Rect()
    node.getBoundsInScreen(rect)

    val text = node.text?.toString()?.trim()
    val className = node.className?.toString().orEmpty()

    val rootHeight = rootRect.height().coerceAtLeast(1)
    val messageAreaTop = rootRect.top + (rootHeight * 0.08f).toInt()
    val messageAreaBottom = rootRect.bottom - (rootHeight * 0.12f).toInt()
    val intersectsMessageArea = rect.bottom >= messageAreaTop && rect.top <= messageAreaBottom

    val isInputLike = className.contains("EditText", ignoreCase = true)

    if (
      !rect.isEmpty() &&
      intersectsMessageArea &&
      !isInputLike &&
      !text.isNullOrBlank()
    ) {
      output.add(
        TextCandidate(
          text = text,
          top = rect.top,
          bottom = rect.bottom,
          left = rect.left,
          right = rect.right
        )
      )
    }

    for (i in 0 until node.childCount) {
      collectTelegramBroadTextCandidates(node.getChild(i), rootRect, output)
    }
  }

  private fun addUniqueTelegramText(output: MutableList<String>, text: String) {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return

    val iterator = output.listIterator()

    while (iterator.hasNext()) {
      val existing = iterator.next()
      val existingNormalized = existing.replace(Regex("\\s+"), " ").trim()

      if (existingNormalized == normalized) return

      if (existingNormalized.contains(normalized) && existingNormalized.length >= normalized.length) {
        return
      }

      if (normalized.contains(existingNormalized) && normalized.length > existingNormalized.length) {
        iterator.remove()
      }
    }

    output.add(text)
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

  private fun shouldIgnoreMessageText(text: String): Boolean {
    val lower = text.lowercase()

    val exactUiTexts = setOf(
      "telegram", "copybridge", "bridge",
      "메시지", "봇", "봇 메뉴", "돌아가기",
      "안 읽은 메시지", "프로필 사진", "icon 프로필 사진 설정",
      "이모지, 스티커 및 gif", "미디어 첨부", "음성 메시지 녹음", "옵션 더 보기",
      "전송: 켬", "전송: 끔",
      "텔레그램 답변복사", "텔레그램으로 전송"
    )

    if (text.length <= 1) return true
    if (lower in exactUiTexts) return true

    return false
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
      val areaIgnored = isInsideMessageArea(rect, rootRect)
      val ignored = if (normalizedText.isBlank()) false else if (areaIgnored) shouldIgnoreMessageText(normalizedText) else shouldIgnoreText(normalizedText)

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
        return clickNodeOrClickableParent(sendButton)
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
    return looksLikeSend
  }

  private fun getAiRoots(): List<AccessibilityNodeInfo> {
    val candidates = mutableListOf<AccessibilityNodeInfo>()

    rootInActiveWindow?.let { root ->
      val pkg = root.packageName?.toString().orEmpty()
      if (isAiPackage(pkg)) candidates.add(root)
    }

    windows.forEach { window ->
      val root = window.root ?: return@forEach
      val pkg = root.packageName?.toString().orEmpty()
      if (isAiPackage(pkg) && candidates.none { it === root }) candidates.add(root)
    }

    return candidates
  }

  private fun isAiPackage(packageName: String): Boolean {
    val lower = packageName.lowercase()
    if (lower.contains("telegram")) return false
    val aiKeywords = listOf("openai", "chatgpt", "gpt", "ai", "bard", "claude")
    return aiKeywords.any { lower.contains(it) }
  }

  private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo?): Boolean {
    if (node == null) return false
    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
    var parent = node.parent
    var depth = 0
    while (parent != null && depth < 5) {
      if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
      parent = parent.parent
      depth += 1
    }
    return false
  }

  private fun performImeEnterIfPossible(node: AccessibilityNodeInfo?): Boolean {
    if (node == null) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    } else {
      false
    }
  }

  private fun clickAiSendButton(roots: List<AccessibilityNodeInfo>): Boolean {
    roots.forEach { root ->
      val btn = findAiSendButton(root)
      if (btn != null) return clickNodeOrClickableParent(btn)
    }
    return false
  }

  private fun findAiSendButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (node == null) return null
    if (isAiSendButton(node)) return node
    for (i in 0 until node.childCount) {
      val found = findAiSendButton(node.getChild(i))
      if (found != null) return found
    }
    return null
  }

  private fun isAiSendButton(node: AccessibilityNodeInfo): Boolean {
    if (!node.isEnabled) return false

    val text = node.text?.toString()?.trim().orEmpty()
    val desc = node.contentDescription?.toString()?.trim().orEmpty()
    val viewId = node.viewIdResourceName.orEmpty()

    val lowerText = text.lowercase()
    val lowerDesc = desc.lowercase()
    val lowerViewId = viewId.lowercase()

    val descLooksLike = lowerDesc.contains("send") ||
      lowerDesc.contains("submit") ||
      lowerDesc.contains("전송") ||
      lowerDesc.contains("보내기") ||
      lowerDesc.contains("메시지 보내기")

    val viewIdLooksLike = lowerViewId.contains("send") ||
      lowerViewId.contains("submit")

    val textExact = lowerText == "send" ||
      lowerText == "submit" ||
      lowerText == "전송" ||
      lowerText == "보내기"

    val looksLikeSend = descLooksLike || viewIdLooksLike || textExact

    return looksLikeSend
  }


  private fun copyGptOutputByChatGptButton(
    gptOutputMode: String,
    aiRoots: List<AccessibilityNodeInfo>
  ): String? {
    val copyButtons = mutableListOf<CopyButtonCandidate>()

    aiRoots.forEach { root ->
      collectGptCopyButtonCandidates(root, copyButtons)
    }

    appendDebugLog("GPT\u2192TG", "COPY_BUTTON candidates=${copyButtons.size} mode=$gptOutputMode")

    val target = if (gptOutputMode == "CODE") {
      copyButtons
        .filter { it.isCodeCopy }
        .maxWithOrNull(compareBy<CopyButtonCandidate> { it.top }.thenBy { it.left })
    } else {
      copyButtons
        .filter { !it.isCodeCopy }
        .maxWithOrNull(compareBy<CopyButtonCandidate> { it.top }.thenBy { it.left })
    } ?: run {
        appendDebugLog("GPT\u2192TG", "COPY_BUTTON target=null mode=$gptOutputMode")
        return null
      }

    appendDebugLog("GPT\u2192TG", "COPY_BUTTON target label=${compactLogPreview(target.label)} top=${target.top} left=${target.left}")

    val sentinel = "__COPYBRIDGE_COPY_SENTINEL_${System.currentTimeMillis()}"
    copyToClipboard(sentinel)

    val clicked = clickNodeOrClickableParent(target.node)
    appendDebugLog("GPT\u2192TG", "COPY_BUTTON clicked=$clicked")
    if (!clicked) return null

    var copied = ""
    var copiedReady = false

    for (attempt in 1..6) {
      android.os.SystemClock.sleep(300L)
      copied = readClipboardText()

      val isSentinel = copied == sentinel || copied.startsWith("__COPYBRIDGE_COPY_SENTINEL_")
      val usable = copied.isNotBlank() && !isSentinel

      appendDebugLog(
        "GPT\u2192TG",
        "COPY_BUTTON poll=$attempt copiedLength=${copied.length} isBlank=${copied.isBlank()} isSentinel=$isSentinel usable=$usable"
      )

      if (usable) {
        copiedReady = true
        break
      }
    }

    if (!copiedReady) {
      appendDebugLog("GPT\u2192TG", "COPY_BUTTON directPasteToken=true reason=clipboardReadFailed")
      return null
    }

    return copied.trim()
  }

  private fun collectGptCopyButtonCandidates(
    node: AccessibilityNodeInfo?,
    output: MutableList<CopyButtonCandidate>
  ) {
    if (node == null) return

    val rect = Rect()
    node.getBoundsInScreen(rect)

    val textValue = node.text?.toString()?.trim().orEmpty()
    val descValue = node.contentDescription?.toString()?.trim().orEmpty()
    val label = listOf(textValue, descValue)
      .filter { it.isNotBlank() }
      .joinToString(" ")
      .trim()

    if (!rect.isEmpty() && label.isNotBlank() && node.isEnabled) {
      val lower = label.lowercase()

      val isCodeCopy =
        lower == "코드 복사" ||
        lower.contains("code copy") ||
        lower.contains("copy code")

      val isAnswerCopy =
        lower == "복사" ||
        lower == "copy"

      if (isCodeCopy || isAnswerCopy) {
        output.add(
          CopyButtonCandidate(
            node = node,
            label = label,
            top = rect.top,
            left = rect.left,
            isCodeCopy = isCodeCopy
          )
        )
      }
    }

    for (i in 0 until node.childCount) {
      collectGptCopyButtonCandidates(node.getChild(i), output)
    }
  }

    private fun isTelegramEditActuallyFilled(
    editNode: AccessibilityNodeInfo?
  ): Boolean {
    val value = editNode?.text?.toString()?.trim().orEmpty()
    if (value.isBlank()) return false
    val placeholders = setOf("메시지", "Message", "message", "Write a message", "메시지 입력")
    return !placeholders.contains(value)
  }

  private fun logTelegramEditSnapshot(
    label: String,
    editNode: AccessibilityNodeInfo?
  ) {
    val editText = editNode?.text?.toString().orEmpty()
    val startPreview = compactLogPreview(editText.take(600))
    val endPreview = compactLogPreview(editText.takeLast(600))
    appendDebugLog("GPT→TG", "$label editTextLength=${editText.length} start=$startPreview end=$endPreview")
  }

  private fun logGptCopyButtonCandidates(
    label: String,
    copyButtons: List<CopyButtonCandidate>
  ) {
    val sorted = copyButtons.sortedWith(compareBy<CopyButtonCandidate> { it.top }.thenBy { it.left })
    sorted.takeLast(12).forEachIndexed { index, candidate ->
      appendDebugLog("GPT→TG", "$label candidate[$index] label=${compactLogPreview(candidate.label)} top=${candidate.top} left=${candidate.left} isCode=${candidate.isCodeCopy}")
    }
  }

    private fun tapNodeCenterByGesture(
    node: AccessibilityNodeInfo?,
    logLabel: String
  ): Boolean {
    if (node == null) { appendDebugLog("GPT\u2192TG", "$logLabel gestureTap node=null"); return false }
    val rect = android.graphics.Rect()
    node.getBoundsInScreen(rect)
    if (rect.width() <= 0 || rect.height() <= 0) { appendDebugLog("GPT\u2192TG", "$logLabel gestureTap invalidBounds left=${rect.left} top=${rect.top} right=${rect.right} bottom=${rect.bottom}"); return false }
    val centerX = rect.centerX().toFloat()
    val centerY = rect.centerY().toFloat()
    val path = android.graphics.Path()
    path.moveTo(centerX, centerY)
    val gesture = android.accessibilityservice.GestureDescription.Builder()
      .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 80L))
      .build()
    val dispatched = dispatchGesture(gesture, null, null)
    appendDebugLog("GPT\u2192TG", "$logLabel gestureTap dispatched=$dispatched centerX=${centerX.toInt()} centerY=${centerY.toInt()} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}")
    return dispatched
  }

    private fun tryTelegramLongClickPaste(
    editNode: AccessibilityNodeInfo?
  ): Boolean {
    if (editNode == null) return false
    appendDebugLog("GPT→TG", "CODE_LONG_PASTE start")
    editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    editNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    android.os.SystemClock.sleep(250L)
    val longClicked = editNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    appendDebugLog("GPT→TG", "CODE_LONG_PASTE longClicked=$longClicked")
    android.os.SystemClock.sleep(500L)
    val roots = getTelegramRoots()
    val pasteCandidates = mutableListOf<AccessibilityNodeInfo>()
    roots.forEach { root -> collectPasteMenuCandidates(root, pasteCandidates) }
    appendDebugLog("GPT→TG", "CODE_LONG_PASTE menuCandidates=${pasteCandidates.size}")
    pasteCandidates.take(8).forEachIndexed { index, candidate -> logPasteMenuCandidate(index, candidate) }
    val target = pasteCandidates.firstOrNull()
    if (target == null) { appendDebugLog("GPT→TG", "CODE_LONG_PASTE target=null"); return false }
    appendDebugLog("GPT→TG", "CODE_LONG_PASTE target=${compactLogPreview(buildNodeLabel(target))}")
    val clicked = clickNodeOrClickableParent(target)
    appendDebugLog("GPT→TG", "CODE_LONG_PASTE clicked=$clicked")
    if (!clicked) return false
    android.os.SystemClock.sleep(500L)
    val valid = isTelegramEditActuallyFilled(editNode)
    appendDebugLog("GPT→TG", "CODE_LONG_PASTE valid=$valid")
    logTelegramEditSnapshot("CODE_LONG_PASTE_SNAPSHOT", editNode)
    return valid
  }

  private fun isExactPasteMenuLabel(
    label: String
  ): Boolean {
    val normalized = label.trim()
    val lower = normalized.lowercase()
    if (normalized.length > 20) return false
    return normalized == "붙여넣기" || lower == "paste"
  }

  private fun logPasteMenuCandidate(
    index: Int,
    node: AccessibilityNodeInfo
  ) {
    val label = buildNodeLabel(node)
    val rect = android.graphics.Rect()
    node.getBoundsInScreen(rect)
    appendDebugLog("GPT→TG", "CODE_LONG_PASTE candidate[$index] label=${compactLogPreview(label)} length=${label.length} class=${node.className} clickable=${node.isClickable} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}")
  }

  private fun collectPasteMenuCandidates(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return
    val label = buildNodeLabel(node).trim()
    if (isExactPasteMenuLabel(label)) {
      out.add(node)
    }
    for (i in 0 until node.childCount) { collectPasteMenuCandidates(node.getChild(i), out) }
  }

  private fun buildNodeLabel(node: AccessibilityNodeInfo?): String {
    if (node == null) return ""
    val parts = mutableListOf<String>()
    node.text?.toString()?.let { if (it.isNotBlank()) parts.add(it) }
    node.contentDescription?.toString()?.let { if (it.isNotBlank()) parts.add(it) }
    return parts.joinToString(" ").trim()
  }

    private fun tryPasteGptCopyButtonToTelegram(
    gptOutputMode: String,
    aiRoots: List<AccessibilityNodeInfo>,
    autoSend: Boolean
  ): Boolean {
    val copyButtons = mutableListOf<CopyButtonCandidate>()
    aiRoots.forEach { root -> collectGptCopyButtonCandidates(root, copyButtons) }
    appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE candidates=${copyButtons.size} mode=$gptOutputMode")
    logGptCopyButtonCandidates("COPY_BUTTON_PASTE", copyButtons)
    val target = findBestGptCopyButtonForPaste(gptOutputMode, copyButtons)
    if (target == null) {
      appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE target=null mode=$gptOutputMode")
      return false
    }
    appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE target label=${compactLogPreview(target.label)} top=${target.top} left=${target.left} isCode=${target.isCodeCopy}")
    val clicked = if (gptOutputMode == "CODE") {
      val gestureClicked = tapNodeCenterByGesture(target.node, "CODE_COPY_BUTTON")
      appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE codeGestureClicked=$gestureClicked")
      if (gestureClicked) { true } else {
        val fallbackClicked = clickNodeOrClickableParent(target.node)
        appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE codeFallbackClick=$fallbackClicked")
        fallbackClicked
      }
    } else {
      clickNodeOrClickableParent(target.node)
    }
    appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE clicked=$clicked")
    if (!clicked) return false
    android.os.SystemClock.sleep(900L)
    val tgRoots = getTelegramRoots()
    appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE telegramRoots=${tgRoots.size}")
    if (tgRoots.isEmpty()) return false
    val tgEdit = findEditableNodeFromRoots(tgRoots)
    appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE telegramEdit=${tgEdit != null}")
    if (tgEdit == null) return false
    tgEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    tgEdit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    var pasted = try { pasteClipboardToNode(tgEdit) } catch (error: Exception) { appendDebugLog("GPT→TG", "EXCEPTION copyButtonPaste ${error::class.java.simpleName}: ${error.message}"); false }
    android.os.SystemClock.sleep(300L)
    var pasteValid = isTelegramEditActuallyFilled(tgEdit)
    appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE pasted=$pasted valid=$pasteValid autoSend=$autoSend")
    logTelegramEditSnapshot("COPY_BUTTON_PASTE_SNAPSHOT", tgEdit)
    if (!pasteValid && gptOutputMode == "CODE") {
      val longPasteValid = tryTelegramLongClickPaste(tgEdit)
      appendDebugLog("GPT→TG", "CODE_LONG_PASTE result=$longPasteValid")
      if (longPasteValid) { pasted = true; pasteValid = true }
    }

    if (!pasteValid && gptOutputMode == "FULL") {
      appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE skipRetryForFull=true -> fallback")
      return false
    }

    if (!pasteValid) {
      for (attempt in 1..2) {
        tgEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        tgEdit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        android.os.SystemClock.sleep(300L)
        val retryPasted = try { pasteClipboardToNode(tgEdit) } catch (error: Exception) { appendDebugLog("GPT→TG", "EXCEPTION copyButtonPasteRetry attempt=$attempt ${error::class.java.simpleName}: ${error.message}"); false }
        android.os.SystemClock.sleep(300L)
        val retryValid = isTelegramEditActuallyFilled(tgEdit)
        appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE retry=$attempt pasted=$retryPasted valid=$retryValid")
        logTelegramEditSnapshot("COPY_BUTTON_PASTE_RETRY_${attempt}_SNAPSHOT", tgEdit)
        if (retryPasted && retryValid) { pasted = true; pasteValid = true; break }
      }
    }
    if (!pasted || !pasteValid) return false
    if (autoSend) { scheduleAutoSendAfterPaste("GPT→TG:$gptOutputMode:copyButtonPaste", tgEdit) } else { Toast.makeText(this, "Telegram 입력창에 넣었습니다.", Toast.LENGTH_SHORT).show() }
    return true
  }

  private fun findBestGptCopyButtonForPaste(
    gptOutputMode: String,
    copyButtons: List<CopyButtonCandidate>
  ): CopyButtonCandidate? {
    if (copyButtons.isEmpty()) return null
    val sorted = copyButtons.sortedWith(compareBy<CopyButtonCandidate> { it.top }.thenBy { it.left })
    return if (gptOutputMode == "CODE") {
      val explicitCodeCopy = sorted.filter { it.isCodeCopy }
      val rightSideCopy = sorted.filter { !it.isCodeCopy && (it.label.lowercase() == "복사" || it.label.lowercase() == "copy") && it.left >= 1200 }
      (explicitCodeCopy + rightSideCopy).lastOrNull()
    } else {
      sorted.filter { !it.isCodeCopy }.lastOrNull()
    }
  }

  private fun collectLatestAiAnswerFallbackTexts(
    roots: List<AccessibilityNodeInfo>
  ): List<String> {
    val copyButtons = mutableListOf<CopyButtonCandidate>()
    roots.forEach { root ->
      collectGptCopyButtonCandidates(root, copyButtons)
    }

    val answerCopyButtons = copyButtons
      .filter { !it.isCodeCopy }
      .sortedWith(compareBy<CopyButtonCandidate> { it.top }.thenBy { it.left })

    val latestAnswerCopyButton = answerCopyButtons.lastOrNull()
    val previousAnswerTop = if (answerCopyButtons.size >= 2) {
      answerCopyButtons[answerCopyButtons.size - 2].top
    } else {
      Int.MIN_VALUE
    }

    val visibleCandidates = collectAiVisibleTextCandidates(roots)

    val scopedCandidates = if (latestAnswerCopyButton != null) {
      visibleCandidates.filter { candidate ->
        candidate.top > previousAnswerTop && candidate.top < latestAnswerCopyButton.top
      }
    } else {
      visibleCandidates
    }

    val scopedTexts = cleanAiOutputTexts(scopedCandidates.map { it.text })

    appendDebugLog(
      "GPT\u2192TG",
      "FULL_FALLBACK answerButtons=${answerCopyButtons.size} visible=${visibleCandidates.size} scoped=${scopedCandidates.size} cleaned=${scopedTexts.size}"
    )

    return if (scopedTexts.isNotEmpty()) {
      scopedTexts
    } else {
      cleanAiOutputTexts(visibleCandidates.map { it.text })
    }
  }

  private fun buildGptToTelegramDebugInfo(
    reason: String,
    gptOutputMode: String,
    aiRoots: List<AccessibilityNodeInfo>,
    telegramRoots: List<AccessibilityNodeInfo>,
    textToSend: String
  ): String {
    val builder = StringBuilder()
    builder.appendLine("[CopyBridge GPT->Telegram \uC9C4\uB2E8]")
    builder.appendLine("reason=$reason")
    builder.appendLine("gptOutputMode=$gptOutputMode")
    builder.appendLine("aiRoots=${aiRoots.size}")
    builder.appendLine("telegramRoots=${telegramRoots.size}")
    builder.appendLine("selectedTextLength=${textToSend.length}")
    builder.appendLine("selectedTextBlank=${textToSend.isBlank()}")
    builder.appendLine("")
    builder.appendLine("[selectedTextPreviewStart]")
    builder.appendLine(textToSend.take(1200))
    builder.appendLine("")
    builder.appendLine("[selectedTextPreviewEnd]")
    builder.appendLine(if (textToSend.length > 1200) textToSend.takeLast(1200) else textToSend)

    val visibleCandidates = collectAiVisibleTextCandidates(aiRoots)
    builder.appendLine("")
    builder.appendLine("[aiVisibleTextCandidates]")
    builder.appendLine("count=${visibleCandidates.size}")
    visibleCandidates.take(20).forEachIndexed { index, candidate ->
      builder.appendLine("#$index top=${candidate.top} left=${candidate.left} text=\"${escapeDebugValue(candidate.text)}\"")
    }
    if (visibleCandidates.size > 20) {
      builder.appendLine("...(visible candidates limited)")
    }

    val rawTexts = mutableListOf<String>()
    aiRoots.forEach { root -> collectAllTexts(root, rawTexts) }
    builder.appendLine("")
    builder.appendLine("[aiRawTexts]")
    builder.appendLine("count=${rawTexts.size}")
    rawTexts.take(20).forEachIndexed { index, value ->
      builder.appendLine("#$index text=\"${escapeDebugValue(value)}\"")
    }
    if (rawTexts.size > 20) {
      builder.appendLine("...(raw texts limited)")
    }

    val tgEdit = findEditableNodeFromRoots(telegramRoots)
    builder.appendLine("")
    builder.appendLine("[telegramInputFound]")
    builder.appendLine(tgEdit != null)

    val tgRect = android.graphics.Rect()
    telegramRoots.forEachIndexed { index, root ->
      root.getBoundsInScreen(tgRect)
      builder.appendLine("#$index className=${root.className} rect=${tgRect} visible=${root.isVisibleToUser}")
    }

    return builder.toString()
  }

  private fun buildAiWindowDebugInfo(reason: String, roots: List<AccessibilityNodeInfo>): String {
    val builder = StringBuilder()
    builder.appendLine("[CopyBridge AI Window 진단]")
    builder.appendLine("reason=$reason")
    builder.appendLine("aiRoots=${roots.size}")
    roots.forEachIndexed { i, root ->
      val r = Rect(); root.getBoundsInScreen(r)
      builder.appendLine("")
      builder.appendLine("[aiRoot $i] package=${root.packageName} class=${root.className}")
      builder.appendLine("bounds=${r.left},${r.top},${r.right},${r.bottom}")
      val lines = mutableListOf<String>()
      collectDebugTextLines(root, r, lines)
      lines.take(MAX_DEBUG_LINES).forEach { builder.appendLine(it) }
      if (lines.size > MAX_DEBUG_LINES) builder.appendLine("...(truncated)")
    }
    val result = builder.toString()
    return if (result.length > MAX_DEBUG_CHARS) result.take(MAX_DEBUG_CHARS) + "\n...(truncated)" else result
  }

  private fun extractCodeBlocks(lines: List<String>): List<String> {
    val codeBlocks = mutableListOf<String>()
    var inBlock = false
    val current = StringBuilder()
    for (line in lines) {
      if (line.trimStart().startsWith("```") || line.trimStart().startsWith("```")) {
        if (inBlock) {
          codeBlocks.add(current.toString().trim())
          current.clear()
          inBlock = false
        } else {
          inBlock = true
        }
      } else if (inBlock) {
        current.appendLine(line)
      }
    }
    if (current.isNotBlank()) codeBlocks.add(current.toString().trim())
    return codeBlocks
  }

  private fun looksLikeCode(text: String): Boolean {
    val codeKeywords = listOf("import ", "const ", "fun ", "class ", "return ",
      "private ", "public ", "val ", "var ", "interface ", "package ")
    if (text.length < 30) return false
    val lower = text.lowercase()
    return codeKeywords.any { lower.contains(it) }
  }

  private fun collectAllTexts(node: AccessibilityNodeInfo?, output: MutableList<String>) {
    if (node == null) return
    val text = node.text?.toString()?.trim()
    if (!text.isNullOrBlank()) output.add(text)
    val desc = node.contentDescription?.toString()?.trim()
    if (!desc.isNullOrBlank()) output.add(desc)
    for (i in 0 until node.childCount) collectAllTexts(node.getChild(i), output)
  }

  private fun collectAiVisibleTextCandidates(
    roots: List<AccessibilityNodeInfo>
  ): List<AiTextCandidate> {
    val candidates = mutableListOf<AiTextCandidate>()
    roots.forEach { root ->
      collectAiVisibleTextCandidateNodes(root, candidates)
    }
    return candidates
      .mapNotNull { candidate ->
        val cleaned = candidate.text.trim()
        if (cleaned.isBlank()) return@mapNotNull null
        if (shouldIgnoreAiText(cleaned)) return@mapNotNull null
        candidate.copy(text = cleaned)
      }
      .sortedWith(compareBy<AiTextCandidate> { it.top }.thenBy { it.left })
  }

  private fun collectAiVisibleTextCandidateNodes(
    node: AccessibilityNodeInfo?,
    output: MutableList<AiTextCandidate>
  ) {
    if (node == null) return
    val rect = Rect()
    node.getBoundsInScreen(rect)
    val text = node.text?.toString()?.trim()
    if (!rect.isEmpty() && !text.isNullOrBlank()) {
      output.add(
        AiTextCandidate(
          text = text,
          top = rect.top,
          left = rect.left
        )
      )
    }
    for (i in 0 until node.childCount) {
      collectAiVisibleTextCandidateNodes(node.getChild(i), output)
    }
  }

  private fun collectAiAnswerTexts(node: AccessibilityNodeInfo?, output: MutableList<String>) {
    if (node == null) return
    val text = node.text?.toString()?.trim()
    if (!text.isNullOrBlank() && !shouldIgnoreAiText(text)) output.add(text)
    for (i in 0 until node.childCount) collectAiAnswerTexts(node.getChild(i), output)
  }

    private fun shouldIgnoreAiText(text: String): Boolean {
    val cleaned = text.trim()
    val lower = cleaned.lowercase()

    val exactIgnores = setOf(
      "chatgpt에 답장",
      "첨부 파일",
      "음성 받아쓰기",
      "음성 대화 시작",
      "좋은 응답",
      "별로인 응답",
      "공유",
      "더 많은 액션",
      "복사",
      "코드 복사",
      "plain text",
      "thinking",
      "위로 이동",
      "소리 내어 읽기",
      "메뉴 편집",
      "메뉴",
      "오픈클로 코딩"
    )

    if (lower in exactIgnores) return true

    val partialIgnores = listOf(
      "초 동안 생각함",
      "동안 생각함",
      "thinking",
      "chatgpt에 답장",
      "음성 받아쓰기",
      "음성 대화 시작",
      "좋은 응답",
      "별로인 응답",
      "더 많은 액션",
      "위로 이동",
      "소리 내어 읽기",
      "메뉴 편집"
    )

    if (partialIgnores.any { lower.contains(it) }) return true

    return false
  }

  private fun cleanAiOutputTexts(rawTexts: List<String>): List<String> {
    val result = mutableListOf<String>()
    rawTexts.forEach { rawText ->
      val cleaned = rawText.trim()
      if (cleaned.isBlank()) return@forEach
      if (cleaned.length <= 1) return@forEach
      if (shouldIgnoreAiText(cleaned)) return@forEach
      addUniqueAiOutputText(result, cleaned)
    }
    return result
  }

  private fun addUniqueAiOutputText(output: MutableList<String>, text: String) {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return
    val iterator = output.listIterator()
    while (iterator.hasNext()) {
      val existing = iterator.next()
      val existingNormalized = existing.replace(Regex("\\s+"), " ").trim()
      if (existingNormalized == normalized) return
      if (existingNormalized.contains(normalized) && existingNormalized.length >= normalized.length) return
      if (normalized.contains(existingNormalized) && normalized.length > existingNormalized.length) iterator.remove()
    }
    output.add(text)
  }

  private fun clampCopyText(text: String): String {
    return text.trim()
  }

  companion object {
    private const val TAG = "CopyBridgeA11y"
    private const val DEBUG_LOG_PREFS_NAME = "copybridge_debug_logs"
    private const val DEBUG_LOG_KEY = "logs"
    private const val MAX_DEBUG_LOG_ENTRIES = 50
    private const val MAX_COPY_LINES = 80
    private const val MAX_COPY_CHARS = 50000
    private const val MAX_SINGLE_LINE_LENGTH = 600
    private const val MAX_DEBUG_LINES = 60
    private const val MAX_DEBUG_CHARS = 6000
    private const val AUTO_SEND_FIRST_RETRY_DELAY_MS = 250L
    private const val AUTO_SEND_SECOND_RETRY_DELAY_MS = 150L
    const val COPY_MODE_FULL = "FULL"
    const val COPY_MODE_LAST = "LAST"
    private var activeService: CopyBridgeAccessibilityService? = null

    fun isServiceActive(): Boolean = activeService != null

    fun requestCopyTelegramToAi(context: Context): Boolean =
      requestCopyTelegramToAi(context, COPY_MODE_FULL)

    fun requestCopyTelegramToAi(context: Context, copyMode: String): Boolean {
      val service = activeService
      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }
      service.handleCopyTelegramToAiRequest(copyMode)
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

    fun requestTelegramToGpt(context: Context, copyMode: String, autoSend: Boolean): Boolean {
      val service = activeService
      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

      val tgRoots = service.getTelegramRoots()
      if (tgRoots.isEmpty()) {
        Toast.makeText(context, "Telegram 채팅방을 화면에 열어주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

      val candidates = service.collectTelegramMessageCandidates(tgRoots)
      if (candidates.isEmpty()) {
        val debug = service.buildTelegramCopyDebugInfo(tgRoots)
        service.copyToClipboard(debug)
        Toast.makeText(context, "Telegram 메시지를 찾지 못했습니다. 진단 정보가 복사되었습니다.", Toast.LENGTH_SHORT).show()
        return false
      }

      val selectedTexts = if (copyMode == COPY_MODE_LAST) {
        candidates.takeLast(1).map { it.text }
      } else {
        val fullTexts = service.preferBottomTelegramClusterTexts(service.collectTelegramFullModeTexts(tgRoots))
        if (fullTexts.isNotEmpty()) {
          fullTexts
        } else {
          candidates.map { it.text }
        }
      }
      val textToSend = selectedTexts.joinToString("\n")
      service.appendDebugLog("TG\u2192GPT", "mode=$copyMode selected=${selectedTexts.size} textLength=${textToSend.length} preview=${service.compactLogPreview(textToSend)}")

      val aiRoots = service.getAiRoots()
      service.appendDebugLog("GPT\u2192TG", "STEP 2 aiRoots=${aiRoots.size}")
      if (aiRoots.isEmpty()) {
        service.copyToClipboard(textToSend)
        Toast.makeText(context, "GPT/AI 앱을 찾지 못했습니다. 텍스트는 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
        return true
      }

      val freshInputRoots = service.getAiRoots()
      val aiEdit = service.findEditableNodeFromRoots(
        if (freshInputRoots.isNotEmpty()) freshInputRoots else aiRoots
      )
      if (aiEdit == null) {
        service.copyToClipboard(textToSend)
        Toast.makeText(context, "GPT 입력창을 찾지 못했습니다. 텍스트는 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
        return true
      }

      val setTextOk = service.setTextToNode(aiEdit, textToSend)
      val inputOk = if (setTextOk) {
        true
      } else {
        service.copyToClipboard(textToSend)
        service.pasteClipboardToNode(aiEdit)
      }

      val modeLabel = if (copyMode == COPY_MODE_LAST) "마지막" else "전체"

      if (!autoSend) {
        val actualCount = if (copyMode == COPY_MODE_LAST) 1 else selectedTexts.size
        if (inputOk) {
          Toast.makeText(context, "GPT 입력창에 넣었습니다(전체: ${actualCount}개).", Toast.LENGTH_SHORT).show()
        } else {
          Toast.makeText(context, "${actualCount}개 텍스트를 클립보드에 복사했습니다.", Toast.LENGTH_SHORT).show()
        }
        return true
      }

      if (!inputOk) {
        val debug = service.buildAiWindowDebugInfo("GPT 텍스트 입력 실패", aiRoots)
        service.copyToClipboard(debug)
        Toast.makeText(context, "GPT 입력에 실패했습니다. 진단 정보가 복사되었습니다.", Toast.LENGTH_SHORT).show()
        return true
      }

      Handler(Looper.getMainLooper()).postDelayed({
        val freshRoots = service.getAiRoots()
        val firstSent = service.clickAiSendButton(freshRoots)

        if (firstSent) {
          Toast.makeText(context, "GPT로 전송했습니다(전체: ${selectedTexts.size}개).", Toast.LENGTH_SHORT).show()
          return@postDelayed
        }

        val focusRoots = service.getAiRoots()
        val focusEdit = service.findEditableNodeFromRoots(focusRoots)
        if (focusEdit != null) {
          focusEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
          focusEdit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
          service.performImeEnterIfPossible(focusEdit)
        }
        Handler(Looper.getMainLooper()).postDelayed({
          val retryRoots = service.getAiRoots()
          val retrySent = service.clickAiSendButton(retryRoots)

          if (retrySent) {
            Toast.makeText(context, "GPT로 전송했습니다(전체: ${selectedTexts.size}개).", Toast.LENGTH_SHORT).show()
          } else {
            val debug = service.buildAiWindowDebugInfo("GPT 전송 버튼 찾기 실패", retryRoots)
            service.copyToClipboard(debug)
            Toast.makeText(context, "GPT 전송 버튼을 찾지 못했습니다. 진단 정보가 복사되었습니다.", Toast.LENGTH_SHORT).show()
          }
        }, 500L)
      }, 1200L)
      return true
    }

    fun requestGptToTelegram(context: Context, gptOutputMode: String, autoSend: Boolean): Boolean {
      val service = activeService
      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

      service.appendDebugLog("GPT\u2192TG", "START mode=$gptOutputMode autoSend=$autoSend")
      service.appendDebugLog("GPT\u2192TG", "STEP after START")

       val aiRoots = service.getAiRoots()
      service.appendDebugLog("GPT\u2192TG", "STEP aiRoots=${aiRoots.size}")
      if (aiRoots.isEmpty()) {
        service.appendDebugLog("GPT\u2192TG", "STOP aiRoots=0")
        Toast.makeText(context, "GPT/AI 앱을 화면에 열어주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

                  val copyButtonPasteOk = service.tryPasteGptCopyButtonToTelegram(gptOutputMode, aiRoots, autoSend)
                  if (copyButtonPasteOk) {
                    service.appendDebugLog("GPT→TG", "STOP copyButtonPaste success mode=$gptOutputMode")
                    return true
                  }
                  service.appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE failed -> fallback collect mode=$gptOutputMode")

                  var textToSend = ""
      var gptCopySource = "unknown"

      service.appendDebugLog("GPT\u2192TG", "STEP before copyButton mode=$gptOutputMode")
      val buttonCopyText = if (gptOutputMode == "FULL") {
        service.appendDebugLog("GPT\u2192TG", "STEP skip copyButton polling for FULL fallback")
        null
      } else {
        try {
          service.copyGptOutputByChatGptButton(gptOutputMode, aiRoots)
        } catch (error: Exception) {
          service.appendDebugLog("GPT\u2192TG", "EXCEPTION copyButton ${error::class.java.simpleName}: ${error.message}")
          null
        }
      }
      service.appendDebugLog("GPT\u2192TG", "STEP after copyButton resultLength=${buttonCopyText?.length ?: -1}")

      if (!buttonCopyText.isNullOrBlank()) {
        gptCopySource = "chatgpt_copy_button"
        textToSend = service.clampCopyText(buttonCopyText)
      } else if (gptOutputMode == "CODE") {
        gptCopySource = "fallback_code_collect"
        service.appendDebugLog("GPT\u2192TG", "STEP fallback CODE collect start")
        val rawTexts = mutableListOf<String>()
        aiRoots.forEach { root -> service.collectAllTexts(root, rawTexts) }
        service.appendDebugLog("GPT\u2192TG", "CODE_FALLBACK rawTexts=${rawTexts.size}")

        val cleanedTexts = service.cleanAiOutputTexts(rawTexts)
        service.appendDebugLog("GPT\u2192TG", "CODE_FALLBACK cleanedTexts=${cleanedTexts.size}")

        val codeBlocks = service.extractCodeBlocks(cleanedTexts)
        val codeCandidates = cleanedTexts.filter { service.looksLikeCode(it) }

        service.appendDebugLog(
          "GPT\u2192TG",
          "CODE_FALLBACK codeBlocks=${codeBlocks.size} codeCandidates=${codeCandidates.size}"
        )

        val selectedCode = if (codeBlocks.isNotEmpty()) {
          codeBlocks.last().trim()
        } else {
          codeCandidates.maxByOrNull { it.length }?.trim().orEmpty()
        }

        service.appendDebugLog(
          "GPT\u2192TG",
          "CODE_FALLBACK selectedLength=${selectedCode.length} preview=${service.compactLogPreview(selectedCode)}"
        )

        textToSend = service.clampCopyText(selectedCode)
        service.appendDebugLog("GPT\u2192TG", "CODE_FALLBACK textToSendLength=${textToSend.length}")
        if (textToSend.isBlank()) {
          val finalTexts = service.collectLatestAiAnswerFallbackTexts(aiRoots)
          val finalCodeBlocks = service.extractCodeBlocks(finalTexts)
          val finalFull = if (finalCodeBlocks.isNotEmpty()) { finalCodeBlocks.last().trim() } else { finalTexts.joinToString("\n") }
          textToSend = service.clampCopyText(finalFull)
          service.appendDebugLog("GPT\u2192TG", "CODE_FINAL_FALLBACK source=${if (finalCodeBlocks.isNotEmpty()) "codeBlocks" else "fullFallback"} textLength=${textToSend.length}")
        }
      } else {
        gptCopySource = "fallback_full_visible"
        service.appendDebugLog("GPT\u2192TG", "STEP fallback FULL collect start")
        val cleanedTexts = service.collectLatestAiAnswerFallbackTexts(aiRoots)
        val fullText = cleanedTexts.joinToString("\n")
        service.appendDebugLog(
          "GPT\u2192TG",
          "FULL_FALLBACK textCount=${cleanedTexts.size} textLength=${fullText.length} preview=${service.compactLogPreview(fullText)}"
        )
        textToSend = service.clampCopyText(fullText)
      }
      if (textToSend.isBlank()) {
        val debug = service.buildAiWindowDebugInfo("GPT 텍스트를 찾지 못함 (mode=$gptOutputMode)", aiRoots)
        service.copyToClipboard(debug)
        Toast.makeText(context, "GPT 텍스트를 찾지 못했습니다. 진단 정보가 복사되었습니다.", Toast.LENGTH_SHORT).show()
        return false
      }

      val tgRoots = service.getTelegramRoots()
      if (tgRoots.isEmpty()) {
        val debug = service.buildGptToTelegramDebugInfo(
          "Telegram 채팅방 찾기 실패",
          gptOutputMode,
          aiRoots,
          tgRoots,
          textToSend
        )
        service.copyToClipboard(debug)
        Toast.makeText(context, "Telegram 채팅방을 찾지 못했습니다. 진단 정보가 복사되었습니다.", Toast.LENGTH_SHORT).show()
        return true
      }

      val tgEdit = service.findEditableNodeFromRoots(tgRoots)
      if (tgEdit == null) {
        val debug = service.buildGptToTelegramDebugInfo(
          "Telegram 입력창 찾기 실패",
          gptOutputMode,
          aiRoots,
          tgRoots,
          textToSend
        )
        service.copyToClipboard(debug)
        Toast.makeText(context, "Telegram 입력창을 찾지 못했습니다. 진단 정보가 복사되었습니다.", Toast.LENGTH_SHORT).show()
        return true
      }

      val setOk = try {
        service.setTextToNode(tgEdit, textToSend)
      } catch (error: Exception) {
        service.appendDebugLog("GPT\u2192TG", "EXCEPTION setText ${error::class.java.simpleName}: ${error.message}")
        false
      }

      val inputOk = if (setOk) {
        true
      } else {
        service.copyToClipboard(textToSend)
        try {
          service.pasteClipboardToNode(tgEdit)
        } catch (error: Exception) {
          service.appendDebugLog("GPT\u2192TG", "EXCEPTION paste ${error::class.java.simpleName}: ${error.message}")
          false
        }
      }
      if (inputOk) {
        if (autoSend) {
          service.scheduleAutoSendAfterPaste("GPT→TG", tgEdit)
        } else {
          Toast.makeText(context, "Telegram 입력창에 넣었습니다.", Toast.LENGTH_SHORT).show()
        }
      } else {
        val debug = service.buildTelegramCopyDebugInfo(tgRoots)
        service.copyToClipboard(debug)
        Toast.makeText(context, "코드 전송에 실패했습니다. 진단 정보가 복사되었습니다.", Toast.LENGTH_SHORT).show()
      }
      return true
    }

    fun requestGptCodeToTelegram(context: Context, autoSend: Boolean): Boolean =
      requestGptToTelegram(context, "CODE", autoSend)
  }
}

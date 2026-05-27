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
  private val bridgeStatusMonitorHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private var bridgeStatusMonitorRunning = false
  private var lastBridgeMonitorGptBusy: Boolean? = null
  private var lastBridgeMonitorTelegramTyping: Boolean? = null
  private var lastBridgeMonitorGptTextSignature: String? = null
  private var lastBridgeMonitorGptTextChangedAtMs: Long = 0L
  private val bridgeGptTextChangeHoldMs: Long = 6000L
  private var lastBridgeMonitorGptActivityAtMs: Long = 0L
  private var lastBridgeMonitorGptStopSeenAtMs: Long = 0L
  private val bridgeGptRecentStopHoldMs: Long = 10000L
  private val bridgeGptIdleCompleteTimeoutMs: Long = 6000L
  private val bridgeStatusMonitorRunnable = object : Runnable {
    override fun run() {
      runBridgeMonitorStatusTick()
      if (bridgeStatusMonitorRunning) {
        bridgeStatusMonitorHandler.postDelayed(this, 1000L)
      }
    }
  }

  private var lastPackageName: String? = null

    private fun startBridgeStatusMonitor() {
    if (bridgeStatusMonitorRunning) return

    bridgeStatusMonitorRunning = true

    appendDebugLog(
      "WIDGET",
      "BRIDGE_MONITOR_START intervalMs=1000"
    )

    bridgeStatusMonitorHandler.removeCallbacks(bridgeStatusMonitorRunnable)
    bridgeStatusMonitorHandler.postDelayed(bridgeStatusMonitorRunnable, 1000L)
  }

  private fun stopBridgeStatusMonitor() {
    bridgeStatusMonitorRunning = false
    bridgeStatusMonitorHandler.removeCallbacks(bridgeStatusMonitorRunnable)

    appendDebugLog(
      "WIDGET",
      "BRIDGE_MONITOR_STOP"
    )
  }

  private fun collectTelegramMonitorRoots(): List<android.view.accessibility.AccessibilityNodeInfo> {
    return collectBridgeMonitorRootsWithPackageFilter("telegram", "org.telegram")
  }

  private fun collectGptMonitorRoots(): List<android.view.accessibility.AccessibilityNodeInfo> {
    return collectBridgeMonitorRootsWithPackageFilter("openai", "chatgpt")
  }

  private fun collectBridgeMonitorRootsWithPackageFilter(
    vararg allowedSubstrings: String
  ): List<android.view.accessibility.AccessibilityNodeInfo> {
    val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
    val allRoots = collectBridgeMonitorRoots()
    for (root in allRoots) {
      try {
        val pkg = root.packageName?.toString()?.lowercase().orEmpty()
        if (pkg.isNotEmpty() && allowedSubstrings.any { pkg.contains(it) }) {
          result.add(root)
        }
      } catch (_: Exception) {}
    }
    return result
  }

  private fun collectBridgeMonitorRoots(): List<android.view.accessibility.AccessibilityNodeInfo> {
    val roots = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()

    try {
      rootInActiveWindow?.let { roots.add(it) }
    } catch (_: Exception) {}

    try {
      windows.forEach { window ->
        window.root?.let { root ->
          if (!roots.any { it == root }) { roots.add(root) }
        }
      }
    } catch (_: Exception) {}

    return roots
  }
  private fun getLatestGptAnswerTextSignatureForDecision(
    roots: List<android.view.accessibility.AccessibilityNodeInfo>
  ): String {
    val candidates = mutableListOf<Pair<String, android.graphics.Rect>>()

    roots.forEach { root -> collectGptAnswerTextNodesForDecision(root, candidates) }

    if (candidates.isEmpty()) {
      appendDebugLog("GPT→TG", "GPT_TEXT_CHANGE_SCAN candidates=0 changed=false active=false ageMs=-1")
      return ""
    }

    val sorted = candidates.sortedWith(compareBy<Pair<String, android.graphics.Rect>> { it.second.bottom }.thenBy { it.second.top })
    val latest = sorted.last()
    val latestText = latest.first
    val latestRect = latest.second

    val normalized = latestText.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    val signatureCore = if (normalized.length > 220) { normalized.takeLast(220) } else { normalized }
    val signature = "${latestRect.top}:${latestRect.bottom}:${normalized.length}:$signatureCore"

    candidates.takeLast(5).forEachIndexed { index, item ->
      val rect = item.second
      appendDebugLog("GPT→TG", "GPT_TEXT_CHANGE_NODE[$index] length=${item.first.length} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom} preview=${compactLogPreview(item.first)}")
    }

    return signature
  }

  private fun collectGptAnswerTextNodesForDecision(
    node: android.view.accessibility.AccessibilityNodeInfo?,
    out: MutableList<Pair<String, android.graphics.Rect>>
  ) {
    if (node == null) return

    val label = buildNodeLabel(node).trim()

    if (label.isNotBlank()) {
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)

      val lower = label.lowercase()
      val isSystemOrOverlay = lower.contains("copybridge") || lower.contains("클립보드") || lower.contains("토스트") ||
        lower.contains("sktelecom") || lower.contains("오전") || lower.contains("오후") ||
        lower.contains("위젯") || lower == "bridge" || label.startsWith("CopyBridge") ||
        label.contains("로그를") || label.contains("복사했습니다")

      val isUiText = label == "복사" || label == "좋은 응답" || label == "별로인 응답" ||
        label == "소리 내어 읽기" || label == "공유" || label == "더 많은 액션" ||
        label == "첨부 파일" || label == "ChatGPT에 답장" || label == "음성 받아쓰기" ||
        label == "음성 대화 시작" || label == "중지" ||
        lower == "copy" || lower == "like" || lower == "dislike" ||
        lower == "read aloud" || lower == "share" || lower == "more" ||
        lower == "stop" || lower == "stop generating" || lower == "stop responding"

      val looksLikeAnswerText = label.length >= 20 && !isUiText && rect.top > 0 && rect.bottom > rect.top && rect.height() >= 20

      if (looksLikeAnswerText) { out.add(label to rect) }
    }

    for (i in 0 until node.childCount) {
      collectGptAnswerTextNodesForDecision(node.getChild(i), out)
    }
  }

  private fun hasBottomGptCompletionActionRowForDecision(
    roots: List<android.view.accessibility.AccessibilityNodeInfo>
  ): Boolean {
    val actionCandidates = mutableListOf<Pair<String, android.graphics.Rect>>()

    roots.forEach { root ->
      collectGptCompletionActionNodesForDecision(root, actionCandidates)
    }

    if (actionCandidates.isEmpty()) {
      appendDebugLog(
        "GPT→TG",
        "GPT_ACTION_ROW_MATCH found=false candidates=0"
      )
      return false
    }

    val maxTop = actionCandidates.maxOf { it.second.top }
    val bottomBand = actionCandidates.filter { kotlin.math.abs(it.second.top - maxTop) <= 80 }
    val uniqueLabels = bottomBand.map { it.first }.toSet()

    val hasEnoughActionRow =
      bottomBand.size >= 3 &&
      uniqueLabels.intersect(
        setOf("복사", "좋은 응답", "별로인 응답", "소리 내어 읽기", "공유", "더 많은 액션", "Copy", "Like", "Dislike", "Read aloud", "Share", "More")
      ).size >= 3

    if (!hasEnoughActionRow) {
      appendDebugLog(
        "GPT→TG",
        "GPT_ACTION_ROW_MATCH found=false reason=insufficientActionRow candidates=${actionCandidates.size} bottomBand=${bottomBand.size} labels=${uniqueLabels.joinToString("|")}"
      )

      bottomBand.take(8).forEachIndexed { index, item ->
        val rect = item.second
        appendDebugLog(
          "GPT→TG",
          "GPT_ACTION_ROW_NODE[$index] label=${compactLogPreview(item.first)} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
        )
      }

      return false
    }

    val textCandidates = mutableListOf<Pair<String, android.graphics.Rect>>()

    roots.forEach { root ->
      collectGptAnswerTextNodesForDecision(root, textCandidates)
    }

    if (textCandidates.isEmpty()) {
      if (hasEnoughActionRow) {
        appendDebugLog(
          "GPT→TG",
          "GPT_ACTION_ROW_MATCH found=true fallback=textNotFoundButActionRow candidates=${actionCandidates.size} bottomBand=${bottomBand.size} labels=${uniqueLabels.joinToString("|")}"
        )
        return true
      }

      appendDebugLog(
        "GPT→TG",
        "GPT_ACTION_ROW_MATCH found=false reason=noLatestAnswerText candidates=${actionCandidates.size} bottomBand=${bottomBand.size} labels=${uniqueLabels.joinToString("|")}"
      )

      bottomBand.take(8).forEachIndexed { index, item ->
        val rect = item.second
        appendDebugLog(
          "GPT→TG",
          "GPT_ACTION_ROW_NODE[$index] label=${compactLogPreview(item.first)} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
        )
      }

      return false
    }

    val latestText = textCandidates.maxByOrNull { it.second.bottom }
    val latestTextRect = latestText?.second ?: android.graphics.Rect()
    val latestTextPreview = latestText?.first.orEmpty()

    val actionTop = bottomBand.minOf { it.second.top }
    val actionBottom = bottomBand.maxOf { it.second.bottom }
    val gap = actionTop - latestTextRect.bottom

    val newerTextBelowAction =
      latestTextRect.top > actionBottom + 20 ||
      latestTextRect.bottom > actionBottom + 20

    val actionRowAttachedToLatestAnswer =
      !newerTextBelowAction &&
      latestTextRect.bottom > 0 &&
      actionTop >= latestTextRect.bottom - 20 &&
      gap <= 420

    appendDebugLog(
      "GPT→TG",
      "GPT_ACTION_ROW_RELATION latestBottom=${latestTextRect.bottom} actionTop=$actionTop actionBottom=$actionBottom gap=$gap newerTextBelowAction=$newerTextBelowAction latestPreview=${compactLogPreview(latestTextPreview)}"
    )

    val found = actionRowAttachedToLatestAnswer

    appendDebugLog(
      "GPT→TG",
      if (found) {
        "GPT_ACTION_ROW_MATCH found=true candidates=${actionCandidates.size} bottomBand=${bottomBand.size} labels=${uniqueLabels.joinToString("|")} relation=latestAnswer"
      } else {
        "GPT_ACTION_ROW_MATCH found=false reason=actionRowNotForLatestAnswer candidates=${actionCandidates.size} bottomBand=${bottomBand.size} labels=${uniqueLabels.joinToString("|")}"
      }
    )

    bottomBand.take(8).forEachIndexed { index, item ->
      val rect = item.second
      appendDebugLog(
        "GPT→TG",
        "GPT_ACTION_ROW_NODE[$index] label=${compactLogPreview(item.first)} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
      )
    }

    return found
  }

  private fun collectGptCompletionActionNodesForDecision(
    node: android.view.accessibility.AccessibilityNodeInfo?,
    out: MutableList<Pair<String, android.graphics.Rect>>
  ) {
    if (node == null) return

    val label = buildNodeLabel(node).trim()
    val isAction =
      label == "복사" || label == "좋은 응답" || label == "별로인 응답" ||
      label == "소리 내어 읽기" || label == "공유" || label == "더 많은 액션" ||
      label == "Copy" || label == "Like" || label == "Dislike" ||
      label == "Read aloud" || label == "Share" || label == "More"

    if (label.length <= 30 && isAction) {
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)
      if (rect.top > 0 && rect.bottom > rect.top) { out.add(label to rect) }
    }

    for (i in 0 until node.childCount) {
      collectGptCompletionActionNodesForDecision(node.getChild(i), out)
    }
  }

  private fun runBridgeMonitorStatusTick() {
    val roots = collectBridgeMonitorRoots()

    if (roots.isEmpty()) {
      appendDebugLog("WIDGET", "BRIDGE_MONITOR_TICK roots=0 skipped=true")

      return
    }

    val allRoots = collectBridgeMonitorRoots()
    val tgRootsForMonitor = collectTelegramMonitorRoots()
    val gptRootsForMonitor = collectGptMonitorRoots()

    appendDebugLog("WIDGET", "BRIDGE_MONITOR_ROOTS all=${allRoots.size} tg=${tgRootsForMonitor.size} gpt=${gptRootsForMonitor.size}")

    if (tgRootsForMonitor.isEmpty() || gptRootsForMonitor.isEmpty()) {
      allRoots.take(12).forEachIndexed { index, root ->
        val rect = android.graphics.Rect()
        root.getBoundsInScreen(rect)
        appendDebugLog(
          "WIDGET",
          "BRIDGE_MONITOR_ROOT_PACKAGE[$index] package=${root.packageName} class=${root.className} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom} visible=${root.isVisibleToUser} childCount=${root.childCount}"
        )
      }
    }

    val telegramTyping = if (tgRootsForMonitor.isNotEmpty()) {
      val tgExact = hasExactTelegramTypingTopBarNodeForDecision(tgRootsForMonitor)
      val tgTopbar = logTelegramTopBarSnapshotForDebug(tgRootsForMonitor)
      appendDebugLog("TG→GPT", "TELEGRAM_MONITOR_TYPING_DECISION exact=$tgExact topbar=$tgTopbar value=${tgExact || tgTopbar}")
      tgExact || tgTopbar
    } else {
      appendDebugLog("WIDGET", "TELEGRAM_MONITOR_SKIPPED reason=noTelegramRoot")
      false
    }

    val previousGptBusy = lastBridgeMonitorGptBusy ?: false

    if (gptRootsForMonitor.isNotEmpty()) {
      val gptBusyByStop = hasExactShortGptBusyNodeForDecision(gptRootsForMonitor)
      val gptCompletedByActionRow = hasBottomGptCompletionActionRowForDecision(gptRootsForMonitor)
      val nowMs = System.currentTimeMillis()
      val latestGptTextSignature = getLatestGptAnswerTextSignatureForDecision(gptRootsForMonitor)
      val previousGptTextSignature = lastBridgeMonitorGptTextSignature

      val gptTextChanged = latestGptTextSignature.isNotBlank() && previousGptTextSignature != null && latestGptTextSignature != previousGptTextSignature

      if (latestGptTextSignature.isNotBlank() && latestGptTextSignature != previousGptTextSignature) {
        lastBridgeMonitorGptTextSignature = latestGptTextSignature
        lastBridgeMonitorGptTextChangedAtMs = nowMs
        lastBridgeMonitorGptActivityAtMs = nowMs
      }

      val gptTextChangeAgeMs = if (lastBridgeMonitorGptTextChangedAtMs > 0L) { nowMs - lastBridgeMonitorGptTextChangedAtMs } else { -1L }

      if (gptBusyByStop) {
        lastBridgeMonitorGptStopSeenAtMs = nowMs
        appendDebugLog("GPT→TG", "GPT_STOP_SEEN updatedAt=$nowMs")
      }

      val gptTextChangeRecentStopSeen = lastBridgeMonitorGptStopSeenAtMs > 0L && nowMs - lastBridgeMonitorGptStopSeenAtMs <= bridgeGptRecentStopHoldMs

      val gptBusyByTextChange =
        previousGptBusy && gptTextChangeRecentStopSeen && !gptCompletedByActionRow &&
        lastBridgeMonitorGptTextChangedAtMs > 0L && gptTextChangeAgeMs in 0L..bridgeGptTextChangeHoldMs

      appendDebugLog("GPT→TG", "GPT_TEXT_CHANGE_SCAN candidates=latest changed=$gptTextChanged active=$gptBusyByTextChange ageMs=$gptTextChangeAgeMs")
      appendDebugLog("GPT→TG", "GPT_TEXT_CHANGE_GUARD changed=$gptTextChanged previousBusy=$previousGptBusy stop=$gptBusyByStop actionComplete=$gptCompletedByActionRow recentStop=$gptTextChangeRecentStopSeen active=$gptBusyByTextChange")

      if (gptBusyByStop || (gptTextChanged && !gptCompletedByActionRow && gptTextChangeRecentStopSeen)) {
        lastBridgeMonitorGptActivityAtMs = nowMs
      }

      val gptIdleAgeMs = if (lastBridgeMonitorGptActivityAtMs > 0L) { nowMs - lastBridgeMonitorGptActivityAtMs } else { -1L }
      val gptCompletedByIdleTimeout = previousGptBusy && !gptBusyByStop && !gptBusyByTextChange && !gptCompletedByActionRow && gptIdleAgeMs > bridgeGptIdleCompleteTimeoutMs

      if (gptCompletedByIdleTimeout) {
        appendDebugLog("GPT→TG", "GPT_IDLE_COMPLETE_TIMEOUT active=true ageMs=$gptIdleAgeMs")
      }

      lastBridgeMonitorGptBusy = when {
        gptBusyByStop -> true
        gptCompletedByActionRow -> false
        gptBusyByTextChange -> true
        gptCompletedByIdleTimeout -> false
        else -> previousGptBusy
      }

      val gptBusyAfterDecision = lastBridgeMonitorGptBusy ?: false
      if (previousGptBusy && !gptBusyAfterDecision) {
        appendDebugLog(
          "GPT→TG",
          "GPT_BUSY_FALSE_EDGE previousBusy=$previousGptBusy stop=$gptBusyByStop actionComplete=$gptCompletedByActionRow recentStop=$gptTextChangeRecentStopSeen active=$gptBusyByTextChange idleTimeout=$gptCompletedByIdleTimeout ageMs=$gptTextChangeAgeMs"
        )

        val edgeRoots = collectBridgeMonitorRoots()
        appendDebugLog(
          "GPT→TG",
          "GPT_BUSY_FALSE_EDGE_ROOTS count=${edgeRoots.size}"
        )
        edgeRoots.take(8).forEachIndexed { index, root ->
          val rect = android.graphics.Rect()
          root.getBoundsInScreen(rect)
          appendDebugLog(
            "GPT→TG",
            "GPT_BUSY_FALSE_EDGE_ROOT[$index] package=${root.packageName} class=${root.className} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom} visible=${root.isVisibleToUser} childCount=${root.childCount}"
          )
        }

        if (gptTextChangeAgeMs >= 0L && gptCompletedByActionRow) {
          appendDebugLog(
            "GPT→TG",
            "GPT_TEXT_MISSING_ACTION_COMPLETE ageMs=$gptTextChangeAgeMs actionComplete=$gptCompletedByActionRow previousBusy=$previousGptBusy"
          )
        }
      }
    } else {
      appendDebugLog("WIDGET", "GPT_MONITOR_SKIPPED reason=noGptRoot")
      lastBridgeMonitorGptBusy = false
    }

    val previousTelegramTyping = lastBridgeMonitorTelegramTyping ?: false

    val changed = lastBridgeMonitorGptBusy == null || lastBridgeMonitorTelegramTyping == null ||
      previousGptBusy != (lastBridgeMonitorGptBusy ?: false) ||
      previousTelegramTyping != telegramTyping

    appendDebugLog("WIDGET", "BRIDGE_MONITOR_TICK roots=${allRoots.size} gptStop=${gptRootsForMonitor.isNotEmpty() && hasExactShortGptBusyNodeForDecision(gptRootsForMonitor)} gptTextChanging=${lastBridgeMonitorGptBusy ?: false} telegramTyping=$telegramTyping")

    if (changed) {
      lastBridgeMonitorTelegramTyping = telegramTyping
      saveBridgeStatusForWidget(gptBusy = lastBridgeMonitorGptBusy, telegramTyping = telegramTyping)
    }  }

override fun onServiceConnected() {
    startBridgeStatusMonitor()

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
    stopBridgeStatusMonitor()

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

    private fun hasTelegramAndGptRootsForBridgeNowInternal(): Boolean {
    val allRoots = collectBridgeMonitorRoots()
    val telegramRoots = allRoots.filter { root ->
      val packageName = root.packageName?.toString().orEmpty()
      packageName.lowercase().contains("telegram")
    }
    val gptRoots = allRoots.filter { root ->
      val packageName = root.packageName?.toString().orEmpty()
      packageName.lowercase().contains("openai") || packageName.lowercase().contains("chatgpt")
    }

    appendDebugLog(
      "WIDGET",
      "TG_TO_GPT_ROOT_READY_CHECK all=${allRoots.size} tg=${telegramRoots.size} gpt=${gptRoots.size}"
    )

    return telegramRoots.isNotEmpty() && gptRoots.isNotEmpty()
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
        val cleaned = normalizeTelegramTextForGptPreserveLines(candidate.text)
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

  private fun dedupeCodeTextForTelegramFinal(codeText: String): String {
    val trimmed = codeText.trim()
    if (trimmed.length <= 100) return trimmed

    val beforeLength = trimmed.length

    val halfLen = trimmed.length / 2
    val firstHalf = trimmed.take(halfLen)
    val secondHalf = trimmed.drop(halfLen - (trimmed.length - halfLen)).take(halfLen)

    if (firstHalf == secondHalf) {
      appendDebugLog("GPT→TG", "CODE_FINAL_DEDUP_HALF_MATCH removed=true")
      appendDebugLog("GPT→TG", "CODE_FINAL_DEDUP_DROP preview=${compactLogPreview(firstHalf.take(60))}")
      val result = trimmed.take(halfLen).trim()
      appendDebugLog("GPT→TG", "CODE_FINAL_DEDUP beforeLength=$beforeLength afterLength=${result.length}")
      return result
    }

    val lines = trimmed.lines()
    val dedupedLines = mutableListOf<String>()
    var previousLine = ""
    for (line in lines) {
      val trimmedLine = line.trim()
      if (trimmedLine != previousLine || trimmedLine.isBlank()) {
        dedupedLines.add(line)
      }
      previousLine = trimmedLine
    }

    val result = dedupedLines.joinToString("\n").trim()
    appendDebugLog("GPT→TG", "CODE_FINAL_DEDUP beforeLength=$beforeLength afterLength=${result.length}")
    return result
  }

  private fun normalizeTelegramTextForGptPreserveLines(value: String): String {
    val keptLines = value
      .replace("\r\n", "\n")
      .replace("\r", "\n")
      .lines()
      .map { it.trimEnd() }
      .dropWhile { it.isBlank() }
      .dropLastWhile { it.isBlank() }

    return keptLines.joinToString("\n").trim()
  }

  private fun cleanTelegramTextForGptInputFinal(
    values: List<String>
  ): List<String> {
    val beforeLines = values.flatMap { it.lines() }

    val cleaned = values.mapNotNull { value ->
      val keptLines = value
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filter { !shouldIgnoreMessageText(it) }

      val joined = keptLines.joinToString("\n").trim()
      if (joined.isBlank()) null else joined
    }

    val afterLines = cleaned.flatMap { it.lines() }
    appendDebugLog("TG→GPT", "TG_FINAL_TEXT_CLEAN beforeLines=${beforeLines.size} afterLines=${afterLines.size}")
    appendDebugLog("TG→GPT", "TG_FINAL_TEXT_SELECTED count=${cleaned.size} textLength=${cleaned.joinToString("\n\n").length}")
    return cleaned
  }

  private fun preferBottomTelegramClusterTexts(
    texts: List<String>
  ): List<String> {
    if (texts.size <= 2) return texts
    val normalized = texts.map { it.trim() }.filter { it.isNotBlank() }
    if (normalized.size <= 2) return normalized
    val rawSelected = normalized.takeLast(3)
    appendDebugLog("TG→GPT", "TG_FULL_BOTTOM_CLUSTER before=${normalized.size} after=${rawSelected.size} max=3 preview=${compactLogPreview(rawSelected.joinToString("\\n"))}")
    val cleaned = cleanTelegramTextForGptInputFinal(rawSelected)
    return cleaned
  }

  private fun saveBridgeStatusForWidget(
    gptBusy: Boolean? = null,
    telegramTyping: Boolean? = null
  ) {
    val prefs = getSharedPreferences("copybridge_busy_state", MODE_PRIVATE)

    val currentGptBusy = prefs.getBoolean("gpt_busy", false)
    val currentTelegramTyping = prefs.getBoolean("telegram_typing", false)

    val nextGptBusy = gptBusy ?: currentGptBusy
    val nextTelegramTyping = telegramTyping ?: currentTelegramTyping

    prefs.edit()
      .putBoolean("gpt_busy", nextGptBusy)
      .putBoolean("telegram_typing", nextTelegramTyping)
      .apply()

    appendDebugLog(
      "WIDGET",
      "BUSY_STATUS_SAVE gptBusy=$nextGptBusy telegramTyping=$nextTelegramTyping"
    )

    try {
      val intent = android.content.Intent(this, FloatingWidgetService::class.java).apply {
        action = "com.hwiject.copybridge.REFRESH_WIDGET"
      }
      startService(intent)
    } catch (e: Exception) {
      appendDebugLog(
        "WIDGET",
        "BUSY_STATUS_REFRESH_ERROR ${e.javaClass.simpleName}: ${compactLogPreview(e.message ?: "")}"
      )
    }
  }

  private fun hasExactShortGptBusyNodeForDecision(
    roots: List<AccessibilityNodeInfo>
  ): Boolean {
    val nodes = mutableListOf<AccessibilityNodeInfo>()

    roots.forEach { root ->
      collectExactShortGptBusyNodesForDecision(root, nodes)
    }

    val found = nodes.isNotEmpty()

    appendDebugLog(
      "GPT→TG",
      "GPT_EXACT_BUSY_MATCH found=$found candidates=${nodes.size}"
    )

    nodes.take(8).forEachIndexed { index, node ->
      val label = buildNodeLabel(node).trim()
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)

      appendDebugLog(
        "GPT→TG",
        "GPT_EXACT_BUSY_NODE[$index] label=${compactLogPreview(label)} class=${node.className} clickable=${node.isClickable} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
      )
    }

    return found
  }

  private fun collectExactShortGptBusyNodesForDecision(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return

    val label = buildNodeLabel(node).trim()
    val lower = label.lowercase()

    val isExactBusy =
      label == "중지" ||
      label == "응답 중지" ||
      label == "생성 중지" ||
      lower == "stop" ||
      lower == "stop generating" ||
      lower == "stop responding"

    if (label.length <= 30 && isExactBusy) {
      out.add(node)
    }

    for (i in 0 until node.childCount) {
      collectExactShortGptBusyNodesForDecision(node.getChild(i), out)
    }
  }

  private fun logGptControlAreaSnapshotForDebug(
    roots: List<AccessibilityNodeInfo>
  ): Boolean {
    appendDebugLog(
      "GPT→TG",
      "GPT_CONTROL_SNAPSHOT roots=${roots.size}"
    )

    val nodes = mutableListOf<AccessibilityNodeInfo>()

    roots.forEach { root ->
      val rootRect = android.graphics.Rect()
      root.getBoundsInScreen(rootRect)

      val screenHeight = if (rootRect.height() > 0) {
        rootRect.height()
      } else {
        2400
      }

      collectBottomControlNodesForDebug(
        node = root,
        out = nodes,
        minY = (screenHeight * 0.60f).toInt()
      )
    }

    val deduped = nodes
      .distinctBy { node ->
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        "${buildNodeLabel(node)}|${node.className}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
      }
      .take(40)

    deduped.forEachIndexed { index, node ->
      val label = buildNodeLabel(node)
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)

      appendDebugLog(
        "GPT→TG",
        "GPT_CONTROL_NODE[$index] label=${compactLogPreview(label)} length=${label.length} class=${node.className} clickable=${node.isClickable} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
      )
    }

    val busyCandidates = deduped.filter { node ->
      val label = buildNodeLabel(node).trim()
      val lower = label.lowercase()

      label.length <= 60 && (
        label == "중지" ||
        label == "응답 중지" ||
        label == "생성 중지" ||
        lower == "stop" ||
        lower == "stop generating" ||
        lower == "stop responding"
      )
    }

    appendDebugLog(
      "GPT→TG",
      "GPT_CONTROL_BUSY_MATCH found=${busyCandidates.isNotEmpty()} candidates=${busyCandidates.size}"
    )

    return busyCandidates.isNotEmpty()
  }

  private fun collectBottomControlNodesForDebug(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>,
    minY: Int
  ) {
    if (node == null) return

    val rect = android.graphics.Rect()
    node.getBoundsInScreen(rect)

    val label = buildNodeLabel(node).trim()

    val isBottomArea = rect.bottom >= minY
    val isUsefulLabel = label.isNotBlank() && label.length <= 120

    if (isBottomArea && isUsefulLabel) {
      out.add(node)
    }

    for (i in 0 until node.childCount) {
      collectBottomControlNodesForDebug(node.getChild(i), out, minY)
    }
  }

  private fun scanGptBusyIndicatorForDebug(
    roots: List<AccessibilityNodeInfo>
  ): Boolean {
    appendDebugLog(
      "GPT→TG",
      "GPT_BUSY_SCAN roots=${roots.size}"
    )

    val busyCandidates = mutableListOf<AccessibilityNodeInfo>()
    val copyLikeCandidates = mutableListOf<AccessibilityNodeInfo>()

    roots.forEach { root ->
      collectGptBusyCandidates(root, busyCandidates)
      collectGptCopyLikeCandidatesForDebug(root, copyLikeCandidates)
    }

    val found = busyCandidates.isNotEmpty()

    appendDebugLog(
      "GPT→TG",
      "GPT_BUSY_FOUND found=$found candidates=${busyCandidates.size} copyLike=${copyLikeCandidates.size}"
    )

    appendDebugLog(
      "GPT→TG",
      "GPT_COPY_LIKE_COUNT count=${copyLikeCandidates.size}"
    )

    busyCandidates.take(12).forEachIndexed { index, node ->
      val label = buildNodeLabel(node)
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)

      appendDebugLog(
        "GPT→TG",
        "GPT_BUSY_CANDIDATE[$index] label=${compactLogPreview(label)} class=${node.className} clickable=${node.isClickable} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
      )
    }

    copyLikeCandidates.take(12).forEachIndexed { index, node ->
      val label = buildNodeLabel(node)
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)

      appendDebugLog(
        "GPT→TG",
        "GPT_COPY_LIKE_CANDIDATE[$index] label=${compactLogPreview(label)} class=${node.className} clickable=${node.isClickable} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
      )
    }

    return found
  }

  private fun collectGptBusyCandidates(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return

    val label = buildNodeLabel(node).trim()
    val lower = label.lowercase()

    if (
      label.contains("중지") ||
      label.contains("응답 중지") ||
      label.contains("생성 중") ||
      lower.contains("stop generating") ||
      lower == "stop" ||
      lower.contains("generating")
    ) {
      out.add(node)
    }

    for (i in 0 until node.childCount) {
      collectGptBusyCandidates(node.getChild(i), out)
    }
  }

  private fun collectGptCopyLikeCandidatesForDebug(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return

    val label = buildNodeLabel(node).trim()
    val lower = label.lowercase()

    if (
      label == "복사" ||
      label == "코드 복사" ||
      lower == "copy" ||
      lower.contains("copy code") ||
      lower.contains("copy")
    ) {
      out.add(node)
    }

    for (i in 0 until node.childCount) {
      collectGptCopyLikeCandidatesForDebug(node.getChild(i), out)
    }
  }

  private fun hasExactTelegramTypingTopBarNodeForDecision(
    roots: List<AccessibilityNodeInfo>
  ): Boolean {
    val nodes = mutableListOf<AccessibilityNodeInfo>()

    roots.forEach { root ->
      val rootRect = android.graphics.Rect()
      root.getBoundsInScreen(rootRect)

      val screenHeight = if (rootRect.height() > 0) {
        rootRect.height()
      } else {
        2400
      }

      collectExactTelegramTypingTopNodesForDecision(
        node = root,
        out = nodes,
        maxY = rootRect.top + (screenHeight * 0.28f).toInt()
      )
    }

    val found = nodes.isNotEmpty()

    appendDebugLog(
      "TG→GPT",
      "TELEGRAM_EXACT_TYPING_MATCH found=$found candidates=${nodes.size}"
    )

    nodes.take(8).forEachIndexed { index, node ->
      val label = buildNodeLabel(node).trim()
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)

      appendDebugLog(
        "TG→GPT",
        "TELEGRAM_EXACT_TYPING_NODE[$index] label=${compactLogPreview(label)} class=${node.className} clickable=${node.isClickable} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
      )
    }

    return found
  }

  private fun collectExactTelegramTypingTopNodesForDecision(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>,
    maxY: Int
  ) {
    if (node == null) return

    val rect = android.graphics.Rect()
    node.getBoundsInScreen(rect)

    val label = buildNodeLabel(node).trim()
    val lower = label.lowercase()

    val isTopArea = rect.top >= 0 && rect.top <= maxY
    val isExactTyping =
      label == "입력 중" ||
      label.endsWith("입력 중") ||
      lower == "typing" ||
      lower.endsWith("typing")

    if (isTopArea && label.length <= 40 && isExactTyping) {
      out.add(node)
    }

    for (i in 0 until node.childCount) {
      collectExactTelegramTypingTopNodesForDecision(node.getChild(i), out, maxY)
    }
  }

  private fun logTelegramTopBarSnapshotForDebug(
    roots: List<AccessibilityNodeInfo>
  ): Boolean {
    appendDebugLog(
      "TG→GPT",
      "TELEGRAM_TOPBAR_SNAPSHOT roots=${roots.size}"
    )

    val nodes = mutableListOf<AccessibilityNodeInfo>()

    roots.forEach { root ->
      val rootRect = android.graphics.Rect()
      root.getBoundsInScreen(rootRect)

      val screenHeight = if (rootRect.height() > 0) {
        rootRect.height()
      } else {
        2400
      }

      collectTopAreaNodesForDebug(
        node = root,
        out = nodes,
        maxY = rootRect.top + (screenHeight * 0.28f).toInt()
      )
    }

    val deduped = nodes
      .distinctBy { node ->
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        "${buildNodeLabel(node)}|${node.className}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
      }
      .take(30)

    deduped.forEachIndexed { index, node ->
      val label = buildNodeLabel(node)
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)
      appendDebugLog(
        "TG→GPT",
        "TELEGRAM_TOPBAR_NODE[$index] label=${compactLogPreview(label)} length=${label.length} class=${node.className} clickable=${node.isClickable} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
      )
    }

    val typingCandidates = deduped.filter { node ->
      val label = buildNodeLabel(node).trim()
      label.length <= 40 && (
        label == "입력 중" ||
        label.contains("입력 중") ||
        label.lowercase() == "typing" ||
        label.lowercase().contains("typing")
      )
    }

    appendDebugLog(
      "TG→GPT",
      "TELEGRAM_TOPBAR_TYPING_MATCH found=${typingCandidates.isNotEmpty()} candidates=${typingCandidates.size}"
    )

    return typingCandidates.isNotEmpty()
  }

  private fun collectTopAreaNodesForDebug(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>,
    maxY: Int
  ) {
    if (node == null) return

    val rect = android.graphics.Rect()
    node.getBoundsInScreen(rect)

    val label = buildNodeLabel(node).trim()

    val isTopArea = rect.top >= 0 && rect.top <= maxY
    val isUsefulLabel = label.isNotBlank() && label.length <= 120

    if (isTopArea && isUsefulLabel) {
      out.add(node)
    }

    for (i in 0 until node.childCount) {
      collectTopAreaNodesForDebug(node.getChild(i), out, maxY)
    }
  }

  private fun scanTelegramTypingIndicatorForDebug(
    roots: List<AccessibilityNodeInfo>
  ): Boolean {
    appendDebugLog(
      "TG→GPT",
      "TELEGRAM_TYPING_SCAN roots=${roots.size}"
    )

    val candidates = mutableListOf<AccessibilityNodeInfo>()

    roots.forEach { root ->
      collectTelegramTypingCandidates(root, candidates)
    }

    val found = candidates.isNotEmpty()

    appendDebugLog(
      "TG→GPT",
      "TELEGRAM_TYPING_FOUND found=$found candidates=${candidates.size}"
    )

    candidates.take(8).forEachIndexed { index, node ->
      val label = buildNodeLabel(node)
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)

      appendDebugLog(
        "TG→GPT",
        "TELEGRAM_TYPING_CANDIDATE[$index] label=${compactLogPreview(label)} class=${node.className} clickable=${node.isClickable} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
      )
    }

    return found
  }

  private fun collectTelegramTypingCandidates(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return

    val label = buildNodeLabel(node).trim()
    val lower = label.lowercase()

    if (
      label.contains("입력 중") ||
      lower.contains("typing") ||
      lower.contains("is typing")
    ) {
      out.add(node)
    }

    for (i in 0 until node.childCount) {
      collectTelegramTypingCandidates(node.getChild(i), out)
    }
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
        val cleaned = normalizeTelegramTextForGptPreserveLines(candidate.text)
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
    val trimmed = text.trim()

    if (trimmed.length <= 2) return true

    val exactUiTexts = setOf(
      "telegram", "copybridge", "bridge",
      "메시지", "뽃", "뽃 메뉴", "돌아가기",
      "안 읽은 메시지", "프로필 사진", "icon 프로필 사진 설정",
      "이메지, 스티커 및 gif", "미디어 첨부", "음성 메시지 녹음", "옵션 더 보기",
      "전송: 켈", "전송: 끁",
      "테레그램 답바복사", "테레그램으로 전송"
    )
    if (lower in exactUiTexts) return true

    if (trimmed.all { it == '-' || it == '=' || it == '_' || it == '~' || it == '*' }) return true

    val timePat = Regex("^\\s*(\\d{1,2}):(\\d{2})(:(\\d{2}))?\\s*(\\uC624\\uC804|\\uC624\\uD6C4|AM|PM|am|pm)?\\s*$")
    if (timePat.matches(trimmed)) return true

    val sepPat = Regex("^[\\-\\=\\_\\~\\*\\.\\s]{3,}$")
    if (sepPat.matches(trimmed)) return true

    val partialIgnores = listOf(
      "온라인", "오프라인", "접속", "마지막으로 본",
      "last seen", "online", "offline",
      "저장한 메시지", "채널", "그룹",
      "초\\b", "분 전", "시간 전", "일 전",
      "방금", "조회수", "읽음",
      "CopyBridge 로그를 클립보드에 복사했습니다",
      "GPT/TG 전송 테스트 결과가 여기에 누적됩니다"
    )
    if (partialIgnores.any { lower.contains(it) }) return true

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
    val body = lines
      .map { normalizeTelegramTextForGptPreserveLines(it) }
      .filter { it.isNotBlank() }
      .joinToString("\n\n")
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
    } else if (gptOutputMode == "FULL") {
      val gestureClicked = tapNodeCenterByGesture(target.node, "FULL_COPY_BUTTON")
      appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE fullGestureClicked=$gestureClicked")
      if (gestureClicked) { true } else {
        val fallbackClicked = clickNodeOrClickableParent(target.node)
        appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE fullFallbackClick=$fallbackClicked")
        fallbackClicked
      }
    } else {
      val defaultClicked = clickNodeOrClickableParent(target.node)
      appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE defaultClick=$defaultClicked")
      defaultClicked
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

    if (gptOutputMode == "FULL") {
      val copiedTextBeforePaste = readClipboardText()
      val dedupedTextBeforePaste = dedupeTelegramSendTextFinal(copiedTextBeforePaste)

      if (copiedTextBeforePaste.isBlank()) {
        appendDebugLog(
          "GPT→TG",
          "COPY_BUTTON_FULL_BLOCKED reason=blankClipboard"
        )
        Toast.makeText(this, "GPT 전체 복사에 실패했습니다. 전송하지 않았습니다.", Toast.LENGTH_SHORT).show()
        return true
      }

      if (dedupedTextBeforePaste.length < MIN_FULL_COPY_BUTTON_TEXT_LENGTH) {
        appendDebugLog(
          "GPT→TG",
          "COPY_BUTTON_FULL_BLOCKED reason=tooShort length=${dedupedTextBeforePaste.length} min=$MIN_FULL_COPY_BUTTON_TEXT_LENGTH preview=${compactLogPreview(dedupedTextBeforePaste)}"
        )
        Toast.makeText(this, "GPT 전체 복사 결과가 너무 짧아 전송하지 않았습니다.", Toast.LENGTH_SHORT).show()
        return true
      }

      if (
        dedupedTextBeforePaste.isNotBlank() &&
        dedupedTextBeforePaste != copiedTextBeforePaste
      ) {
        appendDebugLog(
          "GPT→TG",
          "COPY_BUTTON_FULL_DEDUP before=${copiedTextBeforePaste.length} after=${dedupedTextBeforePaste.length}"
        )
        copyToClipboard(dedupedTextBeforePaste)
      }
    }

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

  private fun pickLatestGptAnswerTextForFullFallback(
    roots: List<android.view.accessibility.AccessibilityNodeInfo>
  ): String {
    appendDebugLog("GPT→TG", "GPT_LATEST_ANSWER_PICK_START roots=${roots.size}")

    val actionCandidates = mutableListOf<Pair<String, android.graphics.Rect>>()
    val textCandidates = mutableListOf<Pair<String, android.graphics.Rect>>()

    roots.forEach { root ->
      collectGptCompletionActionNodesForDecision(root, actionCandidates)
      collectGptAnswerTextNodesForDecision(root, textCandidates)
    }

    if (textCandidates.isEmpty()) {
      appendDebugLog("GPT→TG", "GPT_LATEST_ANSWER_PICK_FALLBACK reason=noTextCandidates")
      return ""
    }

    val actionTop = if (actionCandidates.isNotEmpty()) {
      val maxTop = actionCandidates.maxOf { it.second.top }
      val bottomBand = actionCandidates.filter { kotlin.math.abs(it.second.top - maxTop) <= 80 }
      if (bottomBand.size >= 3) bottomBand.minOf { it.second.top } else Int.MAX_VALUE
    } else { Int.MAX_VALUE }

    val filteredTexts = textCandidates.filter { item ->
      val text = item.first.trim()
      val rect = item.second
      val isBeforeAction = actionTop == Int.MAX_VALUE || rect.bottom <= actionTop + 40
      val isUsable = text.length >= 10 && rect.top > 80 && rect.bottom > rect.top &&
        !text.contains("CopyBridge 로그를 클립보드") &&
        !text.contains("SKTelecom") && !text.contains("신호가 강합니다") &&
        text != "CopyBridge CopyBridge"
      isBeforeAction && isUsable
    }

    if (filteredTexts.isEmpty()) {
      appendDebugLog("GPT→TG", "GPT_LATEST_ANSWER_PICK_FALLBACK reason=noFilteredTextCandidates actionTop=$actionTop textCandidates=${textCandidates.size}")
      return ""
    }

    val latestBottom = filteredTexts.maxOf { it.second.bottom }
    val latestBand = filteredTexts.filter { kotlin.math.abs(it.second.bottom - latestBottom) <= 900 }.sortedBy { it.second.top }
    val selected = latestBand.joinToString("\n\n") { it.first.trim() }.trim()

    appendDebugLog("GPT→TG", "GPT_LATEST_ANSWER_PICK actionTop=$actionTop textCandidates=${textCandidates.size} filtered=${filteredTexts.size} selected=${latestBand.size}")

    latestBand.takeLast(6).forEachIndexed { index, item ->
      val rect = item.second
      appendDebugLog("GPT→TG", "GPT_LATEST_ANSWER_PICK_NODE[$index] length=${item.first.length} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom} preview=${compactLogPreview(item.first)}")
    }

    appendDebugLog("GPT→TG", "GPT_LATEST_ANSWER_PICK_SELECTED textLength=${selected.length}")
    return selected
  }

  private fun collectLatestAiAnswerFallbackTexts(
    roots: List<AccessibilityNodeInfo>
  ): List<String> {
    val latestPickedText = pickLatestGptAnswerTextForFullFallback(roots)
    if (latestPickedText.isNotBlank()) {
      return listOf(latestPickedText)
    }

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

  private fun logAiRootsForTgToGptDebug(
    label: String,
    roots: List<AccessibilityNodeInfo>
  ) {
    roots.take(6).forEachIndexed { index, root ->
      val rect = Rect()
      root.getBoundsInScreen(rect)
      appendDebugLog(
        "TG→GPT",
        "$label[$index] package=${root.packageName} class=${root.className} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom} visible=${root.isVisibleToUser} childCount=${root.childCount}"
      )
    }
  }

  private fun getAiRootsWithRetryForTgToGpt(
    label: String,
    maxAttempts: Int = TG_TO_GPT_FIND_RETRY_COUNT,
    delayMs: Long = TG_TO_GPT_FIND_RETRY_DELAY_MS
  ): List<AccessibilityNodeInfo> {
    var latestRoots = emptyList<AccessibilityNodeInfo>()

    for (attempt in 1..maxAttempts) {
      latestRoots = getAiRoots()

      appendDebugLog(
        "TG→GPT",
        "$label attempt=$attempt roots=${latestRoots.size}"
      )

      if (latestRoots.isNotEmpty()) {
        logAiRootsForTgToGptDebug("${label}_ROOT", latestRoots)
        return latestRoots
      }

      if (attempt < maxAttempts) {
        android.os.SystemClock.sleep(delayMs)
      }
    }

    return latestRoots
  }

  private fun findAiEditableWithRetryForTgToGpt(
    initialRoots: List<AccessibilityNodeInfo>,
    maxAttempts: Int = TG_TO_GPT_FIND_RETRY_COUNT,
    delayMs: Long = TG_TO_GPT_FIND_RETRY_DELAY_MS
  ): Pair<AccessibilityNodeInfo?, List<AccessibilityNodeInfo>> {
    var latestRoots = initialRoots

    for (attempt in 1..maxAttempts) {
      if (latestRoots.isEmpty()) {
        latestRoots = getAiRoots()
      }

      val editNode = findEditableNodeFromRoots(latestRoots)

      appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_FIND_EDIT attempt=$attempt roots=${latestRoots.size} found=${editNode != null}"
      )

      if (editNode != null) {
        return editNode to latestRoots
      }

      if (attempt < maxAttempts) {
        android.os.SystemClock.sleep(delayMs)
        latestRoots = getAiRoots()
      }
    }

    return null to latestRoots
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
    private const val MIN_FULL_COPY_BUTTON_TEXT_LENGTH = 500
    private const val MIN_CODE_SEND_TEXT_LENGTH = 20
    private const val TG_TO_GPT_FIND_RETRY_COUNT = 5
    private const val TG_TO_GPT_FIND_RETRY_DELAY_MS = 250L
    private const val AUTO_SEND_FIRST_RETRY_DELAY_MS = 250L
    private const val AUTO_SEND_SECOND_RETRY_DELAY_MS = 150L
    const val COPY_MODE_FULL = "FULL"
    const val COPY_MODE_LAST = "LAST"
    private var activeService: CopyBridgeAccessibilityService? = null

    fun isServiceActive(): Boolean = activeService != null

    fun hasTelegramAndGptRootsForBridgeNow(): Boolean {
      val service = activeService ?: return false
      return service.hasTelegramAndGptRootsForBridgeNowInternal()
    }

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

      service.appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_START copyMode=$copyMode autoSend=$autoSend"
      )

      val tgRoots = service.getTelegramRoots()
      if (tgRoots.isEmpty()) {
        Toast.makeText(context, "Telegram 채팅방을 화면에 열어주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

      service.appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_TG_ROOTS count=${tgRoots.size}"
      )
      val telegramTypingByTopBar = service.logTelegramTopBarSnapshotForDebug(tgRoots)
      var telegramTypingDecision = telegramTypingByTopBar

      if (!telegramTypingDecision) {
        service.appendDebugLog(
          "TG→GPT",
          "TELEGRAM_TYPING_RECHECK_START delayMs=300"
        )

        try {
          Thread.sleep(300)
        } catch (_: InterruptedException) {
        }

        val tgRootsForRecheck = service.getTelegramRoots()
        val telegramTypingByTopBarRecheck = service.logTelegramTopBarSnapshotForDebug(tgRootsForRecheck)
        val telegramTypingByExactRecheck = service.hasExactTelegramTypingTopBarNodeForDecision(tgRootsForRecheck)

        telegramTypingDecision = telegramTypingByTopBarRecheck || telegramTypingByExactRecheck

        service.appendDebugLog(
          "TG→GPT",
          "TELEGRAM_TYPING_RECHECK topbar=$telegramTypingByTopBarRecheck exact=$telegramTypingByExactRecheck roots=${tgRootsForRecheck.size}"
        )
      }

      service.appendDebugLog(
        "TG→GPT",
        "TELEGRAM_TYPING_DECISION source=topbar+recheck value=$telegramTypingDecision broadIgnored=true"
      )
      service.saveBridgeStatusForWidget(telegramTyping = telegramTypingDecision)

      if (telegramTypingDecision) {
        service.appendDebugLog(
          "TG→GPT",
          "TG_TO_GPT_BLOCKED reason=telegramTyping source=topbar+recheck"
        )
        return false
      }

      service.scanTelegramTypingIndicatorForDebug(tgRoots)

      val candidates = service.collectTelegramMessageCandidates(tgRoots)

      service.appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_CANDIDATES count=${candidates.size}"
      )

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

      service.appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_SELECTED count=${selectedTexts.size} textLength=${textToSend.length} preview=${service.compactLogPreview(textToSend)}"
      )

      val aiRoots = service.getAiRootsWithRetryForTgToGpt("TG_TO_GPT_AI_ROOTS")
      if (aiRoots.isEmpty()) {
        service.copyToClipboard(textToSend)
        service.appendDebugLog(
          "TG→GPT",
          "TG_TO_GPT_BLOCKED reason=noAiRootsAfterRetry textLength=${textToSend.length}"
        )
        Toast.makeText(
          context,
          "GPT 화면을 찾지 못했습니다. 텍스트는 복사되었습니다. GPT 화면을 한 번 터치한 뒤 다시 시도해주세요.",
          Toast.LENGTH_LONG
        ).show()
        return true
      }

      val freshInputRoots = service.getAiRootsWithRetryForTgToGpt("TG_TO_GPT_FRESH_AI_ROOTS")
      val rootsForEdit = if (freshInputRoots.isNotEmpty()) freshInputRoots else aiRoots

      val editResult = service.findAiEditableWithRetryForTgToGpt(rootsForEdit)
      val aiEdit = editResult.first

      service.appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_AI_EDIT_FOUND value=${aiEdit != null} roots=${editResult.second.size}"
      )

      if (aiEdit == null) {
        service.copyToClipboard(textToSend)
        service.appendDebugLog(
          "TG→GPT",
          "TG_TO_GPT_BLOCKED reason=noAiEditAfterRetry textLength=${textToSend.length}"
        )
        Toast.makeText(
          context,
          "GPT 입력창을 찾지 못했습니다. 텍스트는 복사되었습니다. GPT 입력창을 한 번 터치한 뒤 다시 시도해주세요.",
          Toast.LENGTH_LONG
        ).show()
        return true
      }

      val setTextOk = service.setTextToNode(aiEdit, textToSend)

      service.appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_SET_TEXT result=$setTextOk textLength=${textToSend.length}"
      )

      val inputOk = if (setTextOk) {
        true
      } else {
        service.copyToClipboard(textToSend)

        val clearOk = service.setTextToNode(aiEdit, "")
        service.appendDebugLog(
          "TG→GPT",
          "TG_TO_GPT_CLEAR_BEFORE_PASTE result=$clearOk"
        )

        if (!clearOk) {
          service.appendDebugLog(
            "TG→GPT",
            "TG_TO_GPT_PASTE_BLOCKED reason=clearFailed textLength=${textToSend.length}"
          )
          Toast.makeText(
            context,
            "GPT 입력창 초기화에 실패했습니다. 텍스트는 복사되었습니다. 입력창을 비운 뒤 다시 눌러주세요.",
            Toast.LENGTH_LONG
          ).show()
          return true
        }

        aiEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        aiEdit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        android.os.SystemClock.sleep(200L)

        val pasteOk = service.pasteClipboardToNode(aiEdit)

        service.appendDebugLog(
          "TG→GPT",
          "TG_TO_GPT_PASTE_FALLBACK result=$pasteOk textLength=${textToSend.length}"
        )

        pasteOk
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
        service.appendDebugLog(
          "TG→GPT",
          "TG_TO_GPT_BLOCKED reason=inputFailedAfterSetTextAndPaste"
        )
        Toast.makeText(
          context,
          "GPT 입력에 실패했습니다. 진단 정보가 복사되었습니다. GPT 입력창을 한 번 터치한 뒤 다시 시도해주세요.",
          Toast.LENGTH_LONG
        ).show()
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

  // DEDUP_TELEGRAM_SEND_TEXT_FINAL
  private fun dedupeTelegramSendTextFinal(raw: String): String {
    val original = raw.trim()
    if (original.length < 80) return raw

    fun normalize(value: String): String =
      value.trim().replace(Regex("\\s+"), " ")

    val rawBlocks =
      original
        .split(Regex("\\n\\s*\\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    if (rawBlocks.size >= 2 && rawBlocks.size % 2 == 0) {
      val half = rawBlocks.size / 2
      val firstHalf = rawBlocks.take(half).map(::normalize)
      val secondHalf = rawBlocks.drop(half).map(::normalize)
      if (firstHalf == secondHalf && rawBlocks.take(half).joinToString("\n\n").length >= 80) {
        return rawBlocks.take(half).joinToString("\n\n").trim()
      }
    }

    var blockChanged = false
    val dedupedBlocks = mutableListOf<String>()

    for (block in rawBlocks) {
      val prev = dedupedBlocks.lastOrNull()
      val isAdjacentDuplicate = prev != null && block.length >= 30 && normalize(prev) == normalize(block)
      if (isAdjacentDuplicate) { blockChanged = true } else { dedupedBlocks.add(block) }
    }

    val blockText = if (blockChanged) { dedupedBlocks.joinToString("\n\n").trim() } else { original }

    val lines = blockText.lines()
    val dedupedLines = mutableListOf<String>()
    var lineChanged = false

    for (line in lines) {
      val prev = dedupedLines.lastOrNull()
      val isAdjacentDuplicateLine = prev != null && line.trim().length >= 30 && normalize(prev) == normalize(line)
      if (isAdjacentDuplicateLine) { lineChanged = true } else { dedupedLines.add(line) }
    }

    return if (lineChanged) { dedupedLines.joinToString("\n").trim() } else { blockText }
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
      val gptBusyByControlArea = service.logGptControlAreaSnapshotForDebug(aiRoots)
      var gptBusyDecision = gptBusyByControlArea

      if (!gptBusyDecision) {
        service.appendDebugLog(
          "GPT→TG",
          "GPT_BUSY_RECHECK_START delayMs=300"
        )

        try {
          Thread.sleep(300)
        } catch (_: InterruptedException) {
        }

        val aiRootsForRecheck = service.getAiRoots()
        val gptBusyByControlAreaRecheck = service.logGptControlAreaSnapshotForDebug(aiRootsForRecheck)
        val gptBusyByExactRecheck = service.hasExactShortGptBusyNodeForDecision(aiRootsForRecheck)

        gptBusyDecision = gptBusyByControlAreaRecheck || gptBusyByExactRecheck

        service.appendDebugLog(
          "GPT→TG",
          "GPT_BUSY_RECHECK controlArea=$gptBusyByControlAreaRecheck exact=$gptBusyByExactRecheck roots=${aiRootsForRecheck.size}"
        )
      }

      service.appendDebugLog(
        "GPT→TG",
        "GPT_BUSY_DECISION source=controlArea+recheck value=$gptBusyDecision broadIgnored=true"
      )
      service.saveBridgeStatusForWidget(gptBusy = gptBusyDecision)

      if (gptBusyDecision) {
        service.appendDebugLog(
          "GPT→TG",
          "GPT_TO_TG_BLOCKED reason=gptBusy source=controlArea+recheck"
        )
        return false
      }

      service.scanGptBusyIndicatorForDebug(aiRoots)
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
            val buttonCopyText = try {
        service.appendDebugLog(
          "GPT\u2192TG",
          "STEP try copyButton polling for FULL"
        )
        service.copyGptOutputByChatGptButton(gptOutputMode, aiRoots)
      } catch (error: Exception) {
        service.appendDebugLog(
          "GPT\u2192TG",
          "EXCEPTION copyButton ${error::class.java.simpleName}: ${error.message}"
        )
        null
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

          val finalFull = if (finalCodeBlocks.isNotEmpty()) {
            finalCodeBlocks.last().trim()
          } else {
            finalTexts.joinToString("\n")
          }

          textToSend = service.clampCopyText(finalFull)
          service.appendDebugLog(
            "GPT→TG",
            "CODE_FINAL_FALLBACK source=${if (finalCodeBlocks.isNotEmpty()) "codeBlocks" else "visibleTexts"} textLength=${textToSend.length}"
          )
        }
          // CODE_EMPTY_FALLBACK restored with short-text guard

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
      // CODE dedupe call removed in 214: restore stable CODE transfer


        if (gptOutputMode == "CODE" && textToSend.trim().length < MIN_CODE_SEND_TEXT_LENGTH) {
          service.appendDebugLog(
            "GPT→TG",
            "CODE_SEND_BLOCKED reason=tooShort length=${textToSend.trim().length} min=$MIN_CODE_SEND_TEXT_LENGTH preview=${service.compactLogPreview(textToSend)}"
          )
          Toast.makeText(context, "코드 내용이 너무 짧아 전송하지 않았습니다.", Toast.LENGTH_SHORT).show()
          return false
        }


      
      val beforeTelegramFinalDedupeLength = textToSend.length
      textToSend = dedupeTelegramSendTextFinal(textToSend)
      if (textToSend.length != beforeTelegramFinalDedupeLength) {
        service.appendDebugLog(
          "GPT→TG",
          "DEDUP_TELEGRAM_SEND_TEXT_FINAL before=$beforeTelegramFinalDedupeLength after=${textToSend.length}"
        )
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

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
  private var bridgeGptInsufficientActionRowStreak = 0
  private var bridgeLastGptInsufficientActionRowAtMs: Long = 0L
  private val bridgeStatusMonitorRunnable = object : Runnable {
    override fun run() {
      runBridgeMonitorStatusTick()
      if (bridgeStatusMonitorRunning) {
        bridgeStatusMonitorHandler.postDelayed(this, 1000L)
      }
    }
  }

  private var lastPackageName: String? = null
  private var lastYouTubeSkipSourceScanAtMs = 0L
  private var lastYouTubeSkipFullScanAtMs = 0L
  private var lastYouTubeSkipClickAtMs = 0L
  private var pendingGptToTelegram: PendingGptToTelegramRequest? = null
  private var pendingGptIdleChecks = 0
  private val pendingGptToTelegramHandler = Handler(Looper.getMainLooper())
  private val pendingGptToTelegramRunnable = object : Runnable {
    override fun run() {
      runPendingGptToTelegramCheck()
    }
  }
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

  private fun getInteractiveWindowsAcrossAllDisplays():
    List<android.view.accessibility.AccessibilityWindowInfo> {
    val result = mutableListOf<android.view.accessibility.AccessibilityWindowInfo>()

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val allDisplays = windowsOnAllDisplays

        for (index in 0 until allDisplays.size()) {
          result.addAll(allDisplays.valueAt(index))
        }
      } else {
        result.addAll(windows)
      }
    } catch (error: Exception) {
      appendDebugLog(
        "A11Y",
        "ALL_DISPLAY_WINDOWS_ERROR ${error::class.java.simpleName}: ${compactLogPreview(error.message.orEmpty())}"
      )

      try {
        result.addAll(windows)
      } catch (_: Exception) {}
    }

    return result.distinctBy { window ->
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        "${window.displayId}:${window.id}"
      } else {
        window.id.toString()
      }
    }
  }

  private fun collectInteractiveRootsAcrossAllDisplays(): List<AccessibilityNodeInfo> {
    val result = mutableListOf<AccessibilityNodeInfo>()
    val seen = mutableSetOf<String>()

    fun addRoot(root: AccessibilityNodeInfo?) {
      if (root == null) return

      val rect = Rect()
      try {
        root.getBoundsInScreen(rect)
      } catch (_: Exception) {}

      val key =
        "${root.windowId}|${root.packageName}|${root.className}|" +
          "${rect.left},${rect.top},${rect.right},${rect.bottom}"

      if (seen.add(key)) {
        result.add(root)
      }
    }

    try {
      addRoot(rootInActiveWindow)
    } catch (_: Exception) {}

    getInteractiveWindowsAcrossAllDisplays().forEach { window ->
      try {
        addRoot(window.root)
      } catch (_: Exception) {}
    }

    return result
  }

  private fun collectBridgeMonitorRoots(): List<android.view.accessibility.AccessibilityNodeInfo> {
    return collectInteractiveRootsAcrossAllDisplays()
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
      if (false) { appendDebugLog("GPT→TG", "GPT_TEXT_CHANGE_NODE[$index] length=${item.first.length} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom} preview=${compactLogPreview(item.first)}") }
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
      resetGptInsufficientActionRowForKeyboardCleanup("noActionCandidates")

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
      recordGptInsufficientActionRowForKeyboardCleanup(
        candidates = actionCandidates.size,
        bottomBand = bottomBand.size,
        labels = uniqueLabels
      )

      appendDebugLog(
        "GPT→TG",
        "GPT_ACTION_ROW_MATCH found=false reason=insufficientActionRow candidates=${actionCandidates.size} bottomBand=${bottomBand.size} labels=${uniqueLabels.joinToString("|")}"
      )

      bottomBand.take(8).forEachIndexed { index, item ->
        val rect = item.second
        if (false) { appendDebugLog("GPT→TG", "GPT_ACTION_ROW_NODE[$index] label=${compactLogPreview(item.first)} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}") }
      }

      return false
    }

    resetGptInsufficientActionRowForKeyboardCleanup("enoughActionRow")

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
        if (false) { appendDebugLog("GPT→TG", "GPT_ACTION_ROW_NODE[$index] label=${compactLogPreview(item.first)} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}") }
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

    if (false) {
      appendDebugLog(
        "GPT→TG",
        "GPT_ACTION_ROW_RELATION latestBottom=${latestTextRect.bottom} actionTop=$actionTop actionBottom=$actionBottom gap=$gap newerTextBelowAction=$newerTextBelowAction latestPreview=${compactLogPreview(latestTextPreview)}"
      )
    }

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
      if (false) { appendDebugLog("GPT→TG", "GPT_ACTION_ROW_NODE[$index] label=${compactLogPreview(item.first)} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}") }
    }

    return found
  }

  private fun recordGptInsufficientActionRowForKeyboardCleanup(
    candidates: Int,
    bottomBand: Int,
    labels: Set<String>
  ) {
    val nowMs = System.currentTimeMillis()
    val recent = bridgeLastGptInsufficientActionRowAtMs > 0L &&
      nowMs - bridgeLastGptInsufficientActionRowAtMs <= 2500L
    bridgeGptInsufficientActionRowStreak = if (recent) {
      bridgeGptInsufficientActionRowStreak + 1
    } else { 1 }
    bridgeLastGptInsufficientActionRowAtMs = nowMs
    appendDebugLog("GPT→TG", "GPT_INSUFFICIENT_ACTION_ROW_STREAK streak=$bridgeGptInsufficientActionRowStreak candidates=$candidates bottomBand=$bottomBand labels=${labels.joinToString("|")}")
  }

  private fun resetGptInsufficientActionRowForKeyboardCleanup(reason: String) {
    if (bridgeGptInsufficientActionRowStreak > 0) {
      appendDebugLog("GPT→TG", "GPT_INSUFFICIENT_ACTION_ROW_STREAK_RESET reason=$reason previousStreak=$bridgeGptInsufficientActionRowStreak")
    }
    bridgeGptInsufficientActionRowStreak = 0
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
      if (false) { appendDebugLog("WIDGET", "BRIDGE_MONITOR_TICK roots=0 skipped=true") }

      return
    }

    val allRoots = collectBridgeMonitorRoots()
    val tgRootsForMonitor = collectTelegramMonitorRoots()
    val gptRootsForMonitor = collectGptMonitorRoots()

    if (false) { appendDebugLog("WIDGET", "BRIDGE_MONITOR_ROOTS all=${allRoots.size} tg=${tgRootsForMonitor.size} gpt=${gptRootsForMonitor.size}") }

    if (false && (tgRootsForMonitor.isEmpty() || gptRootsForMonitor.isEmpty())) {
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
      if (false) { appendDebugLog("WIDGET", "TELEGRAM_MONITOR_SKIPPED reason=noTelegramRoot") }
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
      val nowMs = System.currentTimeMillis()
      val stopAgeMs = if (lastBridgeMonitorGptStopSeenAtMs > 0L) {
        nowMs - lastBridgeMonitorGptStopSeenAtMs
      } else {
        -1L
      }
      val activityAgeMs = if (lastBridgeMonitorGptActivityAtMs > 0L) {
        nowMs - lastBridgeMonitorGptActivityAtMs
      } else {
        -1L
      }
      val recentStopSeen = stopAgeMs in 0L..bridgeGptRecentStopHoldMs
      val recentActivitySeen = activityAgeMs in 0L..bridgeGptRecentStopHoldMs

      if (previousGptBusy && (recentStopSeen || recentActivitySeen)) {
        lastBridgeMonitorGptBusy = true
        appendDebugLog(
          "GPT→TG",
          "GPT_MONITOR_MISSING_ROOT_KEEP_BUSY previousBusy=$previousGptBusy recentStop=$recentStopSeen stopAgeMs=$stopAgeMs recentActivity=$recentActivitySeen activityAgeMs=$activityAgeMs"
        )
      } else {
        if (previousGptBusy) {
          appendDebugLog(
            "GPT→TG",
            "GPT_MONITOR_MISSING_ROOT_CLEAR_BUSY previousBusy=$previousGptBusy recentStop=$recentStopSeen stopAgeMs=$stopAgeMs recentActivity=$recentActivitySeen activityAgeMs=$activityAgeMs"
          )
        }
        lastBridgeMonitorGptBusy = false
      }
    }

    val previousTelegramTyping = lastBridgeMonitorTelegramTyping ?: false

    val changed = lastBridgeMonitorGptBusy == null || lastBridgeMonitorTelegramTyping == null ||
      previousGptBusy != (lastBridgeMonitorGptBusy ?: false) ||
      previousTelegramTyping != telegramTyping

    if (false) { appendDebugLog("WIDGET", "BRIDGE_MONITOR_TICK roots=${allRoots.size} gptStop=${gptRootsForMonitor.isNotEmpty() && hasExactShortGptBusyNodeForDecision(gptRootsForMonitor)} gptTextChanging=${lastBridgeMonitorGptBusy ?: false} telegramTyping=$telegramTyping") }

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

  private fun handleGlobalHardwareKeyboardShortcut(action: String): Boolean {
    val prefs = getSharedPreferences(
      "copybridge_floating_widget",
      MODE_PRIVATE
    )

    val autoSend = prefs.getBoolean(
      "auto_send_enabled",
      false
    )

    return when (action) {
      CopyBridgeKeyboardShortcutBridge.ACTION_TELEGRAM_TO_GPT -> {
        val replyMode = when (
          prefs.getString(
            "reply_copy_mode",
            COPY_MODE_FULL
          )
        ) {
          COPY_MODE_LAST -> COPY_MODE_LAST
          else -> COPY_MODE_FULL
        }

        appendDebugLog(
          "SHORTCUT",
          "GLOBAL TG_TO_GPT replyMode=$replyMode autoSend=$autoSend"
        )

        CopyBridgeAccessibilityService.requestTelegramToGpt(
          this,
          replyMode,
          autoSend
        )
      }

      CopyBridgeKeyboardShortcutBridge.ACTION_GPT_TO_TELEGRAM -> {
        val gptMode = when (
          prefs.getString(
            "gpt_output_mode",
            "FULL"
          )
        ) {
          "CODE" -> "CODE"
          else -> "FULL"
        }

        appendDebugLog(
          "SHORTCUT",
          "GLOBAL GPT_TO_TG gptMode=$gptMode autoSend=$autoSend"
        )

        CopyBridgeAccessibilityService.requestGptToTelegram(
          this,
          gptMode,
          autoSend
        )
      }

      else -> false
    }
  }


  override fun onKeyEvent(event: android.view.KeyEvent): Boolean {
    if (event.action != android.view.KeyEvent.ACTION_DOWN) {
      return false
    }

    if (event.repeatCount > 0) {
      return false
    }

    val captureAction = CopyBridgeKeyboardShortcutSettings.getCaptureAction(this)
    if (captureAction.isNotBlank()) {
      val capturedSpec = CopyBridgeKeyboardShortcutSettings.specFromEvent(event)
      if (capturedSpec == null) {
        Toast.makeText(
          this,
          "Ctrl, Alt, Meta 중 하나 이상을 포함한 단축키를 입력하세요.",
          Toast.LENGTH_SHORT
        ).show()
        appendDebugLog(
          "SHORTCUT",
          "capture invalid action=$captureAction keyCode=${event.keyCode} ctrl=${event.isCtrlPressed} shift=${event.isShiftPressed} alt=${event.isAltPressed} meta=${event.isMetaPressed}"
        )
        return true
      }

      val conflictAction = CopyBridgeKeyboardShortcutSettings.conflictAction(this, captureAction, capturedSpec)
      if (conflictAction.isNotBlank()) {
        Toast.makeText(
          this,
          "이미 ${CopyBridgeKeyboardShortcutSettings.actionLabel(conflictAction)}에 사용 중인 단축키입니다.",
          Toast.LENGTH_SHORT
        ).show()
        appendDebugLog(
          "SHORTCUT",
          "capture conflict action=$captureAction conflict=$conflictAction label=${CopyBridgeKeyboardShortcutSettings.labelForSpec(capturedSpec)}"
        )
        return true
      }

      CopyBridgeKeyboardShortcutSettings.saveSpec(this, captureAction, capturedSpec)
      CopyBridgeKeyboardShortcutSettings.cancelCapture(this)

      Toast.makeText(
        this,
        "${CopyBridgeKeyboardShortcutSettings.actionLabel(captureAction)}: ${CopyBridgeKeyboardShortcutSettings.labelForSpec(capturedSpec)} 저장",
        Toast.LENGTH_SHORT
      ).show()

      appendDebugLog(
        "SHORTCUT",
        "capture saved action=$captureAction label=${CopyBridgeKeyboardShortcutSettings.labelForSpec(capturedSpec)}"
      )
      return true
    }

    val shortcutAction = CopyBridgeKeyboardShortcutSettings.findMatchingAction(this, event) ?: return false
    return handleGlobalHardwareKeyboardShortcut(shortcutAction)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    val packageName = event?.packageName?.toString()
    if (!packageName.isNullOrBlank()) {
      lastPackageName = packageName
    }

    val safeEvent = event ?: return
    if (packageName != YOUTUBE_PACKAGE_NAME) {
      return
    }

    handleYouTubeAutoSkipEvent(safeEvent)
  }

  private fun isYouTubeAutoSkipEnabled(): Boolean {
    return getSharedPreferences(
      YOUTUBE_AUTO_SKIP_PREFS,
      MODE_PRIVATE
    ).getBoolean(
      YOUTUBE_AUTO_SKIP_KEY,
      true
    )
  }

  private fun normalizeYouTubeControlLabel(
    value: CharSequence?
  ): String {
    return value
      ?.toString()
      ?.trim()
      ?.lowercase(java.util.Locale.ROOT)
      ?.replace(Regex("\\s+"), " ")
      .orEmpty()
  }

  private fun isExactYouTubeSkipLabel(
    node: AccessibilityNodeInfo
  ): Boolean {
    val labels = listOf(
      normalizeYouTubeControlLabel(node.text),
      normalizeYouTubeControlLabel(node.contentDescription)
    )

    return labels.any { label ->
      label == "skip ad" ||
        label == "skip ads" ||
        label == "skip ad button" ||
        label == "skip ads button" ||
        label == "광고 건너뛰기" ||
        label == "광고 건너뛰기 버튼"
    }
  }

  private fun findYouTubeSkipNode(
    node: AccessibilityNodeInfo?,
    remainingBudget: IntArray
  ): AccessibilityNodeInfo? {
    if (node == null || remainingBudget[0] <= 0) return null

    remainingBudget[0] -= 1

    val packageName = node.packageName?.toString().orEmpty()
    if (
      packageName.isNotBlank() &&
      packageName != YOUTUBE_PACKAGE_NAME
    ) {
      return null
    }

    if (node.isEnabled && isExactYouTubeSkipLabel(node)) {
      return node
    }

    for (index in 0 until node.childCount) {
      val found = findYouTubeSkipNode(
        node.getChild(index),
        remainingBudget
      )

      if (found != null) return found
      if (remainingBudget[0] <= 0) break
    }

    return null
  }

  private fun findClickableYouTubeSkipTarget(
    node: AccessibilityNodeInfo
  ): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node

    repeat(4) {
      val candidate = current ?: return null
      if (
        candidate.isEnabled &&
        candidate.isClickable &&
        candidate.packageName?.toString() == YOUTUBE_PACKAGE_NAME
      ) {
        return candidate
      }

      current = try {
        candidate.parent
      } catch (_: Exception) {
        null
      }
    }

    return null
  }

  private fun clickYouTubeSkipCandidate(
    sourceNode: AccessibilityNodeInfo
  ): Boolean {
    val target = findClickableYouTubeSkipTarget(sourceNode) ?: return false
    val result = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    if (result) {
      lastYouTubeSkipClickAtMs = android.os.SystemClock.uptimeMillis()
      appendDebugLog("YOUTUBE", "AUTO_SKIP_CLICKED")
    } else {
      appendDebugLog("YOUTUBE", "AUTO_SKIP_CLICK_FAILED")
    }

    return result
  }

  private fun handleYouTubeAutoSkipEvent(
    event: AccessibilityEvent
  ) {
    if (!isYouTubeAutoSkipEnabled()) return
    if (
      event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
      event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    ) {
      return
    }

    val now = android.os.SystemClock.uptimeMillis()
    if (now - lastYouTubeSkipClickAtMs < YOUTUBE_SKIP_CLICK_COOLDOWN_MS) {
      return
    }

    var candidate: AccessibilityNodeInfo? = null
    if (
      now - lastYouTubeSkipSourceScanAtMs >=
        YOUTUBE_SOURCE_SCAN_THROTTLE_MS
    ) {
      lastYouTubeSkipSourceScanAtMs = now
      candidate = findYouTubeSkipNode(event.source, intArrayOf(120))
    }

    if (candidate != null) {
      clickYouTubeSkipCandidate(candidate)
      return
    }

    if (
      now - lastYouTubeSkipFullScanAtMs <
        YOUTUBE_FULL_SCAN_THROTTLE_MS
    ) {
      return
    }

    lastYouTubeSkipFullScanAtMs = now
    val youtubeRoots = collectInteractiveRootsAcrossAllDisplays()
      .filter { root ->
        root.packageName?.toString() == YOUTUBE_PACKAGE_NAME
      }

    for (root in youtubeRoots) {
      candidate = findYouTubeSkipNode(root, intArrayOf(400))
      if (candidate != null) break
    }

    candidate?.let(::clickYouTubeSkipCandidate)
  }

  override fun onInterrupt() {
    Log.d(TAG, "CopyBridge accessibility service interrupted")
  }

  override fun onDestroy() {
    stopBridgeStatusMonitor()
    pendingGptToTelegramHandler.removeCallbacks(pendingGptToTelegramRunnable)
    pendingGptToTelegram = null
    pendingGptIdleChecks = 0

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
      val firstEdit = findEditableNodeFromRoots(firstRoots) ?: editableNode
      val firstSent = clickTelegramSendButtonSafely(firstRoots, firstEdit)

      if (firstSent) {
        Toast.makeText(this, "AI → TG 붙여넣기 완료: $mode + 전송", Toast.LENGTH_SHORT).show()
        hideTelegramKeyboardAfterSendSafely("firstSent")
        return@postDelayed
      }

      Handler(Looper.getMainLooper()).postDelayed({
        val secondRoots = getTelegramRoots()
        val secondEdit = findEditableNodeFromRoots(secondRoots) ?: firstEdit
        val secondSent = clickTelegramSendButtonSafely(secondRoots, secondEdit)

        if (secondSent) {
          Toast.makeText(this, "AI → TG 붙여넣기 완료: $mode + 전송", Toast.LENGTH_SHORT).show()
          hideTelegramKeyboardAfterSendSafely("secondSent")
        } else {
          val debug = buildTelegramCopyDebugInfo(secondRoots)
          copyToClipboard(debug)
          Toast.makeText(
            this,
            "붙여넣기는 완료했지만 안전한 Telegram 전송 버튼을 찾지 못했습니다.",
            Toast.LENGTH_SHORT
          ).show()
        }
      }, 350L)
    }, 450L)
  }

  private fun clearTelegramInputFocusAfterSend(source: String): Boolean {
    val roots = getTelegramRoots()
    if (roots.isEmpty()) {
      appendDebugLog(
        "GPT→TG",
        "TELEGRAM_INPUT_CLEAR_FOCUS_SKIPPED source=$source reason=noTelegramRoot"
      )
      return false
    }

    val editNodes = mutableListOf<AccessibilityNodeInfo>()
    roots.forEach { root ->
      collectTelegramEditTextNodes(root, editNodes)
    }

    val focusedNode = editNodes.firstOrNull { it.isFocused }
    val targetNode = focusedNode ?: editNodes.maxByOrNull { node ->
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)
      rect.bottom
    }

    if (targetNode == null) {
      appendDebugLog(
        "GPT→TG",
        "TELEGRAM_INPUT_CLEAR_FOCUS_SKIPPED source=$source reason=noEditText editNodes=0 roots=${roots.size}"
      )
      return false
    }

    val rect = android.graphics.Rect()
    targetNode.getBoundsInScreen(rect)
    val result = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
    val stillFocused = targetNode.isFocused

    appendDebugLog(
      "GPT→TG",
      "TELEGRAM_INPUT_CLEAR_FOCUS_AFTER_SEND source=$source result=$result focused=$stillFocused editNodes=${editNodes.size} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
    )

    return result
  }

  private fun collectTelegramEditTextNodes(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return

    val className = node.className?.toString().orEmpty()
    if (className.contains("EditText", ignoreCase = true)) {
      out.add(node)
    }

    for (index in 0 until node.childCount) {
      collectTelegramEditTextNodes(node.getChild(index), out)
    }
  }

  private fun getTelegramDisplayIdsForKeyboardCleanup(): Set<Int> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()

    val telegramWindowIds = getTelegramRoots()
      .map { root -> root.windowId }
      .toSet()
    if (telegramWindowIds.isEmpty()) return emptySet()

    return getInteractiveWindowsAcrossAllDisplays()
      .filter { window -> telegramWindowIds.contains(window.id) }
      .map { window -> window.displayId }
      .toSet()
  }

  private fun hasVisibleImeWindowOnTelegramDisplay(
    source: String
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      appendDebugLog(
        "GPT→TG",
        "TELEGRAM_IME_WINDOW_CHECK source=$source result=false reason=apiBelowR"
      )
      return false
    }

    val telegramDisplayIds = getTelegramDisplayIdsForKeyboardCleanup()
    if (telegramDisplayIds.isEmpty()) {
      appendDebugLog(
        "GPT→TG",
        "TELEGRAM_IME_WINDOW_CHECK source=$source result=false reason=noTelegramDisplay"
      )
      return false
    }

    val matchingImeWindows = getInteractiveWindowsAcrossAllDisplays()
      .filter { window ->
        if (
          window.type !=
            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        ) {
          return@filter false
        }
        if (!telegramDisplayIds.contains(window.displayId)) {
          return@filter false
        }

        val rect = android.graphics.Rect()
        try {
          window.getBoundsInScreen(rect)
        } catch (_: Exception) {
          return@filter false
        }
        !rect.isEmpty()
      }

    val result = matchingImeWindows.isNotEmpty()
    appendDebugLog(
      "GPT→TG",
      "TELEGRAM_IME_WINDOW_CHECK source=$source result=$result telegramDisplays=${telegramDisplayIds.joinToString(",")} imeCount=${matchingImeWindows.size}"
    )
    return result
  }

  private fun hideTelegramKeyboardAfterSendSafely(
    source: String
  ) {
    val clearResult = clearTelegramInputFocusAfterSend(source)

    appendDebugLog(
      "GPT→TG",
      "TELEGRAM_POST_SEND_CLEAR_FOCUS source=$source result=$clearResult"
    )

    Handler(Looper.getMainLooper()).postDelayed({
      val hideButtonResult = clickKeyboardHideButtonAfterSendSafely(source)

      appendDebugLog(
        "GPT→TG",
        "TELEGRAM_KEYBOARD_HIDE_BUTTON_RESULT source=$source result=$hideButtonResult"
      )

      Handler(Looper.getMainLooper()).postDelayed(beforeBack@{
        val imeVisibleBeforeBack = hasVisibleImeWindowOnTelegramDisplay(
          "${source}_beforeBack"
        )

        if (!imeVisibleBeforeBack) {
          appendDebugLog(
            "GPT→TG",
            "TELEGRAM_KEYBOARD_BACK_SKIPPED source=$source reason=noImeWindow"
          )
          return@beforeBack
        }

        val backResult = performGlobalAction(GLOBAL_ACTION_BACK)
        appendDebugLog(
          "GPT→TG",
          "TELEGRAM_KEYBOARD_BACK source=$source result=$backResult reason=confirmedImeWindow"
        )

        Handler(Looper.getMainLooper()).postDelayed({
          val stillVisible = hasVisibleImeWindowOnTelegramDisplay(
            "${source}_afterBack"
          )
          appendDebugLog(
            "GPT→TG",
            "TELEGRAM_KEYBOARD_BACK_VERIFY source=$source imeStillVisible=$stillVisible secondBack=false"
          )
        }, 300L)
      }, 300L)
    }, 260L)
  }


  private fun clickKeyboardHideButtonAfterSendSafely(source: String): Boolean {
    val candidates = mutableListOf<AccessibilityNodeInfo>()

    val telegramDisplayIds = getTelegramDisplayIdsForKeyboardCleanup()
    val candidateWindows = getInteractiveWindowsAcrossAllDisplays()
      .filter { window ->
        if (
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
          telegramDisplayIds.isNotEmpty()
        ) {
          telegramDisplayIds.contains(window.displayId)
        } else {
          true
        }
      }

    candidateWindows.forEach { window ->
      val root = window.root ?: return@forEach
      collectKeyboardHideButtonCandidates(root, candidates)
    }

    val target = candidates.firstOrNull()
    if (target == null) {
      val windowSummary = candidateWindows.mapNotNull { window ->
        val root = window.root ?: return@mapNotNull null
        val rect = android.graphics.Rect()
        root.getBoundsInScreen(rect)
        val packageName = root.packageName?.toString().orEmpty()
        val className = root.className?.toString().orEmpty()
        "$packageName/$className/${rect.left},${rect.top},${rect.right},${rect.bottom}"
      }.take(8).joinToString("|")

      appendDebugLog(
        "GPT→TG",
        "TELEGRAM_KEYBOARD_HIDE_BUTTON_AFTER_SEND_SKIPPED source=$source reason=noCandidate windows=$windowSummary"
      )
      return false
    }

    val clicked = clickNodeOrNearClickableParentSafely(target) { true }

    appendDebugLog(
      "GPT→TG",
      "TELEGRAM_KEYBOARD_HIDE_BUTTON_AFTER_SEND source=$source clicked=$clicked label=${buildNodeLabel(target)} class=${target.className}"
    )

    return clicked
  }

  private fun collectKeyboardHideButtonCandidates(
    node: AccessibilityNodeInfo?,
    out: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return

    val labels = listOf(
      normalizeExactControlLabel(node.text),
      normalizeExactControlLabel(node.contentDescription)
    )
    val looksLikeKeyboardHide = labels.any { label ->
      label == "키보드 숨기기" ||
        label == "키보드 닫기" ||
        label == "키보드 내리기" ||
        label == "hide keyboard" ||
        label == "close keyboard" ||
        label == "dismiss keyboard"
    }

    if (looksLikeKeyboardHide) {
      out.add(node)
    }

    for (index in 0 until node.childCount) {
      collectKeyboardHideButtonCandidates(node.getChild(index), out)
    }
  }

  private fun getTelegramRoots(): List<AccessibilityNodeInfo> {
    return collectInteractiveRootsAcrossAllDisplays()
      .filter { root ->
        isTelegramPackage(root.packageName?.toString().orEmpty())
      }
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

   private data class PendingGptToTelegramRequest(
    val mode: String,
    val autoSend: Boolean,
    val requestedAtMs: Long
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

    logTelegramCandidateDebug("primaryRaw", primaryCandidates)
    logTelegramCandidateDebug("broadRaw", broadCandidates)

    val primarySorted = normalizeTelegramMessageCandidates(primaryCandidates)
    val broadSorted = normalizeTelegramMessageCandidates(broadCandidates)

    logTelegramCandidateDebug("primaryNormalized", primarySorted)
    logTelegramCandidateDebug("broadNormalized", broadSorted)

    val primaryRecovered = if (primarySorted.isEmpty() && primaryCandidates.isNotEmpty()) {
      recoverTelegramPrimaryRawCandidates(primaryCandidates)
    } else {
      emptyList()
    }

    logTelegramCandidateDebug("primaryRecovered", primaryRecovered)

    val chosenSorted = if (primarySorted.isNotEmpty()) {
      appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_CANDIDATE_SOURCE source=primary primary=${primarySorted.size} broad=${broadSorted.size} recovered=${primaryRecovered.size}"
      )
      primarySorted
    } else if (primaryRecovered.isNotEmpty()) {
      appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_CANDIDATE_SOURCE source=primaryRawRecover primary=0 recovered=${primaryRecovered.size} broad=${broadSorted.size}"
      )
      primaryRecovered
    } else {
      appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_CANDIDATE_SOURCE source=broadFallback primary=0 recovered=0 broad=${broadSorted.size}"
      )
      broadSorted
    }

    val result = mutableListOf<TextCandidate>()
    var previousText = ""

    chosenSorted.forEach { candidate ->
      if (candidate.text == previousText) return@forEach
      result.add(candidate)
      previousText = candidate.text
    }

    val finalResult = result.takeLast(MAX_COPY_LINES)

    appendDebugLog(
      "TG→GPT",
      "TG_TO_GPT_CANDIDATE_RESULT chosen=${chosenSorted.size} final=${finalResult.size} preview=${compactLogPreview(finalResult.joinToString("\\n") { it.text })}"
    )

    return finalResult
  }

  private fun logTelegramCandidateDebug(
    source: String,
    candidates: List<TextCandidate>
  ) {
    appendDebugLog(
      "TG→GPT",
      "TG_TO_GPT_CANDIDATE_DETAIL source=$source count=${candidates.size}"
    )

    candidates.takeLast(12).forEachIndexed { index, candidate ->
      appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_CANDIDATE_DETAIL[$source][$index] textLength=${candidate.text.length} bounds=${candidate.left},${candidate.top},${candidate.right},${candidate.bottom} preview=${compactLogPreview(candidate.text)}"
      )
    }
  }

  private fun recoverTelegramPrimaryRawCandidates(
    candidates: List<TextCandidate>
  ): List<TextCandidate> {
    val recovered = candidates.mapNotNull { candidate ->
      val keptLines = normalizeTelegramTextForGptPreserveLines(candidate.text)
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filter { !shouldIgnoreMessageText(it) }

      val joined = keptLines.joinToString("\n").trim()
      if (joined.isBlank()) {
        null
      } else {
        candidate.copy(text = joined)
      }
    }.sortedWith(compareBy<TextCandidate> { it.top }.thenBy { it.left })

    appendDebugLog(
      "TG→GPT",
      "TG_TO_GPT_PRIMARY_RAW_RECOVER before=${candidates.size} after=${recovered.size} preview=${compactLogPreview(recovered.joinToString("\n") { it.text })}"
    )

    return recovered
  }

  private fun normalizeTelegramMessageCandidates(
    candidates: List<TextCandidate>
  ): List<TextCandidate> {
    return candidates
      .mapNotNull { candidate ->
        val cleaned = normalizeTelegramTextForGptPreserveLines(candidate.text)
        if (cleaned.isBlank()) return@mapNotNull null
        if (shouldIgnoreMessageText(cleaned)) return@mapNotNull null
        candidate.copy(text = cleaned)
      }
      .sortedWith(compareBy<TextCandidate> { it.top }.thenBy { it.left })
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

   private fun queueGptToTelegramAfterCompletion(
    context: Context,
    mode: String,
    autoSend: Boolean
  ) {
    val replacing = pendingGptToTelegram != null
    pendingGptToTelegram = PendingGptToTelegramRequest(
      mode = mode,
      autoSend = autoSend,
      requestedAtMs = System.currentTimeMillis()
    )
    pendingGptIdleChecks = 0
    pendingGptToTelegramHandler.removeCallbacks(pendingGptToTelegramRunnable)
    pendingGptToTelegramHandler.postDelayed(pendingGptToTelegramRunnable, 700L)
    appendDebugLog(
      "GPT→TG",
      "GPT_TO_TG_QUEUED mode=$mode autoSend=$autoSend replacing=$replacing"
    )
    Toast.makeText(
      context,
      if (replacing) "Telegram 전송 예약을 갱신했습니다."
      else "GPT 답변 완료 후 Telegram 전송을 예약했습니다.",
      Toast.LENGTH_SHORT
    ).show()
  }

  private fun runPendingGptToTelegramCheck() {
    val pending = pendingGptToTelegram ?: return
    val ageMs = System.currentTimeMillis() - pending.requestedAtMs
    if (ageMs > 10 * 60 * 1000L) {
      appendDebugLog("GPT→TG", "GPT_TO_TG_QUEUE_EXPIRED ageMs=$ageMs")
      pendingGptToTelegram = null
      pendingGptIdleChecks = 0
      Toast.makeText(
        this,
        "GPT→Telegram 전송 예약이 시간 초과로 취소되었습니다.",
        Toast.LENGTH_SHORT
      ).show()
      return
    }

    val roots = getAiRoots()
    if (roots.isEmpty()) {
      pendingGptIdleChecks = 0
      pendingGptToTelegramHandler.postDelayed(pendingGptToTelegramRunnable, 700L)
      return
    }

    val definitelyBusy = hasExactShortGptBusyNodeForDecision(roots)
    if (definitelyBusy) {
      pendingGptIdleChecks = 0
      pendingGptToTelegramHandler.postDelayed(pendingGptToTelegramRunnable, 700L)
      return
    }

    pendingGptIdleChecks += 1
    appendDebugLog(
      "GPT→TG",
      "GPT_TO_TG_QUEUE_IDLE checks=$pendingGptIdleChecks"
    )
    if (pendingGptIdleChecks < 2) {
      pendingGptToTelegramHandler.postDelayed(pendingGptToTelegramRunnable, 700L)
      return
    }

    pendingGptToTelegram = null
    pendingGptIdleChecks = 0
    appendDebugLog(
      "GPT→TG",
      "GPT_TO_TG_QUEUE_DISPATCH mode=${pending.mode} autoSend=${pending.autoSend}"
    )
    requestGptToTelegramNow(this, pending.mode, pending.autoSend)
  }

  private fun requestGptToTelegramNow(
    context: Context,
    gptOutputMode: String,
    autoSend: Boolean
  ): Boolean = requestGptToTelegramNowInternal(context, gptOutputMode, autoSend)

  private fun requestGptToTelegramWithReservation(
    context: Context,
    gptOutputMode: String,
    autoSend: Boolean
  ): Boolean {
    val roots = getAiRoots()
    val definitelyBusy = roots.isNotEmpty() && hasExactShortGptBusyNodeForDecision(roots)
    appendDebugLog(
      "GPT→TG",
      "GPT_TO_TG_RESERVATION_DECISION definitelyBusy=$definitelyBusy roots=${roots.size}"
    )
    return if (definitelyBusy) {
      queueGptToTelegramAfterCompletion(context, gptOutputMode, autoSend)
      true
    } else {
      requestGptToTelegramNow(context, gptOutputMode, autoSend)
    }
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
    if (false) { appendDebugLog(
      "TG→GPT",
      "TELEGRAM_TOPBAR_SNAPSHOT roots=${roots.size}"
    ) }

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
      if (false) { appendDebugLog(
        "TG→GPT",
        "TELEGRAM_TOPBAR_NODE[$index] label=${compactLogPreview(label)} length=${label.length} class=${node.className} clickable=${node.isClickable} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom}"
      ) }
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

    if (false) { appendDebugLog(
      "TG→GPT",
      "TELEGRAM_TOPBAR_TYPING_MATCH found=${typingCandidates.isNotEmpty()} candidates=${typingCandidates.size}"
    ) }

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
    val candidates = collectTelegramMessageCandidates(roots)
    val finalResult = candidates.map { it.text }

    appendDebugLog(
      "TG→GPT",
      "fullCollect source=collectTelegramMessageCandidates final=${finalResult.size} preview=${compactLogPreview(finalResult.joinToString("\\n"))}"
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
    val arguments = Bundle().apply {
      putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }
    val directResult = node.performAction(
      AccessibilityNodeInfo.ACTION_SET_TEXT,
      arguments
    )
    if (directResult) return true

    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    return node.performAction(
      AccessibilityNodeInfo.ACTION_SET_TEXT,
      arguments
    )
  }

  private fun pasteClipboardToNode(node: AccessibilityNodeInfo): Boolean {
    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    val directResult = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    if (directResult) return true

    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
  }

  private fun normalizeExactControlLabel(value: CharSequence?): String {
    return value
      ?.toString()
      ?.trim()
      ?.lowercase(java.util.Locale.ROOT)
      ?.replace(Regex("\\s+"), " ")
      .orEmpty()
  }

  private fun clickNodeOrNearClickableParentSafely(
    node: AccessibilityNodeInfo?,
    expectedPackage: (String) -> Boolean
  ): Boolean {
    var current = node ?: return false
    var depth = 0

    while (depth <= 2) {
      val packageName = current.packageName?.toString().orEmpty()
      if (current.isEnabled && expectedPackage(packageName)) {
        if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
          return true
        }
      }

      if (depth == 2) break
      current = try {
        current.parent ?: return false
      } catch (_: Exception) {
        return false
      }
      depth += 1
    }

    return false
  }

  private fun isExactTelegramSendNode(node: AccessibilityNodeInfo): Boolean {
    if (!node.isEnabled) return false
    if (!isTelegramPackage(node.packageName?.toString().orEmpty())) return false

    val text = normalizeExactControlLabel(node.text)
    val desc = normalizeExactControlLabel(node.contentDescription)
    val viewId = node.viewIdResourceName
      ?.substringAfterLast('/')
      ?.lowercase(java.util.Locale.ROOT)
      .orEmpty()

    val labelMatch = text in setOf(
      "send", "전송", "보내기", "메시지 보내기"
    ) || desc in setOf(
      "send", "send message", "전송", "보내기", "메시지 보내기"
    )
    val exactIdMatch = viewId in setOf(
      "send_button", "chat_message_send", "button_send", "chat_send_button"
    )
    return labelMatch || exactIdMatch
  }

  private fun collectExactTelegramSendNodes(
    node: AccessibilityNodeInfo?,
    output: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return
    if (isExactTelegramSendNode(node)) output.add(node)
    for (index in 0 until node.childCount) {
      collectExactTelegramSendNodes(node.getChild(index), output)
    }
  }

  private fun clickTelegramSendButtonSafely(
    roots: List<AccessibilityNodeInfo>,
    editNode: AccessibilityNodeInfo?
  ): Boolean {
    val edit = editNode ?: return false
    val editRect = Rect()
    edit.getBoundsInScreen(editRect)
    if (editRect.isEmpty()) return false

    val candidates = mutableListOf<AccessibilityNodeInfo>()
    roots.forEach { root ->
      val rootRect = Rect()
      root.getBoundsInScreen(rootRect)
      if (
        !rootRect.isEmpty() &&
        rootRect.contains(editRect.centerX(), editRect.centerY())
      ) {
        collectExactTelegramSendNodes(root, candidates)
      }
    }
    val verticalTolerance = maxOf(240, editRect.height().coerceAtLeast(1) * 3)
    val target = candidates.mapNotNull { node ->
      val rect = Rect()
      node.getBoundsInScreen(rect)
      if (
        rect.isEmpty() ||
        kotlin.math.abs(rect.centerY() - editRect.centerY()) > verticalTolerance
      ) null else node to rect
    }.minWithOrNull(
      compareBy<Pair<AccessibilityNodeInfo, Rect>> {
        kotlin.math.abs(it.second.centerY() - editRect.centerY())
      }.thenByDescending { it.second.centerX() }
    )?.first ?: return false

    return clickNodeOrNearClickableParentSafely(target, ::isTelegramPackage)
  }

  private fun getAiRoots(): List<AccessibilityNodeInfo> {
    return collectInteractiveRootsAcrossAllDisplays()
      .filter { root ->
        isAiPackage(root.packageName?.toString().orEmpty())
      }
  }

  private fun isAiPackage(packageName: String): Boolean {
    val lower = packageName.lowercase()
    if (lower.contains("telegram")) return false
    val aiKeywords = listOf("openai", "chatgpt", "gpt", "ai", "bard", "claude")
    return aiKeywords.any { lower.contains(it) }
  }

  private fun performImeEnterIfPossible(node: AccessibilityNodeInfo?): Boolean {
    if (node == null) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    } else {
      false
    }
  }

  private fun isExactChatGptSendNode(node: AccessibilityNodeInfo): Boolean {
    if (!node.isEnabled) return false
    if (!isChatGptPackageForTgToGpt(node.packageName?.toString().orEmpty())) {
      return false
    }

    val labels = listOf(
      normalizeExactControlLabel(node.text),
      normalizeExactControlLabel(node.contentDescription)
    )
    return labels.any { label ->
      label == "send" ||
        label == "send message" ||
        label == "submit" ||
        label == "전송" ||
        label == "보내기" ||
        label == "메시지 보내기"
    }
  }

  private fun collectExactChatGptSendNodes(
    node: AccessibilityNodeInfo?,
    output: MutableList<AccessibilityNodeInfo>
  ) {
    if (node == null) return
    if (isExactChatGptSendNode(node)) output.add(node)
    for (index in 0 until node.childCount) {
      collectExactChatGptSendNodes(node.getChild(index), output)
    }
  }

  private fun findExactChatGptSendNearComposer(
    roots: List<AccessibilityNodeInfo>,
    editNode: AccessibilityNodeInfo?
  ): AccessibilityNodeInfo? {
    val edit = editNode ?: return null
    val editRect = Rect()
    edit.getBoundsInScreen(editRect)
    if (editRect.isEmpty()) return null

    val candidates = mutableListOf<AccessibilityNodeInfo>()
    roots.forEach { root ->
      val rootRect = Rect()
      root.getBoundsInScreen(rootRect)
      if (
        !rootRect.isEmpty() &&
        rootRect.contains(editRect.centerX(), editRect.centerY())
      ) {
        collectExactChatGptSendNodes(root, candidates)
      }
    }
    val verticalTolerance = maxOf(240, editRect.height().coerceAtLeast(1) * 3)
    return candidates.mapNotNull { node ->
      val rect = Rect()
      node.getBoundsInScreen(rect)
      if (
        rect.isEmpty() ||
        kotlin.math.abs(rect.centerY() - editRect.centerY()) > verticalTolerance
      ) null else node to rect
    }.minWithOrNull(
      compareBy<Pair<AccessibilityNodeInfo, Rect>> {
        kotlin.math.abs(it.second.centerY() - editRect.centerY())
      }.thenByDescending { it.second.centerX() }
    )?.first
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

    val clicked = clickExactGptCopyCandidate(target)
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
      val labels = listOf(
        normalizeExactControlLabel(node.text),
        normalizeExactControlLabel(node.contentDescription)
      )
      val isCodeCopy = labels.any { value ->
        value == "코드 복사" ||
          value == "copy code" ||
          value == "code copy" ||
          value == "코드 복사 버튼" ||
          value == "copy code button"
      }
      val isAnswerCopy = labels.any { value ->
        value == "복사" ||
          value == "copy" ||
          value == "복사 버튼" ||
          value == "copy button"
      }

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

  private fun clickExactGptCopyCandidate(
    candidate: CopyButtonCandidate
  ): Boolean {
    val node = candidate.node
    if (!isChatGptPackageForTgToGpt(node.packageName?.toString().orEmpty())) {
      return false
    }
    return clickNodeOrNearClickableParentSafely(
      node,
      ::isChatGptPackageForTgToGpt
    )
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
    val fullPasteSentinel = "COPYBRIDGE_GPT_COPY_SENTINEL_${System.currentTimeMillis()}"

    if (gptOutputMode == "FULL") {

        copyToClipboard(fullPasteSentinel)

        appendDebugLog(

            "GPT→TG",

            "COPY_BUTTON_FULL_SENTINEL_SET length=${fullPasteSentinel.length}"

        )

        android.os.SystemClock.sleep(120L)

    }

    val clicked = clickExactGptCopyCandidate(target)
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
          "COPY_BUTTON_FULL_CLIPBOARD_EMPTY_NO_GESTURE"
        )
        val extractedText = pickLatestGptAnswerTextForFullFallback(aiRoots).trim()
        if (extractedText.isBlank()) return false

        val setTextOk = setTextToNode(tgEdit, extractedText)
        android.os.SystemClock.sleep(220L)
        val setVerified = setTextOk && isTelegramEditActuallyFilled(tgEdit)
        appendDebugLog(
          "GPT→TG",
          "GPT_TEXTVIEW_EXTRACT_SAFE_SET result=$setTextOk verified=$setVerified length=${extractedText.length}"
        )
        if (!setVerified) return false

        if (autoSend) {
          scheduleAutoSendAfterPaste("GPT→TG_FULL_TEXTVIEW_EXTRACT", tgEdit)
        } else {
          Toast.makeText(this, "Telegram 입력창에 붙여넣었습니다.", Toast.LENGTH_SHORT).show()
        }
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

    var pasted = try { pasteClipboardToNode(tgEdit) } catch (error: Exception) { appendDebugLog("GPT→TG", "EXCEPTION copyButtonPaste ${error::class.java.simpleName}: ${error.message}"); false }
    android.os.SystemClock.sleep(300L)
    var pasteValid = isTelegramEditActuallyFilled(tgEdit)
    appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE pasted=$pasted valid=$pasteValid autoSend=$autoSend")
    logTelegramEditSnapshot("COPY_BUTTON_PASTE_SNAPSHOT", tgEdit)
    if (!pasteValid && gptOutputMode == "CODE") {
      appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE_CODE_FAST_FALLBACK reason=invalidPaste skipLongPaste=true skipRetry=true")
      return false
    }

    if (!pasteValid && gptOutputMode == "FULL") {
      appendDebugLog("GPT→TG", "COPY_BUTTON_PASTE skipRetryForFull=true -> fallback")
      return false
    }

    if (!pasteValid) {
      for (attempt in 1..2) {
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

  private data class TgToGptChatWindowTarget(
    val root: AccessibilityNodeInfo,
    val displayId: Int,
    val bounds: Rect,
    val isFocused: Boolean,
    val isActive: Boolean
  )

  private data class TgToGptComposerCandidate(
    val node: AccessibilityNodeInfo,
    val bounds: Rect,
    val score: Int
  )

  private enum class TgToGptSendResult {
    VERIFIED,
    ISSUED_UNVERIFIED,
    FAILED
  }

  private fun isChatGptPackageForTgToGpt(packageName: String): Boolean {
    val lower = packageName.lowercase()
    return lower == "com.openai.chatgpt" ||
      lower.contains("openai") ||
      lower.contains("chatgpt")
  }

  private fun getChatGptWindowTargetsForTgToGpt(): List<TgToGptChatWindowTarget> {
    val targets = mutableListOf<TgToGptChatWindowTarget>()

    getInteractiveWindowsAcrossAllDisplays().forEach { window ->
      val root = try {
        window.root
      } catch (_: Exception) {
        null
      } ?: return@forEach

      if (!isChatGptPackageForTgToGpt(root.packageName?.toString().orEmpty())) {
        return@forEach
      }

      val bounds = Rect()
      try {
        window.getBoundsInScreen(bounds)
      } catch (_: Exception) {}
      if (bounds.isEmpty) {
        try {
          root.getBoundsInScreen(bounds)
        } catch (_: Exception) {}
      }
      if (bounds.isEmpty) return@forEach

      val displayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
          window.displayId
        } catch (_: Exception) {
          android.view.Display.DEFAULT_DISPLAY
        }
      } else {
        android.view.Display.DEFAULT_DISPLAY
      }

      targets.add(
        TgToGptChatWindowTarget(
          root = root,
          displayId = displayId,
          bounds = Rect(bounds),
          isFocused = try { window.isFocused } catch (_: Exception) { false },
          isActive = try { window.isActive } catch (_: Exception) { false }
        )
      )
    }

    return targets
      .distinctBy { target ->
        "${target.displayId}|${target.root.windowId}|" +
          "${target.bounds.left},${target.bounds.top},${target.bounds.right},${target.bounds.bottom}"
      }
      .sortedWith(
        compareByDescending<TgToGptChatWindowTarget> { it.isFocused }
          .thenByDescending { it.isActive }
          .thenByDescending { it.bounds.width() * it.bounds.height() }
      )
  }

  private fun getChatGptRootsForTgToGpt(): List<AccessibilityNodeInfo> {
    val targets = getChatGptWindowTargetsForTgToGpt()
    if (targets.isNotEmpty()) return targets.map { it.root }

    return collectInteractiveRootsAcrossAllDisplays().filter { root ->
      isChatGptPackageForTgToGpt(root.packageName?.toString().orEmpty())
    }
  }

  private fun findChatGptComposerCandidateForTgToGpt(
    target: TgToGptChatWindowTarget
  ): TgToGptComposerCandidate? {
    val candidates = mutableListOf<TgToGptComposerCandidate>()
    val rootBounds = target.bounds

    fun visit(node: AccessibilityNodeInfo?, depth: Int) {
      if (node == null || depth > 35) return
      val rect = Rect()
      try { node.getBoundsInScreen(rect) } catch (_: Exception) {}

      if (!rect.isEmpty) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          node.hintText?.toString().orEmpty()
        } else ""
        val viewId = node.viewIdResourceName.orEmpty()
        val className = node.className?.toString().orEmpty()
        val probe = "$text $desc $hint $viewId $className".lowercase()
        val centerYRatio = (rect.centerY() - rootBounds.top).toFloat() /
          rootBounds.height().coerceAtLeast(1).toFloat()
        val widthRatio = rect.width().toFloat() /
          rootBounds.width().coerceAtLeast(1).toFloat()
        val hasSetText = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        val hasPaste = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE }
        val inputSemantic = probe.contains("chatgpt에 답장") ||
          probe.contains("메시지") || probe.contains("message") ||
          probe.contains("prompt") || probe.contains("ask anything") ||
          probe.contains("ask chatgpt") || probe.contains("입력")
        val controlSemantic = probe.contains("send") || probe.contains("submit") ||
          probe.contains("보내기") || probe.contains("전송") || probe.contains("중지") ||
          probe.contains("stop generating") || probe.contains("음성") ||
          probe.contains("voice") || probe.contains("첨부") ||
          probe.contains("attach") || probe.contains("camera") || probe.contains("menu")

        var score = 0
        if (node.isEditable) score += 140
        if (hasSetText) score += 120
        if (className.contains("EditText", ignoreCase = true)) score += 100
        if (hasPaste) score += 40
        if (inputSemantic) score += 100
        if (node.isFocusable) score += 25
        if (node.isClickable) score += 20
        if (widthRatio >= 0.35f) score += 30
        if (centerYRatio >= 0.55f) score += 15
        if (centerYRatio >= 0.70f) score += 25
        if (centerYRatio >= 0.96f) score -= 30
        if (widthRatio < 0.15f) score -= 40
        if (controlSemantic && !inputSemantic) score -= 140

        if (score >= 60) {
          candidates.add(TgToGptComposerCandidate(node, Rect(rect), score))
        }
      }

      for (childIndex in 0 until node.childCount) {
        visit(node.getChild(childIndex), depth + 1)
      }
    }

    visit(target.root, 0)
    val winner = candidates.maxWithOrNull(
      compareBy<TgToGptComposerCandidate> { it.score }
        .thenBy { it.bounds.centerY() }
    )

    appendDebugLog(
      "TG→GPT",
      "COMPOSER_CANDIDATE count=${candidates.size} selected=${winner != null} score=${winner?.score ?: -1} bounds=${winner?.bounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "none"} displayId=${target.displayId}"
    )
    return winner
  }

  private fun hasChatGptAccessibilityInputConnectionForTgToGpt(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

    return try {
      val accessibilityIme = inputMethod
      val editorPackage = accessibilityIme?.currentInputEditorInfo?.packageName.orEmpty()
      accessibilityIme?.currentInputStarted == true &&
        accessibilityIme.currentInputConnection != null &&
        isChatGptPackageForTgToGpt(editorPackage)
    } catch (_: Exception) {
      false
    }
  }

  private fun waitForChatGptAccessibilityInputConnectionForTgToGpt(
    attempts: Int = 5,
    delayMs: Long = 100L
  ): Boolean {
    for (attempt in 1..attempts) {
      if (hasChatGptAccessibilityInputConnectionForTgToGpt()) {
        appendDebugLog("TG→GPT", "A11Y_INPUT_CONNECTION_READY attempt=$attempt")
        return true
      }
      if (attempt < attempts) android.os.SystemClock.sleep(delayMs)
    }
    return false
  }

  private fun focusChatGptComposerForTgToGpt(
    preferredEdit: AccessibilityNodeInfo?
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (hasChatGptAccessibilityInputConnectionForTgToGpt()) {
      appendDebugLog("TG→GPT", "COMPOSER_FOCUS alreadyReady=true")
      return true
    }

    if (preferredEdit != null) {
      try {
        preferredEdit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        preferredEdit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      } catch (_: Exception) {}
      if (waitForChatGptAccessibilityInputConnectionForTgToGpt()) {
        appendDebugLog("TG→GPT", "COMPOSER_FOCUS source=preferredEdit success=true")
        return true
      }
    }

    appendDebugLog("TG→GPT", "COMPOSER_FOCUS success=false coordinateFallback=false")
    return false
  }

  private fun textMatchesExpectedForTgToGpt(actual: String, expected: String): Boolean {
    if (actual.isBlank() || expected.isBlank()) return false
    if (actual == expected) return true
    val prefix = expected.take(minOf(expected.length, 96))
    val suffix = expected.takeLast(minOf(expected.length, 96))
    return (prefix.isNotBlank() && actual.contains(prefix)) ||
      (suffix.isNotBlank() && actual.contains(suffix))
  }

  private fun verifyNodeTextForTgToGpt(
    node: AccessibilityNodeInfo?,
    expected: String
  ): Boolean {
    if (node == null) return false
    return try {
      node.refresh()
      textMatchesExpectedForTgToGpt(node.text?.toString().orEmpty(), expected)
    } catch (_: Exception) {
      false
    }
  }

  private fun commitTextWithAccessibilityImeForTgToGpt(
    text: String,
    preferredEdit: AccessibilityNodeInfo?,
    targets: List<TgToGptChatWindowTarget>
  ): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      appendDebugLog("TG→GPT", "A11Y_IME skipped=true reason=apiBelow33")
      return false
    }

    if (!focusChatGptComposerForTgToGpt(preferredEdit)) {
      appendDebugLog("TG→GPT", "A11Y_IME focusReady=false")
      return false
    }

    return try {
      val accessibilityIme = inputMethod ?: run {
        appendDebugLog("TG→GPT", "A11Y_IME unavailable=true")
        return false
      }
      val editorPackage = accessibilityIme.currentInputEditorInfo?.packageName.orEmpty()
      val connection = accessibilityIme.currentInputConnection
      val inputStarted = accessibilityIme.currentInputStarted

      appendDebugLog(
        "TG→GPT",
        "A11Y_IME_READY started=$inputStarted package=$editorPackage connection=${connection != null}"
      )

      if (!inputStarted || connection == null || !isChatGptPackageForTgToGpt(editorPackage)) {
        return false
      }

      connection.performContextMenuAction(android.R.id.selectAll)
      android.os.SystemClock.sleep(50L)
      connection.commitText(text, 1, null)

      for (verifyAttempt in 1..8) {
        android.os.SystemClock.sleep(100L)
        if (verifyNodeTextForTgToGpt(preferredEdit, text)) {
          appendDebugLog("TG→GPT", "A11Y_IME_COMMIT verified=true source=node attempt=$verifyAttempt")
          return true
        }

        try {
          val surrounding = connection.getSurroundingText(
            minOf(text.length + 512, 8192),
            512,
            0
          )
          val surroundingText = surrounding?.text?.toString().orEmpty()
          if (textMatchesExpectedForTgToGpt(surroundingText, text)) {
            appendDebugLog(
              "TG→GPT",
              "A11Y_IME_COMMIT verified=true source=connection attempt=$verifyAttempt length=${surroundingText.length}"
            )
            return true
          }
        } catch (_: Exception) {}
      }

      appendDebugLog("TG→GPT", "A11Y_IME_COMMIT verified=false")
      false
    } catch (error: Exception) {
      appendDebugLog(
        "TG→GPT",
        "A11Y_IME_ERROR ${error::class.java.simpleName}: ${compactLogPreview(error.message.orEmpty())}"
      )
      false
    }
  }

  private fun inputTextToChatGptForTgToGpt(
    text: String,
    editNode: AccessibilityNodeInfo?,
    roots: List<AccessibilityNodeInfo>
  ): Boolean {
    if (editNode != null) {
      val setTextResult = try { setTextToNode(editNode, text) } catch (_: Exception) { false }
      appendDebugLog("TG→GPT", "INPUT_PATH_SET_TEXT actionResult=$setTextResult")
      if (setTextResult) {
        android.os.SystemClock.sleep(150L)
        if (verifyNodeTextForTgToGpt(editNode, text)) {
          appendDebugLog("TG→GPT", "INPUT_PATH_SET_TEXT verified=true")
          return true
        }
        appendDebugLog("TG→GPT", "INPUT_PATH_SET_TEXT verified=false")
      }
    }

    val targets = getChatGptWindowTargetsForTgToGpt()
    appendDebugLog(
      "TG→GPT",
      "INPUT_PATH_A11Y_IME targets=${targets.size} editNode=${editNode != null} roots=${roots.size}"
    )
    if (commitTextWithAccessibilityImeForTgToGpt(text, editNode, targets)) {
      appendDebugLog("TG→GPT", "INPUT_PATH_A11Y_IME success=true")
      return true
    }

    if (editNode != null) {
      copyToClipboard(text)
      try {
        setTextToNode(editNode, "")
        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        android.os.SystemClock.sleep(120L)
        val pasteAccepted = pasteClipboardToNode(editNode)
        android.os.SystemClock.sleep(150L)
        val verified = pasteAccepted && verifyNodeTextForTgToGpt(editNode, text)
        appendDebugLog("TG→GPT", "INPUT_PATH_CLIPBOARD accepted=$pasteAccepted verified=$verified")
        if (verified) return true
      } catch (error: Exception) {
        appendDebugLog(
          "TG→GPT",
          "INPUT_PATH_CLIPBOARD_ERROR ${error::class.java.simpleName}: ${compactLogPreview(error.message.orEmpty())}"
        )
      }
    }

    appendDebugLog("TG→GPT", "INPUT_PATH_ALL_FAILED")
    return false
  }

  private fun verifyChatGptSendAfterActionForTgToGpt(
    expectedText: String,
    attempts: Int = 10,
    delayMs: Long = 180L
  ): Boolean {
    for (attempt in 1..attempts) {
      android.os.SystemClock.sleep(delayMs)
      val roots = getChatGptRootsForTgToGpt()

      if (hasExactShortGptBusyNodeForDecision(roots)) {
        appendDebugLog("TG→GPT", "SEND_VERIFY success=true reason=busy attempt=$attempt")
        return true
      }

      val edit = findEditableNodeFromRoots(roots)
      if (edit != null) {
        try {
          edit.refresh()
          val currentText = edit.text?.toString().orEmpty()
          if (currentText.isBlank() || !textMatchesExpectedForTgToGpt(currentText, expectedText)) {
            appendDebugLog("TG→GPT", "SEND_VERIFY success=true reason=nodeCleared attempt=$attempt")
            return true
          }
        } catch (_: Exception) {}
      }

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
          val accessibilityIme = inputMethod
          val editorPackage = accessibilityIme?.currentInputEditorInfo?.packageName.orEmpty()
          val connection = accessibilityIme?.currentInputConnection
          if (
            accessibilityIme?.currentInputStarted == true &&
            connection != null &&
            isChatGptPackageForTgToGpt(editorPackage)
          ) {
            val surrounding = connection.getSurroundingText(
              minOf(expectedText.length + 512, 8192), 512, 0
            )
            val currentText = surrounding?.text?.toString().orEmpty()
            if (currentText.isBlank() || !textMatchesExpectedForTgToGpt(currentText, expectedText)) {
              appendDebugLog("TG→GPT", "SEND_VERIFY success=true reason=connectionChanged attempt=$attempt")
              return true
            }
          }
        } catch (_: Exception) {}
      }
    }

    appendDebugLog("TG→GPT", "SEND_VERIFY success=false")
    return false
  }

  private fun sendChatGptForTgToGpt(expectedText: String): TgToGptSendResult {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      try {
        val accessibilityIme = inputMethod
        val editorInfo = accessibilityIme?.currentInputEditorInfo
        val editorPackage = editorInfo?.packageName.orEmpty()
        val connection = accessibilityIme?.currentInputConnection
        if (
          accessibilityIme?.currentInputStarted == true && connection != null &&
          isChatGptPackageForTgToGpt(editorPackage)
        ) {
          val maskedAction = editorInfo?.imeOptions?.and(
            android.view.inputmethod.EditorInfo.IME_MASK_ACTION
          ) ?: android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED
          val action = when {
            editorInfo != null && editorInfo.actionId != 0 -> editorInfo.actionId
            maskedAction == android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> maskedAction
            maskedAction == android.view.inputmethod.EditorInfo.IME_ACTION_GO -> maskedAction
            maskedAction == android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> maskedAction
            else -> android.view.inputmethod.EditorInfo.IME_ACTION_SEND
          }
          connection.performEditorAction(action)
          appendDebugLog("TG→GPT", "TG_TO_GPT_SEND_METHOD method=editorAction issued=true action=$action")
          if (verifyChatGptSendAfterActionForTgToGpt(expectedText)) {
            return TgToGptSendResult.VERIFIED
          }
        }
      } catch (error: Exception) {
        appendDebugLog(
          "TG→GPT",
          "SEND_PATH_EDITOR_ACTION_ERROR ${error::class.java.simpleName}: ${compactLogPreview(error.message.orEmpty())}"
        )
      }
    }

    val freshRoots = getChatGptRootsForTgToGpt()
    val freshEdit = findEditableNodeFromRoots(freshRoots)
    val exactSend = findExactChatGptSendNearComposer(freshRoots, freshEdit)
    val buttonAccepted = clickNodeOrNearClickableParentSafely(
      exactSend,
      ::isChatGptPackageForTgToGpt
    )
    appendDebugLog(
      "TG→GPT",
      "TG_TO_GPT_SEND_METHOD method=exactButton issued=$buttonAccepted candidatesRestricted=true"
    )
    if (buttonAccepted) {
      return if (verifyChatGptSendAfterActionForTgToGpt(expectedText)) {
        TgToGptSendResult.VERIFIED
      } else {
        TgToGptSendResult.ISSUED_UNVERIFIED
      }
    }

    val fallbackEdit = findEditableNodeFromRoots(getChatGptRootsForTgToGpt())
    if (fallbackEdit != null && performImeEnterIfPossible(fallbackEdit)) {
      appendDebugLog("TG→GPT", "TG_TO_GPT_SEND_METHOD method=imeEnter issued=true")
      if (verifyChatGptSendAfterActionForTgToGpt(expectedText)) {
        return TgToGptSendResult.VERIFIED
      }
    }

    appendDebugLog("TG→GPT", "SEND_PATH_FAILED")
    return TgToGptSendResult.FAILED
  }

  private fun getAiRootsWithRetryForTgToGpt(
    label: String,
    maxAttempts: Int = TG_TO_GPT_FIND_RETRY_COUNT,
    delayMs: Long = TG_TO_GPT_FIND_RETRY_DELAY_MS
  ): List<AccessibilityNodeInfo> {
    var latestRoots = emptyList<AccessibilityNodeInfo>()

    for (attempt in 1..maxAttempts) {
      latestRoots = getChatGptRootsForTgToGpt()

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

 private fun queueTextForImeFallbackForTgToGpt(text: String, autoSend: Boolean, reason: String, roots: List<AccessibilityNodeInfo>): Boolean {
 val trimmedText = text.trim()
 if (trimmedText.isEmpty()) { appendDebugLog("TG→GPT", "TG_TO_GPT_IME_FALLBACK_QUEUE skipped=true reason=$reason emptyText=true"); return false }
 val pendingId = System.currentTimeMillis()
 val prefs = getSharedPreferences("copybridge_ime_prefs", android.content.Context.MODE_PRIVATE)
 prefs.edit().putLong("pending_id", pendingId).putString("pending_text", trimmedText).putBoolean("pending_auto_send", autoSend).putString("pending_reason", reason).putLong("queued_at", pendingId).putLong("consumed_id", 0L).putLong("committed_id", 0L).putBoolean("commit_ok", false).putLong("committed_at", 0L).putBoolean("editor_action_ok", false).putLong("editor_action_at", 0L).putString("ime_last_event", "queued").putString("ime_last_trigger", "accessibility_queue").putString("ime_last_status", "pending_saved").putBoolean("ime_last_has_connection", false).putLong("ime_last_pending_id", pendingId).putInt("ime_last_text_length", trimmedText.length).putLong("ime_last_at", pendingId).putString("ime_last_error", "").apply()
 appendDebugLog("TG→GPT", "TG_TO_GPT_IME_FALLBACK_QUEUED pendingId=$pendingId reason=$reason textLength=${trimmedText.length} autoSend=$autoSend roots=${roots.size}")
 logImeSystemStatusForTgToGpt("afterQueue:$reason:$pendingId")
 appendDebugLog("TG→GPT", "TG_TO_GPT_IME_FALLBACK coordinateTap=false reason=$reason")
 val immediateRequested = try { CopyBridgeInputMethodService.requestCommitNow("immediate:$reason:$pendingId") } catch (error: Exception) { appendDebugLog("TG→GPT", "TG_TO_GPT_IME_REQUEST_COMMIT_ERROR phase=immediate pendingId=$pendingId reason=$reason ${error::class.java.simpleName}: ${compactLogPreview(error.message ?: "")}"); false }
 appendDebugLog("TG→GPT", "TG_TO_GPT_IME_REQUEST_COMMIT_NOW phase=immediate pendingId=$pendingId requested=$immediateRequested reason=$reason")
 logImeSystemStatusForTgToGpt("afterImmediateRequest:$reason:$pendingId")
 listOf(300L, 900L, 1600L, 2800L).forEachIndexed { index, delayMs -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ val requested = try { CopyBridgeInputMethodService.requestCommitNow("delayed$index:$reason:$pendingId") } catch (error: Exception) { appendDebugLog("TG→GPT", "TG_TO_GPT_IME_REQUEST_COMMIT_ERROR phase=delayed$index pendingId=$pendingId reason=$reason ${error::class.java.simpleName}: ${compactLogPreview(error.message ?: "")}"); false }; appendDebugLog("TG→GPT", "TG_TO_GPT_IME_REQUEST_COMMIT_NOW phase=delayed$index delayMs=$delayMs pendingId=$pendingId requested=$requested reason=$reason") }, delayMs) }
 android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
 val committedId = prefs.getLong("committed_id", 0L); val commitOk = prefs.getBoolean("commit_ok", false)
 if (committedId == pendingId && commitOk) { appendDebugLog("TG→GPT", "TG_TO_GPT_IME_PICKER_SKIPPED pendingId=$pendingId reason=$reason alreadyCommitted=true"); return@postDelayed }
 try { val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager; imm?.showInputMethodPicker(); appendDebugLog("TG→GPT", "TG_TO_GPT_IME_PICKER_SHOWN pendingId=$pendingId reason=$reason delayMs=650"); logImeSystemStatusForTgToGpt("afterPickerShown:$reason:$pendingId") } catch (error: Exception) { appendDebugLog("TG→GPT", "TG_TO_GPT_IME_PICKER_ERROR pendingId=$pendingId reason=$reason ${error::class.java.simpleName}: ${compactLogPreview(error.message ?: "")}") }
 }, 650L)
 scheduleImeFallbackResultChecksForTgToGpt(pendingId, autoSend, reason)
 return true
 }

 private fun scheduleImeFallbackResultChecksForTgToGpt(pendingId: Long, autoSend: Boolean, reason: String) {
 val sentOnce = java.util.concurrent.atomic.AtomicBoolean(false)
 listOf(900L, 1800L, 3200L, 5000L, 7200L).forEachIndexed { index, delayMs -> android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
 val prefs = getSharedPreferences("copybridge_ime_prefs", android.content.Context.MODE_PRIVATE)
 val data = (listOf(
 "committed_id" to prefs.getLong("committed_id", 0L).toString(),
 "commit_ok" to prefs.getBoolean("commit_ok", false).toString(),
 "committed_at" to prefs.getLong("committed_at", 0L).toString(),
 "editor_action_ok" to prefs.getBoolean("editor_action_ok", false).toString(),
 "editor_action_at" to prefs.getLong("editor_action_at", 0L).toString(),
 "ime_alive" to prefs.getBoolean("ime_alive", false).toString(),
 "ime_event" to prefs.getString("ime_last_event", "").orEmpty(),
 "ime_trigger" to prefs.getString("ime_last_trigger", "").orEmpty(),
 "ime_status" to prefs.getString("ime_last_status", "").orEmpty(),
 "ime_has_connection" to prefs.getBoolean("ime_last_has_connection", false).toString(),
 "ime_pending_id" to prefs.getLong("ime_last_pending_id", 0L).toString(),
 "ime_text_length" to prefs.getInt("ime_last_text_length", 0).toString(),
 "ime_last_at" to prefs.getLong("ime_last_at", 0L).toString(),
 "ime_error" to prefs.getString("ime_last_error", "").orEmpty()
 ).joinToString(" "))
 if (index == 0) { logImeSystemStatusForTgToGpt("fallbackCheck0:$reason:$pendingId") }
 appendDebugLog("TG→GPT", "TG_TO_GPT_IME_FALLBACK_CHECK index=$index delayMs=$delayMs pendingId=$pendingId autoSend=$autoSend reason=$reason data=$data")
 if (prefs.getBoolean("commit_ok", false) && autoSend && sentOnce.compareAndSet(false, true)) {
 val alreadySentByIme = prefs.getBoolean("editor_action_ok", false)
 if (alreadySentByIme) {
 appendDebugLog("TG→GPT", "TG_TO_GPT_IME_AUTOSEND_SKIPPED reason=imeAlreadySent")
 } else {
 val pendingText = prefs.getString("pending_text", "").orEmpty()
 val result = if (pendingText.isNotBlank()) {
 sendChatGptForTgToGpt(pendingText)
 } else {
 TgToGptSendResult.FAILED
 }
 appendDebugLog("TG→GPT", "TG_TO_GPT_IME_AUTOSEND_SAFE result=$result")
 }
 }
 }, delayMs) }
 }

 private fun logImeSystemStatusForTgToGpt(reason: String) {
 val component = android.content.ComponentName(this, CopyBridgeInputMethodService::class.java)
 val componentShort = component.flattenToShortString(); val componentFull = component.flattenToString()
 var defaultIme = ""; var defaultImeReadable = true; var defaultImeError = ""
 try { defaultIme = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty() } catch (error: Exception) { defaultImeReadable = false; defaultImeError = "${error::class.java.simpleName}: ${compactLogPreview(error.message ?: "")}" }
 var enabledImeSetting = ""; var enabledImeReadable = true; var enabledImeError = ""
 try { enabledImeSetting = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_INPUT_METHODS).orEmpty() } catch (error: Exception) { enabledImeReadable = false; enabledImeError = "${error::class.java.simpleName}: ${compactLogPreview(error.message ?: "")}" }
 val defaultIsCopy = defaultIme.contains(packageName) && defaultIme.contains("CopyBridgeInputMethodService")
 val enabledSettingHasCopy = enabledImeSetting.contains(packageName) && enabledImeSetting.contains("CopyBridgeInputMethodService")
 appendDebugLog("TG→GPT", "TG_TO_GPT_IME_SYSTEM_SETTINGS reason=$reason componentShort=$componentShort componentFull=$componentFull defaultReadable=$defaultImeReadable defaultIme=${compactLogPreview(defaultIme)} defaultIsCopy=$defaultIsCopy defaultError=${compactLogPreview(defaultImeError)} enabledReadable=$enabledImeReadable enabledSettingHasCopy=$enabledSettingHasCopy enabledSetting=${compactLogPreview(enabledImeSetting)} enabledError=${compactLogPreview(enabledImeError)}")
 try { val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager; val enabledList = imm?.enabledInputMethodList.orEmpty()
 val enabledSummary = enabledList.mapIndexed { index, info -> val subtypeCount = try { info.subtypeCount } catch (_: Exception) { -1 }; val subtypeSummary = try { val parts = mutableListOf<String>(); for (subIndex in 0 until subtypeCount.coerceAtLeast(0)) { val subtype = info.getSubtypeAt(subIndex); parts.add("sub[$subIndex]:mode=${subtype.mode},locale=${subtype.locale},extra=${compactLogPreview(subtype.extraValue)}") }; parts.joinToString(",") } catch (error: Exception) { "subtypeError=${error::class.java.simpleName}" }; "$index:id=${compactLogPreview(info.id)} pkg=${info.packageName} service=${info.serviceName} subtypes=$subtypeCount $subtypeSummary" }.joinToString(" ; ")
 val copyInEnabledList = enabledList.any { info -> info.packageName == packageName && (info.serviceName.contains("CopyBridgeInputMethodService") || info.id.contains("CopyBridgeInputMethodService")) }
 appendDebugLog("TG→GPT", "TG_TO_GPT_IME_SYSTEM_LIST reason=$reason enabledListCount=${enabledList.size} copyInEnabledList=$copyInEnabledList enabledList=${compactLogPreview(enabledSummary)}")
 } catch (error: Exception) { appendDebugLog("TG→GPT", "TG_TO_GPT_IME_SYSTEM_LIST_ERROR reason=$reason ${error::class.java.simpleName}: ${compactLogPreview(error.message ?: "")}") }
 }
 private fun getAiRootsDeepForTgToGpt(baseRoots: List<AccessibilityNodeInfo>, logReason: String): List<AccessibilityNodeInfo> {
 val roots = mutableListOf<AccessibilityNodeInfo>()
 fun addRoot(root: AccessibilityNodeInfo?, source: String) {
 if (root == null) return
 try { root.refresh() } catch (_: Exception) {}
 val packageName = root.packageName?.toString().orEmpty()
 if (!isChatGptPackageForTgToGpt(packageName)) return
 val rect = android.graphics.Rect(); root.getBoundsInScreen(rect); roots.add(root)
 appendDebugLog("TG→GPT", "TG_TO_GPT_DEEP_ROOT source=$source reason=$logReason package=$packageName class=${root.className} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom} childCount=${root.childCount}")
 }
 baseRoots.forEachIndexed { index, root -> addRoot(root, "base[$index]") }
 try { if (android.os.Build.VERSION.SDK_INT >= 33) { addRoot(getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE), "activePrefetchHybrid") } else { addRoot(rootInActiveWindow, "activeLegacy") } } catch (error: Exception) { appendDebugLog("TG→GPT", "TG_TO_GPT_DEEP_ROOT_ACTIVE_ERROR reason=$logReason ${error::class.java.simpleName}: ${compactLogPreview(error.message ?: "")}") }
 try { val windowList = getInteractiveWindowsAcrossAllDisplays(); appendDebugLog("TG→GPT", "TG_TO_GPT_DEEP_WINDOWS reason=$logReason count=${windowList.size}")
 windowList.forEachIndexed { index, window -> try { val root = if (android.os.Build.VERSION.SDK_INT >= 33) { window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE) } else { window.root }; addRoot(root, "window[$index]:type=${window.type}") } catch (error: Exception) { appendDebugLog("TG→GPT", "TG_TO_GPT_DEEP_WINDOW_ROOT_ERROR reason=$logReason index=$index ${error::class.java.simpleName}: ${compactLogPreview(error.message ?: "")}") } } } catch (error: Exception) { appendDebugLog("TG→GPT", "TG_TO_GPT_DEEP_WINDOWS_ERROR reason=$logReason ${error::class.java.simpleName}: ${compactLogPreview(error.message ?: "")}") }
 val deduped = roots.distinctBy { node -> val rect = android.graphics.Rect(); node.getBoundsInScreen(rect); "${node.packageName}|${node.className}|${rect.left},${rect.top},${rect.right},${rect.bottom}|${node.childCount}" }
 appendDebugLog("TG→GPT", "TG_TO_GPT_DEEP_ROOTS_DONE reason=$logReason base=${baseRoots.size} raw=${roots.size} deduped=${deduped.size}")
 return deduped
 }

 private fun logAiInputCandidateProbeForTgToGpt(roots: List<AccessibilityNodeInfo>, reason: String, maxLogs: Int = 28) {
 val candidates = mutableListOf<String>()
 fun visit(node: AccessibilityNodeInfo?, depth: Int) {
 if (node == null || candidates.size >= maxLogs) return
 val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
 val className = node.className?.toString().orEmpty(); val textValue = node.text?.toString().orEmpty(); val descValue = node.contentDescription?.toString().orEmpty()
 val hintValue = if (android.os.Build.VERSION.SDK_INT >= 26) { node.hintText?.toString().orEmpty() } else { "" }
 val packageName = node.packageName?.toString().orEmpty(); val actionIds = node.actionList.joinToString("|") { it.id.toString() }
 val lower = "$className $textValue $descValue $hintValue".lowercase()
 val looksLikeInput = node.isEditable || className.contains("EditText", true) || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE } || node.isFocused || node.isAccessibilityFocused || lower.contains("message") || lower.contains("메시지") || lower.contains("prompt") || lower.contains("ask") || lower.contains("send") || lower.contains("전송") || lower.contains("입력")
 val inLowerArea = roots.firstOrNull()?.let { root -> val rootRect = android.graphics.Rect(); root.getBoundsInScreen(rootRect); rect.top >= rootRect.top + (rootRect.height() * 0.55f).toInt() } ?: false
 if (looksLikeInput || inLowerArea) { candidates.add("depth=$depth package=$packageName class=$className editable=${node.isEditable} focused=${node.isFocused} aFocused=${node.isAccessibilityFocused} enabled=${node.isEnabled} clickable=${node.isClickable} actions=$actionIds bounds=${rect.left},${rect.top},${rect.right},${rect.bottom} text=${compactLogPreview(textValue)} desc=${compactLogPreview(descValue)} hint=${compactLogPreview(hintValue)}") }
 for (index in 0 until node.childCount) { visit(node.getChild(index), depth + 1) }
 }
 roots.forEach { root -> visit(root, 0) }
 appendDebugLog("TG→GPT", "TG_TO_GPT_DEEP_CANDIDATE_PROBE reason=$reason roots=${roots.size} candidates=${candidates.size}")
 candidates.take(maxLogs).forEachIndexed { index, line -> appendDebugLog("TG→GPT", "TG_TO_GPT_DEEP_CANDIDATE[$index] reason=$reason $line") }
 }

 private fun findAiEditableWithRetryForTgToGpt(initialRoots: List<AccessibilityNodeInfo>): Pair<AccessibilityNodeInfo?, List<AccessibilityNodeInfo>> {
 appendDebugLog("TG→GPT", "CB_BUILD_MARKER_203_22_OFFICIAL_DEEP")
 var latestRoots = initialRoots; var latestSearchRoots = initialRoots
 for (attempt in 1..5) {
 val reason = "attempt=$attempt"
 val deepRoots = getAiRootsDeepForTgToGpt(latestRoots, reason)
 val searchRoots = (latestRoots + deepRoots).distinctBy { node -> val rect = android.graphics.Rect(); node.getBoundsInScreen(rect); "${node.packageName}|${node.className}|${rect.left},${rect.top},${rect.right},${rect.bottom}|${node.childCount}" }
 latestSearchRoots = searchRoots
 val found = findEditableNodeFromRoots(searchRoots)
 appendDebugLog("TG→GPT", "TG_TO_GPT_FIND_EDIT attempt=$attempt roots=${searchRoots.size} baseRoots=${latestRoots.size} deepRoots=${deepRoots.size} found=${found != null}")
 if (found != null) { val rect = android.graphics.Rect(); found.getBoundsInScreen(rect); appendDebugLog("TG→GPT", "TG_TO_GPT_FIND_EDIT_FOUND_DEEP attempt=$attempt class=${found.className} editable=${found.isEditable} focused=${found.isFocused} actions=${found.actionList.joinToString("|") { it.id.toString() }} bounds=${rect.left},${rect.top},${rect.right},${rect.bottom} text=${compactLogPreview(found.text?.toString().orEmpty())} hint=${if (android.os.Build.VERSION.SDK_INT >= 26) compactLogPreview(found.hintText?.toString().orEmpty()) else ""}"); return Pair(found, searchRoots) }
 if (attempt == 1 || attempt == 5) { logAiInputCandidateProbeForTgToGpt(searchRoots, "notFound:$reason") }
 android.os.SystemClock.sleep(when (attempt) { 1 -> 260L; 2 -> 620L; else -> 250L })
 val refreshedRoots = getAiRootsWithRetryForTgToGpt("TG_TO_GPT_DEEP_REFRESH_ROOTS_$attempt")
 if (refreshedRoots.isNotEmpty()) { latestRoots = refreshedRoots }
 }
 logAiInputCandidateProbeForTgToGpt(latestSearchRoots, "finalNotFound")
 return Pair(null, latestSearchRoots)
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

  private fun finishTelegramToGptWithSelectedTexts(
    context: Context,
    copyMode: String,
    autoSend: Boolean,
    selectedTexts: List<String>
  ): Boolean {
    val textToSend = selectedTexts.joinToString("\n")
    appendDebugLog(
      "TG→GPT",
      "mode=$copyMode selected=${selectedTexts.size} textLength=${textToSend.length} preview=${compactLogPreview(textToSend)}"
    )
    appendDebugLog(
      "TG→GPT",
      "TG_TO_GPT_SELECTED count=${selectedTexts.size} textLength=${textToSend.length} preview=${compactLogPreview(textToSend)}"
    )

    val aiRoots = getAiRootsWithRetryForTgToGpt("TG_TO_GPT_AI_ROOTS")
    if (aiRoots.isEmpty()) {
      copyToClipboard(textToSend)
      appendDebugLog(
        "TG→GPT",
        "TG_TO_GPT_BLOCKED reason=noChatGptWindow textLength=${textToSend.length}"
      )
      Toast.makeText(
        context,
        "ChatGPT 창을 찾지 못했습니다. 원문은 클립보드에 보관했습니다.",
        Toast.LENGTH_LONG
      ).show()
      return true
    }

    val editResult = findAiEditableWithRetryForTgToGpt(aiRoots)
    val aiEdit = editResult.first
    appendDebugLog(
      "TG→GPT",
      "TG_TO_GPT_EDIT_RESULT found=${aiEdit != null} roots=${editResult.second.size} fallbackToInputConnection=${aiEdit == null}"
    )
    val inputOk = inputTextToChatGptForTgToGpt(
      text = textToSend,
      editNode = aiEdit,
      roots = editResult.second
    )
    appendDebugLog(
      "TG→GPT",
      "TG_TO_GPT_INPUT_DONE success=$inputOk editableFound=${aiEdit != null}"
    )
    if (!inputOk) {
      copyToClipboard(textToSend)
      appendDebugLog("TG→GPT", "TG_TO_GPT_BLOCKED reason=allInputPathsFailed")
      Toast.makeText(
        context,
        "ChatGPT 입력에 실패했습니다. 원문은 클립보드에 보관했습니다.",
        Toast.LENGTH_LONG
      ).show()
      return true
    }

    val actualCount = if (copyMode == COPY_MODE_LAST) 1 else selectedTexts.size
    if (!autoSend) {
      Toast.makeText(
        context,
        "GPT 입력창에 넣었습니다(전체: ${actualCount}개).",
        Toast.LENGTH_SHORT
      ).show()
      return true
    }

    Handler(Looper.getMainLooper()).postDelayed({
      when (sendChatGptForTgToGpt(textToSend)) {
        TgToGptSendResult.VERIFIED -> {
          Toast.makeText(
            context,
            "GPT로 전송했습니다(전체: ${actualCount}개).",
            Toast.LENGTH_SHORT
          ).show()
        }
        TgToGptSendResult.ISSUED_UNVERIFIED -> {
          Toast.makeText(
            context,
            "GPT 전송 동작을 실행했습니다. 화면에서 전송 여부를 확인해주세요.",
            Toast.LENGTH_LONG
          ).show()
        }
        TgToGptSendResult.FAILED -> {
          Toast.makeText(
            context,
            "GPT 입력은 완료했지만 안전한 전송 버튼을 찾지 못했습니다.",
            Toast.LENGTH_LONG
          ).show()
        }
      }
    }, 300L)
    return true
  }

  companion object {
    private const val TAG = "CopyBridgeA11y"
    private const val DEBUG_LOG_PREFS_NAME = "copybridge_debug_logs"
    private const val DEBUG_LOG_KEY = "logs"
    private const val MAX_DEBUG_LOG_ENTRIES = 150
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
    private const val YOUTUBE_PACKAGE_NAME = "com.google.android.youtube"
    private const val YOUTUBE_AUTO_SKIP_PREFS = "copybridge_youtube_settings"
    private const val YOUTUBE_AUTO_SKIP_KEY = "auto_skip_enabled"
    private const val YOUTUBE_SOURCE_SCAN_THROTTLE_MS = 300L
    private const val YOUTUBE_FULL_SCAN_THROTTLE_MS = 750L
    private const val YOUTUBE_SKIP_CLICK_COOLDOWN_MS = 1200L
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
      service.appendDebugLog("TG→GPT", "TG_TO_GPT_CANDIDATES count=${candidates.size}")
      if (candidates.isEmpty()) {
        val debug = service.buildTelegramCopyDebugInfo(tgRoots)
        service.copyToClipboard(debug)
        Toast.makeText(
          context,
          "Telegram 메시지를 찾지 못했습니다. 진단 정보가 복사되었습니다.",
          Toast.LENGTH_SHORT
        ).show()
        return false
      }
      val selectedTexts = if (copyMode == COPY_MODE_LAST) {
        candidates.takeLast(1).map { it.text }
      } else {
        val fullTexts = service.preferBottomTelegramClusterTexts(
          service.collectTelegramFullModeTexts(tgRoots)
        )
        if (fullTexts.isNotEmpty()) fullTexts else candidates.map { it.text }
      }
      return service.finishTelegramToGptWithSelectedTexts(
        context = context,
        copyMode = copyMode,
        autoSend = autoSend,
        selectedTexts = selectedTexts
      )
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

    private fun requestGptToTelegramNowInternal(context: Context, gptOutputMode: String, autoSend: Boolean): Boolean {
      val service = activeService
      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }

      service.appendDebugLog("GPT\u2192TG", "START mode=$gptOutputMode autoSend=$autoSend")
      service.appendDebugLog("GPT\u2192TG", "STEP after START")

       val aiRoots = service.getAiRoots()
      service.appendDebugLog("GPT\u2192TG", "STEP aiRoots=${aiRoots.size}")
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

    fun requestGptToTelegram(
      context: Context,
      gptOutputMode: String,
      autoSend: Boolean
    ): Boolean {
      val service = activeService
      if (service == null) {
        Toast.makeText(context, "CopyBridge 접근성 권한을 먼저 켜주세요.", Toast.LENGTH_SHORT).show()
        return false
      }
      return service.requestGptToTelegramWithReservation(context, gptOutputMode, autoSend)
    }

    fun requestGptCodeToTelegram(context: Context, autoSend: Boolean): Boolean =
      requestGptToTelegram(context, "CODE", autoSend)
  }
}

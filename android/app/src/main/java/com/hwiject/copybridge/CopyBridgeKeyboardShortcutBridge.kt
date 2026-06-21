package com.hwiject.copybridge

import java.lang.ref.WeakReference

object CopyBridgeKeyboardShortcutBridge {
 const val ACTION_TELEGRAM_TO_GPT = "telegram_to_gpt"
 const val ACTION_GPT_TO_TELEGRAM = "gpt_to_telegram"

 @Volatile
 private var floatingServiceRef: WeakReference<FloatingWidgetService>? = null

 fun attach(service: FloatingWidgetService) {
  floatingServiceRef = WeakReference(service)
 }

 fun detach(service: FloatingWidgetService) {
  if (floatingServiceRef?.get() === service) {
   floatingServiceRef = null
  }
 }

 fun handle(action: String): Boolean {
  val service = floatingServiceRef?.get() ?: return false
  return service.handleHardwareKeyboardShortcutAction(action)
 }
}

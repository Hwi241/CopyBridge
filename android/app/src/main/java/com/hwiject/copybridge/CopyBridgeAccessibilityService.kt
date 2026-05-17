package com.hwiject.copybridge

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class CopyBridgeAccessibilityService : AccessibilityService() {
 override fun onAccessibilityEvent(event: AccessibilityEvent?) {
 // 다음 단계에서 현재 앱 패키지 확인, 텔레그램 텍스트 수집, 입력창 탐색 로직을 추가한다.
 }

 override fun onInterrupt() {
 // 접근성 서비스가 중단될 때 필요한 정리 로직을 이후 단계에서 추가한다.
 }
}

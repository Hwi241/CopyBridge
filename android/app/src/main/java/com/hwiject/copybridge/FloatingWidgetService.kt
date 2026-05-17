package com.hwiject.copybridge

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.widget.Toast

class FloatingWidgetService : Service() {
 override fun onCreate() {
 super.onCreate()
 Toast.makeText(this, "CopyBridge 위젯 서비스 준비", Toast.LENGTH_SHORT).show()
 }

 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
 return START_NOT_STICKY
 }

 override fun onBind(intent: Intent?): IBinder? {
 return null
 }
}

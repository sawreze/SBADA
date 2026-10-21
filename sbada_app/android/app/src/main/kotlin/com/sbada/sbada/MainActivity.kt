package com.sbada.sbada

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 스바다 알림 표시(푸시).
 * 서버 → (WebSocket 'notification' 이벤트) → Dart → MethodChannel("sbada/notify") → 이 코드 → Android 알림 표시줄
 * 패키지를 추가하지 않고 Android 기본 NotificationManager 를 직접 쓴다.
 * 또 MethodChannel("sbada/config") 로 카카오 네이티브 앱 키를 Dart 에 전달한다(키는 소스에 없음).
 */
class MainActivity : FlutterActivity() {

    private val channelName = "sbada/notify"
    private val configChannelName = "sbada/config"
    private val notificationChannelId = "sbada_default"
    private val permissionRequestCode = 1001

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        createNotificationChannel()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermission" -> {
                    if (!hasNotificationPermission()) requestNotificationPermission()
                    result.success(hasNotificationPermission())
                }
                "show" -> {
                    val id = call.argument<Int>("id") ?: 0
                    val title = call.argument<String>("title") ?: "스바다"
                    val body = call.argument<String>("body") ?: ""
                    result.success(show(id, title, body))
                }
                else -> result.notImplemented()
            }
        }
        // 설정값 전달: 카카오 네이티브 앱 키(AndroidManifest meta-data ← android/local.properties)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, configChannelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "kakaoNativeAppKey" -> result.success(kakaoNativeAppKey())
                else -> result.notImplemented()
            }
        }
    }

    private fun kakaoNativeAppKey(): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        }
        // 키가 숫자로만 되어 있으면 meta-data 가 숫자로 읽힐 수 있어 get()?.toString() 으로 받는다
        return info.metaData?.get("com.sbada.KAKAO_NATIVE_APP_KEY")?.toString()?.trim() ?: ""
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), permissionRequestCode)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                notificationChannelId,
                "스바다 알림",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "교환 요청·수락, 새 채팅, 비슷한 가격대 물건 알림" }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun show(id: Int, title: String, body: String): Boolean {
        if (!hasNotificationPermission()) return false
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val iconId = resources.getIdentifier("ic_stat_sbada", "drawable", packageName)
            .takeIf { it != 0 } ?: applicationInfo.icon
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, notificationChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
        }
        val notification = builder
            .setSmallIcon(iconId)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setColor(0xFF2F6BFF.toInt())
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
        return true
    }
}

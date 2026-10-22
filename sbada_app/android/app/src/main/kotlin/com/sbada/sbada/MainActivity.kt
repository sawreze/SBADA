package com.sbada.sbada

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * 스바다 알림 표시(푸시).
 * 서버 → (WebSocket 'notification' 이벤트) → Dart → MethodChannel("sbada/notify") → 이 코드 → Android 알림 표시줄
 * 패키지를 추가하지 않고 Android 기본 NotificationManager 를 직접 쓴다.
 * 또 MethodChannel("sbada/config") 로 카카오 네이티브 앱 키를 Dart 에 전달한다(키는 소스에 없음).
 * MethodChannel("sbada/location") 은 GPS 로 지금 위치를 한 번 읽는다(동네 찾기, 패키지 없이 LocationManager).
 */
class MainActivity : FlutterActivity() {

    private val channelName = "sbada/notify"
    private val configChannelName = "sbada/config"
    private val notificationChannelId = "sbada_default"
    private val permissionRequestCode = 1001
    private val locationChannelName = "sbada/location"
    private val locationRequestCode = 1002
    private var pendingLocation: MethodChannel.Result? = null

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
        // GPS: 지금 위치(위도 · 경도) 한 번 읽기 → Dart 가 서버 /api/geo/reverse 로 동네 이름을 찾는다
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, locationChannelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "current" -> {
                    if (hasLocationPermission()) {
                        currentLocation(result)
                    } else {
                        pendingLocation?.error("CANCELLED", "다른 위치 요청이 들어왔어요", null)
                        pendingLocation = result
                        requestPermissions(
                            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                            locationRequestCode
                        )
                    }
                }
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


    // ===== GPS(동네 찾기) =====
    // 동네 이름만 필요해서 '대략적인 위치'(ACCESS_COARSE_LOCATION)면 충분하다. 사용자가 '정확한 위치'를 허용하면 GPS 도 쓴다.
    // 결과: {lat, lng} / 오류 코드: PERMISSION_DENIED · LOCATION_OFF · TIMEOUT (Dart 가 안내 문구로 바꾼다)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != locationRequestCode) return
        val result = pendingLocation ?: return
        pendingLocation = null
        if (hasLocationPermission()) currentLocation(result)
        else result.error("PERMISSION_DENIED", "위치 권한이 없어요", null)
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun currentLocation(result: MethodChannel.Result) {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
        }
        val providers = candidates.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            result.error("LOCATION_OFF", "휴대폰의 위치(GPS)가 꺼져 있어요", null)
            return
        }
        try {
            // 10분 안에 잡힌 위치가 있으면 그걸 쓴다(동네는 잘 안 바뀌니까 빠르게)
            val last = providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000L) {
                result.success(mapOf("lat" to last.latitude, "lng" to last.longitude))
                return
            }
            var done = false
            val handler = Handler(Looper.getMainLooper())
            fun finish(location: Location?) {
                if (done) return
                done = true
                val loc = location ?: last
                if (loc != null) result.success(mapOf("lat" to loc.latitude, "lng" to loc.longitude))
                else result.error("TIMEOUT", "위치를 찾지 못했어요", null)
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                handler.postDelayed({ signal.cancel(); finish(null) }, 15_000L)
                manager.getCurrentLocation(providers.first(), signal, mainExecutor) { finish(it) }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = finish(location)
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                handler.postDelayed({ manager.removeUpdates(listener); finish(null) }, 15_000L)
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(providers.first(), listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            result.error("PERMISSION_DENIED", "위치 권한이 없어요", null)
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
            .setColor(0xFF2E9E63.toInt()) // 알림 아이콘 색(민트 계열 초록, 흰 바탕에서도 보이게)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
        return true
    }
}

package com.example.gooble

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.gooble/overlay"
    private val OVERLAY_REQ = 1234
    private val PROJECTION_REQ = 5678

    companion object {
        var projectionResultCode = 0
        var projectionData: Intent? = null
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startGooble" -> {
                        if (canDrawOverlays()) {
                            requestProjection()
                            result.success("started")
                        } else {
                            requestOverlayPermission()
                            result.success("permission_needed")
                        }
                    }
                    "stopGooble" -> {
                        stopService(Intent(this, GoobleService::class.java))
                        result.success("stopped")
                    }
                    "hasPermission" -> result.success(canDrawOverlays())
                    else -> result.notImplemented()
                }
            }
    }

    private fun canDrawOverlays() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this) else true

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")), OVERLAY_REQ)
        }
    }

    private fun requestProjection() {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), PROJECTION_REQ)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQ && resultCode == Activity.RESULT_OK) {
            projectionResultCode = resultCode
            projectionData = data
            startGoobleService()
        } else if (requestCode == OVERLAY_REQ) {
            if (canDrawOverlays()) requestProjection()
        }
    }

    private fun startGoobleService() {
        val intent = Intent(this, GoobleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else startService(intent)
    }
}

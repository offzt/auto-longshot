package com.longshot.autoshot

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.longshot.autoshot.service.CaptureAccessibilityService
import com.longshot.autoshot.service.CaptureService

/**
 * 主界面：权限引导 + 开始/停止截屏 + 参数设置。
 *
 * 流程：授权（无障碍/通知/悬浮窗）→ 媒体投影授权 → 启动前台服务自动滚动截屏。
 * 停止截屏：本页"停止并保存"按钮 / 悬浮窗按钮 / 通知栏按钮，程序不会自动停止。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnViaProjection: TextView
    private lateinit var btnKeepAlive: TextView
    private lateinit var tvKeepAliveSteps: TextView
    private lateinit var etSlideRatio: EditText
    private lateinit var etInterval: EditText
    private lateinit var etScale: EditText
    private lateinit var etMaxFrames: EditText

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                // 把授权结果传给前台服务
                startService(
                    Intent(this, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(CaptureService.EXTRA_DATA, result.data)
                )
            } else {
                Toast.makeText(this, "已取消截屏授权", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnOverlay = findViewById(R.id.btnEnableOverlay)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnViaProjection = findViewById(R.id.btnViaProjection)
        btnKeepAlive = findViewById(R.id.btnKeepAlive)
        tvKeepAliveSteps = findViewById(R.id.tvKeepAliveSteps)
        etSlideRatio = findViewById(R.id.etSlideRatio)
        etInterval = findViewById(R.id.etInterval)
        etScale = findViewById(R.id.etScale)
        etMaxFrames = findViewById(R.id.etMaxFrames)

        loadSettingsToUi()

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }
        // 无障碍免授权快捷模式（服务健康时免录屏弹窗）
        btnViaProjection.setOnClickListener { startViaAccessibility() }
        // 保活引导：防止国产 ROM 后台清理导致无障碍服务被关
        btnKeepAlive.setOnClickListener { applyKeepAlive() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ------------------------------------------------------------------
    // 状态与权限
    // ------------------------------------------------------------------

    private fun refreshStatus() {
        val accOn = CaptureAccessibilityService.isConnected
        btnAccessibility.text =
            getString(if (accOn) R.string.status_enabled_accessibility else R.string.status_accessibility)
        btnAccessibility.setBackgroundColor(
            ContextCompat.getColor(this, if (accOn) android.R.color.holo_green_light else android.R.color.holo_red_light)
        )

        val overlayOn = Settings.canDrawOverlays(this)
        btnOverlay.text =
            getString(if (overlayOn) R.string.status_enabled_overlay else R.string.status_overlay)
        btnOverlay.setBackgroundColor(
            ContextCompat.getColor(this, if (overlayOn) android.R.color.holo_green_light else android.R.color.holo_red_light)
        )

        tvStatus.text = if (CaptureService.isCapturing) {
            "● 截屏进行中（已截 ${CaptureService.currentFrames} 帧）：请切到目标应用查看滚动，" +
                "结束请点下方「停止并保存」或悬浮窗/通知栏按钮"
        } else {
            getString(R.string.status_ready)
        }
    }

    private fun isNotificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun onStartClicked() {
        // 1. 无障碍服务（自动滚动必须要用）
        if (!CaptureAccessibilityService.isConnected) {
            Toast.makeText(this, R.string.toast_no_accessibility, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // 2. 通知权限（Android 13+）
        if (!isNotificationPermissionGranted()) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 3. 悬浮窗权限（非必需，仅引导）
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "建议开启悬浮窗权限，以便在聊天界面快速停止截屏", Toast.LENGTH_LONG).show()
        }
        // 4. 保存设置
        saveSettingsFromUi()

        // 5. 默认路线：系统录屏授权（MediaProjection）——最稳定，兼容所有 ROM（每次会话授权一次）
        tvStatus.text = "正在请求屏幕录制授权…请在系统弹窗点「允许」"
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /** 无障碍免授权快捷模式（Android 11+ 且服务健康时：无弹窗直接启动） */
    private fun startViaAccessibility() {
        if (!CaptureAccessibilityService.isConnected) {
            Toast.makeText(this, R.string.toast_no_accessibility, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!isNotificationPermissionGranted()) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        saveSettingsFromUi()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("无障碍截图需 Android 11+，已改用录屏授权")
            projectionLauncher.launch(
                (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                    .createScreenCaptureIntent()
            )
            return
        }
        startService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
        )
        tvStatus.text =
            "● 截屏进行中（无障碍快捷模式），请切到目标应用查看滚动；" +
                "结束时点「停止并保存」或悬浮窗/通知栏按钮"
        Toast.makeText(this, "已启动（无障碍快捷模式），请切到目标应用开始滚动", Toast.LENGTH_LONG).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /**
     * 保活引导（国产 ROM：小米/红米/HyperOS 后台清理会把进程杀掉，
     * 导致无障碍服务从"已开启"自动变回"已关闭"）。
     * 步骤：免电池优化 → 自启动 → 最近任务锁定 → 省电策略无限制。
     */
    private fun applyKeepAlive() {
        // 1. 展开步骤说明
        tvKeepAliveSteps.visibility = View.VISIBLE
        tvKeepAliveSteps.text = getString(R.string.keep_alive_step1) + "\n" +
            getString(R.string.keep_alive_step2) + "\n" +
            getString(R.string.keep_alive_step3) + "\n" +
            getString(R.string.keep_alive_step4)

        // 2. 免电池优化（系统弹确认框）
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                // 部分 ROM 不支持该 intent，直接跳应用详情页
                goToAppDetails()
            }
        } else {
            Toast.makeText(this, "已开启免电池优化", Toast.LENGTH_SHORT).show()
        }

        // 3. 跳转厂商自启动管理页（小米等）
        openAutoStartSettings()
    }

    /** 厂商自启动管理页；失败则退回应用详情 */
    private fun openAutoStartSettings() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        try {
            val component: ComponentName? = when {
                manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                    ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                manufacturer.contains("oppo") || manufacturer.contains("oneplus") ->
                    ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                else -> null
            }
            if (component != null) {
                val intent = Intent().setComponent(component)
                startActivity(intent)
            } else {
                goToAppDetails()
            }
        } catch (e: Exception) {
            goToAppDetails()
        }
    }

    private fun goToAppDetails() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // 设置持久化
    // ------------------------------------------------------------------

    private fun loadSettingsToUi() {
        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
        etSlideRatio.setText(sp.getInt("slideRatio", 60).toString())
        etInterval.setText(sp.getInt("interval", 700).toString())
        etScale.setText(sp.getFloat("scale", 1.0f).toString())
        etMaxFrames.setText(sp.getInt("maxFrames", 200).toString())
    }

    private fun saveSettingsFromUi() {
        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp.edit()
            .putInt("slideRatio", etSlideRatio.text.toString().toIntOrNull()?.coerceIn(10, 85) ?: 60)
            .putInt("interval", etInterval.text.toString().toIntOrNull()?.coerceIn(200, 5000) ?: 700)
            .putFloat("scale", etScale.text.toString().toFloatOrNull()?.coerceIn(0.5f, 1.0f) ?: 1.0f)
            .putInt("maxFrames", etMaxFrames.text.toString().toIntOrNull()?.coerceIn(20, 1000) ?: 200)
            .apply()
    }
}

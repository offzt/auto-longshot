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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.longshot.autoshot.service.CaptureAccessibilityService
import com.longshot.autoshot.service.CaptureService

/**
 * 主界面：权限状态可视化 + 滑杆设置 + 开始/停止截屏。
 *
 * 流程：授权（无障碍/通知/悬浮窗）→ 媒体投影授权 → 启动前台服务自动滚动截屏。
 * 停止截屏：本页"停止并保存" / 悬浮窗按钮 / 通知栏按钮，程序不会自动停止
 * （仅内存预算/高度上限等保护条件会触发自动停止并保存）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var dotAcc: View
    private lateinit var dotOv: View
    private lateinit var dotNt: View
    private lateinit var btnAccessibility: TextView
    private lateinit var btnOverlay: TextView
    private lateinit var btnNotification: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnViaProjection: MaterialButton
    private lateinit var btnKeepAlive: MaterialButton
    private lateinit var tvKeepAliveSteps: TextView
    private lateinit var sRatio: Slider
    private lateinit var tvRatioVal: TextView
    private lateinit var sInterval: Slider
    private lateinit var tvIntVal: TextView
    private lateinit var scaleGroup: MaterialButtonToggleGroup
    private lateinit var tvMeta: TextView

    private var screenHeightPx = 0

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        dotAcc = findViewById(R.id.dotAcc)
        dotOv = findViewById(R.id.dotOv)
        dotNt = findViewById(R.id.dotNt)
        btnAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnOverlay = findViewById(R.id.btnEnableOverlay)
        btnNotification = findViewById(R.id.btnEnableNotification)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnViaProjection = findViewById(R.id.btnViaProjection)
        btnKeepAlive = findViewById(R.id.btnKeepAlive)
        tvKeepAliveSteps = findViewById(R.id.tvKeepAliveSteps)
        sRatio = findViewById(R.id.sRatio)
        tvRatioVal = findViewById(R.id.tvRatioVal)
        sInterval = findViewById(R.id.sInterval)
        tvIntVal = findViewById(R.id.tvIntVal)
        scaleGroup = findViewById(R.id.scaleGroup)
        tvMeta = findViewById(R.id.tvMeta)

        screenHeightPx = resources.displayMetrics.heightPixels

        loadSettingsToUi()
        wireListeners()

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
        btnNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "当前系统无需单独授权通知", Toast.LENGTH_SHORT).show()
            }
        }
        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }
        btnViaProjection.setOnClickListener { startViaAccessibility() }
        btnKeepAlive.setOnClickListener { applyKeepAlive() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        updateMeta()
    }

    // ------------------------------------------------------------------
    // 控件联动
    // ------------------------------------------------------------------

    private fun wireListeners() {
        sRatio.addOnChangeListener { _, value, _ ->
            tvRatioVal.text = "${value.toInt()}%"
            updateMeta()
        }
        sInterval.addOnChangeListener { _, value, _ ->
            tvIntVal.text = "${value.toInt()}ms"
        }
        scaleGroup.addOnButtonCheckedListener { _, _, _ ->
            updateMeta()
        }
    }

    /** 实时估算：当前滑动距离下每屏新增多少像素 */
    private fun updateMeta() {
        val ratio = sRatio.value
        val scale = selectedScale()
        // 与 CaptureAccessibilityService 相同的手势几何：实际滑动受"终点保护线"钳制
        val startY = (screenHeightPx * 0.62f).toInt()
        val topGuard = (screenHeightPx * 0.12f).toInt()
        val travel = minOf((screenHeightPx * ratio / 100f).toInt(), startY - topGuard)
        val fresh = ((screenHeightPx - 205 - travel) * scale).toInt()
        tvMeta.text = "当前设置：每次新增 ≈ ${fresh}px · 内存与高度上限自动保护，无需手动设屏数"
    }

    private fun selectedScale(): Float = when (scaleGroup.checkedButtonId) {
        R.id.btnScale075 -> 0.75f
        R.id.btnScale050 -> 0.5f
        else -> 1.0f
    }

    // ------------------------------------------------------------------
    // 状态与权限
    // ------------------------------------------------------------------

    private fun refreshStatus() {
        val accOn = CaptureAccessibilityService.isConnected
        setPerm(btnAccessibility, dotAcc, accOn)

        val overlayOn = Settings.canDrawOverlays(this)
        setPerm(btnOverlay, dotOv, overlayOn)

        val notifOn = isNotificationPermissionGranted()
        setPerm(btnNotification, dotNt, notifOn)

        tvStatus.text = when {
            CaptureService.isCapturing ->
                getString(R.string.status_capturing, CaptureService.currentFrames)
            else -> getString(R.string.status_ready)
        }
        tvStatus.setBackgroundResource(
            if (CaptureService.isCapturing) R.drawable.pill_ok else R.drawable.pill_idle
        )
    }

    private fun setPerm(btn: TextView, dot: View, on: Boolean) {
        dot.setBackgroundResource(if (on) R.drawable.dot_ok else R.drawable.dot_bad)
        btn.text = getString(if (on) R.string.perm_on else R.string.perm_go)
        btn.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.success else R.color.primary)
        )
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
        tvStatus.setBackgroundResource(R.drawable.pill_idle)
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
        Toast.makeText(this, "已启动（无障碍快捷模式），请切到目标应用开始滚动", Toast.LENGTH_LONG).show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    /**
     * 保活引导（国产 ROM：小米/红米/HyperOS 后台清理会把进程杀掉，
     * 导致无障碍服务从"已开启"自动变回"已关闭"）。
     */
    private fun applyKeepAlive() {
        tvKeepAliveSteps.visibility = View.VISIBLE
        tvKeepAliveSteps.text = getString(R.string.keep_alive_hint) + "\n" +
            getString(R.string.keep_alive_step1) + "\n" +
            getString(R.string.keep_alive_step2) + "\n" +
            getString(R.string.keep_alive_step3) + "\n" +
            getString(R.string.keep_alive_step4)

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
                goToAppDetails()
            }
        } else {
            Toast.makeText(this, "已开启免电池优化", Toast.LENGTH_SHORT).show()
        }
        openAutoStartSettings()
    }

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
                startActivity(Intent().setComponent(component))
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
        sRatio.value = sp.getInt("slideRatio", 60).toFloat().coerceIn(10f, 85f)
        sInterval.value = sp.getInt("interval", 900).toFloat().coerceIn(600f, 2000f)
        tvRatioVal.text = "${sRatio.value.toInt()}%"
        tvIntVal.text = "${sInterval.value.toInt()}ms"
        when (sp.getFloat("scale", 1.0f)) {
            0.75f -> scaleGroup.check(R.id.btnScale075)
            0.5f -> scaleGroup.check(R.id.btnScale050)
            else -> scaleGroup.check(R.id.btnScale100)
        }
    }

    private fun saveSettingsFromUi() {
        val sp = getSharedPreferences("settings", Context.MODE_PRIVATE)
        sp.edit()
            .putInt("slideRatio", sRatio.value.toInt())
            .putInt("interval", sInterval.value.toInt())
            .putFloat("scale", selectedScale())
            .apply()
    }
}

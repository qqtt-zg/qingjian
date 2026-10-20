//! 现代化全屏沉浸式二维码扫描页；采用原生系统质感取景框、激光线与手电筒辅助。
package org.qingjian.speech

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class ScanActivity : CaptureActivity() {
    private lateinit var barcodeScanner: DecoratedBarcodeView
    private lateinit var btnTorch: ImageButton
    private lateinit var laserLine: View
    private var isTorchOn = false
    private var laserAnimator: ObjectAnimator? = null

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_scan)
        barcodeScanner = findViewById(R.id.zxing_barcode_scanner)
        // 隐藏老旧的默认灰色单调提示文字，改用现代居中胶囊
        barcodeScanner.setStatusText(null)
        return barcodeScanner
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸式全屏透明状态栏
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }

        super.onCreate(savedInstanceState)

        // 绑定顶部关闭按钮
        findViewById<ImageButton>(R.id.btnScanClose).setOnClickListener {
            finish()
        }

        // 绑定手电筒照明开关
        btnTorch = findViewById(R.id.btnScanTorch)
        btnTorch.setOnClickListener {
            toggleTorch()
        }

        // 启动激光线往复平滑扫动动画
        laserLine = findViewById(R.id.scanLaserLine)
        startLaserAnimation()
    }

    private fun toggleTorch() {
        isTorchOn = !isTorchOn
        if (isTorchOn) {
            barcodeScanner.setTorchOn()
            btnTorch.isSelected = true
        } else {
            barcodeScanner.setTorchOff()
            btnTorch.isSelected = false
        }
        btnTorch.setImageResource(if(isTorchOn)R.drawable.ic_flashlight_off else R.drawable.ic_flashlight)
        btnTorch.contentDescription=if(isTorchOn)"关闭手电筒" else "打开手电筒"
    }

    private fun startLaserAnimation() {
        val density = resources.displayMetrics.density
        val travelDistance = 250f * density
        laserAnimator = ObjectAnimator.ofFloat(laserLine, "translationY", 0f, travelDistance).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (laserAnimator?.isPaused == true) {
            laserAnimator?.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        laserAnimator?.pause()
        if (isTorchOn) {
            barcodeScanner.setTorchOff()
            isTorchOn = false
            btnTorch.isSelected = false
            btnTorch.setImageResource(R.drawable.ic_flashlight)
            btnTorch.contentDescription="打开手电筒"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        laserAnimator?.cancel()
        laserAnimator = null
    }
}

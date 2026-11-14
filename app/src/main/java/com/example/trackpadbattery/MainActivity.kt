package com.example.trackpadbattery

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var ringView: BatteryRingView
    private lateinit var percentText: TextView
    private lateinit var statusSubText: TextView

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> checkBattery()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    ringView.setBattery(-1, false)
                    percentText.text = "--%"
                    statusSubText.text = "케이블이 분리되었습니다"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(dp(28), dp(32), dp(28), dp(32))
        }

        // iOS 위젯 스타일 배터리 카드 (터치 시 새로고침)
        val widgetCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(36).toFloat()
                setColor(Color.parseColor("#B8BCC2"))
            }
            setPadding(dp(36), dp(36), dp(36), dp(32))
            setOnClickListener { checkBattery() }
        }

        ringView = BatteryRingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(170), dp(170)).apply {
                bottomMargin = dp(16)
            }
        }

        percentText = TextView(this).apply {
            text = "--%"
            setTextColor(Color.parseColor("#111111"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }

        statusSubText = TextView(this).apply {
            text = "C타입 케이블을 연결하거나 위젯을 터치하세요"
            setTextColor(Color.parseColor("#3A3A3C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }

        widgetCard.addView(ringView)
        widgetCard.addView(percentText)
        widgetCard.addView(statusSubText)

        root.addView(widgetCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbAttachReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbAttachReceiver, filter)
        }

        checkBattery()
    }

    override fun onResume() {
        super.onResume()
        checkBattery()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbAttachReceiver)
        } catch (_: Throwable) {}
    }

    private fun checkBattery() {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList.values
            if (devices.isEmpty()) {
                ringView.setBattery(-1, false)
                percentText.text = "--%"
                statusSubText.text = "C타입 케이블을 연결하거나 위젯을 터치하세요"
                return
            }

            val device = devices.firstOrNull {
                it.vendorId == 0x05AC && (it.productId == 0x0324 || it.productId == 0x0265)
            } ?: devices.firstOrNull { it.vendorId == 0x05AC } ?: devices.first()

            if (!usbManager.hasPermission(device)) {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
                val pi = PendingIntent.getBroadcast(this, 0, Intent("USB_PERM"), flags)
                usbManager.requestPermission(device, pi)
                statusSubText.text = "권한 팝업에서 [확인] 후 위젯을 터치하세요"
                return
            }

            val conn = usbManager.openDevice(device) ?: run {
                statusSubText.text = "USB 장치를 열 수 없습니다"
                return
            }
            try {
                readBattery(conn, device)
            } finally {
                conn.close()
            }
        } catch (t: Throwable) {
            statusSubText.text = "오류: ${t.javaClass.simpleName}"
        }
    }

    private fun readBattery(conn: UsbDeviceConnection, device: UsbDevice) {
        val buf = ByteArray(128)
        val reqIn = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01
        val reqOut = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (!conn.claimInterface(intf, true)) continue

            val wake = byteArrayOf(0xF1.toByte(), 0x02, 0x01)
            conn.controlTransfer(reqOut, 0x09, 0x03F1, intf.id, wake, wake.size, 300)

            for (wVal in intArrayOf(0x0190, 0x0390)) {
                buf.fill(0)
                buf[0] = 0x90.toByte()
                val len = conn.controlTransfer(reqIn, 0x01, wVal, intf.id, buf, buf.size, 1000)
                val b0 = buf[0].toInt() and 0xFF
                val b1 = buf[1].toInt() and 0xFF
                val b2 = buf[2].toInt() and 0xFF
                if (len >= 3 && b0 == 0x90 && b2 in 0..100 && (len > 3 || b1 != 0 || b2 != 0)) {
                    val charging = (b1 and 0x02) != 0
                    ringView.setBattery(b2, charging)
                    percentText.text = "$b2%"
                    statusSubText.text = "위젯을 터치하면 새로고침됩니다"
                    conn.releaseInterface(intf)
                    return
                }
            }
            conn.releaseInterface(intf)
        }
        statusSubText.text = "트랙패드를 살짝 문지른 뒤 위젯을 터치하세요"
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    class BatteryRingView(context: Context) : View(context) {
        private var percentage: Int = -1
        private var isCharging: Boolean = false

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#8E9299")
            strokeCap = Paint.Cap.ROUND
        }

        private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2CE553")
            strokeCap = Paint.Cap.ROUND
        }

        private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#1C1C1E")
        }

        private val iconLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#B8BCC2")
            strokeCap = Paint.Cap.ROUND
        }

        private val arcRect = RectF()
        private val padRect = RectF()
        private val boltPath = Path()

        fun setBattery(pct: Int, charging: Boolean) {
            percentage = pct
            isCharging = charging
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val stroke = w * 0.11f
            val radius = (minOf(w, h) / 2f) - stroke

            trackPaint.strokeWidth = stroke
            progressPaint.strokeWidth = stroke

            arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawOval(arcRect, trackPaint)

            if (percentage in 0..100) {
                progressPaint.color = when {
                    percentage <= 15 -> Color.parseColor("#FF3B30")
                    percentage <= 25 -> Color.parseColor("#FFCC00")
                    else -> Color.parseColor("#2CE553")
                }
                val sweep = (percentage / 100f) * 360f
                canvas.drawArc(arcRect, -90f, sweep, false, progressPaint)
            }

            // 중앙 매직 트랙패드 아이콘
            val padW = radius * 0.82f
            val padH = radius * 0.64f
            val corner = padW * 0.16f
            padRect.set(cx - padW / 2f, cy - padH / 2f, cx + padW / 2f, cy + padH / 2f)
            canvas.drawRoundRect(padRect, corner, corner, iconFillPaint)

            iconLinePaint.strokeWidth = stroke * 0.14f
            val lineY = padRect.bottom - padH * 0.20f
            canvas.drawLine(padRect.left + padW * 0.15f, lineY, padRect.right - padW * 0.15f, lineY, iconLinePaint)

            // 상단 번개 표시
            if (isCharging) {
                val topY = cy - radius
                val bSize = stroke * 0.65f
                boltPath.reset()
                boltPath.moveTo(cx + bSize * 0.15f, topY - bSize)
                boltPath.lineTo(cx - bSize * 0.55f, topY + bSize * 0.1f)
                boltPath.lineTo(cx - bSize * 0.05f, topY + bSize * 0.1f)
                boltPath.lineTo(cx - bSize * 0.20f, topY + bSize)
                boltPath.lineTo(cx + bSize * 0.55f, topY - bSize * 0.1f)
                boltPath.lineTo(cx + bSize * 0.05f, topY - bSize * 0.1f)
                boltPath.close()
                canvas.drawPath(boltPath, iconFillPaint)
            }
        }
    }
}

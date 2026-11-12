package com.example.trackpadbattery

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var usbManager: UsbManager
    private lateinit var statusText: TextView

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.trackpadbattery.USB_PERMISSION"
        private const val APPLE_VID = 0x05AC
        private val TRACKPAD_PIDS = setOf(0x0324, 0x0265)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    readBattery(device)
                } else {
                    statusText.text = "USB 접근 권한이 거부되었습니다."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            text = "매직 트랙패드를 USB-C로 연결하세요."
        }

        val refreshBtn = Button(this).apply {
            text = "배터리 확인"
            setOnClickListener { checkConnectedTrackpad() }
        }

        layout.addView(statusText)
        layout.addView(refreshBtn)
        setContentView(layout)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        checkConnectedTrackpad()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun checkConnectedTrackpad() {
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == APPLE_VID && it.productId in TRACKPAD_PIDS
        }
        if (device == null) {
            statusText.text = "연결된 매직 트랙패드를 찾을 수 없습니다.\n(C-to-C 케이블 연결 확인)"
            return
        }

        if (usbManager.hasPermission(device)) {
            readBattery(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val permIntent = Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) }
            val pendingIntent = PendingIntent.getBroadcast(this, 0, permIntent, flags)
            usbManager.requestPermission(device, pendingIntent)
        }
    }

    private fun readBattery(device: UsbDevice) {
        val connection = usbManager.openDevice(device) ?: run {
            statusText.text = "USB 장치를 열 수 없습니다."
            return
        }

        try {
            val buffer = ByteArray(8)
            val requestType = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (!connection.claimInterface(intf, true)) continue

                for (reportValue in intArrayOf(0x0190, 0x0390)) {
                    val len = connection.controlTransfer(
                        requestType,
                        0x01,
                        reportValue,
                        intf.id,
                        buffer,
                        buffer.size,
                        1000
                    )
                    if (len >= 3 && (buffer[0].toInt() and 0xFF) == 0x90) {
                        val flags = buffer[1].toInt() and 0xFF
                        val percent = buffer[2].toInt() and 0xFF
                        val charging = (flags and 0x02) != 0
                        val chargeLabel = if (charging) "⚡ 충전 중" else "🔋 배터리 사용 중"
                        statusText.text = "Magic Trackpad\n\n$percent%\n($chargeLabel)"
                        connection.releaseInterface(intf)
                        return
                    }
                }
                connection.releaseInterface(intf)
            }
            statusText.text = "배터리 리포트(0x90)를 읽지 못했습니다."
        } finally {
            connection.close()
        }
    }
}

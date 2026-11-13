package com.example.trackpadbattery

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            text = "매직 트랙패드를 C타입 케이블로 연결하고\n원하는 기능을 누르세요."
            setPadding(0, 0, 0, 32)
        }
        layout.addView(statusText)

        // 1. 배터리 조회 버튼
        layout.addView(Button(this).apply {
            text = "🔋 배터리 잔량 확인"
            textSize = 17f
            setOnClickListener { withTrackpad { conn, dev -> readBattery(conn, dev) } }
        })

        // 2. 햅틱 피드백 4단계 버튼 (무소음 / 약하게 / 중간 / 강하게)
        val presets = listOf(
            "무소음 (Silent)" to 0x000017,
            "약하게 (Light)" to 0x040415,
            "중간 (Medium)" to 0x060617,
            "강하게 (Firm)" to 0x08081E
        )

        for ((label, code) in presets) {
            layout.addView(Button(this).apply {
                text = "📳 진동: $label"
                setOnClickListener {
                    withTrackpad { conn, dev -> sendHaptic(conn, dev, label, code) }
                }
            })
        }

        setContentView(layout)
    }

    private fun withTrackpad(action: (UsbDeviceConnection, UsbDevice) -> Unit) {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList.values
            if (devices.isEmpty()) {
                statusText.text = "연결된 USB 장치가 없습니다.\n(C-to-C 케이블 연결을 확인하세요)"
                return
            }

            val device = devices.firstOrNull {
                it.vendorId == 0x05AC && (it.productId == 0x0324 || it.productId == 0x0265)
            } ?: devices.firstOrNull { it.vendorId == 0x05AC } ?: devices.first()

            if (!usbManager.hasPermission(device)) {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
                val pi = PendingIntent.getBroadcast(this, 0, Intent("USB_PERM"), flags)
                usbManager.requestPermission(device, pi)
                statusText.text = "권한 창에서 [확인]을 누른 뒤\n다시 버튼을 눌러주세요!"
                return
            }

            val conn = usbManager.openDevice(device) ?: run {
                statusText.text = "USB 장치를 열 수 없습니다."
                return
            }
            try {
                action(conn, device)
            } finally {
                conn.close()
            }
        } catch (t: Throwable) {
            statusText.text = "오류: ${t.javaClass.simpleName}\n${t.message}"
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
                    statusText.text = "🔋 Magic Trackpad 배터리: $b2%\n(${if (charging) "⚡ USB 전원 공급 중" else "배터리 사용 중"})"
                    conn.releaseInterface(intf)
                    return
                }
            }
            conn.releaseInterface(intf)
        }
        statusText.text = "배터리 리포트(0x90) 응답이 없습니다.\n패드를 한 번 문지른 뒤 다시 눌러보세요."
    }

    private fun sendHaptic(conn: UsbDeviceConnection, device: UsbDevice, label: String, feedback: Int) {
        val c0 = (feedback and 0xFF).toByte()
        val c1 = minOf((feedback shr 8) and 0xFF, 0x20).toByte()
        val c2 = minOf((feedback shr 16) and 0xFF, 0x40).toByte()

        val packet = byteArrayOf(
            0x53, 0x01, c0, 0x78, 0x02, c1, 0x24, 0x30,
            c2, 0x01, c2, 0x18, 0x48, 0x12
        )

        val reqOut = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01
        var successIntf = -1

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (!conn.claimInterface(intf, true)) continue
            val r1 = conn.controlTransfer(reqOut, 0x09, 0x0253, intf.id, packet, packet.size, 500)
            val r2 = if (r1 < 0) conn.controlTransfer(reqOut, 0x09, 0x0353, intf.id, packet, packet.size, 500) else r1
            if (r2 >= 0 && successIntf == -1) successIntf = intf.id
            conn.releaseInterface(intf)
        }

        if (successIntf != -1) {
            statusText.text = "✅ [진동: $label] 적용 완료!"
        } else {
            statusText.text = "⚠️ 진동 신호 전송 실패 (트랙패드를 한 번 문지른 뒤 다시 눌러보세요)"
        }
    }
}

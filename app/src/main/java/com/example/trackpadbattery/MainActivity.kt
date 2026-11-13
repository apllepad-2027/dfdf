package com.example.trackpadbattery

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        statusText = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            text = "1) 매직 트랙패드를 C타입 케이블로 연결하고\n2) 아래 버튼을 누르세요."
        }

        val btn = Button(this).apply {
            text = "배터리 확인"
            textSize = 18f
            setOnClickListener { checkTrackpad() }
        }

        layout.addView(statusText)
        layout.addView(btn)
        setContentView(layout)
    }

    private fun checkTrackpad() {
        try {
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList.values

            if (devices.isEmpty()) {
                statusText.text = "연결된 USB 장치가 없습니다.\n(트랙패드 전원과 C-to-C 케이블을 확인하세요)"
                return
            }

            val device = devices.firstOrNull { it.vendorId == 0x05AC } ?: devices.first()

            if (!usbManager.hasPermission(device)) {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
                val pi = PendingIntent.getBroadcast(this, 0, Intent("USB_PERM"), flags)
                usbManager.requestPermission(device, pi)
                statusText.text = "화면에 뜬 권한 창에서 [확인]을 누른 뒤\n다시 한번 [배터리 확인] 버튼을 눌러주세요!"
                return
            }

            val conn = usbManager.openDevice(device) ?: run {
                statusText.text = "USB 장치를 열 수 없습니다."
                return
            }

            try {
                val buf = ByteArray(8)
                val reqType = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or 0x01

                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    if (!conn.claimInterface(intf, true)) continue

                    for (reportVal in intArrayOf(0x0190, 0x0390)) {
                        val len = conn.controlTransfer(reqType, 0x01, reportVal, intf.id, buf, buf.size, 1000)
                        if (len >= 3 && (buf[0].toInt() and 0xFF) == 0x90) {
                            val flags = buf[1].toInt() and 0xFF
                            val pct = buf[2].toInt() and 0xFF
                            val charging = (flags and 0x02) != 0
                            statusText.text = "🔋 Magic Trackpad 배터리: $pct%\n(${if (charging) "⚡ 충전 중" else "배터리 사용 중"})"
                            conn.releaseInterface(intf)
                            return
                        }
                    }
                    conn.releaseInterface(intf)
                }
                statusText.text = "장치는 찾았으나(VID:${device.vendorId}) 배터리 리포트(0x90) 응답이 없습니다."
            } finally {
                conn.close()
            }
        } catch (t: Throwable) {
            statusText.text = "오류: ${t.javaClass.simpleName}\n${t.message}"
        }
    }
}

                    

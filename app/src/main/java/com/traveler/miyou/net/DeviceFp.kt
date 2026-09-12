// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.net

import com.traveler.miyou.store.CookieStore
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * 设备指纹注册。
 *
 * 战绩类接口对设备指纹敏感：如果本地随机生成的 13 位 hex 从未向官方注册过，
 * 很容易被风控判定为异常环境，接口返回 **retcode 5003（当前账号存在风险，暂无数据）**。
 *
 * 这里的流程对齐 Snap.Hutao（MIT，胡桃工具箱）的 UserFingerprintService：
 * 先本地随机 13 位 hex，再用 public-data-api 的 `device-fp/api/getFp` 注册，
 * 拿到服务器认可的 device_fp 后缓存 7 天，之后一直用它做 `x-rpc-device_fp`。
 */
object DeviceFp {

    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 返回可用的设备指纹；注册失败则退回本地随机值（可能仍被风控）。 */
    fun ensure(store: CookieStore): String {
        val local = store.deviceFp()
        val saved = store.registeredFp()
        if (!saved.isNullOrBlank() && System.currentTimeMillis() - store.registeredFpAt() < TTL_MS) {
            return saved
        }
        val registered = register(store.deviceId(), local)
        if (!registered.isNullOrBlank()) {
            store.saveRegisteredFp(registered)
            return registered
        }
        return saved ?: local
    }

    private fun register(deviceId: String, localFp: String): String? {
        val device = randomUpperAndNumber(12)
        val product = randomUpperAndNumber(6)
        val ext = JSONObject().apply {
            put("proxyStatus", 0)
            put("isRoot", 0)
            put("romCapacity", "512")
            put("deviceName", device)
            put("productName", product)
            put("romRemain", "512")
            put("hostname", "dg02-pool03-kvm87")
            put("screenSize", "1440x2905")
            put("isTablet", 0)
            put("aaid", "")
            put("model", device)
            put("brand", "XiaoMi")
            put("hardware", "qcom")
            put("deviceType", "OP5913L1")
            put("devId", "REL")
            put("serialNumber", "unknown")
            put("sdCapacity", 512215)
            put("buildTime", "1693626947000")
            put("buildUser", "android-build")
            put("simState", 5)
            put("ramRemain", "239814")
            put("appUpdateTimeDiff", 1702604034482L)
            put("deviceInfo", "XiaoMi/$product/OP5913L1:13/SKQ1.221119.001/T.118e6c7-5aa23-73911:user/release-keys")
            put("vaid", "")
            put("buildType", "user")
            put("sdkVersion", "34")
            put("ui_mode", "UI_MODE_TYPE_NORMAL")
            put("isMockLocation", 0)
            put("cpuType", "arm64-v8a")
            put("isAirMode", 0)
            put("ringMode", 2)
            put("chargeStatus", 1)
            put("manufacturer", "XiaoMi")
            put("emulatorStatus", 0)
            put("appMemory", "512")
            put("osVersion", "14")
            put("vendor", "unknown")
            put("accelerometer", "1.4883357x7.1712894x6.2847486")
            put("sdRemain", 239600)
            put("buildTags", "release-keys")
            put("packageName", "com.mihoyo.hyperion")
            put("networkType", "WiFi")
            put("oaid", "")
            put("debugStatus", 1)
            put("ramCapacity", "469679")
            put("magnetometer", "20.081251x-27.487501x2.1937501")
            put("display", "${product}_13.1.0.181(CN01)")
            put("appInstallTimeDiff", 1688455751496L)
            put("packageVersion", "2.20.1")
            put("gyroscope", "0.030226856x0.014647375x0.010652636")
            put("batteryStatus", 100)
            put("hasKeyboard", 0)
            put("board", "taro")
        }

        val body = JSONObject().apply {
            put("device_id", randomLowerHex(16))
            put("bbs_device_id", deviceId)
            put("seed_id", UUID.randomUUID().toString())
            put("seed_time", System.currentTimeMillis().toString())
            put("platform", "2")
            put("device_fp", localFp)
            put("app_name", "bbs_cn")
            put("ext_fields", ext.toString())
        }.toString()

        val headers = mapOf(
            "User-Agent" to ApiConst.UA_DESKTOP,
            "Accept" to "application/json",
            "Content-Type" to "application/json"
        )
        return try {
            parseDeviceFp(Http.post(ApiConst.DEVICE_FP_URL, body, headers).body)
        } catch (e: Exception) {
            null
        }
    }

    private fun randomUpperAndNumber(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return buildString { repeat(length) { append(chars[Random.nextInt(chars.length)]) } }
    }

    private fun randomLowerHex(length: Int): String {
        val chars = "0123456789abcdef"
        return buildString { repeat(length) { append(chars[Random.nextInt(chars.length)]) } }
    }
}

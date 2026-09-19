// SPDX-FileCopyrightText: 2026 having5548
// SPDX-License-Identifier: GPL-3.0-or-later

package com.traveler.miyou.watch

import android.content.Context
import com.traveler.miyou.net.DeviceFp
import com.traveler.miyou.net.MiyouApi
import com.traveler.miyou.store.AccountStore
import com.traveler.miyou.store.CookieStore
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 手表同步核心：经小米穿戴互联 SDK（xms-wearable）与手表端快应用收发数据。
 *
 * 配对前提（官方 interconnect 协议要求）：
 * - 手表端快应用 manifest.json 的 package 必须等于本应用 applicationId（com.traveler.miyou）
 * - 手表端 rpk 必须用本应用同一套签名（release.keystore 导出的 private/certificate pem）
 *
 * 消息协议（JSON 文本，经蓝牙由小米穿戴通道转发）：
 * - 手机 → 手表 dailyNote：{"type":"dailyNote","v":2,"date","ts","resin":{cur,max,rec},"coin":{cur,max},"task":{cur,total},"sign":{today,days}}
 *   rec = 树脂恢复剩余秒数，手表端用 ts+rec 推算回满时刻
 * - 手机 → 手表 getStorageInfo：{"action":"getStorageInfo"}，手表回 storageInfo
 * - 手表 → 手机 storageInfo：{"type":"storageInfo","versionName","buildTime","usedKb"}
 * - 手表 → 手机 requestNote：{"action":"requestNote"}，收到后自动回发一条最新 dailyNote
 */
object WatchNoteSync {

    data class SendResult(val ok: Boolean, val message: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var listenerNodeId: String? = null

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    // ---------------- 状态查询 ----------------

    /**
     * 刷新手表连接状态（连接 / 电量 / 手表端应用是否安装），结果写入 [WatchSyncState]。
     * 设置页与保活服务的定时刷新共用这一个入口。
     */
    suspend fun refreshStatus(context: Context) {
        val app = context.applicationContext
        appContext = app
        val node = try {
            Wearable.getNodeApi(app).getConnectedNodes().await().firstOrNull()
        } catch (e: Exception) {
            WatchSyncState.update {
                it.copy(connected = false, deviceName = null, battery = -1, lastCheck = System.currentTimeMillis())
            }
            return
        }
        if (node == null) {
            WatchSyncState.update {
                it.copy(connected = false, deviceName = null, battery = -1, installed = null, lastCheck = System.currentTimeMillis())
            }
            return
        }
        val battery = runCatching {
            Wearable.getNodeApi(app).query(node.id, DataItem.ITEM_BATTERY).await().battery
        }.getOrDefault(-1)
        val installed = runCatching {
            Wearable.getNodeApi(app).isWearAppInstalled(node.id).await()
        }.getOrNull()
        ensureListener(app, node.id)
        WatchSyncState.update {
            it.copy(
                connected = true, deviceName = node.name, battery = battery,
                installed = installed, lastCheck = System.currentTimeMillis()
            )
        }
    }

    /** 请求手表端回报版本 / 构建时间 / 存储占用（手表回 storageInfo 后写入 WatchSyncState）。 */
    fun requestStorageInfo(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                val node = Wearable.getNodeApi(app).getConnectedNodes().await().firstOrNull() ?: return@launch
                ensureListener(app, node.id)
                Wearable.getMessageApi(app)
                    .sendMessage(node.id, "{\"action\":\"getStorageInfo\"}".toByteArray(Charsets.UTF_8))
                    .await()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- 便签发送 ----------------

    /** 手动发送入口（手表同步页按钮）：拉取最新数据并发送。不抛异常，结果在 SendResult 里。 */
    suspend fun sendNow(context: Context): SendResult {
        return try {
            val app = context.applicationContext
            appContext = app
            val node = connectedNode(app)
                ?: return SendResult(
                    false,
                    "未找到已连接的手表（请确认手表蓝牙已连接，且手机上小米运动健康/穿戴 App 已配对设备）"
                )
            ensurePermission(node.id)
            val installed = runCatching {
                Wearable.getNodeApi(app).isWearAppInstalled(node.id).await()
            }.getOrNull()
            if (installed == false) {
                return SendResult(
                    false,
                    "手表端还未安装「旅行便签」快应用：请先用 AstroBox 安装 watchapp/dist 下的 rpk，再重试"
                )
            }
            val payload = withContext(Dispatchers.IO) { buildPayload(app) }
            sendMessage(app, node.id, payload)
            ensureListener(app, node.id)
            SendResult(true, "已发送到「${node.name}」")
        } catch (e: Exception) {
            SendResult(false, "发送失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun connectedNode(app: Context): Node? =
        Wearable.getNodeApi(app).getConnectedNodes().await().firstOrNull()

    private suspend fun sendMessage(app: Context, nodeId: String, payload: JSONObject) {
        Wearable.getMessageApi(app)
            .sendMessage(nodeId, payload.toString().toByteArray(Charsets.UTF_8))
            .await()
    }

    /** 消息接口要求 DEVICE_MANAGER 权限；首次申请会自动弹授权（穿戴 App 侧确认）。 */
    private suspend fun ensurePermission(nodeId: String) {
        val app = appContext ?: return
        val auth = Wearable.getAuthApi(app)
        val granted = auth.checkPermissions(
            nodeId, arrayOf(Permission.DEVICE_MANAGER, Permission.NOTIFY)
        ).await()
        if (granted.all { it }) return
        auth.requestPermission(nodeId, Permission.DEVICE_MANAGER, Permission.NOTIFY).await()
    }

    // ---------------- 消息监听 ----------------

    /**
     * 注册（或切换到新设备）消息监听：处理手表的 requestNote 自动回发与 storageInfo 回包。
     * 幂等：同一 nodeId 只注册一次。
     */
    fun ensureListener(app: Context, nodeId: String) {
        if (listenerNodeId == nodeId) return
        listenerNodeId?.let { old -> runCatching { Wearable.getMessageApi(app).removeListener(old) } }
        listenerNodeId = nodeId
        val listener = OnMessageReceivedListener { _, message ->
            val json = runCatching { JSONObject(String(message, Charsets.UTF_8)) }.getOrNull() ?: return@OnMessageReceivedListener
            when {
                json.optString("action") == "requestNote" -> {
                    scope.launch {
                        val ctx = appContext ?: return@launch
                        try {
                            val node = connectedNode(ctx) ?: return@launch
                            val payload = withContext(Dispatchers.IO) { buildPayload(ctx) }
                            sendMessage(ctx, node.id, payload)
                        } catch (_: Exception) {
                            // 手表请求回发失败时静默：手表端会显示未同步状态，可在手机端手动重发
                        }
                    }
                }
                json.optString("type") == "storageInfo" -> {
                    WatchSyncState.update {
                        it.copy(
                            versionName = json.optString("versionName", "").ifBlank { null },
                            buildTime = json.optString("buildTime", "").ifBlank { null },
                            storageUsedKb = if (json.has("usedKb")) json.optLong("usedKb", -1L) else -1L
                        )
                    }
                }
            }
        }
        Wearable.getMessageApi(app).addListener(nodeId, listener)
    }

    // ---------------- 数据组装 ----------------

    /**
     * 拉取签到状态 + 实时便签 + 旅行日历，组装结构化 dailyNote 载荷。
     * 与首页同一套数据口径；全程只读，绝不触发签到。失败时抛异常（含可读原因）。
     */
    private fun buildPayload(context: Context): JSONObject {
        val accounts = AccountStore(context)
        val activeId = accounts.activeId() ?: throw IllegalStateException("未登录，请先登录账号")
        val store = CookieStore(context, activeId)
        val deviceId = store.deviceId()
        val deviceFp = DeviceFp.ensure(store)
        val uid = store.roleUid() ?: throw IllegalStateException("未绑定原神角色，请先在首页刷新一次")
        val region = store.roleRegion() ?: throw IllegalStateException("未绑定原神角色，请先在首页刷新一次")

        // 签到状态：只读查询
        val info = MiyouApi.fetchLunaInfo(store.cookieTokenCookieStr(), deviceId, uid, region)
        val signedToday = info.message.isBlank() && info.isSign
        val signedDays = if (info.message.isBlank()) info.totalSignDay else 0

        // 实时便签（widget v2 / stoken 通道）
        val resp = MiyouApi.fetchWidgetResin(store.stokenCookieStr(), deviceId, deviceFp)
        val note = resp.note

        val payload = JSONObject()
            .put("type", "dailyNote")
            .put("v", 2)
            .put("date", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
            .put("ts", System.currentTimeMillis())
            .put(
                "sign",
                JSONObject().put("today", signedToday).put("days", signedDays)
            )
        if (note != null) {
            payload.put(
                "resin",
                JSONObject()
                    .put("cur", note.currentResin)
                    .put("max", note.maxResin)
                    .put("rec", note.resinRecoveryTime)
            )
            payload.put(
                "coin",
                JSONObject().put("cur", note.currentHomeCoin).put("max", note.maxHomeCoin)
            )
            payload.put(
                "task",
                JSONObject().put("cur", note.finishedTaskNum).put("total", note.totalTaskNum)
            )
        } else {
            payload.put("noteError", resp.message.ifBlank { "实时便签获取失败" })
        }
        return payload
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}

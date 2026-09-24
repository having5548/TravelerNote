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
import com.xiaomi.xms.wearable.node.DataItem
import com.xiaomi.xms.wearable.node.DataSubscribeResult
import com.xiaomi.xms.wearable.node.Node
import com.xiaomi.xms.wearable.node.OnDataChangedListener
import com.xiaomi.xms.wearable.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
 * - 手机 → 手表 dailyNote（v3 多账号）：
 *   {"type":"dailyNote","v":3,"date","ts","active","count","skipped",
 *    "accounts":[{"id","label","uid","region","ts","resin":{cur,max,rec},"coin":{cur,max},
 *                 "task":{cur,total},"sign":{today,days},"error"?}, …最多 5 个],
 *    // 同时把当前账号的字段再放一份到顶层，兼容只认 v2 的旧手表端
 *    "resin":{…},"coin":{…},"task":{…},"sign":{…}}
 *   rec = 树脂恢复剩余秒数，手表端用 ts+rec 推算回满时刻；单账号失败只写该条目的 error
 * - 手机 → 手表 dailyNote + noteError：整体拉取失败时也发一条，手表端直接显示原因
 * - 手机 → 手表 getStorageInfo：{"action":"getStorageInfo"}，手表回 storageInfo
 * - 手表 → 手机 storageInfo：{"type":"storageInfo","versionName","buildTime","usedKb"}
 * - 手表 → 手机 requestNote：{"action":"requestNote"}，收到后自动回发一条最新 dailyNote
 */
object WatchNoteSync {

    /** 推送给手表的最大账号数（手表端按同样上限做切换）。 */
    const val MAX_WATCH_ACCOUNTS = 5

    data class SendResult(val ok: Boolean, val message: String)

    /** 电量只在 1..100 之间才认为是有效值：SDK 查不到时会给 0，显示"电量 0%"会误导。 */
    private fun sanitizeBattery(raw: Int): Int = if (raw in 1..100) raw else -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var listenerNodeId: String? = null

    /**
     * 连接状态机：
     * - 手表断连 → 进入**准备状态**（不再反复推数据，只等重连）
     * - 手表重连 → **立刻**拉起手表端应用并补推一次数据（见 [onWatchReconnected]）
     */
    @Volatile
    private var lastConnected: Boolean? = null

    /** 已订阅过 ITEM_CONNECTION 的节点（订阅只为更快发现断连，轮询仍是兜底）。 */
    private var subscribedNodeId: String? = null

    private val connectionListener = OnDataChangedListener { _, _, result ->
        runCatching {
            if (result.connectedStatus == DataSubscribeResult.RESULT_CONNECTION_DISCONNECTED) {
                markDisconnected()
            }
        }
    }

    /** 载荷缓存：手表可能连续拉取，别重复打米游社接口。 */
    private var payloadCache: JSONObject? = null
    private var payloadCacheAt = 0L
    private const val PAYLOAD_TTL_MS = 60_000L

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
        addOnSuccessListener { if (c.isActive) c.resume(it) }
        addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
    }

    // ---------------- 连接状态机 ----------------

    private fun markDisconnected() {
        lastConnected = false
        WatchSyncState.update {
            it.copy(
                connected = false, deviceName = null, battery = -1, charging = false,
                preparing = true, lastCheck = System.currentTimeMillis()
            )
        }
    }

    /**
     * 手表重新连上：只更新连接状态。
     *
     * 现在是**手表端主动拉取**的模式——手机端不再自动推送、也不再主动拉起手表应用；
     * 手表打开应用或点刷新时会发 `requestNote`，这里负责把数据回给它。
     */
    private fun onWatchReconnected() {
        WatchSyncState.update { it.copy(preparing = false, connected = true) }
    }

    // ---------------- 状态查询 ----------------

    /**
     * 刷新手表连接状态（连接 / 电量 / 充电 / 手表端应用是否安装），结果写入 [WatchSyncState]。
     * 设置页与保活服务的定时刷新共用这一个入口。
     *
     * 注意：电量属于穿戴数据，**必须先有 DEVICE_MANAGER 权限**才查得到；
     * 没权限时 SDK 会返回 0，所以这里先检查权限，未授予就把电量标记为未知。
     */
    suspend fun refreshStatus(context: Context) {
        val app = context.applicationContext
        appContext = app
        val node = try {
            Wearable.getNodeApi(app).getConnectedNodes().await().firstOrNull()
        } catch (e: Exception) {
            markDisconnected()
            return
        }
        if (node == null) {
            markDisconnected()
            return
        }

        // 断连后重新连上：立刻拉起手表端 + 补推一次
        val reconnected = lastConnected == false
        lastConnected = true
        if (reconnected) {
            onWatchReconnected()
        }

        val granted = runCatching {
            Wearable.getAuthApi(app)
                .checkPermissions(node.id, arrayOf(Permission.DEVICE_MANAGER))
                .await().all { it }
        }.getOrDefault(false)

        var battery = -1
        var charging = false
        if (granted) {
            runCatching {
                val q = Wearable.getNodeApi(app).query(node.id, DataItem.ITEM_BATTERY).await()
                battery = sanitizeBattery(q.battery)
                charging = q.isCharging
            }
        }

        val installed = runCatching {
            Wearable.getNodeApi(app).isWearAppInstalled(node.id).await()
        }.getOrNull()
        ensureListener(app, node.id)
        ensureConnectionSubscription(app, node.id)
        WatchSyncState.update {
            it.copy(
                connected = true, deviceName = node.name, battery = battery, charging = charging,
                permissionGranted = granted, installed = installed, lastCheck = System.currentTimeMillis()
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

    // ---------------- 便签推送 ----------------

    /**
     * 把便签数据发给手表。
     *
     * 现在**只有手表端主动拉取时**才会调用（手表打开应用 / 点刷新 / 数据超过 4 小时自动获取）：
     * 手机端不再定时推送，也不主动拉起手表应用。失败时也会回一条带 noteError 的载荷，
     * 让手表端直接显示原因而不是干等。
     */
    suspend fun pushNote(context: Context, forceRefresh: Boolean = false): SendResult {
        val app = context.applicationContext
        appContext = app
        val node = connectedNode(app)
        if (node == null) {
            // 手表不在线：进入准备状态（不算错误）
            markDisconnected()
            return SendResult(false, "手表未连接")
        }
        runCatching { ensurePermission(node.id) }

        val payload = try {
            withContext(Dispatchers.IO) { buildPayload(app, forceRefresh) }
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            runCatching {
                sendMessage(
                    app, node.id,
                    JSONObject()
                        .put("type", "dailyNote")
                        .put("v", 2)
                        .put("ts", System.currentTimeMillis())
                        .put("date", today())
                        .put("noteError", reason)
                )
            }
            WatchSyncState.update { it.copy(noteError = reason) }
            return SendResult(false, "推送失败：$reason")
        }

        return try {
            sendMessage(app, node.id, payload)
            ensureListener(app, node.id)
            val summary = payload.optJSONArray("accounts")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("label")?.takeIf { it.isNotBlank() }
                }
            }.orEmpty()
            WatchSyncState.update {
                it.copy(
                    noteError = null,
                    notePushedAt = System.currentTimeMillis(),
                    startError = null,
                    preparing = false,
                    connected = true,
                    accountsSummary = summary.joinToString("、").takeIf { summary.isNotEmpty() }
                )
            }
            val suffix = if (summary.size > 1) "（${summary.size} 个账号）" else ""
            SendResult(true, "已同步到「${node.name}」$suffix")
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            WatchSyncState.update { it.copy(noteError = reason) }
            SendResult(false, "同步失败：$reason")
        }
    }

    private suspend fun connectedNode(app: Context): Node? = try {
        Wearable.getNodeApi(app).getConnectedNodes().await().firstOrNull()
    } catch (e: Exception) {
        null
    }

    private suspend fun sendMessage(app: Context, nodeId: String, payload: JSONObject) {
        Wearable.getMessageApi(app)
            .sendMessage(nodeId, payload.toString().toByteArray(Charsets.UTF_8))
            .await()
    }

    /** 消息接口要求 DEVICE_MANAGER 权限；首次申请会自动弹授权（穿戴 App 侧确认）。 */
    suspend fun ensurePermission(nodeId: String) {
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
     * 订阅手表连接状态：断连能更快发现（进入准备状态）。
     * 订阅失败无所谓——[refreshStatus] 的轮询仍是兜底。
     */
    private fun ensureConnectionSubscription(app: Context, nodeId: String) {
        if (subscribedNodeId == nodeId) return
        subscribedNodeId?.let { old ->
            runCatching { Wearable.getNodeApi(app).unsubscribe(old, DataItem.ITEM_CONNECTION) }
        }
        subscribedNodeId = nodeId
        runCatching {
            Wearable.getNodeApi(app).subscribe(nodeId, DataItem.ITEM_CONNECTION, connectionListener)
        }
    }

    /**
     * 注册（或切换到新设备）消息监听：处理手表的 requestNote 自动回发与 storageInfo 回包。
     * 幂等：同一 nodeId 只注册一次。
     */
    fun ensureListener(app: Context, nodeId: String) {
        if (listenerNodeId == nodeId) return
        listenerNodeId?.let { old -> runCatching { Wearable.getMessageApi(app).removeListener(old) } }
        listenerNodeId = nodeId
        val listener = com.xiaomi.xms.wearable.message.OnMessageReceivedListener { _, message ->
            val json = runCatching { JSONObject(String(message, Charsets.UTF_8)) }.getOrNull()
                ?: return@OnMessageReceivedListener
            when {
                json.optString("action") == "requestNote" -> {
                    scope.launch {
                        val ctx = appContext ?: return@launch
                        // 手表主动拉取：用缓存载荷快速回发（缓存过期才重新拉接口）
                        runCatching { pushNote(ctx, forceRefresh = false) }
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

    private fun buildPayload(context: Context, forceRefresh: Boolean): JSONObject {
        val cached = payloadCache
        if (!forceRefresh && cached != null && System.currentTimeMillis() - payloadCacheAt < PAYLOAD_TTL_MS) {
            return cached
        }
        val payload = buildPayloadUncached(context)
        payloadCache = payload
        payloadCacheAt = System.currentTimeMillis()
        return payload
    }

    /**
     * 拉取**所有账号**（最多 [MAX_WATCH_ACCOUNTS] 个）的签到状态与实时便签，组装 v3 多账号载荷。
     * 数据来源与手机端多账号完全一致（同一套 AccountStore / CookieStore）。
     *
     * - 单个账号失败只在该条目上写 `error`，不影响其他账号
     * - 顶层同时放当前账号的字段，兼容只认 v2 的旧手表端
     * 全程只读，绝不触发签到。
     */
    private fun buildPayloadUncached(context: Context): JSONObject {
        val accounts = AccountStore(context)
        val ids = accounts.ids()
        if (ids.isEmpty()) throw IllegalStateException("手机端未登录，请先登录账号")

        val picked = ids.take(MAX_WATCH_ACCOUNTS)
        val activeId = accounts.activeId()
        // 设备标识与账号无关（`device` 全局共用），随便取一个账号的 store 即可
        val deviceStore = CookieStore(context, picked.first())
        val deviceId = deviceStore.deviceId()
        val deviceFp = DeviceFp.ensure(deviceStore)

        val ts = System.currentTimeMillis()
        val arr = JSONArray()
        var activeEntry: JSONObject? = null
        picked.forEach { id ->
            val entry = buildAccountEntry(context, id, accounts.label(id), deviceId, deviceFp, ts)
            if (id == activeId) activeEntry = entry
            arr.put(entry)
        }
        val primary = activeEntry ?: arr.optJSONObject(0)

        val payload = JSONObject()
            .put("type", "dailyNote")
            .put("v", 3)
            .put("date", today())
            .put("ts", ts)
            .put("active", activeId ?: picked.first())
            .put("count", arr.length())
            .put("skipped", (ids.size - picked.size).coerceAtLeast(0))
            .put("accounts", arr)

        // 兼容 v2 手表端：当前账号的字段再放一份到顶层
        primary?.let { p ->
            for (key in arrayOf("resin", "coin", "task", "sign")) {
                p.optJSONObject(key)?.let { payload.put(key, it) }
            }
            p.optString("error").takeIf { it.isNotBlank() }?.let { payload.put("noteError", it) }
        }
        return payload
    }

    /** 单个账号的便签条目；任何异常都收敛成条目上的 `error`，不影响其他账号。 */
    private fun buildAccountEntry(
        context: Context,
        id: String,
        label: String,
        deviceId: String,
        deviceFp: String,
        ts: Long
    ): JSONObject {
        val entry = JSONObject()
            .put("id", id)
            .put("label", shortLabel(label, id))
            .put("ts", ts)
        try {
            val store = CookieStore(context, id)
            val uid = store.roleUid()
            val region = store.roleRegion()
            uid?.let { entry.put("uid", it) }
            region?.let { entry.put("region", it) }
            if (uid.isNullOrBlank() || region.isNullOrBlank()) {
                throw IllegalStateException("未绑定原神角色，请先在手机端首页刷新一次")
            }

            // 签到状态：只读查询
            val info = MiyouApi.fetchLunaInfo(store.cookieTokenCookieStr(), deviceId, uid, region)
            val signedToday = info.message.isBlank() && info.isSign
            val signedDays = if (info.message.isBlank()) info.totalSignDay else 0
            entry.put("sign", JSONObject().put("today", signedToday).put("days", signedDays))

            // 实时便签（widget v2 / stoken 通道）
            val resp = MiyouApi.fetchWidgetResin(store.stokenCookieStr(), deviceId, deviceFp)
            val note = resp.note
            if (note != null) {
                entry.put(
                    "resin",
                    JSONObject()
                        .put("cur", note.currentResin)
                        .put("max", note.maxResin)
                        .put("rec", note.resinRecoveryTime)
                )
                entry.put("coin", JSONObject().put("cur", note.currentHomeCoin).put("max", note.maxHomeCoin))
                entry.put("task", JSONObject().put("cur", note.finishedTaskNum).put("total", note.totalTaskNum))
            } else {
                entry.put("error", resp.message.ifBlank { "实时便签获取失败" })
            }
        } catch (e: Exception) {
            entry.put("error", e.message ?: e.javaClass.simpleName)
        }
        return entry
    }

    /**
     * 手表屏幕窄：昵称优先，没有昵称就退化成短 id；
     * 手机端的完整展示名（昵称（UID xxx））留给手机页面用。
     */
    private fun shortLabel(label: String, id: String): String {
        val nick = label.substringBefore("（").trim()
        return if (nick.isNotBlank() && !nick.startsWith("账号 ")) nick else "账号 ${id.takeLast(4)}"
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}

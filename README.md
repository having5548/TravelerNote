# 旅行者便签

适用于 **Android 11（API 30）及以上** 的米游社登录 + 游戏/社区签到 + 实时便签工具。

> **游戏签到（原神 luna）是自动的**：打开 App 就跑，奖励通过游戏内邮件发放。
> **社区（论坛）签到是手动的**：只在首页点「社区签到」按钮时执行，绝不会自动触发。

## 功能

- 打开 App 自动执行原神游戏每日签到；已签到 / 首次绑定 / 需要验证都会在首页状态行说明
- 首页底部「社区签到」按钮：**手动**执行米游社社区（论坛）签到，按钮下显示今日是否已完成（自动刷新只查询状态，不会替你签）
- **旅行日历**：首页按"本月第 N 次签到"列出每天能领的奖励（图标 + 数量），已领取 / 今日 / 未领取三种状态一眼可辨
- 首页展示实时便签：原粹树脂、洞天宝钱、探索派遣、每日委托（widget v2 接口）
- 「我的角色」页：展示角色等级、武器、面板与圣遗物（数据来自提瓦特小助手）
- 登录方式：**扫码登录（自动生成二维码）**、**手机号验证码登录**、手动粘贴 Cookie
- 遇到人机验证时弹网页完成；设置里提供「无感验证」开关（默认关闭，需自备 **https** 打码接口）
- 平板适配（宽屏单列 + 竖屏锁定）、全面屏适配、极简 Material 风格
- 设置支持自定义主题强调色（6 色），明暗跟随系统；可选背景图（每日随机 / 必应每日一图）
- release 包开启 R8 混淆与资源裁剪

> 本工具为个人学习/自用项目。自动签到可能违反米哈游相关服务条款，使用风险自负。

## 登录与凭证

- **扫码登录**：调用 passport `createQRLogin` 生成二维码，本地 zxing 渲染，轮询 `queryQRLoginStatus` 直到确认，取得 stoken。
- **手机号登录**：`createLoginCaptcha`（RSA 加密手机号）发送短信验证码，`loginByMobileCaptcha` 登录。
- **人机验证（AIGIS）**：接口返回 `x-rpc-aigis` 时，弹出 WebView 完成极验，回传 `session_id;base64(validate)` 后重试。
- **签到验证码**：`signLuna` 返回 `data.success == 1` 时视为需要验证；先尝试「无感验证」（需在设置里开启并配置 https 打码接口），失败或未配置则弹出网页手动完成，再用 `x-rpc-challenge / x-rpc-validate / x-rpc-seccode` 重试。
- **游戏签到（自动）**：`event/luna/sign`，打开 App 即执行；`event/luna/info` 的 `is_sign` / `first_bind` 决定后续动作。
- **社区签到（手动）**：`apihub/app/api/signIn`（gids=2）；遇 1034 走 `misc/api/createVerification` → 极验 → `misc/api/verifyVerification` 换取 challenge，再带 `x-rpc-challenge` 重试。今日是否已签用只读接口 `getUserMissionsState`（mission 58）判断，**自动流程不会调用签到接口**。
- **签到奖励日历**：`event/luna/home` 返回本月每天的奖励（`icon/name/cnt`），配合 `event/luna/info` 的 `total_sign_day` / `is_sign` 标出已领取进度与今日可领的那一格。
- 凭证归一化：`stoken → cookie_token / ltoken`，存于应用私有 SharedPreferences（已从备份排除）。

## 构建与签名

环境要求：**JDK 17**（AGP 8.5.2 不支持 JDK 21+）、Android SDK（`platforms/android-34`、`build-tools/34.0.0`）、Gradle 8.7（仓库内已含 wrapper）。

签名信息从仓库根目录的 `keystore.properties` 读取（**该文件不入库**，需自行创建；缺失时 release 退回"不签名"构建，便于在没有密钥的机器上做编译检查）：

```properties
storeFile=release.keystore
storePassword=<口令>
keyAlias=traveler
keyPassword=<口令>
```

```powershell
# Windows
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.4.1"
"sdk.dir=C:/Android/Sdk" | Out-File -Encoding ascii local.properties
.\gradlew.bat assembleRelease
```

打包完成后 `copyReleaseApkToRepo` 会自动把签名包复制到仓库根目录的 `apk/`：

| 位置 | 说明 |
| --- | --- |
| `app/build/outputs/apk/release/app-release.apk` | Gradle 原始产物（已签名、已混淆裁剪） |
| `apk/app-release.apk` | 自动复制出来的分发用 APK |

校验签名：

```powershell
& "$env:ANDROID_SDK\build-tools\34.0.0\apksigner.bat" verify --print-certs apk\app-release.apk
```

不要把 `release.keystore`、`keystore.properties` 和口令提交到公开仓库（`.gitignore` 已排除）。

## 安全与隐私

- 凭证保存在应用私有目录（`session` SharedPreferences），`allowBackup=false`，已从备份/迁移规则中排除。
- 全局禁用明文流量（`usesCleartextTraffic=false`），因此**打码接口只支持 https**；设置页会对非 https 地址直接报错。
- 角色页会把 UID 发送给第三方 `api.lelaer.com` 获取面板数据，介意可不用该页。
- 接口地址与盐以 base64 形式落盘，仅提高静态扫描成本，**不等于加密**，反编译后可直接还原。
- release 启用 `minifyEnabled` + `shrinkResources`，规则见 `app/proguard-rules.pro`，并移除所有 `android.util.Log` 输出。

## 目录结构

```
app/src/main/
  AndroidManifest.xml
  java/com/traveler/miyou/
    net/      DS 签名、接口常量、HTTP、RSA、模型与解析、API、无感验证、背景图、图片缓存
    store/    登录态/设备标识（CookieStore）、设置（SettingsStore）
    ui/       主页、登录、验证码 WebView、设置、列表适配器、主题
  res/        布局、矢量图标、主题、自适应图标、sw600dp 平板资源
```

## 免责声明

本项目仅供学习交流，请勿用于任何商业用途或大规模自动化，遵守米哈游服务条款。

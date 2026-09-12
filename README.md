# 旅行者便签

适用于 **Android 11（API 30）及以上** 的米游社登录 + 游戏/社区签到 + 实时便签工具。

> **游戏签到（原神 luna）是自动的**：打开 App 就跑，奖励通过游戏内邮件发放。
> **社区（论坛）签到是手动的**：只在首页点「社区签到」按钮时执行，绝不会自动触发。

> ⚠️ **本项目是非官方第三方工具**：与米哈游 / 上海米哈游影铁科技有限公司及其关联公司
> **没有任何关系，也未获得任何形式的授权、认可或支持**。「米游社」「原神」等名称、商标、
> 游戏素材及相关接口的权利均归米哈游所有，本项目仅出于个人学习与技术研究目的而使用。
> 自动签到可能违反米哈游的服务条款并带来账号风险，**使用后果由使用者自行承担**。

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
- **战绩（深境螺旋 / 幻想真境剧诗）**：`api-takumi-record` 的 `spiralAbyss` / `role_combat`；DS 用 X4 盐 Gen2 且参与签名的 query 按字母序重排。设备指纹按 [Snap.Hutao](https://github.com/DGP-Studio/Snap.Hutao)（MIT）的流程处理：先本地随机 13 位 hex，再经 `public-data-api/device-fp/api/getFp` 注册并缓存 7 天 —— 未经注册的指纹容易被风控判定为异常环境并返回 `retcode 5003`。
- **网页工具**：工具页里的米游社入口用**应用内 WebView** 打开，加载前会把已缓存的登录凭证（含 `*_v2` 别名与 `domain`）写进 WebView 的 cookie，因此战绩 / 祈愿 / 通行证等页面无需二次登录；右上角仍可切换「用浏览器打开」。

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

- 本项目为**非官方**第三方工具，与米哈游 / 上海米哈游影铁科技有限公司及其关联公司**无任何关联**，
  未获得任何形式的授权、认可或支持。「米游社」「原神」等名称、商标、游戏素材及相关接口的权利均归米哈游所有。
- 本项目仅供**个人学习与技术研究**使用；**禁止**任何商业用途、二次售卖、代练代签牟利或大规模自动化操作。
- 自动签到、自动完成社区任务等行为**可能违反米哈游的服务条款**，可能导致账号被限制、冻结或封禁。
  **请自行评估风险，一切后果由使用者自行承担**；作者不对任何账号损失、数据丢失或封禁负责。
- 应用不会收集或上传你的账号密码，登录凭证仅保存在本机应用私有目录中；
  「我的角色」页会把游戏 UID 发送给第三方 `api.lelaer.com`，使用该页即表示你接受这一点。
- 若本项目无意中侵犯了你的权益，请通过 Issue 联系，核实后会立即删除相关内容或下架本仓库。
- **下载、安装或使用本项目（包括 Release 中的 APK）即表示你已阅读、理解并同意上述全部内容。**
  如果你不同意，请不要使用。

## 许可证

本项目基于 **GNU General Public License v3.0** 发布，许可证全文见 [LICENSE](LICENSE)。

```
Copyright (C) 2026 having5548

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

GPL-3.0 授予你对**代码**自由使用、修改与再分发的权利，但**不免除**上面「免责声明」中的
使用限制与风险提示。再分发（包括修改后发布）时请保留版权声明、许可证全文与本免责声明，
并同样以 GPL-3.0 开放源代码。

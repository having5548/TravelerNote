# 旅行者便签 ProGuard/R8 规则
# 入口类由 Android Gradle Plugin 依据 Manifest 自动保留。
# 其余类全部允许混淆与裁剪，达到减小体积与增加逆向成本的目的。

# 保留 Kotlin 协程元数据（仅调试需要时打开；release 可关闭以进一步裁剪）
# -keepattributes Signature,InnerClasses,EnclosingMethod

# 若使用反射解析（本项目未使用），可按需放开，这里默认全部混淆。
# -keep class com.traveler.miyou.net.** { *; }

# 保留 WebView 相关的回调接口名称（避免个别厂商 WebView 反射失败）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 去除日志，避免泄露运行信息
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# 更激进的混淆优化（入口类由 Manifest 规则保留原名，不受影响）
-optimizationpasses 5
-repackageclasses ''
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

# 小米穿戴互联 SDK（xms-wearable AAR 自带 consumer rules，这里显式保留以防意外裁剪）
-keep class com.xiaomi.xms.wearable.** { *; }
-keep class com.xiaomi.xms.wearable.**$* { *; }

# Shizuku 用户服务：通过 ComponentName 按名绑定，R8 不能改名
-keep class com.traveler.miyou.watch.ShellUserService { *; }
-keep class com.traveler.miyou.watch.IShellService { *; }
-keep class com.traveler.miyou.watch.IShellService$* { *; }

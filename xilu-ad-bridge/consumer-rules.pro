# Xilu-AD 桥接层混淆规则（随 AAR 分发）
-keep class com.xilu.sdk.uni.** { *; }
-keep class io.dcloud.** { *; }

# 广告回调监听器（经 setListener 持有，反射调用风险）
-keep class com.xilu.sdk.ad.listener.** { *; }
-keep class com.xilu.sdk.listener.** { *; }

# uni 引擎回调
-keep class * implements io.dcloud.uniplugin.** { *; }

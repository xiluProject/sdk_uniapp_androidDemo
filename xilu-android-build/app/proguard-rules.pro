# 当前 debug/release 均未开启混淆（minifyEnabled false）。
# 若后续开启 release 混淆，至少保留：
-keep class com.xilu.sdk.uni.** { *; }
-keep class io.dcloud.** { *; }
-dontwarn io.dcloud.**

# sing-box core (gomobile) is called from Go through JNI reflection
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
# Platform callbacks implemented in Kotlin and invoked from Go
-keep class com.vpnhub.app.vpn.** { *; }
-dontwarn go.**

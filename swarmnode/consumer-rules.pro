# Keep gomobile-generated Java classes reachable from the Go side.
# The ant JNI shim resolves natives by exact class + method name
# (Java_baby_freedom_swarm_AntNative_*); renaming either breaks dlsym.
-keepclasseswithmembernames class baby.freedom.swarm.AntNative {
    native <methods>;
}

-keepclasseswithmembernames class baby.freedom.swarm.FreedomIpfsNative {
    native <methods>;
}

# The generated Radicle bindings (uniffi.libradicle_uniffi) go through JNA,
# which binds native functions and callback interfaces by reflection on
# the declared Kotlin/Java names — keep both sides unrenamed.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.libradicle_uniffi.** { *; }
-dontwarn java.awt.**
-keepclasseswithmembernames class baby.freedom.swarm.MyotisNative {
    native <methods>;
}

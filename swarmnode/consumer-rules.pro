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
-keepclasseswithmembernames class baby.freedom.swarm.TorNative {
    native <methods>;
}

# colibri_jni.c resolves Java_baby_freedom_swarm_ColibriNative_n* (#100).
-keepclasseswithmembernames class baby.freedom.swarm.ColibriNative {
    native <methods>;
}

# ant_jni.c's chain transport calls SpendGuard.admit(String) by name from
# JNI_OnLoad-resolved refs (#116). Renamed or stripped, it can't find the
# gate and refuses every broadcast — safe, but no stamp could be bought.
-keep class baby.freedom.swarm.SpendGuard {
    public static boolean admit(java.lang.String);
}

# ant_jni.c's chain transport answers ant's reads through
# AntChainTransport.serve(byte[]), resolved by name in JNI_OnLoad (#273).
# Renamed or stripped, every read ant makes fails with an error (never a
# fallback to one RPC) and the light node can't read the chain.
-keep class baby.freedom.swarm.AntChainTransport {
    public static byte[] serve(byte[]);
}

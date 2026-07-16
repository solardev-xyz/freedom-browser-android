# Keep gomobile-generated Java classes reachable from the Go side.
-keep class mobile.** { *; }
-keep class go.** { *; }

# The ant JNI shim resolves natives by exact class + method name
# (Java_baby_freedom_swarm_AntNative_*); renaming either breaks dlsym.
-keepclasseswithmembernames class baby.freedom.swarm.AntNative {
    native <methods>;
}

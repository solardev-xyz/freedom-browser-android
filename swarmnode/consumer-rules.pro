# Keep gomobile-generated Java classes reachable from the Go side.
-keep class mobile.** { *; }
-keep class go.** { *; }

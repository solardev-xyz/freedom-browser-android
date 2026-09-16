# App-specific R8 rules.
#
# `app/build.gradle.kts` has always listed this file in
# `proguardFiles`; AGP 8 quietly ignored it when it was missing, AGP 9
# fails the build instead ("Supplied proguard configuration does not
# exist"), so it's checked in now.
#
# The rules the release build actually needs come from elsewhere and
# are deliberately not duplicated here:
#   * the JNI entry points live in swarmnode/consumer-rules.pro
#   * Room, Compose and the AndroidX libraries ship their own rules
#     as AAR metadata.

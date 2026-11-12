# Keep rules for the minified build (R8). Everything the app reaches by name rather than by code.

# secp256k1-kmp finds its JNI loader by class name and calls native methods on these classes.
-keep class fr.acinq.secp256k1.** { *; }

# OkHttp looks for optional TLS providers that are not on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization is used without its compiler plugin (JsonElement trees only): there are
# no @Serializable classes of ours to keep; the library ships the rules for its own runtime.

# Firebase Messaging (optional: only when google-services.json is there) and Coil ship their rules.
# The messaging service and the activity are named in the manifest: AAPT keeps them.

# Readable stack traces from a minified build.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# R8 / ProGuard rules for the release build.
#
# Most dependencies (AndroidX, Compose, kotlinx-coroutines, security-crypto)
# ship their own consumer rules. The keeps below cover the reflection-heavy
# Jakarta Mail stack, which R8 would otherwise strip or break.

# Jakarta Mail and JavaBeans Activation Framework use reflection to load
# providers (from META-INF resources) and to build MIME messages.
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-keepnames class * extends javax.mail.Provider

# Optional/desktop-only dependencies referenced by Jakarta Mail that do not
# exist on Android. Silence the missing-class warnings so R8 does not fail.
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**
-dontwarn com.sun.activation.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.auth.callback.**

# Tink (backs EncryptedSharedPreferences for alert credentials).
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Keep generic signatures and annotations for correct runtime type handling.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

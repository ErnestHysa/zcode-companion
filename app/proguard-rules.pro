# F6.2 (gaps-and-polish 2G): release R8 rules. Library-consumer rules ship inside
# OkHttp/Room/DataStore/Compose; the additions below cover what does not.

# ---- kotlinx-serialization (the §7 wire models) — from the kotlinx.serialization guide
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.zcode.android.**$$serializer { *; }
-keepclassmembers class com.zcode.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.zcode.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- Tink (crypto) reflects primitive registries
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ---- Glance widget composition
-keep class androidx.glance.** { *; }

# ---- protobuf-javalite (the §33/§12.4 stores: credential envelope, notification
# records, endpoint store). The runtime's MessageSchema reflects message fields BY
# NAME at schema-for time — renamed fields crash the parse of any stored file
# (F-CRASH, field 2026-10-25: launch crash on upgrade from an older build, never
# on fresh installs). Keep the runtime whole and every generated message's fields.
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ---- keep the crash handler's line numbers in field traces (INV-5 diagnostics)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

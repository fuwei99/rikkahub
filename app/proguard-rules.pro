# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# keep kotlinx serializable classes
-keep @kotlinx.serialization.Serializable class * {*;}

# hnswlib: item 类用 Java 序列化（JavaObjectSerializer）写进向量索引文件，
# R8 的结构优化（即使 -dontobfuscate 不改名）也可能改字段/类结构 → 反序列化 InvalidClassException。
-keep class me.rerere.rikkahub.data.vector.GraphVectorItem { *; }
-keep class me.rerere.rikkahub.data.vector.MemoryVectorItem { *; }
# hnswlib 内部结构（Node、comparator 等）随索引一起被反序列化，一并保留
-keep class com.github.jelmerk.hnswlib.** { *; }

# keep jlatexmath
-keep class org.scilab.forge.jlatexmath.** {*;}

# keep huarangmeng/latex (Compose-native KaTeX renderer)
# 这仨模块里有大量 Kotlin object/data class 单例、KaTeX 字体 asset 反射加载、
# 以及 sealed class 结构。被 R8 shrink 掉后 LatexTextKt.<clinit> 会因为
# NoClassDefFoundError 转成 ExceptionInInitializerError，一进 processLatex 就炸。
-keep class com.hrm.latex.** { *; }
-keepclassmembers class com.hrm.latex.** { *; }
-keep interface com.hrm.latex.** { *; }
-dontwarn com.hrm.latex.**
# 保留 Kotlin metadata & 注解，避免 sealed/inline/companion 反射失败
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, KotlinMetadata

-dontwarn com.google.re2j.**
-dontobfuscate

# Ktor 在 Android 上引用了仅 JVM 可用的 java.lang.management 类（IntellijIdeaDebugDetector）
# Android 不包含这些类，需要告知 R8 忽略
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# java.beans is not available on Android; Jackson references it only on JVM
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient

# auth0/jackson: TypeReference subclasses rely on runtime generic signatures.
# R8 strips Signature/InnerClasses/EnclosingMethod by default, and its class
# merging/inlining optimizations can destroy the anonymous class hierarchy that
# TypeReference.<init> depends on via getClass().getGenericSuperclass().
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep class com.fasterxml.jackson.** { *; }
-keep class com.auth0.jwt.** { *; }

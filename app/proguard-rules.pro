# 模块入口与全部模块类都通过反射/清单引用，全部保留，避免被 R8 改名或裁剪
-keep class com.vpnautoswitch.module.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Xposed API 由 LSPosed 在运行时提供，编译期不可见
-dontwarn de.robv.android.xposed.**
-dontwarn de.robv.android.xposed.callbacks.**

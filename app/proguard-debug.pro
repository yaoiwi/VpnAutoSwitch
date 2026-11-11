# debug 变体的「压缩但不改名」规则：只让 R8 裁掉没用的代码压体积，
# 类名、方法名、堆栈全部保持原样，抓到的崩溃堆栈能直接对源码。
-dontobfuscate

# 行号保留，否则堆栈里全是 Unknown Source
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

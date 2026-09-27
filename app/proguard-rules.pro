# R8 规则。
#
# 本项目没有反射、没有 addJavascriptInterface，AGP 又会为 manifest 里声明的
# 组件自动生成 keep 规则，所以基本不需要额外配置。
#
# 这个文件之前**根本不存在**，而 build.gradle 里引用了它 ——
# 一旦把 minifyEnabled 打开，构建会直接失败。现在补上。

# 保留行号，崩溃栈才有意义。发布时要靠栈追踪定位问题的话，
# 记得一并保存 build/outputs/mapping/release/mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

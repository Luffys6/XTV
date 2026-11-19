# R8 混淆规则
#
# 本项目没有反射、没有 JSON 反序列化框架，所以不需要为业务类写 keep。
# 下面只保留几项"排错需要"的信息，避免线上崩溃堆栈全部变成 a.a.a。
#
# 注意：改这里之后一定要跑一次 release 构建（gradlew assembleRelease），
# 并把产物装到真机点一遍：混淆只会在 release 生效，debug 包测不出问题。

# 保留行号，崩溃堆栈能定位到源码行
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# androidx / zxing 的 consumer 规则由 AGP 自动合并，这里无需重复声明。
# 若将来引入反射（Gson、EventBus 等），记得给对应模型类加：
#   -keep class com.family.quizlock.YourModel { <fields>; }

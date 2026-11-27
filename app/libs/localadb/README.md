# 本机配对构建输入

所有生产构建使用本目录内的文件，不访问 JitPack/Maven 或任何在线配对服务。AppDodge 的网络权限用于发现本机系统发布的端口及连接 127.0.0.1。网络上的其他设备不会被连接。手机端设置不会导出命令行、私钥或配对码。

- `src` 取自 [libadb-android 3.1.1](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1/libadb/src/main/java/io/github/muntashirakon/adb)。只取连接与配对所用的 Java 类。我们的改动：使用内附 Conscrypt 的公共 TLS 接口，拒绝明文 ADB；配对失败后关 socket，不写对端信息；修复极短本机流的 OPEN/EXIT 时序；允许在发送停止请求前复核连接世代及截止时间。按源码文件保留 Apache/BSD/MIT 原有标注。
- [Conscrypt 2.5.3 AAR](https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-android/2.5.3/conscrypt-android-2.5.3.aar)：对应的 jar 和四种 ABI 的 libconscrypt_jni.so 均从 AAR 原样提取。
- [SPAKE2 Android 2.2.1 AAR](https://jitpack.io/com/github/MuntashirAkon/spake2-java/spake2-android/2.2.1/spake2-android-2.2.1.aar)：同样原样提取。完整[对应源码及 Android 构建脚本](sources/spake2-2.2.1-with-native-src.tar.gz)包含上游标签和对应的 spake2-c 子模块版本 0d15933e5ba3e662cb01245a7ac0dc9fca3eac31。允许为调试修改库并重新链接本应用：解开源码，在配置了 Android SDK、NDK、CMake 和 Gradle 的环境执行 `./gradlew :android:assembleRelease`，用得到的 AAR 中 classes.jar 和 jni 各 ABI 替换本目录同名文件。原项目的 settings.gradle 指定模块名；不需要原作者私钥即可重建、重签 APK。
- [Bouncy Castle bcprov-jdk15to18 1.81](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk15to18/1.81/bcprov-jdk15to18-1.81.jar)：仅用于 libadb 的加解密。以 SDK Build Tools 35 的 R8 从原 JAR 保留 `bc.pro` 列出的公开入口及其依赖，使用 `--classfile --release --lib "$JAVA_HOME" --pg-conf bc.pro`，生成后仅打包 .class 条目，不沿用原完整 jar 的签名或服务清单。
- 用 `python3 scripts/build.py` 重新构建 AppDodge，Gradle 也指向同一套源码、JAR 与 JNI 库。修改版可用自己的签名安装；覆盖安装仍需系统认可的原签名。各上游许可与第三方声明随 APK 打包在 assets/notices。

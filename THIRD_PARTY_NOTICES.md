# 第三方构建文件

`gradlew`、`gradlew.bat` 和 `gradle/wrapper/gradle-wrapper.jar` 来自 Gradle 官方 v8.9.0 项目，按 Apache License 2.0 提供。

- 源代码：https://github.com/gradle/gradle/tree/v8.9.0
- 许可证：https://www.apache.org/licenses/LICENSE-2.0
- Wrapper JAR 的 SHA-256 依据：https://services.gradle.org/distributions/gradle-8.9-wrapper.jar.sha256

Android SDK 和 JDK 是用户另行安装的构建工具，本仓库不分发这些工具。

`app/libs/shizuku/` 包含 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 13.1.5 的四个客户端库（api、aidl、shared、provider）。文件是 [Maven Central](https://repo.maven.apache.org/maven2/dev/rikka/shizuku/) 对应官方 AAR 中未经修改的 `classes.jar`。按 [MIT 许可证](app/src/main/assets/notices/Shizuku-API.txt) 分发。其依赖 [AndroidX Annotation 1.3.0](https://developer.android.com/jetpack/androidx/releases/annotation) 按 [Apache License 2.0](app/src/main/assets/notices/AndroidX-Annotation.txt) 分发；两份许可证都随 APK 打包。APK 只包含客户端，不包含 Shizuku 管理器或其后台服务；普通拦截无需安装管理器。

本项目自身源码采用 [MIT 许可证](LICENSE)；以上及以下条目说明仓库内嵌与随 APK 打包的第三方组件来源与许可，第三方组件各自保留原有条款。

AppDodge 的本机无线配对直接连本机的 Android 调试服务，不需要另装管理器。使用 [libadb-android 3.1.1](https://github.com/MuntashirAkon/libadb-android/tree/3.1.1) 的客户端源码（选用 Apache-2.0；保留上游 BSD/MIT 声明）、[Conscrypt 2.5.3](https://github.com/google/conscrypt/tree/2.5.3)、[Bouncy Castle 1.81](https://github.com/bcgit/bc-java/tree/r1rv81) 及 [spake2-android 2.2.1](https://github.com/MuntashirAkon/spake2-java/tree/2.2.1)。[上游变动、库源码与替换方法](app/libs/localadb/README.md)；许可、声明及署名在 APK 的 `assets/notices/`。

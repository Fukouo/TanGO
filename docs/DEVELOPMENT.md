# 开发与构建

TanGO 是原生 Android Java 应用，界面使用 Android View，数据存储使用 SQLite。应用无第三方运行时依赖。

## 环境要求

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK Platform | 35 |
| Android SDK Build Tools | 35.0.0 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.2 |
| 最低 Android 版本 | 8.0 / API 26 |

SDK、JDK 和签名文件不随源码分发。

## Android Studio / Gradle

克隆仓库并在 Android Studio 中打开：

```sh
git clone https://github.com/Fukouo/TanGO.git
cd TanGO
```

安装表中所列 SDK，设置 Gradle JDK 为 17，完成同步后运行 `app`。

也可在项目根目录构建：

```powershell
# Windows
.\gradlew.bat assembleDebug
```

```sh
# macOS / Linux
./gradlew assembleDebug
```

调试 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 使用 SDK 工具直接构建

Windows 下提供不依赖 Gradle 构建流程的 PowerShell 脚本。准备好 JDK 和 SDK 后运行：

```powershell
.\scripts\build-local.ps1 -SdkRoot '你的 Android SDK 目录' -JdkRoot '你的 JDK 17 目录'
```

脚本也会读取 `ANDROID_HOME`、`JAVA_HOME`，或使用项目 `.tools/` 下的工具。输出路径与上面相同。

该脚本使用 `.tools/debug.p12` 签名，文件不存在时生成调试证书。Gradle 构建可能使用不同的调试证书；只有相同包名与签名的 APK 才能直接覆盖安装。请保管自己的签名文件，不要提交到仓库。

## 测试

JVM 测试使用 `org.json:json:20250107`。准备 JDK 17，将该依赖的 JAR 放到 `.tools/json.jar`，然后运行：

```powershell
.\scripts\test.ps1
```

脚本在需要时生成仅用于本地 HTTPS 模拟的证书。测试不需要 API Key，也不会访问付费模型接口。覆盖范围及限制见 [测试说明](TESTING.md)。

## 图标资源

图标源图与预览位于 `assets/branding/`，导出脚本需要 Node.js 和 `sharp`：

```sh
npm install --no-save --no-package-lock sharp
node scripts/prepare-icon.cjs assets/branding/TanGO-dango-flat-v2.png
```

导出会更新 Android 图标资源与预览图。图片变更后，应在实际 Android 启动器中检查圆形、自适应蒙版及缩小效果。

## 代码结构

| 路径 / 类 | 职责 |
| --- | --- |
| `app/src/main/java/.../MainActivity.java` | 查词、详情、收藏、用量与连接界面 |
| `AiClient.java` | 模型列表、查词、详情及网络用量采集 |
| `LookupRules.java` / `LocalSearch.java` | 输入归一化、候选排序及离线收藏搜索 |
| `SearchLadder.java` | 单轮查询的临时思考提档与停止条件 |
| `Store.java` | SQLite 数据、设置与加密凭据 |
| `PlanAuth.java` / `PlanProtocol.java` / `LoopbackLogin.java` | 登录授权、令牌处理与流式响应 |
| `tests/` | JVM 单元检查与本地 HTTPS 集成测试 |

## 提交变更

修改查词、思考强度、网络或存储逻辑时，运行相关测试，并说明真实服务和设备上的验证情况。不要将模拟测试结果描述成真实模型效果。

`.gitignore` 排除了构建产物、开发工具、依赖目录、环境配置、签名私钥及本地备份。提交前仍需检查差异，避免把个人服务地址、凭据或带有账号信息的截图加入源码。

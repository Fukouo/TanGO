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

## 接口兼容性

独立 API 模式使用 Bearer 鉴权，请求基础地址下的 `/models` 和 `/chat/completions`。它按协议接入，不限定模型厂商；若列表接口不可用，可以手动填写模型名称。原生 Anthropic Messages 等不同协议尚未独立适配，需要服务提供 Chat Completions 兼容层。

参考官方文档核对的接口差异：

- [DeepSeek 接入说明](https://api-docs.deepseek.com/)：提供 OpenAI 兼容接口，基础地址为 `https://api.deepseek.com`。
- [DeepSeek Chat Completions 参数](https://api-docs.deepseek.com/api/create-chat-completion/)：明确支持 `none`、`low`、`high`、`max`；兼容别名 `minimal` 映射为 `low`，`medium` / `xhigh` 映射为 `high`。当前应用没有专门压缩这些等价档位，逐级重查可能重复实际强度。
- [Claude OpenAI 兼容层](https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk)：支持 Chat Completions 格式，但忽略 `reasoning_effort`。其思考参数与 OpenAI 不同，当前应用没有映射这些参数。另外，未绑定工作区的部分密钥需要 `anthropic-workspace-id` 请求头，当前应用不支持自定义该请求头。

兼容接口不代表所有参数或功能等价。添加供应商适配时，应分别验证鉴权、必需请求头、模型列表、文本响应、用量、实际思考档位和自动提档行为；不能仅凭请求被接受就宣称提档生效。

## 提交变更

修改查词、思考强度、网络或存储逻辑时，运行相关测试，并说明真实服务和设备上的验证情况。不要将模拟测试结果描述成真实模型效果。

`.gitignore` 排除了构建产物、开发工具、依赖目录、环境配置、签名私钥及本地备份。提交前仍需检查差异，避免把个人服务地址、凭据或带有账号信息的截图加入源码。

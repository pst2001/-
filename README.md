# 文字转语音（TTS）Android App

一个**只用系统内置 `android.speech.tts.TextToSpeech`** 的原生文字转语音应用。
Kotlin 1.9 + Jetpack Compose（Material 3）+ MVVM/StateFlow，**不申请危险权限、不联网、零第三方 SDK**。

> 本工程已在本机实际编译通过：`assembleDebug` / `assembleRelease` 成功，`TextChunker` 6 个单元测试全绿。

---

## 〇、直接装到手机（不用自己编译）

已构建好的安装包在 `dist/` 目录：

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `dist/TtsApp-1.0.0-release.apk` | 约 1.0 MB | **推荐**。已用 v2 方案签名，R8 混淆 + 资源压缩，`debuggable=false` |
| `dist/TtsApp-1.0.0-debug.apk` | 约 8.5 MB | 备用。未混淆、可调试，万一 release 有异常可用它对比排查 |

**安装步骤**

1. 把 APK 发到手机（微信文件传输助手 / QQ / 数据线 / 网盘都行）。
2. 手机上点开 APK，系统提示「未知来源应用」时，允许该来源安装。
3. 安装完成后桌面会出现「文字转语音」图标（喇叭图标）。
4. 首次朗读没声音，先按第三节装中文语音包。

> 小米/红米需在「设置 → 应用设置 → 授权管理 → 安装未知应用」里给对应 App 放行；
> 华为/荣耀在「设置 → 安全 → 更多安全设置 → 外部来源应用下载」放行。

**签名信息（自己留档）**

- 密钥库：`Y:\AndroidBuild\keystore\ttsapp-release.jks`
- 别名 `ttsapp`，口令 `ttsapp2026`（store 与 key 相同），RSA 2048，有效期 10000 天
- 证书 SHA-256：`e0c87d16dea0effe9e123287ff9faedb32b2407cd7e0feed6ad6a7a5acab152a`

> ⚠️ 这个密钥库只适合自用测试。**要上架应用市场，请自己重新生成一套并离线备份**——
> 一旦丢失，后续版本无法覆盖安装（签名不一致）。

---

## 一、项目结构

```
TtsApp/
├── settings.gradle.kts                 # 模块声明 / 仓库配置
├── build.gradle.kts                    # 根构建脚本（只声明插件版本）
├── gradle.properties                   # AndroidX、非传递 R 类等开关
├── gradle/
│   ├── libs.versions.toml              # Version Catalog：所有版本集中管理
│   └── wrapper/                        # gradle-wrapper.jar + properties（已生成）
├── gradlew / gradlew.bat               # Wrapper 脚本，可直接 ./gradlew assembleRelease
├── local.properties                    # sdk.dir + release 签名配置（不进仓库）
├── dist/                               # 已构建好的安装包
│   ├── TtsApp-1.0.0-release.apk        # 1.0 MB，已签名、R8 混淆
│   └── TtsApp-1.0.0-debug.apk          # 8.5 MB，备用
└── app/
    ├── build.gradle.kts                # compileSdk 34 / minSdk 24 / JDK 17 / R8
    ├── proguard-rules.pro              # 仅保留入口与回调接口
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml     # 零 uses-permission，单 Activity
        │   ├── java/com/example/ttsapp/
        │   │   ├── MainActivity.kt             # 单 Activity，setContent
        │   │   ├── model/TtsUiState.kt         # UiState + 状态机枚举 + 参数上下限
        │   │   ├── tts/TextChunker.kt          # 长文本分段（纯函数，可单测）
        │   │   ├── tts/TtsManager.kt           # 封装 TextToSpeech：初始化/分段/回调/音频焦点
        │   │   └── ui/
        │   │       ├── TtsScreen.kt            # 主界面 Compose
        │   │       ├── TtsViewModel.kt         # 状态与业务逻辑 + 手写 Factory
        │   │       └── theme/{Color,Theme,Type}.kt
        │   └── res/
        │       ├── values/{strings,colors,themes}.xml
        │       ├── values-night/{colors,themes}.xml
        │       ├── drawable/ic_launcher_foreground.xml
        │       ├── mipmap-anydpi-v26/ic_launcher{,_round}.xml   # 自适应图标（API 26+）
        │       ├── mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.png  # API 24~25 回退
        │       └── xml/network_security_config.xml
        └── test/java/com/example/ttsapp/tts/TextChunkerTest.kt  # 6 个分段单测
```

---

## 二、自己编译（可选，环境已配好）

### 0. 本机工具链（已就绪，无需再装）

本机没装 Android Studio，但用便携方式备齐了整条链，都在 Y 盘：

| 组件 | 位置 |
| --- | --- |
| JDK 17 | `C:\Program Files\JetBrains\PyCharm 2024.1.1\jbr`（PyCharm 自带 JBR，含 javac/keytool） |
| Gradle 8.7 | `Y:\AndroidBuild\gradle-8.7` |
| Android SDK（platform-34 + build-tools 34.0.0 + platform-tools） | `Y:\AndroidBuild\sdk` |
| Gradle 依赖缓存 | `Y:\AndroidBuild\gradle-home` |
| release 密钥库 | `Y:\AndroidBuild\keystore\ttsapp-release.jks` |

`local.properties` 里的 `sdk.dir` 和签名四项已指向上述路径（该文件不进仓库）。

### 1. 命令行构建（已实测通过）

```bash
cd Y:/chatGPT/2026-11-04-18-38-11/TtsApp
export JAVA_HOME="C:/Program Files/JetBrains/PyCharm 2024.1.1/jbr"
export GRADLE_USER_HOME="Y:/AndroidBuild/gradle-home"

./gradlew assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # → app/build/outputs/apk/release/app-release.apk（已签名）
./gradlew testDebugUnitTest  # → 6 个分段单测
```

实测结果：`assembleDebug` / `assembleRelease` 均 BUILD SUCCESSFUL，
release 包 1021 KB，`apksigner verify` 通过（v2 方案），6/6 单测通过。

> 本机外网只放行腾讯云镜像，所以 `settings.gradle.kts` 把腾讯的 Google Maven / Maven Central
> 镜像排在仓库列表最前，`gradle-wrapper.properties` 的 distributionUrl 也指向腾讯镜像。
> 换到能直连的机器时，删掉这几行镜像即可回到官方源。

### 2. 用 Android Studio（要改 UI 时）

1. 装 Android Studio（`developer.android.com/studio`），首次启动装 SDK Platform 34 + Build-Tools 34.0.0。
2. `File > Open` → 选 `TtsApp` 目录（**不是** `app` 子目录）。
3. `Settings > Build, Execution, Deployment > Build Tools > Gradle` 确认 **Gradle JDK = 17**。
4. 连真机或开模拟器 → 点绿色 ▶。

> **上架前记得改包名**：`com.example.ttsapp` 是演示包名，应用市场不接受 `com.example.*`。
> 需同时改 `app/build.gradle.kts` 的 `namespace` 与 `applicationId`，以及
> `app/src/main/java/com/example/ttsapp/` 目录（AS 里右键包名 → Refactor → Rename）。

---

## 三、让模拟器「能读出中文」

模拟器镜像默认**没有中文语音数据**，不装的话点播放只会静默失败或报
`LANG_MISSING_DATA`。三种办法，任选其一：

### 办法 A：系统设置里装语音包（推荐）

1. 模拟器 `Settings > System > Languages & input > Text-to-speech output`
   （中文界面：`设置 > 系统 > 语言和输入法 > 文字转语音输出`）。
2. `Preferred engine` 选 **Google 文字转语音**（或 `com.google.android.tts`）。
3. 点引擎右侧齿轮 → `Install voice data` → 选 **中文（中国）** → 下载安装。
4. 回到 App，语言下拉里选「中文（中国）」，再点播放。

### 办法 B：用 Play 商店装 Google TTS

带 Google APIs 的镜像自带 Play 商店，搜 `Speech Services by Google` 安装后，
同上进入设置下载中文语音包。

### 办法 C：直接用真机

国产 ROM（MIUI / HyperOS、ColorOS、OriginOS、EMUI）通常内置讯飞/自研引擎，
中文语音包默认已装，装上 APK 即可出声。若无声，进入
`设置 > 无障碍/语言与输入法 > 文字转语音`，把首选引擎切到系统自带引擎。

---

## 四、8 条关键实现要点 → 代码位置

| # | 要求 | 实现位置 |
| --- | --- | --- |
| 1 | `onInit` 异步，就绪前禁止 `speak()` | `TtsManager.initialize/handleInitResult` 维护 `isReady`；`TtsUiState.isReady` 控制按钮 `enabled`，未就绪时顶部显示加载条 |
| 2 | 初始化时用 `isLanguageAvailable()` 校验并引导去设置 | `TtsManager.selectLanguage`（先 `isLanguageAvailable` 再 `setLanguage`）→ 回调 `onLanguageUnavailable` → UI 弹 `VoiceMissingBanner` + 跳 `com.android.settings.TTS_SETTINGS` |
| 3 | 长文本按句子分段，每段 ≤3500，`QUEUE_ADD` 入队，末段判定结束 | `TextChunker.chunk()`；`TtsManager.speak()` 首段 `QUEUE_FLUSH`、其余 `QUEUE_ADD`；`onDone` 里比较 `index >= chunks.lastIndex` 判整体结束 |
| 4 | 无原生 pause/resume，用 `stop()` + 偏移量伪暂停 | `TtsManager.pause()` 记录 `lastKnownOffset`（来自 `onRangeStart`，不支持时退化为按段 `onStart`），`resume()` 从该偏移重新 `speak()`；代码注释已说明权衡 |
| 5 | 生命周期释放，避免泄漏/后台朗读 | `TtsViewModel.onCleared()` → `TtsManager.release()`（`stop()` + `shutdown()`）；`MainActivity` 单 Activity，`onCleared` 覆盖返回键退出 |
| 6 | 音频焦点 | `requestAudioFocus()` 用 `AUDIOFOCUS_GAIN_TRANSIENT`（API 26+ `AudioFocusRequest`，24~25 走旧 API）；`AUDIOFOCUS_LOSS(_TRANSIENT)` 自动 `stop()` 并提示 |
| 7 | `onError` 不能静默 | `UtteranceProgressListener.onError(id, errorCode)` → 按错误码映射中文文案 → `Snackbar` |
| 8 | 不申请无关权限 | `AndroidManifest.xml` 中**没有任何 `<uses-permission>`**（打包后唯一一条 `com.example.ttsapp.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 是 androidx.core 自动注入的「应用私有签名级权限」，不涉及隐私，详见 SECURITY.md） |

另外两个易错点也已处理：

- **旋转不丢状态**：状态全在 `TtsViewModel`（`StateFlow`）里，Activity 重建不影响；Manifest 未声明 `configChanges`，走标准重建流程。
- **改文本会重置进度**：`TtsViewModel.onTextChange()` 检测到正在播放/暂停时先 `stop()`，再清零 `spokenChars` 并 Snackbar 提示。

---

## 五、验收自测

| 验收项 | 操作 | 预期 | 本机验证 |
| --- | --- | --- | --- |
| 构建 | `./gradlew assembleDebug` | 编译通过，无错误 | ✅ BUILD SUCCESSFUL |
| 单测 | `./gradlew testDebugUnitTest` | 分段逻辑全绿 | ✅ 6/6 通过 |
| 签名 | `apksigner verify app-release.apk` | 签名有效 | ✅ v2 校验通过 |
| 出声 | 输入「你好，欢迎使用文字转语音应用」→ 播放 | 听到中文朗读，状态变「正在朗读」 | ⬜ 待真机 |
| 变速 | 语速拖到 2.0x → 播放 | 明显变快（播放中松手会从当前位置按新语速重读） | ⬜ 待真机 |
| 停止 | 朗读中点「停止」 | 立即静音，状态回「就绪」，进度归零 | ⬜ 待真机 |
| 长文本 | 粘贴 >5000 字 | 分 2 段连续读完，不中断、不报错 | ⬜ 待真机 |
| 暂停续读 | 播放中点「暂停」→「继续」 | 从暂停处附近继续（可能重读当前一句，见「已知限制」） | ⬜ 待真机 |
| 退出无声 | 朗读中按返回键退出 | 立即停止，无后台声音 | ⬜ 待真机 |
| 旋转 | 输入文本 + 调参数 + 播放中旋转屏幕 | 文本、参数、状态、进度都保留 | ⬜ 待真机 |
| 无网可用 | 开飞行模式 | 功能完全正常（不依赖网络） | ⬜ 待真机 |

> 本机没有连接手机/模拟器（`adb devices` 为空），所以「出声」类项目需要你装上 APK 后实测。

---

## 六、已知限制

1. **伪暂停精度取决于引擎**：`TextToSpeech` 没有 pause API。支持 `onRangeStart` 的引擎
   （Google TTS）能精确到字符；不支持的老引擎退化为「按段」记录，继续时会重读当前这一段。
2. **单段上限 3500 字符**：超过 4000 字符单次 `speak()` 会被部分引擎截断，所以必须分段；
   分段处可能出现极短的停顿。
3. **音色**：语言下拉列的是引擎支持的 `Locale`，同一语言下的多音色（voice）需要
   `tts.voices` 才能列出，本 MVP 未做（见下节扩展）。
4. **模拟器无声**：99% 是没装中文语音包，见第三节。

---

## 七、可继续扩展

- **导出 WAV**：`tts.synthesizeToFile(text, params, file, id)`，输出到
  `getExternalFilesDir(Environment.DIRECTORY_MUSIC)`（注意：这需要写应用私有目录，仍不需要存储权限）。
- **多音色**：`tts.voices.filter { it.locale == locale }` + `tts.voice = voice`。
- **接收外部文本**：给 `MainActivity` 加 `ACTION_SEND` / `ACTION_PROCESS_TEXT` 过滤器，
  在 `onCreate` 里读 `intent.getCharSequenceExtra(Intent.EXTRA_TEXT)`。
  ⚠️ 这会新增一个 `exported` 的入口，**任何应用都能把文本推给你的 App**，
  做安全评估时属于新增攻击面，建议加长度上限与内容校验（本 MVP 刻意不做）。
- **朗读进度可视化到句子级**：`TextChunker` 已经返回每段 `start`，可映射成高亮区间。

---

## 八、安全相关

本工程按「最小权限 + 最小暴露面」设计，详细自查表见 [`SECURITY.md`](./SECURITY.md)。
一句话版本：**没有网络权限、没有危险权限、只有一个 exported 的启动 Activity、不落盘不上传不打日志**。

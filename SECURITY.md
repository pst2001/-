# 安全自查报告（TtsApp）

面向「上架前安全检查 / 隐私合规检测 / 静态扫描（MobSF、AppScan、应用市场隐私检测）」的自查清单。
结论：**不申请任何权限，不联网，不采集、不存储、不上传任何用户数据。**

---

## 1. 权限面：0 个 `<uses-permission>`

`app/src/main/AndroidManifest.xml` 中没有任何权限声明。

| 常见被滥用权限 | 本应用 | 说明 |
| --- | --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | ❌ 不申请 | 朗读完全由本机 TTS 引擎完成 |
| `RECORD_AUDIO` | ❌ 不申请 | 只做文字→语音，不采集麦克风 |
| `READ/WRITE_EXTERNAL_STORAGE` | ❌ 不申请 | 不导入导出文件 |
| `READ_PHONE_STATE` | ❌ 不申请 | 来电打断靠 `AudioFocus` 回调，无需读电话状态 |
| `QUERY_ALL_PACKAGES` | ❌ 不申请 | 只用 `<queries>` 精确声明 `TTS_SERVICE` 这一个 intent |

> 检测工具通常会核对「Manifest 权限 ∩ 隐私政策声明」是否一致。本应用权限为空，
> 隐私政策只需写「本应用不收集任何个人信息」，最容易通过审核。

**打包后实际权限（`aapt2 dump badging app-release.apk` 实测）**

```
uses-permission: name='com.example.ttsapp.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

这是 `androidx.core` 在清单合并时自动注入的**应用私有、签名级**权限（用于 `registerReceiver`
的 `RECEIVER_NOT_EXPORTED` 兼容），不是系统权限、不申请任何系统能力、不涉及隐私。
绝大多数扫描器不会把它计入「敏感权限」，若检测报告要求「零权限」，可在
`AndroidManifest.xml` 用 `tools:node="remove"` 移除（需自行确认没有使用动态广播）。

## 2. 组件暴露面：1 个 Activity，0 个 Service/Receiver/Provider

| 组件 | exported | 风险 | 处置 |
| --- | --- | --- | --- |
| `MainActivity` | `true`（桌面启动器必需） | 外部可启动到首页，但不接受任何外部 Intent 参数 | 无 `ACTION_SEND` / `ACTION_VIEW` / `ACTION_PROCESS_TEXT` 过滤器，外部无法注入文本 |
| Service / Receiver / Provider | 无 | — | 不声明，天然无劫持面 |

`<queries>` 只声明 `android.intent.action.TTS_SERVICE`，不申请 `QUERY_ALL_PACKAGES`，
避免「读取已安装应用列表」类合规问题。

## 3. 数据面：不采集、不落盘、不上传、不打日志

- 输入文本只存在于 `TtsViewModel` 的内存 `StateFlow` 中，**不写 SharedPreferences / 数据库 / 文件**；
- `allowBackup="false"`：文本不会被系统备份带到云端或另一台设备；
- 全工程**没有 `Log.d/e/i` 打印用户文本**（避免 logcat 被其他应用读取）；
- 没有崩溃上报 SDK、没有统计 SDK、没有广告 SDK、**第三方依赖仅 AndroidX/Compose**；
- 剪贴板只读不写，也不主动读取剪贴板（用户手动粘贴才进入内存）。

## 4. 网络面：即使误加代码也发不出去

- Manifest 无 `INTERNET` 权限 → 任何 socket 调用都会抛 `SecurityException`；
- `android:usesCleartextTraffic="false"` + `res/xml/network_security_config.xml`
  中 `cleartextTrafficPermitted="false"`，双保险禁止明文 HTTP；
- 不引入任何网络库（OkHttp / Retrofit / Volley 均无）。

## 5. 输入与稳定性（DoS 面）

| 风险 | 处置 |
| --- | --- |
| 粘贴超大文本导致卡顿/内存压力 | `TtsUiState.MAX_INPUT_CHARS = 20_000` 硬上限，超出截断并提示 |
| 单次 `speak()` 超长被引擎拒绝 | `TextChunker` 按句切成 ≤3500 字符，`QUEUE_ADD` 顺序入队 |
| 分段切坏 emoji（代理对） | `hardCut()` 检测 `Character.isLowSurrogate/isHighSurrogate`，不从中断开 |
| 引擎异步初始化竞态 | `onInit` 前 `isReady=false`，UI 按钮全部 disabled；回调统一切主线程 |
| 过期回调造成状态错乱 | 每次 `speak/stop` 自增 `sessionToken`，`utteranceId` 带会话号，旧会话回调直接丢弃 |
| 跳转系统设置页崩溃 | `startActivity` 包 `try/catch (ActivityNotFoundException / SecurityException)`，回退系统设置首页 |
| 引擎异常/未安装 | `onError` 映射中文提示 + Snackbar，不静默失败；`release()` 中 `stop/shutdown` 均 `try/catch` |

## 6. 构建与发布加固

- `release` 开启 `isMinifyEnabled = true` + `isShrinkResources = true`（R8 混淆 + 资源压缩）；
- `proguard-rules.pro` 只保留入口 Activity 与回调接口，保留 `SourceFile/LineNumberTable`
  便于崩溃定位、但重命名为 `SourceFile` 不泄漏源码结构；
- `debug` 与 `release` 使用同一 `applicationId`，**未开启 `debuggable` 到 release**（AGP 默认）；
- `.gitignore` 已排除 `*.jks / *.keystore / local.properties`，避免签名文件误提交。

发布前还需人工完成：

1. ~~生成上传密钥并签名~~ —— **已完成（自用测试签名）**：
   ```bash
   keytool -genkeypair -v -keystore ttsapp-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias ttsapp
   ```
   签名信息放在 `local.properties`（不进仓库），`app/build.gradle.kts` 从该文件读取；
   未配置时 release 自动退回不签名，不会把口令写进构建脚本。
   **上架请自行重新生成并离线备份**，签名不一致会导致无法覆盖安装。
2. `targetSdk` 保持 34（应用市场新规要求）。
3. 隐私政策写明：不收集个人信息、无网络请求、无第三方 SDK。
4. 若后续加了 `INTERNET`（例如接云端 TTS），必须同步更新隐私政策与
   `network_security_config`，并重新做一次本表自查。

**本次构建实测（release 包）**

| 检查项 | 命令 | 结果 |
| --- | --- | --- |
| 包名 / SDK | `aapt2 dump badging` | `com.example.ttsapp`，minSdk 24，targetSdk 34 |
| 权限 | 同上 | 仅 1 条自有签名级权限（见第 1 节说明） |
| 备份 | 同上（manifest） | `allowBackup=false` |
| 明文流量 | 同上（manifest） | `usesCleartextTraffic=false` |
| 可调试 | manifest 无 `debuggable` 属性 | ✅ release 不可调试 |
| 签名 | `apksigner verify --print-certs` | ✅ Verified，APK Signature Scheme v2 |
| 混淆/压缩 | 构建日志 | ✅ `minifyReleaseWithR8` + `shrinkReleaseRes` |
| 体积 | `ls -lh` | 1021 KB（debug 为 8.5 MB，R8 后缩小 88%） |


## 7. 常见扫描器关注项对照

| 扫描项 | 结果 |
| --- | --- |
| 硬编码密钥 / Token / 密码 | 无（无任何密钥） |
| 明文 HTTP / 弱 TLS | 无网络代码，且明文已禁用 |
| WebView + `setJavaScriptEnabled(true)` | 无 WebView |
| 动态加载代码（DexClassLoader / 反射调用 hide API） | 无 |
| 日志泄漏敏感信息 | 无日志 |
| 组件导出风险 | 仅启动 Activity，无参数入口 |
| 备份泄露 | `allowBackup=false` |
| 导出组件被 Intent 注入 | 不接受外部 Intent |
| 权限过度申请 | 0 权限 |
| 第三方 SDK 数据采集 | 无第三方 SDK |
| 反调试 / 加固缺失 | 未加固（属正常，纯本地工具类应用无需加固） |
| 可调试标志 | release 已实测无 `debuggable` 属性 |
| 签名强度 | RSA 2048 + APK Signature Scheme v2（实测校验通过） |

## 8. 残余风险（如实说明）

1. **系统 TTS 引擎本身**：朗读文本会通过 Binder 传给系统 TTS 引擎（如 Google TTS）。
   这是 Android 平台机制，任何 TTS 应用都无法避免；若引擎被配置成在线合成，
   文本可能离开设备。应用内已在错误码 `ERROR_NETWORK` 处提示「请安装离线语音包」。
2. **输入法**：用户输入的文本会经过系统输入法，属于平台层行为。
3. **未做防截屏**：未设置 `FLAG_SECURE`，因为朗读类工具需要用户截图分享，属产品取舍。

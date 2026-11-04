# 本工程没有使用反射 / 序列化 / JNI，默认规则已足够。
# 仅显式保留入口 Activity 与 TTS 回调接口，避免后续改动 R8 规则时被裁掉。

-keep class com.example.ttsapp.MainActivity { *; }
-keep interface com.example.ttsapp.tts.TtsManager$Listener { *; }

# 保留行号信息，便于崩溃定位，同时不泄漏源码结构（混淆后仍可还原调用栈）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

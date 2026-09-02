# 群聊助手（Android）

一个严格只读微信的 Android 原型：收集用户明确选择的置顶群消息，把整理后的上下文交给手机上的 ChatGPT 生成摘要与计划表，再将 ChatGPT 的完整回复分享回 App，自动合并计划并提醒重要变化。

## 已实现

- `NotificationListenerService` 读取微信通知，接收端只保存白名单群名。
- `AccessibilityService` 仅观察微信窗口；用户发起一次性扫描后，可打开已选会话、读取可见文字并返回。
- 首次使用提供单独的敏感数据说明和主动同意。
- 群白名单、本地 SQLite 去重、30 天默认消息保留期和一键清空。
- 中文相对日期/时间解析及本地任务预览规则引擎。
- 通过 Android `ACTION_SEND` 把结构化提示交给 ChatGPT，不需要 API Key。
- ChatGPT 输出 Markdown 摘要/任务表，同时附带约束 JSON；回答可通过系统分享或复制粘贴导回 App。
- 每次提示带一次性随机交换凭证；App 只接受最近 7 天内由自己生成且尚未导入的对应结果，避免其他 App 伪造计划更新。
- 合并时保留未提及的旧计划；只有明确返回的状态和时间才更新对应条目。
- 课程/任务取消、重新启用、完成、截止提前/延后/新增/移除、负责人变化会写入变更记录并发系统通知。

## 严格只读边界

微信无障碍服务没有 `ACTION_SET_TEXT`、剪贴板、粘贴、消息发送队列或“发送”按钮点击。允许的节点动作只有：

1. 点击用户白名单中的会话行；
2. 滚动会话列表；
3. 返回服务自己打开的会话。

一次导航请求带随机 ID、短 TTL、应用内签名权限和防重放缓存。Manifest 未声明 `INTERNET` 权限；只有用户在预览页确认“交给 ChatGPT”时，Android 才把所选文本分享给 ChatGPT App。

## 使用流程

1. 安装后阅读并同意用途说明。
2. 开启“微信通知使用权”和“无障碍只读服务”，允许计划变更通知。
3. 输入准确群名并选择，或在微信会话列表发起“发现置顶群”。
4. 点“扫描已选群”；通知消息会在之后自动收集。
5. 选择 24 小时、3 天或 7 天，预览并交给 ChatGPT。
6. ChatGPT 回答后，把完整回复分享给“群聊助手”；如果分享的是链接而非正文，复制完整回复并使用“粘贴导入”。
7. App 自动刷新摘要和计划表；课程取消或截止变化会出现在通知栏。

## 构建

需要：

- JDK 17
- Android SDK Platform 35 / Build Tools 35.0.0
- Android Studio 或可联网解析依赖的 Gradle 环境

```powershell
./gradlew.bat test
./gradlew.bat assembleDebug
```

调试 APK 通常位于 `app/build/outputs/apk/debug/app-debug.apk`。

当前开发主机只有 JDK 17，没有 Android SDK；Gradle 分发包下载也被网络重置，因此本仓库完成了源代码、XML 和静态边界检查，但尚未在本机生成 APK。

## 现实限制

- 微信不是公开数据接口，界面层级和资源 ID 会随版本/OEM 变化。无法可靠识别“置顶”时，应手动输入准确群名。
- 无障碍只能读取当前窗口实际暴露给 Android 的可见内容；图片、语音、折叠内容和未加载的历史消息不会被可靠读取。
- 被静音且没有系统通知的消息不能靠通知监听获得。
- ChatGPT Android 客户端没有在官方开发文档中提供稳定的“传入指定对话并把回答回调给第三方 App”协议，所以结果回流采用用户明确触发的 Android 分享/粘贴流程。
- 正式上架 Google Play 前必须完成无障碍 API 声明、应用内显著披露、隐私政策和数据安全表；本应用不应声明自己是面向残障人士的 `isAccessibilityTool`。

## 关键目录

- `app/src/main/java/com/xinfen/wxassistant/service/`：微信只读采集服务
- `app/src/main/java/com/xinfen/wxassistant/data/`：白名单、消息、摘要、计划与变更合并
- `app/src/main/java/com/xinfen/wxassistant/domain/`：中文日期、任务规则与 ChatGPT 基础提示
- `app/src/main/java/com/xinfen/wxassistant/integration/`：ChatGPT 分享、结构化结果解析
- `app/src/main/java/com/xinfen/wxassistant/notification/`：计划变更通知
- `app/src/main/java/com/xinfen/wxassistant/ui/`：授权、采集、摘要和计划界面

Android 能力依据：[AccessibilityService](https://developer.android.com/guide/topics/ui/accessibility/service)、[NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)。分发前请核对 [Google Play 无障碍 API 政策](https://support.google.com/googleplay/android-developer/answer/10964491?hl=zh-Hans)。

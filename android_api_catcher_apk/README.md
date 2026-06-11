# Rock Catcher API APK

这是一个独立 APK 项目，包名是 `com.example.rockcatcherapi`，可以和原来的 YOLO 版 `Rock Catcher` 同时安装。

这个版本不使用 YOLO/TFLite。它把当前屏幕截图压缩成 JPEG，通过你填写的 OpenAI 兼容视觉 API 发给大模型，请模型返回精灵中心坐标：

```json
{"found":true,"x":123,"y":456,"confidence":0.86,"reason":"target"}
```

然后应用继续复用原来的逻辑：用标定好的准星和扔球键坐标计算滑动手势，通过无障碍服务执行，并在屏幕上显示几秒手势轨迹。

## 构建

在本目录执行：

```powershell
.\build_apk.ps1
```

输出 APK：

```text
app\build\outputs\apk\debug\app-debug.apk
```

脚本会优先使用本目录 `.android-sdk`，如果不存在，会复用 `..\android_catcher_apk\.android-sdk`。

## 手机使用

1. 安装 `android_api_catcher_apk\app\build\outputs\apk\debug\app-debug.apk`。
2. 打开 `Rock Catcher API`。
3. 点“打开无障碍设置”，启用 `Rock Catcher API Gestures`。
4. 回到应用，填写 API URL、API Key、模型名。默认 URL/模型名按 DeepSeek V4 Flash 填写，但 API 必须支持图片输入才可以识别画面。
5. 点“标定准星 / 标定扔球键”，导入游戏截图，分别点准星中心和扔球键中心。
6. 回主界面，按需要调整 X/Y 灵敏度、方向、最大步长、手势时长。
7. 点“启动悬浮条 / 准备接管”，授权截屏。
8. 进入游戏，用悬浮条操作：
   - `抓捕`：开始接管手机并发送滑动手势。
   - `暂停`：继续识别，但不控制手机；再点一次从当前画面继续。
   - `停止`：结束服务。
   - `录制`：保存当前截图到应用私有目录，方便回看 API 识别效果和坐标。

## DeepSeek V4 能不能用

技术上，这个 APK 可以接任何“支持图片输入，并能返回坐标 JSON”的大模型 API。

DeepSeek 官方 API 文档当前列出的 V4 模型是 `deepseek-v4-flash` 和 `deepseek-v4-pro`，聊天接口按文本 `content` 接收消息，没有看到官方 `image_url` / 图片输入字段。也就是说，当前官方 DeepSeek V4 文本 API 不能直接完成精灵定位；它看不到画面，只能处理文字。

如果未来 DeepSeek 官方或某个兼容供应商提供 V4 视觉模型，并且接口支持图片输入和坐标 JSON 输出，这个 APK 可以接上。但即使用视觉大模型，网络延迟、费用和稳定性也会限制实时效果：它更适合低频识别、录制分析或辅助判断，不如本地 YOLO/模板适合实时闭环操控。

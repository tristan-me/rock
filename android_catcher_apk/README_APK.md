# Rock Catcher APK

这是一个独立 Android APK 工程，没有改动上级目录里的 Python 脚本原型。

APK 能力：

- 使用 `MediaProjection` 获取屏幕截图。
- 使用 `AccessibilityService` 在用户显式启用后执行手势。
- 支持外部 `model.tflite` YOLO 推理，识别 `sprite`；准星和扔球键使用手动标定坐标。
- 支持 APK 内导入少量图片、辅助标注精灵、端上轻量训练，生成 `template_model.tsv`。
- 通过无障碍悬浮条在游戏中随时选择抓捕、暂停、停止或录制当前画面。

不包含绕过检测、防封、注入、改包、读内存或批量账号功能。

## 端上训练说明

当前 APK 内置的是轻量模板训练，不是完整 YOLO 训练。原因是完整 YOLO 训练依赖 PyTorch/Ultralytics 等训练链，直接塞进手机 APK 会非常重、慢且不稳定。这个版本只从你标注的精灵框中抽取视觉特征，生成一个小模型；准星和扔球键不训练，只在独立页面标定它们在屏幕上的固定中心坐标。

生成文件位于：

```text
/sdcard/Android/data/com.example.rockcatcher/files/template_model.tsv
/sdcard/Android/data/com.example.rockcatcher/files/template_model_report.txt
/sdcard/Android/data/com.example.rockcatcher/files/trainer/images/
/sdcard/Android/data/com.example.rockcatcher/files/trainer/annotations.tsv
```

## APK 使用流程

1. 安装 APK：

```powershell
D:\rock\android_catcher_apk\.android-sdk\platform-tools\adb.exe install -r D:\rock\android_catcher_apk\app\build\outputs\apk\debug\app-debug.apk
```

2. 打开 `Rock Catcher`。

3. 点 `精灵图片 / 标注 / 训练`。

4. 点 `导入图片`，选择几张游戏截图。建议先用相同分辨率、相同 UI 布局、精灵清楚出现的截图。

5. 在训练页标注：

- 点 `标精灵` 后，在精灵身上拖框。至少需要 1 个 `sprite` 标注，建议准备 8 张以上不同截图。
- 点一下图片会生成默认大小框；拖动会生成自定义框。
- 每张图精灵框标完后点 `保存标注`。

6. 有了至少一个精灵框后，可以点 `自动建议`，APK 会用已有标注给当前图补一个精灵框。建议仍然目测检查，不准就撤销重标。

7. 点 `训练并评估`。完成后页面会显示：

- 精灵样本数量，以及建议还需要补多少张图片。
- `avg_score` 和 `avg_iou` 每行中文解释。
- 模型保存路径。

8. 回主界面，点 `标定准星 / 扔球键`，导入一张游戏截图，然后分别点准星中心和扔球键中心。这里只保存坐标，不参与训练。

9. 回主界面，打开无障碍设置并启用 `Rock Catcher Gestures`，然后点 `启动悬浮条 / 准备接管`，同意截屏授权。此时后台会持续识别，但不会控制手机。

10. 进入游戏后使用悬浮条：

- `抓捕`：开始软件接管手机并发送滑动手势，按钮会变成 `暂停`。
- `暂停`：临时不接管手机，后台仍识别画面；再点 `抓捕` 会继续接管。
- `停止`：结束后台截屏和接管。
- `录制`：保存当前游戏画面到训练图片目录，方便补训练图、重新标定或排查识别问题。

11. 调参：

- `端上模型最低匹配分`：默认 `0.68`。误识别多就调高，识别不到就略降。
- `端上模型扫描步长 px`：默认 `8`。越小越准但越慢。
- `端上模型扫描最长边 px`：默认 `640`。越大越准但越慢。
- `准星 X/Y` 和 `扔球键 X/Y`：固定标定坐标，可以手填，也可以在 `标定准星 / 扔球键` 页面写入。
- `X/Y 灵敏度`、`X/Y 方向`、`最大滑动步长 px`：游戏长按扔球键并移动时视角会跟着动，所以需要实测这些参数。方向错了就把对应方向从 `1` 改为 `-1`；移动过头就降低灵敏度或最大步长；移动不够就提高灵敏度。

## YOLO TFLite 路线

如果后续你在电脑上训练好了 YOLO 并导出 TFLite，仍然可以放入：

```powershell
D:\rock\android_catcher_apk\.android-sdk\platform-tools\adb.exe push best_float32.tflite /sdcard/Android/data/com.example.rockcatcher/files/model.tflite
```

运行时会先尝试 `model.tflite`。如果没有 TFLite，或 TFLite 没识别到精灵，会回退使用 `template_model.tsv`。

## 构建

本目录提供不依赖 Gradle 的构建脚本：

```powershell
cd D:\rock\android_catcher_apk
.\build_apk.ps1
```

构建成功后，debug APK 位于：

```text
D:\rock\android_catcher_apk\app\build\outputs\apk\debug\app-debug.apk
```

# Rock Catcher APK

这是一个独立 Android APK 工程，没有改动上级目录里的 Python 脚本原型。

APK 能力：

- 使用 `MediaProjection` 获取屏幕截图。
- 使用 `AccessibilityService` 在用户显式启用后执行手势。
- 支持外部 `model.tflite` YOLO 推理，识别 `sprite`；准星和扔球键使用手动标定坐标。
- 支持 APK 内导入少量图片、辅助标注精灵、端上轻量训练，生成 `template_model.tsv`。
- 默认 dry-run；只有点 `启动 Armed` 后才会执行无障碍手势。

不包含绕过检测、防封、注入、改包、读内存或批量账号功能。

## 端上训练说明

当前 APK 内置的是轻量模板训练，不是完整 YOLO 训练。原因是完整 YOLO 训练依赖 PyTorch/Ultralytics 等训练链，直接塞进手机 APK 会非常重、慢且不稳定。这个版本只从你标注的精灵框中抽取视觉特征，生成一个小模型；准星和扔球键不训练，只标定它们在屏幕上的固定中心坐标。

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

3. 点 `导入图片 / 标注 / 训练`。

4. 点 `导入图片`，选择几张游戏截图。建议先用相同分辨率、相同 UI 布局、精灵清楚出现的截图。

5. 在训练页标注：

- 点 `标精灵` 后，在精灵身上拖框。至少需要 1 个 `sprite` 标注，建议准备 8 张以上不同截图。
- 点 `标定准星` 后，在准星位置点一下或拖一个小框；APK 会把中心点写入主界面的准星 X/Y，不参与训练。
- 点 `标定扔球键` 后，在扔球按钮位置点一下或拖一个小框；APK 会把中心点写入主界面的扔球键 X/Y，不参与训练。
- 点一下图片会生成默认大小框；拖动会生成自定义框。
- 每张图精灵框标完后点 `保存标注`。

6. 有了至少一个精灵框后，可以点 `自动建议`，APK 会用已有标注给当前图补一个精灵框。建议仍然目测检查，不准就撤销重标。

7. 点 `训练并评估`。完成后页面会显示：

- 精灵样本数量，以及建议还需要补多少张图片。
- `avg_score` 和 `avg_iou` 每行中文解释。
- 模型保存路径。

8. 回主界面，先点 `启动 Dry Run`。Dry Run 只截屏、识别精灵、计算应该怎么滑，不会控制手机。状态里看到 `template loaded` 且 `sprite=...` 有分数，说明端上模型正在工作。

9. 调参：

- `端上模型最低匹配分`：默认 `0.68`。误识别多就调高，识别不到就略降。
- `端上模型扫描步长 px`：默认 `8`。越小越准但越慢。
- `端上模型扫描最长边 px`：默认 `640`。越大越准但越慢。
- `准星 X/Y` 和 `扔球键 X/Y`：固定标定坐标，可以手填，也可以在训练页点 `标定准星 / 标定扔球键` 自动写入。

10. 只有 dry-run 识别稳定、准星/按钮坐标正确后，再开启无障碍服务并点 `启动 Armed`。Armed 会真正发送无障碍滑动手势；每次自动手势会在屏幕上显示一条短暂轨迹，最多保留几条，几秒后自动消失，避免遮挡堆叠。

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

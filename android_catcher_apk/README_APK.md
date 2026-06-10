# Rock Catcher APK

这是一个独立 Android APK 工程，没有改动上级目录里的 Python 脚本原型。

APK 能力：

- 使用 `MediaProjection` 获取屏幕截图。
- 使用 `AccessibilityService` 在用户显式启用后执行手势。
- 支持外部 `model.tflite` YOLO 推理，识别 `sprite`、`reticle`、`ball_button`。
- 支持 APK 内导入少量图片、辅助标注、端上轻量训练，生成 `template_model.tsv`。
- 默认 dry-run；只有点 `启动 Armed` 后才会执行无障碍手势。

不包含绕过检测、防封、注入、改包、读内存或批量账号功能。

## 端上训练说明

当前 APK 内置的是轻量模板训练，不是完整 YOLO 训练。原因是完整 YOLO 训练依赖 PyTorch/Ultralytics 等训练链，直接塞进手机 APK 会非常重、慢且不稳定。这个版本会从你标注的框中抽取视觉特征，生成一个小模型，适合先用几张截图验证“识别 + 拖准星 + 松手”的动作流程。

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

- 点 `标精灵` 后，在精灵身上拖框。至少需要 1 个 `sprite` 标注。
- 点 `标准星` 后，可框准星；不标也可以回主界面填写固定准星坐标。
- 点 `标扔球键` 后，可框扔球按钮；不标也可以回主界面填写固定按钮坐标。
- 点一下图片会生成默认大小框；拖动会生成自定义框。
- 每张图标完后点 `保存标注`。

6. 有了至少一个精灵框后，可以点 `自动建议`，APK 会用已有标注给当前图补缺失框。建议仍然目测检查，不准就撤销重标。

7. 点 `训练并评估`。完成后页面会显示：

- 三类样本数量。
- training images self-check 的 `avg_score` 和 `avg_iou`。
- 模型保存路径。

8. 回主界面，先点 `启动 Dry Run`。状态里看到 `template loaded` 且 `sprite=...` 有分数，说明端上模型正在工作。

9. 调参：

- `端上模型最低匹配分`：默认 `0.68`。误识别多就调高，识别不到就略降。
- `端上模型扫描步长 px`：默认 `8`。越小越准但越慢。
- `端上模型扫描最长边 px`：默认 `640`。越大越准但越慢。
- `准星 X/Y` 和 `扔球键 X/Y`：如果没有标注准星/按钮，使用这里的固定坐标。

10. 只有 dry-run 识别稳定、准星/按钮坐标正确后，再开启无障碍服务并点 `启动 Armed`。

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

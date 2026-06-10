# Rock Catcher

本项目是一个本地安卓画面识别与手势自动化原型，技术路线是：

- Android 设备或模拟器通过 ADB 截屏，scrcpy 可作为实时预览窗口。
- YOLO 识别精灵、准星和扔球按钮。
- OpenCV 模板匹配可作为准星/按钮的辅助识别。
- ADB `input motionevent` 或 Airtest/ADB swipe 发送按住、移动、松手手势。

请只用于本地学习、可控测试或辅助研究。不要加入绕过检测、防封、注入、改包、内存读取、批量账号等能力；正式服使用脚本可能违反游戏规则并导致账号处罚。

## 目录

```text
rock_catcher/
  adb.py             # ADB 截屏与触控封装
  aim.py             # 识别后闭环拖动与释放逻辑
  capture.py         # 采集训练截图
  config.py          # 配置加载
  controllers.py     # ADB/Airtest 手势后端
  dataset.py         # 生成 YOLO 数据集结构
  detectors.py       # YOLO + 模板匹配
  label_tool.py      # 简单 OpenCV 标注工具
  run.py             # 主运行入口
  train_yolo.py      # YOLO 训练封装
config.example.yaml  # 示例配置
requirements.txt
```

## 安装

1. 安装 Android platform-tools，让 `adb` 能在命令行里运行。
2. 可选安装 `scrcpy`，用于在电脑上看实时画面。
3. 安装 Python 依赖：

```powershell
.\setup.ps1
```

确认设备连接：

```powershell
adb devices
```

如果有多台设备，把设备 serial 写进 `config.local.yaml` 的 `device.serial`。

## 快速流程

复制配置：

```powershell
Copy-Item config.example.yaml config.local.yaml
```

如果已经运行过 `setup.ps1`，这一步会自动完成。

采集截图：

```powershell
python -m rock_catcher.capture --out data/raw --count 200 --interval 0.4
```

标注截图。建议至少标注 `sprite`，如果准星和扔球按钮位置稳定，也可以只在配置里写固定坐标：

```powershell
python -m rock_catcher.label_tool data/raw --labels data/labels --classes sprite reticle ball_button
```

生成 YOLO 数据集：

```powershell
python -m rock_catcher.dataset --images data/raw --labels data/labels --out data/yolo --classes sprite reticle ball_button
```

训练模型：

```powershell
python -m rock_catcher.train_yolo --data data/yolo/data.yaml --epochs 80 --imgsz 960 --out models
```

训练完成后，把 `config.local.yaml` 里的 `model.path` 改成训练输出的 `best.pt`。

先干跑验证识别：

```powershell
.\run_dry.ps1
```

确认识别框、准星和按钮都正确后，再显式授权触控：

```powershell
python -m rock_catcher.run --config config.local.yaml --arm --preview
```

`--arm` 才会真的向手机/模拟器发送触控事件。

## 配置要点

- `device.controller: auto` 会优先尝试 ADB `motionevent`，支持真正的 down/move/up 闭环；不支持时退回一次性 swipe。
- `fallback.reticle` 和 `fallback.ball_button` 是坐标兜底。如果模型不识别准星/按钮，可用它们。
- `aim.direction_x/y` 决定拖动方向。如果发现越拖越远，把对应方向从 `1` 改成 `-1`。
- `aim.gain_x/y` 是拖动灵敏度，过大容易抖动，过小会追不上。
- `aim.release_radius_px` 是准星与精灵中心距离小于多少像素时松手。

## 标注工具按键

- `0/1/2/...`：切换类别。
- 鼠标左键拖拽：画框。
- `u`：撤销当前图片最后一个框。
- `s`：保存当前图片标注。
- `n`：保存并下一张。
- `p`：上一张。
- `q` 或 `Esc`：退出。

## scrcpy 预览

项目运行不强依赖 scrcpy，因为截屏走 ADB。你可以单独开一个预览窗口：

```powershell
scrcpy --stay-awake
```

如果用模拟器，建议固定分辨率和 DPI；训练、干跑、实际运行都用同一套分辨率，识别和坐标会稳定很多。

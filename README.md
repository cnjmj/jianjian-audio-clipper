---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '5fcf009a-1283-489c-ac75-2582933b10e6'
  PropagateID: '5fcf009a-1283-489c-ac75-2582933b10e6'
  ReservedCode1: '3dbbb17b-5973-403c-a43a-688dfbb687a2'
  ReservedCode2: '3dbbb17b-5973-403c-a43a-688dfbb687a2'
---

# 简剪 · 音频剪辑（JianJian AudioClip）

一款安卓上的原生音频剪辑应用，全中文界面、Material 风格、**零权限**（使用系统 SAF 文件选择器，不上传任何数据）。

## 功能特性

- **MP3 帧级无损裁剪**：自研 MPEG 帧头解析（含 ID3v2 跳过），按帧定位，字节级裁剪、不重编码、无音质损失
- **WAV 字节级裁剪**：解析 RIFF 头按字节率裁剪 PCM 数据，无损
- **M4A / AAC / MP4 / 3GP 无损重封装**：MediaExtractor + MediaMuxer 直接重封装输出 m4a
- **OGG / FLAC / AMR 等**：解码为 PCM 导出标准 WAV
- 播放 / 暂停、区间试听，播放时屏幕自动长亮
- 进度条拖动快速定位，±0.5s / ±5s 精细微调
- **起止时间手动输入**（格式 `分:秒.毫秒`，如 `01:23.500`），支持 `45.5`、`90` 等简写，精确定位
- 文件信息卡片：格式、时长、采样率、声道、比特率、文件大小（毫秒级读取，秒开）
- 剪辑历史记录（SQLite 本地存储，可长按删除、一键清空）

## 构建

环境要求：JDK 17+、Android SDK 34（本项目使用 Gradle 8.9）

```bash
# 在项目根目录
gradle assembleDebug
# 或用 Android Studio 直接打开本项目，点 Run

# 产物
app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

1. 打开应用，点击右下角「选择音频」
2. 拖动进度条或用输入框手动设置起点 / 终点（长按「设为起点」回到开头，长按「设为终点」跳到结尾）
3. 「试听区间」确认效果，微调按钮精确到 0.5 秒
4. 点「导出剪辑片段」，选择保存位置即完成

## 技术要点

| 模块 | 实现 |
|------|------|
| MP3 帧解析 | 自实现 MPEG1/2/2.5 Layer III 帧头解析（含 ID3v2 跳过） |
| 剪辑核心 | `AudioTrimmer`（策略模式按格式分发：mp3 / wav / remux / decode） |
| 文件信息 | MediaExtractor 读取轨道元数据 + ContentResolver 查询大小 |
| 界面 | Java 原生 + Material Components，两 Activity（主页 / 编辑页） |
| 存储 | SQLite 剪辑历史 |

## 项目结构

```
app/src/main/java/com/jmj/audioclip/
├── MainActivity.java      # 主界面：选择音频、历史记录
├── EditorActivity.java    # 编辑界面：定位、标记、播放、导出
├── AudioTrimmer.java      # 剪辑核心引擎（MP3/WAV/重封装/解码）
├── Mp3Frame.java          # MPEG 帧头解析
├── DbHelper.java          # SQLite 历史记录
└── HistoryAdapter.java    # 历史列表适配器
```

版本：1.6（minSdk 21 / targetSdk 34）

> AI生成
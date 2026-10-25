# Wherever Play

> 伴奏播放 + PDF 谱面按时间轴自动翻页。练琴、排练、上台都能用，**纯本地运行，零联网**，不上传任何文件。

弹伴奏的时候总要空出一只手去翻谱。Wherever Play 的做法是：先给每一页谱面打一个「时间点」，
播放音频时到了这个时间就自动翻到对应页——手不用离开琴。

## 功能

- **打点**：剪映式时间轴，拖动圆点改时间，点轨道跳光标，边缘自动加速滚动，60 步撤销。
- **播放**：单页 / 双页并排（电子书模式），跟手滑动翻页，手动翻页不打断音频，下个打点自动回到自动模式。
- **播放列表**：把几首曲子串成一场演出，曲间可选自动衔接（可设间隔秒数）或手动衔接；长按拖动排序。
- **提前量**：全局或单曲设置「提前 N 毫秒翻页」，赶在乐句前把下一谱摆好。
- **乐器标签**：内置 8 种乐器 + 可自建冷门乐器（总数上限 20），首页按标签筛选，卡片封面左下角显示。
- **导入导出**：项目打包成 `.wplay`，播放列表打包成 `.wplist`（自动内嵌其中用到的项目）。
- **多选批量**：两个首页都支持长按多选，批量导出或删除。

## 技术栈

| 项 | 版本 |
|---|---|
| AGP | 9.4.0（内置 Kotlin，不再单独加 `kotlin-android` 插件） |
| Kotlin | 2.3.21 |
| Compose BOM | 2026.09.00 |
| Gradle | 9.6.0 |
| compileSdk / targetSdk | 37 |
| minSdk | 24（Android 7.0） |

架构上没有引 Navigation、没有引 Room：`domain/` 是纯 Kotlin（零 `android.*` 依赖，为将来上 KMP 留路），
UI 用 sealed `Route` + 状态机路由，存储是 JSON 文件（临时文件 + rename 防损坏），
PDF 用系统 `PdfRenderer` + LRU 缓存，音频用 `MediaPlayer`。

```
app/src/main/java/com/whereverplay/app/
├── domain/    Model / MarkRules / PageResolver / Playlist —— 业务规则全在这一层
├── data/      JSON 存储、SAF 媒体导入、标签、.wplay 打包
├── pdf/       渲染 + LRU 缓存 + 预渲染
├── player/    AudioPlayer 接口 + MediaPlayer 实现
└── ui/        全部 Compose 界面
```

## 构建

```bash
# 需在 local.properties 里指定 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 打包格式

`.wplay` 和 `.wplist` 都是标准 ZIP，可以直接用解压软件打开查看：

```
xxx.wplay
├── manifest.json        格式版本、项目元信息、打点数组、原项目 id
└── media/               音频、PDF 谱面、封面
```

```
xxx.wplist
├── manifest.json        播放列表定义 + 内嵌的所有可用项目
└── media/               内嵌项目的媒体文件
```

导入时**尽量保留原 id**，所以「删掉某个项目后再导入它的 `.wplay`」能让播放列表里的失效占位自动接上。

## 状态

早期版本，功能可用但还在改。当前 `0.1.0 (100)`，仅发布 debug 构建。

已知待办：
- PDF 渲染缓存 24 MB 约合 3 页，三页并排时处于临界
- 后台播放 / 通知栏控制
- `.wplay` 的 Intent 打开（从聊天软件点开直接进 App）
- 中英文切换

# Demo 使用与验收说明

> 版本：0.1.0-demo　构建时间：2026-10-24
> 代码位置：`D:\project\android\wherever play\`（只改这个目录，未碰 `demo` 与 jar）

---

## 1. 现在能不能跑

**能。** `app-debug.apk` 已经构建成功（约 11.6 MB）。

两条安装路径：

| 方式 | 操作 |
|---|---|
| Android Studio 运行 | 手机连 USB → 顶部选中设备 → 点绿色 ▶ Run |
| 直接装 APK | `app/build/outputs/apk/debug/app-debug.apk` 拷到手机点击安装（需允许「未知来源」） |

> 这是 debug 包，仅供测试。正式分发前必须换成 release 签名。

---

## 2. 已实现的功能（对照需求确认稿 §19）

| 功能 | 状态 | 说明 |
|---|---|---|
| 新建项目：选音频 + 选 PDF + 命名 | ✅ | SAF 选择器；音频限 30 分钟；自动读时长与页数 |
| 媒体复制进私有目录 | ✅ | `files/media/`，原文件删了也不影响 |
| 项目列表 | ✅ | 显示时长/页数/打点数，未打点有黄色角标 |
| 打点页：播放 + 进度条 + 圆点拖动 | ✅ | 圆点在条上方，带向下引导线，不会误触 |
| 「在此处添加第 N 页」 | ✅ | 落在当前播放头处；页数满后提示「所有页面已创建」 |
| 微调 ±0.1s / ±1s | ✅ | 选中圆点后出现 |
| 严格递增夹紧 | ✅ | 拖动不能越过邻居，最小间隔 300ms |
| 完成校验 | ✅ | 页数不齐会提示缺哪几页 |
| 预览窗 | ✅ | 随播放头实时显示对应页 |
| 播放页：全屏谱面 + 自动翻页 | ✅ | 预渲染上下页，翻页提前量可 ±100ms 调 |
| 手动上一页/下一页 | ✅ | 播放页底部 |
| 屏幕常亮 | ✅ | 播放页硬编码开启 |
| 后台播放 / 通知栏 | ❌ | Demo 不做 |
| 播放列表 / 收藏 / 搜索 | ❌ | Demo 不做 |
| 液态玻璃 / 封面 / 主题 | ❌ | Demo 不做，纯深色默认控件 |
| 波形轨道 / 时间轴缩放 | ❌ | Demo 用简化线性时间轴，v1 换剪映式 |

---

## 3. 怎么测（三步）

1. **准备素材**：一首 3–5 分钟的 mp3/wav/flac 伴奏 + 一份 5 页以上的 PDF 谱。
   > 强烈建议用**扫描版** PDF 测，它的渲染耗时远高于矢量谱，能暴露真实问题。
2. **打点**：新建 → 选文件 → 点「播放」，听到该翻页的地方暂停 → 点「在此处添加第 N 页」。
   全部页打完 → 点「完成」，会自动进播放页。
3. **验证**：点播放，看谱面是否在正确时机翻页。

---

## 4. 三个验收指标怎么看

接上手机，Logcat 过滤框输入 `tag:WP-PDF|WP-SYNC`（或直接 `WP-`）。

### ① 翻页迟滞 → 看 `WP-PDF`

```
D/WP-PDF: render page=3 1080x1528 cost=142ms
```

- **< 100ms**：很理想，预渲染完全够用
- **100–300ms**：可接受，但必须靠预渲染兜住
- **> 400ms**：说明是高清扫描谱，需要提高预渲染提前量或降低渲染分辨率

### ② 同步精度 → 看 `WP-SYNC`

```
D/WP-SYNC: switch to page=3 at position=41230ms mark=41100ms offset=0ms diff=130ms
```

`diff` 就是实际翻页时刻与标记时刻的偏差。

- **|diff| < 150ms**：人耳几乎察觉不到
- **150–300ms**：能感觉到，需要调翻页提前量
- 如果 `diff` 一直在 200ms 以上且稳定，说明轮询间隔（16ms）不是瓶颈，
  真正原因是渲染没跟上 —— 这时用播放页的「翻页提前量」+100ms 去补偿

### ③ 内存 → 看 Android Studio 的 Profiler

播放页停留 3–5 分钟，观察内存是否持续上涨（上涨说明有 Bitmap 泄漏）。
预期稳定在 100MB 上下。

---

## 5. 已知限制（Demo 有意为之）

1. **时间轴是简化版**：线性、无缩放、无波形、无片段轨道。
   代码里标注为「Demo 简化版时间轴」，v1 会整体替换，这块代码注定要重写。
2. **播放器用系统 MediaPlayer**，不是 Media3。已抽成 `AudioPlayer` 接口，替换成本低。
3. **存储用 JSON 文件**，不是 Room。已抽成 `ProjectRepository` 接口。
4. **翻页没有动画**，是硬切。
5. **不解析音频内嵌封面**，列表用色块占位。

---

## 6. 代码结构

```
app/src/main/java/com/whereverplay/app/
├── domain/                     ← 纯 Kotlin，不含任何 android.*，将来可复用到 iOS
│   ├── Model.kt                Project / Mark
│   ├── MarkRules.kt            打点校验、夹紧、新增时间计算
│   ├── PageResolver.kt         时间 → 页码；时间码格式化
│   └── ProjectRepository.kt    存储接口
├── data/
│   ├── JsonProjectStore.kt     JSON 实现（v1 换 Room）
│   └── MediaInstaller.kt       SAF 复制、读时长、读内嵌封面
├── pdf/PdfPageRenderer.kt      渲染 + LRU 缓存（位图必须 ARGB_8888）
├── player/AudioPlayer.kt       播放器接口 + MediaPlayer 实现
└── ui/
    ├── AppRoot.kt              状态路由（未用 navigation-compose）
    ├── ProjectListScreen.kt
    ├── CreateProjectScreen.kt
    ├── MarkingScreen.kt        打点页 + DemoTimeline
    ├── PlaybackScreen.kt       播放页
    └── PdfPreview.kt           单线程渲染调度 + 邻居页预渲染
```

**架构要点**：`domain/` 里没有任何 Android 依赖，这是为了将来用 KMP 复用到 iOS 时能直接拿走。
审核代码时可以从 `domain/MarkRules.kt` 开始看，核心逻辑一共不到 100 行。

---

## 7. 环境备忘（本机）

| 项 | 值 |
|---|---|
| AGP | 9.4.0（**内置 Kotlin 支持**，无需 kotlin-android 插件） |
| Kotlin | 2.3.21（Compose 编译器插件版本与之匹配） |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk | 37 |
| minSdk | 24 |
| Gradle | 9.6.0 |
| Maven 镜像 | 阿里云优先（`settings.gradle.kts`） |

> 踩坑记录：
> 1. AGP 9 内置 Kotlin 时，`buildFeatures.compose = true` **仍必须**应用
>    `org.jetbrains.kotlin.plugin.compose` 插件，否则报 "Compose Compiler Gradle plugin is required"；
>    且该插件版本必须与内置 Kotlin 版本一致（2.3.21）。
> 2. **`PdfRenderer.Page.render()` 的目标位图必须是 ARGB_8888**。
>    传 RGB_565 会抛异常，表现极具迷惑性：界面全黑但「渲染耗时」只有 3ms（其实是失败的耗时）。
>    省内存不能靠降色深，只能靠限制渲染宽度 + 缓存上限。
> 3. Compose 里 `LocalContentColor` 默认是**黑色**。根内容不套 `Surface` 时，
>    深色背景上的标题与会字段名会变成「黑字黑底」看不见。

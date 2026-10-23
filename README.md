# 通用 Android 视频播放器框架

基于 Media3 的独立 Android 视频播放器框架。提供列表、详情播放、预加载、共享缓存及渲染管理。

## 包含内容

| 目录 | 内容 |
| --- | --- |
| `player/` | 可复用Android Library：全局资源、列表/详情控制器、双引擎、预加载、渲染租约及可选基础UI |
| `player-ui/` | 可选接入层：自动处理 RecyclerView / ViewPager2 监听、宿主绑定、生命周期和状态恢复 |
| `sample/` | 示例首页、RecyclerView 列表自动播放、竖向 ViewPager2 短视频播放，使用随包本地视频 |
| `tools/` | 框架本地分发打包工具 |
| `docs/` | 架构与宿主接入说明 |

## 核心行为

- 整个进程由一个`VideoPlaybackRuntime`管理资源，最多两个ExoPlayer；列表和详情页共用所有权仲裁。低内存、解码资源失败时可降为单引擎。
- 当前项播放、相邻项静音暂停预热；前后对称预加载窗口为±2，双方先达到500ms，再补充相邻项缓冲。
- 完成的sample可交接；未完成时走共享缓存回退，不把部分预加载伪装为命中。取消后的旧代次立即失效，再按最新窗口重建。
- 渲染Surface归引擎，宿主只借用；旧句柄的命令和解绑无法控制新归属。拖动不提前暂停当前项，也不闪暂停按钮。
- 保留精确恢复；非零进度预热先启用500ms codec加速，随后仅保留动态调度，首帧/取消提前结束，最长1500ms。
- 缓存、网络、本地读取和加载取消分别计量；诊断默认关闭，不输出签名URL、请求头或业务埋点。起播缓冲、预热读取阈值不是进程总内存的硬上限。

版本与Android SDK范围以[版本目录](gradle/libs.versions.toml)为准。当前直接Media3依赖锁定为经过验证的版本；升级需要重新验证preload/sample及渲染交接。支持基于Media3的MP4/HLS/DASH路径；安全DRM Surface、Cast、PiP系统窗口、后台Service/MediaSession与业务手势UI未在本模块实现。

## 构建和运行

安装JDK 21与版本目录对应的Android SDK，配置`ANDROID_HOME`或本机未跟踪的`local.properties`，在本目录执行：

```sh
./gradlew :player:testDebugUnitTest :player-ui:testDebugUnitTest :sample:assembleDebug :player:assembleDebugAndroidTest :player-ui:assembleDebugAndroidTest
./gradlew :player:lintRelease :player-ui:lintRelease :sample:lintDebug
```

依赖只使用公共仓库。需要镜像时可显式传`-PandroidRepositoryMirror=...`与`-PmavenRepositoryMirror=...`；默认不依赖镜像或任何私有Maven。首次构建需要网络，不能假设宿主的Gradle缓存已齐备。

运行示例：

```sh
./gradlew :sample:installDebug
adb -s DEVICE_SERIAL shell am start -n io.github.silascui.sloth/io.github.silascui.sloth.sample.DemoActivity
```

首页提供两个入口：

- **列表自动播放**：RecyclerView 卡片列表，停止滚动后播放可见视频面积最大的卡片；复用和移出屏幕时注销宿主，同一时间只播放一个视频。
- **短视频上下滑动播放**：竖向 ViewPager2 全屏切换，拖动预热相邻视频，滚动停止后播放选中项。

两种示例均默认静音，点击视频暂停/继续；切后台暂停，返回恢复，退出释放会话，并保存播放位置和用户暂停状态。列表示例还保存滚动位置。使用本地视频，无需网络或账号。

`demo1.mp4` 和 `demo2.mp4` 为本地生成的动态测试图案，不包含原项目画面或品牌素材。两者均为 1080×1920、24fps、3 秒的 H.264/AAC MP4（静音音轨）；安装 FFmpeg 后可运行 `bash tools/generate-demo-videos.sh` 重新生成。

## 推荐接入：列表与短视频

宿主完成隐私授权后初始化一次：`VideoPlaybackRuntime.initialize(applicationContext)`。
先按下方 [Maven Central](#maven-central) 配置依赖；源码工程也可以使用 `implementation(project(":player-ui"))`。让 Adapter 的 Holder 继承 `VideoViewHolder(host, itemView)`，然后在设置好 Adapter 后调用：

```kotlin
// 列表自动播放；Fragment 中 owner 使用 viewLifecycleOwner。
val feedPlayback = recyclerView.bindVideoFeed(this, media, stateKey = "feed")
// 或短视频 ViewPager2：
val shortVideoPlayback = pager.bindVideoFeed(this, media, stateKey = "short-video")
```

两行示例是不同页面的替代方案；导入 `io.github.silascui.sloth.ui.bindVideoFeed`。框架自动注册/注销宿主、处理滚动选播、页面暂停与销毁，并在提供 stateKey 时保存播放状态。Adapter 只负责界面和数据。

动态数据、隐藏 Tab、状态恢复与高级控制见[简化接入说明](docs/ui-integration.md)。

## 播放命令与控制面板

列表和单视频均实现 `PlaybackController`，提供显式播放、暂停、重试、重播、跳转、静音和倍速，以及可订阅的不可变 `PlaybackState`。

```kotlin
playback.bindControls(controls, activity) // 控制面板、全屏、手势与生命周期自动接入
playback.controller.play()
playback.controller.pause()
```

两个示例均接入进度、静音、倍速、错误重试和可退出的全屏控制。完整协议及生命周期边界见[播放控制与全屏说明](docs/playback-controls.md)。

## 核心手动接入

```kotlin
// 在宿主自己的隐私/授权流程之后、主线程初始化一次。
VideoPlaybackRuntime.initialize(applicationContext)
val controller = FeedPlaybackController(initiallyMuted = true, loop = true)
val media = listOf(VideoMedia("episode-1", myVideoUrl))
controller.submitFeed(media)
// ViewHolder只绑定封面、文案和渲染宿主；token使用Holder或其他稳定对象。
controller.registerHost(holder, position, media[position], holder.videoHostView)
// onStart/onStop或可见性变化：
controller.setVisible(true)
controller.setVisible(false)
// onViewDetached/onViewRecycled移除注册；重新附着时重新注册。
controller.unregisterHost(holder)
// 页面销毁：
controller.close()
```

`VideoHostView`是可选基础UI；也可以实现`FeedRenderTarget`，用`PlayerRendering.bind()`绑定自有Media3 PlayerView。列表滚动需把DRAGGING/SETTLING/IDLE传给`setScrolling()`，把`onPageScrolled()`传给`previewPageScroll()`，把`onPageSelected()`传给`select()`。完整用法见[列表示例](sample/src/main/java/io/github/silascui/sloth/sample/FeedListActivity.kt)和[短视频示例](sample/src/main/java/io/github/silascui/sloth/sample/ShortVideoActivity.kt)。

详情页用`SingleVideoController`，短剧列表用`FeedPlaybackController(loop = false)`配合`onEnded`控制下一集，仍使用同一个Runtime。不要每个Holder或页面创建ExoPlayer或另一个缓存实例。

`shutdown()`是应用级清理入口，先关闭所有Controller和浮窗句柄再调用；页面退出只需`close()`，不要关闭其他页面正在使用的Runtime。公共播放控制与宿主回调都在主线程使用。全局配置与事件对象应是Application级对象，不应捕获Activity或View。

## Maven Central

发布配置使用 `io.github.silascui` 命名空间。在宿主项目的 `settings.gradle.kts` 中配置公共仓库（已有则无需重复添加）：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

在应用模块的 `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation("io.github.silascui:sloth-ui:1.0.0") // 自动引入同版本核心
    // 只使用核心时，改用下面这一项：
    // implementation("io.github.silascui:sloth:1.0.0")
}
```

同步 Gradle 后按上面的示例初始化和绑定播放器。使用公开依赖无需配置 Central Token 或 GPG 密钥。版本须在 Central Portal 达到 `PUBLISHED` 状态；Actions 上传成功后仍需手动确认发布。凭据、GPG 签名、GitHub Actions 和上传步骤见[发布指南](docs/publishing.md)。

## 分发

按以下命令生成的本机分发文件位于`dist/`：源码ZIP用于完整工程接入，Maven ZIP包含核心和 UI 接入层的 Release AAR、sources JAR、POM 和 Gradle 模块元数据。它是本地仓库，未上传或发布到任何远程服务。

重新生成本地Maven仓库：

```sh
./gradlew :player:publishReleasePublicationToBundleRepository :player-ui:publishReleasePublicationToBundleRepository
python3 -B tools/package_framework.py
```

产物在`player/build/bundle-repository/`。使用单独AAR时仍需声明匹配的Media3依赖；AAR不是将所有第三方依赖嵌入的fat-AAR。推荐按[接入文档](docs/integration.md)使用本地Maven元数据或源码组合构建。

更多内容：[架构与类](docs/architecture.md)、[宿主适配与迁移](docs/integration.md)、[来源与许可边界](NOTICE.md)。本框架保留精确恢复语义，不承诺所有内容、设备及恢复位置均在100ms内起播。

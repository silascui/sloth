# 列表与短视频的简化接入

`player` 提供独立播放核心；可选的 `player-ui` 提供 RecyclerView / ViewPager2 接入，依赖核心但核心不反向依赖 UI 模块。已有 `FeedPlaybackController`、`SingleVideoController` 和自定义 `FeedRenderTarget` 接入继续有效。

## 初始化一次

先按[接入文档的 Maven Central 配置](integration.md#从-maven-central-接入)添加 `implementation("io.github.silascui:sloth-ui:1.0.0")`，它会自动引入同版本核心。

宿主完成自己的隐私授权流程后，在主线程初始化进程级运行时：

```kotlin
VideoPlaybackRuntime.initialize(applicationContext, PlayerConfiguration())
```

已有运行时不能重复初始化。普通页面退出由 binding 自动关闭会话，不调用全局 `shutdown()`。

## 数据与 ViewHolder

```kotlin
val videos = listOf(VideoMedia("video-1", "asset:///demo1.mp4"))

class VideoHolder(val host: VideoHostView) : VideoViewHolder(host)
// 卡片还有标题等内容时，传入卡片根视图：VideoViewHolder(host, cardRoot)
```

Adapter 只负责创建控件、绑定封面和业务文案。无需在 bind/attach/detach/recycle 中注册播放器，也不需要持有 Player。

## RecyclerView 列表

先配置 LayoutManager 和 Adapter，再绑定：

```kotlin
import io.github.silascui.sloth.ui.bindVideoFeed

recyclerView.layoutManager = LinearLayoutManager(context)
recyclerView.adapter = videoAdapter
val playback = recyclerView.bindVideoFeed(
    owner = this, // Activity；Fragment 中使用 viewLifecycleOwner
    media = videos,
    stateKey = "home-video-feed",
)
```

列表停止滚动后选取可播放、可见视频面积最大的卡片；面积相同时保持当前项，避免反复切换。默认静音和循环，列表模式关闭备用解码预热。开始滚动不立即打断当前视频，当前宿主移出屏幕时解除绑定。

## ViewPager2 短视频

```kotlin
pager.orientation = ViewPager2.ORIENTATION_VERTICAL
pager.offscreenPageLimit = 1
pager.adapter = videoAdapter
val playback = pager.bindVideoFeed(this, videos, stateKey = "short-video-feed")
```

自动处理选中、拖动方向提示、相邻预热和停止滚动后的播放。没有恢复状态时保留宿主绑定前设置的 currentItem；有恢复状态时优先恢复已保存的选中页。默认静音和循环，可通过 `initiallyMuted`、`loop` 参数调整。

## 生命周期、状态和可见性

- owner STARTED 时允许播放，STOPPED 时暂停并归还句柄，DESTROYED 时移除监听并关闭控制器。Fragment 应使用 **viewLifecycleOwner**。
- 传入 `stateKey` 时，owner 必须实现 SavedStateRegistryOwner（ComponentActivity 和 Fragment 的 View 生命周期 owner 均支持）。同一 owner 下每个 binding 的 key 必须唯一；不传 key 则不自动保存。
- 自动恢复选中索引、播放位置、手动暂停、静音、倍速，以及普通列表的滚动位置。媒体列表仍由宿主恢复，媒体 ID 应保持稳定。
- 隐藏 Tab 等页面可能仍处于 STARTED，宿主需调用 `playback.setVisible(false)`；再次显示时传 true。
- 同一 View 只能存在一个 binding。替换整个 Adapter 或提前结束接入时，先调用 `playback.close()`。重复 close 安全；销毁时无需再次手动释放。
- 所有接入和控制操作均在主线程进行。

## 动态数据

媒体列表必须与 Adapter 的**整体位置和数量**一一对应。更新 Adapter 后，提交匹配的媒体数据：

```kotlin
videoAdapter.submitList(newItems) {
    playback.submitFeed(newItems.map { it.videoMedia })
}
```

不要在 Diff 计算完成前提交。数量不一致期间 binding 暂停播放，显式提交不匹配的数据会直接报错。普通非视频 Holder 不参与选播；对应位置仍需有匹配的媒体描述。单独修改业务标题或封面无需重新提交媒体。

## 播放控制

```kotlin
playback.controller.setMuted(false)
playback.controller.setSpeed(1.5f)
playback.controller.seekTo(10_000L)
```

需要自定义渲染目标、浮窗、特殊选播规则或完全自定义生命周期时，直接使用核心控制器。通过 binding 接入时，由 binding 负责关闭控制器。

## 依赖与分发

源码工程依赖 `implementation(project(":player-ui"))` 即可；它会传递核心依赖。只需核心时依赖 `:player`。

Maven Central 和本地仓库均使用 `io.github.silascui:sloth:1.0.0` 和 `io.github.silascui:sloth-ui:1.0.0`。使用 Central 时按[接入文档](integration.md#从-maven-central-接入)配置；维护者发布步骤见[发布指南](publishing.md)。如需本地分发，按 README 同时构建两个模块的本地仓库后运行打包工具，Maven ZIP 包含两个模块及其依赖元数据。

# 统一播放控制与展示

列表的 `FeedPlaybackController` 和单视频的 `SingleVideoController` 均实现 `PlaybackController`。原有接口保留，控件和页面逻辑可仅依赖该协议。

## 命令

```kotlin
val controller: PlaybackController = playback.controller
controller.play()             // 显式播放；仍需通过宿主授权策略
controller.pause()            // 手动暂停，并使尚未完成的播放授权请求失效
controller.seekTo(10_000L)
controller.setSpeed(1.5f)
controller.setMuted(false)
controller.retry()            // 重新准备当前媒体，仍重新检查播放策略
controller.replay()           // 从头播放
controller.togglePlayback()
```

隐藏或滚动中的页面不会因调用命令提前开始播放；明确的播放请求可等待宿主就绪或滚动停止。已获准的手动播放在同一媒体的宿主重新就绪时继续有效；选择其他媒体、隐藏页面或暂停会取消旧的待执行请求和手动授权上下文。`retry` 不负责刷新业务 URL；URL 过期时由宿主提交更新后的媒体。

## 可观察状态

```kotlin
val subscription = controller.observeState { state ->
    // PlaybackStatus、blockReason、positionMs、durationMs、bufferedPositionMs、
    // speed、muted、userPaused、playWhenReady、canPlay/canPause/canSeek/canRetry、error
    render(state)
}
// 对应页面/视图销毁时：
subscription.close()
```

订阅立即收到当前快照；相同快照不重复发送。播放引擎事件驱动状态变化，有可见播放且存在观察者时每 500ms 更新进度。回调在主线程同步执行，可以发起其他控制命令；内部按顺序分发，避免重入后状态倒退。回调不要阻塞或抛异常。

`PlaybackState` 为不持有 View/Player 的不可变快照，区分空态、等待、就绪、缓冲、播放、暂停、结束、错误和关闭。等待原因区分宿主未就绪、媒体不可播放、策略阻止、页面隐藏、被接管和滚动中。错误只包含安全的错误码，不携带原始 URL 或异常文本。

控制器关闭时发送 CLOSED，释放观察者。状态订阅的 close 可重复调用。所有控制器操作和状态读取均在主线程执行。

## 控制面板

```kotlin
val controls = PlayerControlView(context)
container.addView(controls)
playback.bindControls(controls, activity) // 同时接入控制面板与全屏
// 不需要全屏时：playback.bindControls(controls)
```

绑定对象负责原容器选播、Pager 手势和展示窗口的清理。共享面板改绑其他页面前，先调用旧 binding 的 `unbindControls()`；同一面板不能同时归两个 binding 管理。仅使用 SingleVideoController 或自定义控制器时，也可直接 `controls.bind(controller)`。

面板包含播放/暂停、结束后重播、错误重试、静音切换、倍速切换、播放和缓冲进度。拖动进度条在松手后跳转；键盘和无障碍进度操作也可跳转。媒体切换时，不把旧视频的拖动结果应用到新视频。

面板附着时订阅状态，脱离窗口时自动取消订阅；切换控制器或调用 `bind(null)` 会立即解除旧绑定。面板不关闭控制器、不操作底层 Player；绑定对象或宿主仍负责控制器生命周期。

## 自定义全屏展示

需要自己控制展示入口时，直接使用协调器；此方案与上面的自动全屏接入择一使用。

```kotlin
val fullScreen = FullScreenCoordinator(activity, viewLifecycleOwner)
fullScreen.onFullScreenChanged = { active ->
    playback.setSelectionEnabled(!active)
    controls.isFullScreen = active
}
controls.onFullScreenClick = {
    // currentVideoHost 必须是已附着到容器、由当前控制器持有的渲染视图。
    fullScreen.toggle(currentVideoHost, playback.controller)
}
```

默认 VideoViewHolder 为渲染视图提供稳定容器。自定义 itemView 时也需把 VideoHostView 放在容器内；不能直接搬移 RecyclerView/AdapterView 自己管理的条目根视图。

协调器把现有视图移到沉浸式 Dialog，退出时恢复原父容器、位置和布局参数。提供控制器时，全屏窗口内自动增加同一协议的控制面板。它不创建新播放器或新播放租约。通过系统返回键、退出按钮、页面停止或销毁退出全屏。

进入全屏期间应冻结原列表/Pager 的自动选播，退出后恢复；两个示例已接入。一个视图只能归一个全屏协调器所有。手动结束页面接入时，先关闭全屏协调器，再关闭播放 binding。

横竖屏遵循宿主 Activity 的方向配置，不强制修改 requestedOrientation 或 Manifest。Activity 重建会结束当前展示模式，媒体进度由原有保存机制恢复；需要重建后恢复全屏的应用，应在新视图就绪后按自身页面状态重新进入。

本协调器处理应用内全屏，不创建系统悬浮窗。已有 `transferToFloating` 继续作为系统浮窗的底层租约转交入口，窗口和权限仍由宿主负责。

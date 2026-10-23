# 播放器接入层重构

本次将宿主重复的列表选播、ViewHolder 注册回收、滚动监听、页面生命周期和 Bundle 状态序列化收进可选 `player-ui` 模块。核心 `player` 保留原接口和独立依赖边界，自定义渲染与特殊业务仍可使用底层控制器。

公开接入入口为 `RecyclerView.bindVideoFeed`、`ViewPager2.bindVideoFeed` 和 `VideoViewHolder`。绑定对象自动管理开始、停止、销毁和状态恢复；支持隐藏 Tab 显式可见性、动态数据提交、重复绑定检查和幂等关闭。列表与短视频示例均迁移到该接口，进程初始化统一到 SampleApplication。

同时将不可变媒体的 sourceKey 改为构造时计算，避免每次选播与预加载访问时重复生成 URL 摘要。逻辑 ID 与 URL 修订后的身份规则保持原有测试覆盖。

本地分发同时生成核心与 UI 接入层，校验 UI 到核心的依赖和调试测试宿主未进入 Release AAR。

## 验证

- 核心 43 项单元测试、接入层 6 项选播规则单元测试通过。
- 示例 Debug、两个模块的 Release 和新增设备测试 APK 构建通过。
- 接入层 Debug/Release 静态检查均无问题；示例仅保留原有 DataExtractionRules 警告。
- API 35 模拟器完整六项设备复测通过：页面隐藏、列表滚动选播、重复绑定与重新绑定、页面重建恢复、数据清空与重载、生命周期停止和销毁。
- 首轮有一次 ActivityScenario 销毁等待超时，完整复测未复现；保留首轮与复测日志。
- 最终代码的初始页与保存状态优先级两项定向设备验证通过（累计七个不同设备用例，状态恢复在最终代码上复验）。

证据见 [构建日志](verification/framework-build.log)、[最终构建日志](verification/ui-final-build.log)、[设备复测日志](verification/ui-device-tests.log)、[初始页定向日志](verification/ui-selection-tests.log)和[首轮设备日志](verification/ui-device-tests-first-run.log)。

## 实际页面检查的限制

最终示例在 API 35 模拟器成功启动。列表滚动后从第一项切换到第三项显示输出，短视频页面也显示输出。但两种模式均出现明显条纹花屏，不能判定视频画面正确。对原始 demo1.mp4 进行独立解码得到正常图像，故这次重构不能宣称视觉播放验收通过；异常究竟来自模拟器解码/渲染环境还是既有核心渲染路径，尚未定位。

截图见 [列表初始输出](verification/feed-playback.png)、[列表滑动后输出](verification/feed-after-scroll.png)和[短视频输出](verification/short-video-playback.png)。检查过程中还遇到模拟器 System UI 无响应提示，已选择等待；这可能影响设备测试稳定性，未据此断定首轮超时原因。

设备用例覆盖接入协议与状态管理，不代表多设备解码性能、所有媒体格式或视觉适配验收。接入步骤和数据一致性要求见 [UI 接入说明](ui-integration.md)。

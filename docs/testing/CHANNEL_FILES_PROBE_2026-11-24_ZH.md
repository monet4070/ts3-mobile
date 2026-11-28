# 频道文件只读探针：隔离验证

2026-11-24 早期验证记录：当时只有显式打开开关的 Debug 显示入口。现在标准 Debug 和 Release 均提供「频道文件」，无需构建开关。没有文件上传、重命名、删除、自动取附件或外部预览。使用固定 TS3j，未更换或重写客户端握手或语音协议。

## 边界

- 只创建当前连接与本次频道访问有效的读端口。返回同一频道也不恢复旧端口；关闭、断开和取消不保留活跃 TCP 或未完成的暂存文件。
- 列表把频道和目录同时绑定到请求，等到服务端结束通知才交付。没有相关请求编号的列表在超时或结果不明后禁用到重连，不借下一次点击接受旧通知。服务器明确拒绝不是空目录。
- 下载按本连接单调传输 ID 匹配协商；TCP 只连现有 TS3 会话的对端 IP，禁止由通知重定向到另一个地址。明确拒绝大于 20 MiB、少读或多读；只把长度验证成功的临时文件交给界面。上限针对首版，不等同于服务器配额或加密校验。
- 点击文件才暂存；再点「保存已验证文件…」才打开系统选择器。取消选择不导出，写入失败不会显示已保存。应用私有缓存不写入备份或诊断。远端文件、路径、传输键不出现在应用日志。
- 协议数值和通知来自公开 [ReSpeak 声明 `83cb8a9`](https://github.com/ReSpeak/tsdeclarations/blob/83cb8a94b65021bb8ec4beb339e5bfbdd93df60f/Messages.toml#L824)。解析器、回环 TCP、Android 界面与官方服务端互通分别验收。

## 离线回归

```sh
./gradlew :ts3-protocol:test --tests '*Ts3jChannelFilesTest' --tests '*ChannelFileDownloadTest' --tests '*SessionSnapshotStoreTest'
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

`ChannelFilesDialogTest` 的 5 项 API 35 用例覆盖目录导航、取消清理暂存、拒绝和文档提供方写入。
真实的 Android 测试文档提供方收到的 32,769 字节与输入一致；不可写时不显示成功。
在暂存刚完成、还没显示保存按钮时替换界面也会清理暂存。这些用例不经过 TS3 服务器。
日志与 QA APK 在 `build/ts6-borrow/`。

## 官方服务端互通门槛

已从官方下载页核对 TeamSpeak Server **3.13.9** 的 macOS 压缩包 SHA-256：`b7a6f9016d019d239d29e3ba8c4a2f2b2eb93e3d6de94778aa35a30dbc768766`。安装包暂存于 `build/ts6-borrow/official/`。获得许可确认后复跑时遵守以下边界：

1. 使用全新实例，让 UDP 语音和 TCP 文件仅绑定本机 IPv4 回环地址（例如 UDP `19987`、TCP `23033`）；关闭 ServerQuery，不修改用户现有实例或开放局域网端口。TS3 客户端连接用独立临时身份。
2. 使用两个无密码的合成频道：默认频道允许新身份浏览、下载和使用 Opus Voice；另建唯一名为
   `ts3mobile-denied` 的可加入频道，只限制文件列表及下载。配置其 `i_ft_needed_file_browse_power`、
   `i_ft_needed_file_download_power` 高于访客对应权限，不改变发话或加入权限。探针使用临时身份，无需管理权限。
3. 生成合成文件。命令只创建一个新目录，不启动或修改服务端。将 `readable/` 下的目录树放进默认频道文件目录，
   将 `denied/` 中的 `probe.txt` 放进拒绝频道根目录。先核实虚拟服务及两个实际频道 ID；不接触现有频道或文件。
   将该隔离实例的下载带宽限制到让 8 MiB 测试文件约 4～12 秒传完，避免回环传完太快而无法观察重叠。

   ```sh
   python3 tools/prepare-channel-file-fixture.py --out build/ts6-borrow/fixture-run1
   ```

4. 从工作区运行完整客户端探针。不传参数时常规单测跳过；显式运行仅连接 IPv4 回环，不使用 Gradle 测试缓存。

   ```sh
   ./gradlew -Pts3LocalFileProbePort=19987 :ts3-protocol:test --tests '*LocalChannelFilesProbeTest'
   ```

5. 通过条件：根/子/空目录，文件长度及 SHA-256，拒绝不误报为空或成功，退出及离开/返回后旧端口失效。
   下载共存需证明暂存文件还在增长的两个采样之间收到至少三个来自第二个临时客户端的已知 Opus 帧，完成后仍能接收。
   Opus 帧由本机 libopus 1.6.1 编码全零 PCM 并验证 960 采样解码得到；不打开麦克风或采集声音。传输过快、服务器未转发或
   夹具不匹配均失败而非乐观通过。该测试验证 JVM UDP/TCP 共存；Android 实机仍需单独复核保存和实用语音连续性。
   未通过前不开放正式入口、头像、图标或聊天附件自动取件。

### 本机结果

2026-11-24，用户确认后启动一次性官方实例，使用默认固定 TS3j 运行测试，Gradle XML 为 **1 项执行、0 跳过、0 失败**，用例耗时 8.399 秒。验证了根目录、子目录和空目录、权限拒绝、离开／返回与断开后的旧端口失效，以及下载字节数和 SHA-256。8 MiB 文件增长期间与完成后均收到第二个客户端发出的已知 Opus 帧。使用的都是临时身份和合成内容，不打开麦克风。

验收时只监听 IPv4 回环的 UDP `19987` 和 TCP `23033`，ServerQuery 已关闭。官方进程另有非回环 UDP 出站连接，因此本次不是完全离线运行。验收后已正常停止该实例并确认测试端口释放。Gradle 日志和测试摘要留在该次运行目录；不分发含初始化管理凭据的启动日志。

### Android 实机静音验收

2026-11-24，Android 16（API 36）实体设备的定向用例连续两次通过，每次 **1 项，0 跳过，0 失败**。已有应用未被覆盖。用两个临时客户端和零 PCM 编码的 Opus 帧接入官方隔离实例，没有打开麦克风或改变系统音量。`AudioTrack` 播放头实际前进；在文件仍增长的三个相邻采样区间内，接收帧数和输出采样数同时增加，保存后亦继续输出。本次输出路由为 `SPEAKER`，播放统计记录 404,640 个输出采样、0 次重新缓冲、0 次输出失败。

实际 Compose 对话框经 `ContentResolver` 写入测试文档提供方，读回 8,388,608 字节且 SHA-256 与夹具一致。另实测下载中关闭界面会清理暂存、拒绝频道明确报错，以及离开再返回时旧端口不恢复。探针替换系统文件选择结果为测试 URI，不验证系统文件选择器的交互；USB 转发不是普通无线网络，本次也未做可听语音或蓝牙体验评价。Release 入口仍关闭。

#### USB 单设备复跑

使用上述仅回环、关闭 ServerQuery 的一次性服务端，构建启用探针的 Debug 包和对应 Android 测试 APK。已有应用时，为 Debug 单独配置 `applicationIdSuffix` 后再安装；验证测试包和目标包均与已安装的正式应用不同。

```sh
python3 tools/bridge-channel-file-probe.py --bridge-port 23987 --voice-port 19987
adb reverse --no-rebind tcp:23987 tcp:23987
adb reverse --no-rebind tcp:23033 tcp:23033
```

`adb reverse` 只转发 TCP。主机桥在 IPv4 回环接受最多两个测试流，按报文长度封装 UDP；手机侧对应桥只在 Android 测试代码内。文件走独立 TCP 反向端口，服务器通告的端口必须是 `23033`。禁止绑定局域网或公网。

将 `QA_APP` 设为隔离的 Debug 应用包名，先显式启动测试文档提供方，再运行可选真机用例。未传 `ts3UsbFixturePort` 时该用例跳过：

```sh
adb shell content gettype --uri "content://$QA_APP.test.backup-documents/00000000-0000-0000-0000-000000000000"
adb shell am instrument -w -r \
  -e class io.github.ts3mobile.app.ui.LocalChannelFilesAndroidProbeTest \
  -e ts3UsbFixturePort 23987 "$QA_APP.test/androidx.test.runner.AndroidJUnitRunner"
```

如系统拒绝从后台启动 Compose 测试 Activity，可在测试开始后另开终端用 `adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n "$QA_APP/androidx.activity.ComponentActivity"` 启动同一 Activity；无需改手机的后台权限。验收后移除本轮添加的两条反向映射，卸载两个隔离 APK，停止桥和服务端并确认端口释放。

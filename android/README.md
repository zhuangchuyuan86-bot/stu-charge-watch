# 汕大充电 Android 2.0.4

可独立联网的安卓应用，包名 `com.stu.chargewatch`，versionCode 7，最低 Android 8.0 / API 26，目标 API 35。界面、离线校园地图和目录随 APK 打包；WebView 展示本地页面，原生 Java 直接查询充电平台，不依赖电脑、局域网服务或第三方 CORS 代理。建议使用当前系统可用的最新 Android System WebView。

## 当前内容

2.0.4 使用红黄绿表示剩余空位，滑板场一、滑板场二的共同标点保留在用户圈出的田径场下方路口。

目录包含 14 台设备、280 个插座、8 个地点，所有设备名称与分组以用户提供的《学校充电桩设备地址.docx》为准。完整对照见“设备地址对照表.md”，结构化导入来源见 `assets/web/device-locations.json`。仓库只提供查询方式与历史状态参考；旧仓库名字保留为 reference_name。

思源书院按文档从左一至左四依次为 8492、8485、8489、8490。图书馆有 8495、8493、8491，滑板场有 8497、8488，其余为敬一书院 8494、志诚书院 8498、文科教学楼 8484、修远书院 8480、教师宿舍 6723。修远按用户圈图标在敬一书院左上方；教师宿舍在高层教师公寓与淑德书院之间。地图为方位示意。原参考校图署名 Zagniki_、底图来源高德。

详情、地图与通知共用名称，收藏和备注按编号保留；升级清理旧名称缓存并迁移已替换的分组选择。历史快照保持原始时间和状态，不制造实时状态。

支持地图标点、搜索、空位筛选、插座详情、收藏、本地备注、前台自动更新及可选后台空位提醒。前台每 60 秒刷新，Provider 对每台设备至少缓存 60 秒、最多同时查询两台。实时结果超过 120 秒即标记过期并排除当前空闲汇总。历史快照单独标明来源与日期；教师宿舍不预置历史快照。

后台使用 JobScheduler，每 15 分钟安排一次、灵活区间 5 分钟；执行时间受系统调度影响。Android 13 及以上仅在用户开启提醒时申请通知权限。仅已观察到的零空位变为有空位才提醒，通知跳转到对应设备。收藏与后台提醒开关独立；提醒检查目录内的全部设备。

## 源码结构

- `MainActivity.java`：WebView、本地资源拦截、JavaScript 桥、通知权限、系统边距和返回行为。
- `AppBackend.java`：将原生查询与目录转换成页面使用的 JSON，异步刷新及进度。
- `Provider.java`：系统 HTTPS、网页客户端参数读取、设备白名单、并发和缓存。
- `Station.java` / `Settings.java`：目录名称、状态解析、持久化与设置。
- `MonitorJobService.java` / `Notifications.java`：后台检查、状态变化提醒及通知入口。
- `assets/web/`：本地 HTML/CSS/JavaScript，含 `android.js` 桥适配器、`catalog.json`、历史 `snapshot.json` 和矢量 `assets/campus-map.json`。

WebView 使用虚拟 HTTPS 本地源 `https://appassets.androidplatform.net/`，拦截并返回允许的内置资源；文件与内容访问关闭，CSP 限制外部页面资源，外部 HTTPS 链接交给浏览器打开。查询桥仅接受目录内设备和固定查询路由。平台客户端参数动态读取，不在源码中保存实际值。应用只查询状态，不操作登录、订单、支付或启动充电。

## 构建

需要 Python 3、JDK 17、Android SDK Build Tools 35.0.0 和 Android 35 的 `android.jar`。复制 `toolchain.example.json` 为本机配置并调整绝对路径，然后运行：

```powershell
python build.py --toolchain toolchain.json --work build --apk campus-charge-android.apk
```

脚本编译资源并打包 `assets`，编译 Java、运行 D8、对齐并签名 APK，再验证签名与对齐。默认使用 `javac`；配置可增加 `ecj` 字段指定 Eclipse Java Compiler JAR，本次使用 ECJ 3.38.0 / Java 8 语法构建。没有第三方 Android 运行时依赖。

首次构建在工作目录生成 `personal-signing.p12`，别名 `campus-charge`，默认本地签名口令 `android`，可通过 `CHARGE_SIGNING_PASSWORD` 环境变量覆盖。应保管并复用自己的签名文件以便覆盖更新。交付 APK 沿用之前版本的签名，源码包不包含原签名私钥；重新生成私钥的 APK 无法覆盖该已安装版本。

## 验证范围

已使用实际 Station、Settings、Provider、AppBackend Java 源码在 JDK 17、内存 Android 偏好设置桩及 org.json 下完成 14 台设备的真实 HTTPS 查询，均返回 20 个插座；检查状态解析、过期、存储、通知变化条件、缓存、批量与单台刷新、非法设备拒绝、14 台文档名称与归属、缓存保留。

已在桌面 Edge 的手机尺寸模拟中运行 APK 的实际页面资源，使用原生 Java 后端捕获的查询结果回放桥接；验证 320/390/430/768 像素宽度布局、地图、插座详情、搜索、收藏、备注、设置和通知目标事件，无页面脚本错误。手机截图已检查。

最终 APK 已通过清单、DEX、资源一致性、ZIP 完整性、签名 v2/v3 和对齐校验。详细记录随安装包提供于 `android-validation.json`。

尚未进行实体安卓手机或安卓模拟器运行验证，因此 Android WebView 生命周期、安装行为、系统通知投递与后台调度仍需设备验证。平台网页或接口变化可能影响后续连接。

剩余空位颜色：红色为 0；黄色为有空位且占总插座不超过 25%；绿色为超过 25%。地图标点、地点编号、详情和设备卡片统一使用此规则；某地点有未成功更新的设备时，地点显示灰色。20 口设备对应 0 口红、1–5 口黄、6–20 口绿。

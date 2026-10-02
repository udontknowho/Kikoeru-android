# Kikoeru-android(个人修改版)

浏览 Kikoeru 服务器内容的 Android 应用。基于 [Zinhao/Kikoeru-android](https://github.com/Zinhao/Kikoeru-android) 的个人分支,许可证同为 GPL-3.0。

## 本分支改了什么

- **界面重做**:底栏自绘(首页 / 收藏 / 我的),设置页与「我的」页卡片化分组,深色模式,强调色统一为墨绿
- **收藏页**:按 asmr.one `/favourites` 那套分「我的评价 / 想听 / 在听 / 听过 / 重听 / 搁置 / 本地缓存」
- **站点更省事**:内置站点下拉(asmr.one 及备用域名、asmr.homes、asmr.unikon.art、asmr.emoe.top),支持游客与免账号站点,首页点标题即可切站点
- **回到前台需要验证**:全屏遮罩 + 系统指纹/锁屏密码,设置里可开关
- **只让本应用走本地代理**:API / 封面图 / 下载 / 音频流四条出口全部走它,地址可改(默认 `127.0.0.1:7890`),不动系统代理
- **桌面字幕可调**:字号 8~120sp、颜色、透明度、背景
- **闪退日志落盘**:写到 `/sdcard/Android/media/com.zinhao.kikoeru/crash/`
- 去掉未使用的 Firebase / Crashlytics 和上游硬编码的内网代理,并修了一批上游小毛病(排序方向、歌词滚动、详情页声优压住标签等)

## 下载

发在 [Releases](https://github.com/udontknowho/Kikoeru-android/releases),只有 **debug 签名**的包。

仓库里带固定的 debug keystore,同签名的版本可以直接覆盖安装;与上游 release 包签名不同,互换要先卸载。

## 构建

GitHub Actions 手动触发 `Package Kikoeru Debug Apk`,执行 `./gradlew assembleDebug` 并把 APK 作为 artifact 上传。需要 JDK 17。

## 服务器版本

按 Kikoeru-project **V0.6.2** 的 API 适配。

## 许可证

GPL-3.0,与上游一致。衍生的作品同样需要以 GPL-3.0 开源。

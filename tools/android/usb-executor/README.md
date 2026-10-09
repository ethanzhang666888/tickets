# USB执行组件构建输出

此目录由 `npm run build:usb-executor` 生成 `app-usb-executor.apk` 和 `manifest.json`，供桌面应用打包。

执行组件仅由已授权USB调试启动，不注册无障碍服务，不声明网络或前台服务权限，不提供提交或付款动作。
生成的APK不手动修改、不从外部下载；源代码位于 `android-companion/app/src/usbExecution`。

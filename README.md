# FPS Display

一个 Minecraft 26.3 (Fabric) 客户端模组：在屏幕**左侧中间**显示 **FPS** 和 **TPS**。

## 功能

- 单机 / 局域网：TPS 为集成服务器的真实 TPS
- 加入专用服务器：TPS 显示为带 `~` 前缀的本地 tick 率估算值
- 按 **F9** 切换显示 / 隐藏（可在 选项→按键设置 中修改）
- 半透明深色背景框；按 F1 隐藏 GUI 时显示框一并隐藏
- 数值经指数滑动平均（EMA）平滑，不会剧烈跳动

### 颜色规则

FPS 数字按档位着色：

| FPS | 颜色 | 含义 |
|---|---|---|
| ≥ 90 | 蓝 | 性能卓越 |
| 60 – 89 | 绿 | 流畅运行 |
| 30 – 59 | 黄 | 基本可玩 |
| < 30 | 红 | 需要优化 |

TPS 数字按档位着色：

| TPS | 颜色 |
|---|---|
| ≤ 15 | 红 |
| > 15 | 绿 |

## 构建

要求：**JDK 25**（26.3 目标版本为 Java 25）。示例使用官方启动器下载的运行时（内含完整 JDK）：

```bat
set JAVA_HOME=C:\Users\fhz11\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon
gradlew.bat build
```

产物：`build/libs/fpsmod-1.0.0.jar`，放入实例的 `mods` 文件夹：

`D:\minecraft\.minecraft\versions\26.3-Fabric 0.19.5\mods`

## 项目结构

```
src/client/java/com/fpsmod/FpsModClient.java   模组全部逻辑（单一类，无 mixin）
src/main/resources/assets/fpsmod/lang/         按键名称本地化（中/英）
src/main/resources/fabric.mod.json             模组元数据（客户端入口）
```

## 许可证

[MIT](LICENSE) — 可自由使用、修改、分发，保留版权声明即可。

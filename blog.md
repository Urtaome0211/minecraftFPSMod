# 从零开发 Minecraft 26.3 Fabric 模组：游戏内 FPS/TPS 显示（附 26.3 API 踩坑实录）

> 本文记录一个「游戏内 FPS/TPS 显示」客户端模组从零到上线的完整过程。目标版本：Minecraft 26.3 + Fabric Loader 0.19.5。26.3 是一次 API 大改版，网上大部分教程已过时，本文的所有代码都在该版本下实测编译、实机运行通过。

## 一、需求

朋友的需求很简单，但每一项都影响实现：

- 游戏画面内**左侧垂直居中**显示 `FPS` 和 `TPS`；
- 按 **F9** 切换显示/隐藏；
- 半透明深色背景框，F1 隐藏 GUI 时一起隐藏；
- **TPS 要分场景**：单机/局域网显示真实服务器 TPS；进入公共服务器时客户端拿不到服务器 TPS，就显示本地 tick 率并加 `~` 前缀表示"估算值"；
- 数值按档位着色：FPS 蓝（≥90）/绿（60–89）/黄（30–59）/红（<30）；TPS 红（≤15）/绿（>15）。

## 二、2026 年的版本格局：先搞清楚环境

- **MC 26.3 "Wilderness Bound"**（2026-09-15 发布），要求 **Java 25**（`version.json` 里 `javaVersion.majorVersion = 25`），低于 25 的 JDK 连构建都不行；
- **Fabric Loader 0.19.5**（最新稳定版）、**Fabric API 0.162.0+26.3**；
- 构建工具：**Loom 1.18 + Gradle 9.7**；
- 一个重大变化：**Mojang 自 26.1 起发布了无混淆的 Minecraft**——类名、字段名、参数名都是真实的。Fabric 因此宣布 Yarn 映射退役（2016–2025，向它致敬）。这意味着 26.x 的 `build.gradle` 里**不再有 `mappings` 那一行**，写模组时看到的就是 Minecraft 真实源码结构，对新手反而更友好。但代价是：老教程里所有 Yarn 类名全部作废。

我本机只有 Java 22（系统 PATH 上的 Oracle JDK），构建会直接失败。解决办法很简单：官方启动器为 26.3 下载的运行时 `java-runtime-epsilon` 就是完整的 JDK 25（带 `javac`），把它设为 `JAVA_HOME` 即可，无需额外安装。

## 三、项目脚手架

以 [fabric-example-mod](https://github.com/FabricMC/fabric-example-mod) 的 `26.3` 分支为模板。关键配置：

```properties
# gradle.properties
minecraft_version=26.3
loader_version=0.19.5
loom_version=1.18-SNAPSHOT
fabric_api_version=0.162.0+26.3
```

```groovy
// build.gradle 要点（注意：没有 mappings 行！）
loom {
    splitEnvironmentSourceSets()   // 客户端代码放 src/client/java
    mods {
        "fpsmod" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc:fabric-api:${project.fabric_api_version}"
}
tasks.withType(JavaCompile).configureEach { it.options.release = 25 }
```

`fabric.mod.json` 声明为纯客户端模组，只注册 client 入口：

```json
{
  "id": "fpsmod",
  "environment": "client",
  "entrypoints": { "client": ["com.fpsmod.FpsModClient"] },
  "depends": {
    "fabricloader": ">=0.19.5",
    "minecraft": "~26.3",
    "java": ">=25",
    "fabric-api": "*"
  }
}
```

## 四、核心实现

### 1. FPS 测量：在渲染回调里算帧间隔

每个渲染帧记录 `System.nanoTime()`，帧间隔的倒数就是瞬时 FPS，再用指数滑动平均（EMA，α=0.05）平滑，避免数字狂跳：

```java
long now = System.nanoTime();
if (lastFrameNanos != 0) {
    double dt = (now - lastFrameNanos) / 1_000_000_000.0;
    if (dt > 0 && dt < 1.0) {            // 过滤切屏/挂起造成的假帧
        double f = 1.0 / dt;
        fps = fps == 0 ? f : fps * 0.95 + f * 0.05;
    }
}
lastFrameNanos = now;
```

### 2. TPS 测量：两个事件、两种含义

- `ClientTickEvents.END_CLIENT_TICK`：客户端自己的 tick 循环（约 20 tick/s），永远可用；
- `ServerTickEvents.END_SERVER_TICK`：只在**集成服务器**（单机/局域网）运行时触发，测的是真实服务器 TPS。

用 `ServerLifecycleEvents.SERVER_STARTED / SERVER_STOPPED` 维护一个 `serverTpsValid` 标志：联机时它是 `false`，显示就走"本地 tick 率 + `~` 前缀"的兜底逻辑。两处都做 EMA（α=0.1）平滑，且同样过滤 `dt > 1.0` 的异常间隔。

### 3. 渲染：26.3 的全新 HUD API

老教程里的 `HudRenderCallback` 在 26.3 已被移除，取而代之的是 HUD 元素注册系统：

```java
HudElementRegistry.addLast(
    Identifier.fromNamespaceAndPath("fpsmod", "fps_display"),
    FpsModClient::onHudRender);

private static void onHudRender(GuiGraphicsExtractor g, DeltaTracker dt) {
    Minecraft client = Minecraft.getInstance();
    if (!visible || client.gui.hud.isHidden() || client.font == null) return;
    ...
}
```

`extractRenderState` 是函数式接口的唯一方法，注册后由原版 HUD 渲染管线自动调用；`client.gui.hud.isHidden()` 替代了已删除的 `options.hideGui`（F1 生效）。

数值着色采用**分段绘制**：`"FPS: "` 白色、FPS 数字按档位着色、`"   TPS: "` 白色、TPS 数字红/绿。每段用 `font.width(...)` 累加偏移，背景框宽度随之自适应：

```java
int fpsColor = fps >= 90 ? 0xFF5555FF
             : fps >= 60 ? 0xFF55FF55
             : fps >= 30 ? 0xFFFFFF55
             :             0xFFFF5555;
int tpsColor = tps <= 15.0 ? 0xFFFF5555 : 0xFF55FF55;

g.fill(x - 2, y - 2, x + width + 2, y + height + 2, 0x80000000);
g.text(client.font, labelFps, x, y, 0xFFFFFFFF);
x += client.font.width(labelFps);
g.text(client.font, fpsStr, x, y, fpsColor);
// ... TPS 两段同理
```

位置用 `y = (client.getWindow().getGuiScaledHeight() - font.lineHeight) / 2` 实现左侧垂直居中。

## 五、26.3 API 对照表（老教程 → 26.3 实际）

这是本次开发中最耗时的部分——按 1.21 时代的教程写完第一版，编译直接报 8 个错。逐项排查后总结如下：

| 老教程写法 | 26.3 实际 |
|---|---|
| Yarn 映射 / `mappings` 行 | 无混淆，直接用 Mojang 名，无需映射 |
| `HudRenderCallback` | `HudElementRegistry.addLast(...)` + `HudElement` |
| `GuiGraphics.drawString(...)` | `GuiGraphicsExtractor.text(...)` / `.fill(...)` |
| `KeyBindingHelper`（keybinding.v1） | `KeyMappingHelper`（keymapping.v1） |
| `InputConstants.Type.KEYSYM` | `InputConstants.Type.KEYBOARD`（枚举只剩 KEYBOARD/MOUSE） |
| `Options.hideGui` | `client.gui.hud.isHidden()` |
| `ResourceLocation` | `Identifier`（`net.minecraft.resources`） |

`ClientTickEvents` / `ServerTickEvents` / `ServerLifecycleEvents` 三个事件则保持原样，可以直接用。

## 六、这次最"阴险"的一个 Bug：黑框在，文字没了

第一次实机测试：背景黑框完美显示，**文字却凭空消失**。逻辑没报错、日志没异常，怎么看都不像代码问题。最后用 JDK 自带的 `javap -c` 反编译 `GuiGraphicsExtractor` 找到了真相：

```
public void text(Font, FormattedCharSequence, int, int, int, boolean);
    Code:
       0: iload 5
       2: invokestatic ARGB.alpha:(I)I
       5: ifne 9
       8: return        // ← 颜色透明度为 0 时，直接静默丢弃！
```

26.3 的渲染管线全面改用 **ARGB** 颜色。我沿袭老习惯传了 `0xFFFFFF`（RGB 白），被解析成"透明度字节 = 0x00"——完全透明，于是被这个方法**静默跳过**。而背景框用的 `0x80000000` 恰好自带透明度 0x80，所以只有它显示。修复只需要加两个 F：`0xFFFFFFFF`。

教训：换新版本 API 后，颜色格式这类"约定俗成"的细节也可能悄然改变，反编译一眼顶十查。

## 七、构建与安装

```powershell
$env:JAVA_HOME = '<JDK25 路径>'   # 例如官方启动器的 java-runtime-epsilon
.\gradlew.bat build
```

产物 `build/libs/fpsmod-1.0.0.jar` 直接丢进实例的 mods 文件夹即可：

```
.minecraft\versions\26.3-Fabric 0.19.5\mods\fpsmod-1.0.0.jar
```

## 八、结语

整个模组只有一个类、128 行代码、零 mixin，最终效果：左侧居中的半透明框里，`FPS` 数字随帧率在蓝绿黄红间变化，`TPS` 在单机显示真实值、联机显示带 `~` 的估算值，F9 一键开关。

可以继续扩展的方向：配置文件（位置/颜色/开关键自定义）、显示内存与网络延迟、接入 paintable 渲染层、以及把 `~` 估算值换成服务器广播的真实 TPS（需要服务端配合）。如果你也在做 26.3 的模组，希望这份"新旧 API 对照 + 反编译排坑"的记录能帮你少走弯路。

*文中代码均为作者实测通过版本，Minecraft 26.3 / Fabric Loader 0.19.5 / Fabric API 0.162.0+26.3，2026 年 10 月。*

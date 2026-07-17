# LanFireWall

Minecraft 基岩版局域网服务器广告防火墙。通过 WinDivert 内核级数据包拦截，精准过滤局域网游戏列表中泛滥的服务器广告。

## 工作原理

Minecraft 基岩版的局域网发现机制依赖 UDP 广播（默认端口 4445），无良服务器利用这一点持续向局域网广播 MOTD 和 AD 信息，导致玩家本地游戏列表被广告淹没。

LanFireWall 在内核层截获所有发往 UDP 4445 的数据包，基于滑动窗口算法统计每台服务器的发包速率。超过阈值的数据包将被直接丢弃，不会到达游戏客户端。

```
广播包 → WinDivert 内核捕获 → 速率检测 → 放行/丢弃 → 游戏客户端
```

## 速率策略

采用双维度双窗口的层级过滤，每一层都能独立触发拦截：

| 维度 | 窗口 | 阈值 | 含义 |
|------|------|------|------|
| 单服务器 (S) | 60 秒 | 30 包/分钟 | 单台服务器的包速率上限 |
| 单服务器 (S) | 1.5 秒 | 8 包 | 短时突发检测 |
| 单 IP (IP) | 60 秒 | 80 包/分钟 | 同一 IP 下所有广播上限 |
| 单 IP (IP) | 1.5 秒 | 20 包 | IP 级短时突发 |

任一阈值超过即为拦截。这个设计覆盖了单服务器持续骚扰和单 IP 多开服务器批量轰炸两种典型场景。

## 快速开始

### 环境要求

- Windows 10+ 或 Linux（macOS 理论兼容，未测试）
- JRE 17+
- **管理员/root 权限**（WinDivert 需要）

### 构建

项目使用 IntelliJ IDEA 管理，依赖库均在 `lib/` 目录下。

1. 用 IntelliJ IDEA 打开项目
2. 将 `lib/` 下全部 jar 添加为模块依赖
3. Build → Build Project

或者直接用 kotlinc 命令行编译（需自己处理 classpath）。

### 运行

程序启动后会自动请求管理员权限（Windows 下弹出 UAC，Linux 下通过 sudo 重开进程），无需手动以管理员身份运行。

```powershell
java -cp "lib/*;out/production/LanFireWall" MainKt
```

### 退出

`Ctrl + C` 优雅退出，资源会被正确释放。

## 终端输出

运行时会实时显示经过的每个广播包，包括：

```
src: 192.168.1.100:4445
server: 192.168.1.100:例：MineCraft's Server
Motd: §a欢迎光临§r §c我们的§l服务器
dst: 224.0.0.1:4445
S-PPM: 12
IP-PPM: 45
S-PP1.5: 3
IP-PP1.5: 8
```

Minecraft 样式码（`§` 前缀的颜色和格式）会被解析为 ANSI 真彩色终端输出。PPM 数值以渐变色显示：绿色表示健康，接近阈值时转为橙黄，超限时显示为加粗红色。

## 技术栈

| 组件 | 用途 |
|------|------|
| [WinDivert](https://www.reqrypt.org/windivert.html) / [jdivert](https://github.com/ffalcinelli/jdivert) | 内核级网络包捕获和过滤 |
| [JNA](https://github.com/java-native-access/jna) | Windows API 调用（权限检测与 UAC 提权） |
| [Jansi](https://github.com/fusesource/jansi) | ANSI 真彩色终端输出 |
| [juniversalchardet](https://github.com/albfernandez/juniversalchardet) | 编码检测（非 UTF-8 广播包解码） |

## 项目结构

```
src/
├── Main.kt          # 主循环：包捕获、解码、速率判定、转发/丢弃
├── PPTCounter.kt    # 滑动窗口计数器（Packets Per Time）
├── Tools.kt         # 颜色渐变、Minecraft 样式码 ANSI 解析
└── Elevate.kt       # 管理员权限检测与提权（Windows UAC / Unix sudo）
lib/                 # 第三方 jar 依赖
```

## 注意事项

- 过滤规则目前硬编码为 `udp.DstPort == 4445`，仅处理基岩版局域网广播端口
- 滑动窗口最大记录 60 秒内的历史，内存占用极小且自动清理过期记录
- 提权后原进程退出，子进程以 `--elevated-token` 标记防止递归提权

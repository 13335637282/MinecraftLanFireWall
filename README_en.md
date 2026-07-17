# LAN FireWall

A kernel-level firewall for Minecraft Java Edition LAN server advertisements. Uses WinDivert to intercept and filter spam broadcasts that flood the in-game LAN server list.

## How It Works

Minecraft Bedrock Edition uses UDP broadcasts (default port 4445) for LAN server discovery. Malicious servers exploit this by continuously broadcasting MOTD and AD data to the local network, drowning legitimate LAN games in ads.

LanFireWall intercepts all packets headed to UDP 4445 at the kernel level, tracks per-server packet rates with a sliding window algorithm, and drops any packet that exceeds the configured thresholds. Filtered packets never reach the game client.

```
Broadcast → WinDivert Kernel Capture → Rate Detection → Forward / Drop → Game Client
```

## Rate Policy

Two-dimensional, two-window hierarchical filtering. Any layer can trigger a block independently:

| Dimension | Window | Threshold | Meaning |
|-----------|--------|-----------|---------|
| Per Server (S) | 60 s | 30 packets/min | Max rate for a single server |
| Per Server (S) | 1.5 s | 8 packets | Short burst detection |
| Per IP | 60 s | 80 packets/min | Max rate across all broadcasts from one IP |
| Per IP | 1.5 s | 20 packets | IP-level short burst detection |

A packet is dropped if any threshold is exceeded. This covers both persistent single-server spam and the multi-server flood pattern where one IP advertises many servers at once.

## Quick Start

### Prerequisites

- Windows 10+ or Linux (macOS theoretically compatible, untested)
- JRE 17+
- **Administrator / root privileges** (required by WinDivert)

### Build

The project is managed with IntelliJ IDEA. All dependencies are included under `lib/`.

1. Open the project in IntelliJ IDEA
2. Add all jars in `lib/` as module dependencies
3. Build → Build Project

Alternatively, compile with kotlinc directly (handle classpath manually).

### Run

The program automatically requests elevation on startup (UAC prompt on Windows, `sudo` on Linux). No need to launch it as administrator manually.

```powershell
java -cp "lib/*;out/production/LanFireWall" MainKt
```

### Exit

`Ctrl + C` performs a graceful shutdown. All resources are released properly.

## Terminal Output

Each broadcast packet passing through is displayed in real time:

```
src: 192.168.1.100:4445
server: 192.168.1.100:Example Server
Motd: §aWelcome!§r §cOur§l Server
dst: 224.0.0.1:4445
S-PPM: 12
IP-PPM: 45
S-PP1.5: 3
IP-PP1.5: 8
```

Minecraft style codes (`§` prefixed color and format tokens) are parsed into ANSI true-color terminal output. PPM values use a color gradient: green when healthy, shifting to orange near the limit, and bold red once a threshold is breached.

## Tech Stack

| Component | Purpose |
|-----------|---------|
| [WinDivert](https://www.reqrypt.org/windivert.html) / [jdivert](https://github.com/ffalcinelli/jdivert) | Kernel-level packet capture and filtering |
| [JNA](https://github.com/java-native-access/jna) | Windows API calls (privilege detection, UAC elevation) |
| [Jansi](https://github.com/fusesource/jansi) | ANSI true-color terminal output |
| [juniversalchardet](https://github.com/albfernandez/juniversalchardet) | Encoding detection for non-UTF-8 broadcasts |

## Project Structure

```
src/
├── Main.kt          # Main loop: capture, decode, rate check, forward/drop
├── PPTCounter.kt    # Sliding window counter (Packets Per Time)
├── Tools.kt         # Color gradients, Minecraft style code to ANSI conversion
└── Elevate.kt       # Privilege detection and elevation (Windows UAC / Unix sudo)
lib/                 # Third-party jar dependencies
```

## Notes

- The filter rule is currently hardcoded as `udp.DstPort == 4445`, targeting only Bedrock LAN broadcasts
- The sliding window retains at most 60 seconds of history; memory footprint is minimal and expired entries are cleaned automatically
- On elevation, the original process exits and the child process carries a `--elevated-token` flag to prevent infinite recursion

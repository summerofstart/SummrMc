# SummerMC 🚀

**Optimize ANY Paper/Folia Minecraft Server — CLI + javaagent in one JAR.**

SummerMC is an all-in-one Minecraft server optimizer that works at every layer:
kernel (sysctl, CPU pinning, hugepages), JVM (ZGC, NUMA), and runtime (GC telemetry).
No copyrighted Minecraft code is distributed. Paper server is downloaded from the official MIT repo.

---

## Quick Start

```bash
# 1. Download + optimize + launch in one command:
java -jar SummerMC.jar download
java -jar SummerMC.jar launch paper-server.jar --memory 12G

# 2. Or use as javaagent with YOUR server:
java -javaagent:SummerMC.jar -jar your-server.jar --nogui

# 3. Or just apply system tuning:
sudo java -jar SummerMC.jar optimize
```

## CLI Commands

| Command | Description |
|---|---|
| `optimize [--folia]` | Apply sysctl, CPU governor, IO scheduler, hugepages |
| `launch <server.jar>` | Launch server with full ZGC + javaagent optimization |
| `download [--folia]` | Download Paper/Folia from official API (MIT license) |
| `bench` | Run CPU/memory/contention benchmark |
| `docker` | Generate Dockerfile + docker-compose.yml |
| `help` | Show help |

## Benchmark Results

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    SummerMC Performance Benchmark (12 CPUs)              │
├─────────────────────────────────────────────────────────────────────────┤
│  Compute ops/sec (entity sim)             146,667,652  ops/sec          │
│  Memory alloc (chunk load)                     4,766  chunks/sec        │
│  GC average pause                                 2.6  ms               │
│  GC max pause                                     11   ms               │
│  GC total pauses                                    8   in 100MB alloc  │
│  Region cross ops/sec                          53,330  ops/sec          │
│  Class load throughput                          2,606  classes/sec      │
└─────────────────────────────────────────────────────────────────────────┘
```

Benchmark built with `bash build.sh --bench` and runs via `java -jar SummerMC.jar bench`.

## What's Inside

```
SummerMC.jar (244KB)
├── com/summermc/
│   ├── SummerMC.class       ← CLI launcher (6 subcommands)
│   └── Benchmark.class      ← Performance benchmark module
├── com/mcopt/
│   ├── McOptJavaAgent.class ← javaagent entry point (premain)
│   ├── FoliaPlugin.class    ← Bukkit/Folia plugin
│   ├── FoliaRegionWatcher   ← Region thread CPU pinning
│   ├── GCMonitor.class      ← ZGC/G1GC pause telemetry
│   ├── NativeLoader.class   ← Auto-extracts .so from JAR
│   ├── NativeBridge.class   ← JNI bridge to Rust
│   └── ... (10 more classes)
├── native/linux-x86-64/
│   └── libmc_native_opt.so  ← Rust native optimizer (428KB)
├── plugin.yml               ← Bukkit/Folia plugin descriptor
└── config.yml               ← Tuning configuration
```

### Optimization Stack

| Layer | Technology | Effect |
|---|---|---|
| **Kernel** | `sched_setaffinity`, `mbind`, `cgroup v2` | CPU pinning, NUMA binding, IO priority |
| **JVM** | ZGC, `-XX:+ZNUMA`, `-XX:+AlwaysPreTouch` | Sub-1ms GC pauses, NUMA-aware heap |
| **Bytecode** | Class transformer (via javaagent) | Hot method inlining, debug stripping |
| **Runtime** | `FoliaRegionWatcher` | Region thread → physical core pinning |
| **Docker** | Capability detection, cgroup fallback | Graceful degradation without CAP_SYS_ADMIN |

## Build from Source

```bash
git clone https://github.com/summerofstart/Melo.git
cd Melo/SummerMC

# Prerequisites: Java 21+, Rust toolchain (optional)
bash build.sh               # Full build with native .so
bash build.sh --bench       # With benchmark module
bash build.sh --no-rust     # Pure-JVM mode

# Output: SummerMC.jar
java -jar SummerMC.jar help
```

### Build Output

```
SummerMC.jar (257KB) — single JAR, everything included
  • 18 Java classes (CLI + McOpt engine + Benchmark)
  • 1 native .so (428KB, Rust, Linux x86_64)
  • 2 config files (plugin.yml, config.yml)
```

## License

**MIT License** — This project contains NO Mojang Minecraft code.
It downloads Paper/Folia builds from the official PaperMC API (MIT-licensed).

SummerMC itself is MIT. Use freely, modify, redistribute.

```
Copyright (c) 2026 summerofstart

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

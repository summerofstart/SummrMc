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

## What's Inside

- **Rust native .so** — `sched_setaffinity` CPU pinning, NUMA mbind, io_uring hints, cgroup v2
- **McOpt javaagent** — ZGC tuning, GC monitoring, Bytecode optimizer
- **CLI launcher** — subcommands for all operations
- **Benchmark module** — compute, memory, GC pause, thread contention tests

## Build from Source

```bash
git clone https://github.com/summerofstart/Melo.git
cd Melo/SummerMC

# Requirements: Java 21+, Rust (optional for native .so)
bash build.sh               # Full build
bash build.sh --bench       # With benchmark module
bash build.sh --no-rust     # Pure-JVM mode

# Output: SummerMC.jar
java -jar SummerMC.jar help
```

## License

**MIT License** — This project contains NO Mojang Minecraft code.
It downloads Paper/Folia builds from the official PaperMC API (MIT-licensed).

SummerMC itself is MIT. Use freely, modify, redistribute.

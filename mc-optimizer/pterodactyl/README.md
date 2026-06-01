# McOpt Unified for Pterodactyl

## Panel setup

1. Import `egg-mcopt-unified.json` into Pterodactyl.
2. Create a server with a Java 21 image.
3. Create the server and start it.

The install script downloads the release `server.jar`. On first start, the launcher downloads the real Paper server jar as `server.paperclip.jar` when it is missing.

## Startup

```sh
java -jar {{SERVER_JARFILE}}
```

Default variables:

- `SERVER_JARFILE=server.jar`
- `REAL_SERVER_JAR=server.paperclip.jar`
- `PAPER_PROJECT=paper`
- `PAPER_VERSION=1.21.8`
- `PAPER_BUILD=latest`

The launcher automatically uses `SERVER_MEMORY` from Pterodactyl for heap sizing. Override with `MC_HEAP_MB` if needed.

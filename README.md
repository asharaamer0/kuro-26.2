# Kuro

Fabric utility mod for Minecraft 26.2.

Requires Java 25, Fabric Loader 0.20.1 or newer, and Fabric API 0.161.0+26.2 or newer for Minecraft 26.2.
The ImGui title menu uses OpenGL. On the experimental Vulkan backend, Kuro keeps the vanilla title screen; Vulkan has not been smoke-tested.

## Building

### Prerequisites
- JDK 25

Set `JAVA_HOME` to your JDK 25 installation before running the build. Older Java installations on `PATH` cannot run this build.

### Build with script

Linux / macOS:
```bash
./build.sh
```

Windows:
```cmd
build.bat
```

The build scripts try an offline build first and fall back to downloading dependencies. Minecraft 26.2 is unobfuscated and does not use a mappings dependency.

### Build with Gradle

```bash
./gradlew assemble
```

### Outputs

Build artifacts are located in `build/libs/`:
- `kuro-<version>.jar` (mod jar)
- `kuro-<version>-sources.jar` (sources jar)
- `kuro-<version>-src.zip` (source zip archive)

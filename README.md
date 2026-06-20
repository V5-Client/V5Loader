# V5 Developer README

General users should use the public docs:
https://rdbt.top/docs/getting-started

The rest of this README is for developers and contributors.

## License Summary

This project is licensed under **GNU GPL v3.0**. In short:

1. Anyone can copy, modify, and distribute this software.
2. Every distribution must include the license text and existing copyright notices.
3. You can use this software privately.
4. If you distribute modified versions, you must provide the complete source code under GPL-3.0.

- This means that any forks/copies/clones must have the source code freely available.

## Repositories

V5 is split across two repositories:

- **Fabric mod (V5Loader):** https://github.com/V5-Client/V5Loader  
  Contains the technical client internals (rendering, pathfinding, ChatTriggers JavaScript engine).
- **JavaScript module (V5):** https://github.com/V5-Client/V5  
  Contains macros/scripts used by the client.

## Working on the Fabric Mod ([V5Loader](https://github.com/V5-Client/V5Loader))

Run these commands from the `V5Loader` repository root.

### Build outputs

- `NativeSrc/build/V5PathJNI.so` (Linux x86_64)
- `NativeSrc/build/V5PathJNI.dylib` (macOS — build per-arch; see below)
- `NativeSrc/build/Release/V5PathJNI.dll` or `NativeSrc/build/V5PathJNI.dll` (Windows x86_64)

### Bundle output into V5Loader

Copy the built library into the matching per-arch folder:

- `src/main/resources/assets/v5/natives/linux/x86_64/V5PathJNI.so`
- `src/main/resources/assets/v5/natives/macos/arm64/V5PathJNI.dylib`
- `src/main/resources/assets/v5/natives/macos/x86_64/V5PathJNI.dylib`
- `src/main/resources/assets/v5/natives/windows/x86_64/V5PathJNI.dll`

For production release commits, CI builds all platforms in parallel and commits them together.

### Quick dev commands

Each command compiles required native c++ code, then builds the final Kotlin mod. The output can be found at `build/libs/V5-Loader.jar`

- **Linux:**

```bash
cmake -S NativeSrc -B NativeSrc/build -DCMAKE_BUILD_TYPE=Release && cmake --build NativeSrc/build --config Release -j && mkdir -p ./src/main/resources/assets/v5/natives/linux/x86_64 && cp ./NativeSrc/build/V5PathJNI.so ./src/main/resources/assets/v5/natives/linux/x86_64/V5PathJNI.so && ./gradlew build
```

- **macOS (Apple Silicon):**

```bash
cmake -S NativeSrc -B NativeSrc/build-arm64 -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_ARCHITECTURES=arm64 && cmake --build NativeSrc/build-arm64 --config Release -j && mkdir -p ./src/main/resources/assets/v5/natives/macos/arm64 && cp ./NativeSrc/build-arm64/V5PathJNI.dylib ./src/main/resources/assets/v5/natives/macos/arm64/V5PathJNI.dylib && ./gradlew build
```

- **macOS (Intel):**

```bash
cmake -S NativeSrc -B NativeSrc/build-x86_64 -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_ARCHITECTURES=x86_64 && cmake --build NativeSrc/build-x86_64 --config Release -j && mkdir -p ./src/main/resources/assets/v5/natives/macos/x86_64 && cp ./NativeSrc/build-x86_64/V5PathJNI.dylib ./src/main/resources/assets/v5/natives/macos/x86_64/V5PathJNI.dylib && ./gradlew build
```

- **Windows (PowerShell):**

```powershell
cmake -S NativeSrc -B NativeSrc/build -DCMAKE_BUILD_TYPE=Release; cmake --build NativeSrc/build --config Release --parallel; New-Item -ItemType Directory -Force -Path .\src\main\resources\assets\v5\natives\windows\x86_64 | Out-Null; if (Test-Path .\NativeSrc\build\Release\V5PathJNI.dll) { Copy-Item .\NativeSrc\build\Release\V5PathJNI.dll .\src\main\resources\assets\v5\natives\windows\x86_64\V5PathJNI.dll -Force } else { Copy-Item .\NativeSrc\build\V5PathJNI.dll .\src\main\resources\assets\v5\natives\windows\x86_64\V5PathJNI.dll -Force }; .\gradlew build
```

### Install built mod

After building, copy `build/libs/V5-Loader.jar` into `.minecraft/mods/` together with [V5ModLoader.jar](https://rdbt.top/docs/getting-started). V5ModLoader handles authentication and passes the JWT to the loader.

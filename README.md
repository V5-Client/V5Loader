# V5Loader

This project is CTJS 1.21.10 modified for V5 loading (SecureLoader), V5 utils (mixins, renderutils, etc), and Swift, a native pathfinder.

# Native Pathfinder JNI
The loader looks for platform-tagged libraries first, then falls back to the legacy generic `V5PathJNI.*` names.

Linux:
```bash
cmake -S NativeSrc -B NativeSrc/build -DCMAKE_BUILD_TYPE=Release -DV5PATHJNI_OUTPUT_NAME=V5PathJNI-linux-x86_64
cmake --build NativeSrc/build --config Release -j
cp NativeSrc/build/V5PathJNI-linux-x86_64.so src/main/resources/assets/v5/V5PathJNI-linux-x86_64.so
cp NativeSrc/build/V5PathJNI-linux-x86_64.so src/main/resources/assets/v5/V5PathJNI.so
```

macOS:
```bash
cmake -S NativeSrc -B NativeSrc/build -DCMAKE_BUILD_TYPE=Release "-DCMAKE_OSX_ARCHITECTURES=arm64;x86_64" -DCMAKE_OSX_DEPLOYMENT_TARGET=11.0 -DV5PATHJNI_OUTPUT_NAME=V5PathJNI-macos-universal
cmake --build NativeSrc/build --config Release -j
cp NativeSrc/build/V5PathJNI-macos-universal.dylib src/main/resources/assets/v5/V5PathJNI-macos-universal.dylib
cp NativeSrc/build/V5PathJNI-macos-universal.dylib src/main/resources/assets/v5/V5PathJNI.dylib
```

Windows (PowerShell):
```powershell
cmake -S NativeSrc -B NativeSrc/build -A x64 -DCMAKE_BUILD_TYPE=Release -DV5PATHJNI_OUTPUT_NAME=V5PathJNI-windows-x86_64
cmake --build NativeSrc/build --config Release --parallel
Copy-Item NativeSrc/build/Release/V5PathJNI-windows-x86_64.dll src/main/resources/assets/v5/V5PathJNI-windows-x86_64.dll -Force
Copy-Item NativeSrc/build/Release/V5PathJNI-windows-x86_64.dll src/main/resources/assets/v5/V5PathJNI.dll -Force
```

Output libraries are:
- `NativeSrc/build/V5PathJNI-linux-x86_64.so` (Linux)
- `NativeSrc/build/V5PathJNI-macos-universal.dylib` (macOS universal)
- `NativeSrc/build/Release/V5PathJNI-windows-x86_64.dll` or `NativeSrc/build/V5PathJNI-windows-x86_64.dll` (Windows)

For bundling into V5Loader, copy the built library into:
- `src/main/resources/assets/v5/`
(for prod release [commit], this is automatically done by github action)

For modifying pf, I just use this command to automatically compile, then move, then kotlin build (linux):
```bash
cmake -S NativeSrc -B NativeSrc/build -DCMAKE_BUILD_TYPE=Release -DV5PATHJNI_OUTPUT_NAME=V5PathJNI-linux-x86_64 && cmake --build NativeSrc/build --config Release -j && cp ./NativeSrc/build/V5PathJNI-linux-x86_64.so ./src/main/resources/assets/v5/V5PathJNI-linux-x86_64.so && cp ./NativeSrc/build/V5PathJNI-linux-x86_64.so ./src/main/resources/assets/v5/V5PathJNI.so && ./gradlew apiDump && ./gradlew build
```

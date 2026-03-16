# V5Loader

This project is CTJS 1.21.10 modified for V5 loading (SecureLoader), V5 utils (mixins, renderutils, etc), and Swift, a native pathfinder.

# Native Pathfinder JNI
Linux/macOS:
```bash
cmake -S NativeSrc -B NativeSrc/build -DCMAKE_BUILD_TYPE=Release
cmake --build NativeSrc/build --config Release -j
```

Windows (PowerShell):
```powershell
cmake -S NativeSrc -B NativeSrc/build -DCMAKE_BUILD_TYPE=Release
cmake --build NativeSrc/build --config Release --parallel
```

Output libraries are:
- `NativeSrc/build/V5PathJNI.so` (Linux)
- `NativeSrc/build/V5PathJNI.dylib` (macOS)
- `NativeSrc/build/Release/V5PathJNI.dll` or `NativeSrc/build/V5PathJNI.dll` (Windows)

For bundling into V5Loader, copy the built library into:
- `src/main/resources/assets/v5/`
(for prod release [commit], this is automatically done by github action)
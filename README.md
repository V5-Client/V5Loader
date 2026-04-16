# V5Loader

This project is CTJS 1.21.10 modified for V5 loading (SecureLoader), V5 utils (mixins, renderutils, etc), and Swift, a native pathfinder.

## License Summary
This project is licensed under GNU GPL v3.0. In short:

1. Anyone can copy, modify, and distribute this software.
2. Every distribution must include the license text and existing copyright notices.
3. You can use this software privately.
4. If you distribute modified versions or binaries, you must also provide the complete corresponding source code under GPL-3.0.

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

For modifying pf, I just use this command to automatically compile, then move, then kotlin build (linux):
```bash
cmake -S NativeSrc -B NativeSrc/build -DCMAKE_BUILD_TYPE=Release && cmake --build NativeSrc/build --config Release -j && cp ./NativeSrc/build/V5PathJNI.so ./src/main/resources/assets/v5/V5PathJNI.so && ./gradlew apiDump && ./gradlew build
```

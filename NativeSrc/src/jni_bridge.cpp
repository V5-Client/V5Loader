#include <jni.h>

#include "pathfinder.hpp"

#include <atomic>
#include <string>
#include <vector>

namespace {

v5pf::WorldState g_worldState;
std::atomic_bool g_cancelSearch{false};

std::vector<int> toIntVector(JNIEnv* env, jintArray array) {
  if (array == nullptr) return {};

  const jsize len = env->GetArrayLength(array);
  if (len <= 0) return {};

  std::vector<int> out(static_cast<size_t>(len));
  env->GetIntArrayRegion(array, 0, len, reinterpret_cast<jint*>(out.data()));
  return out;
}

std::vector<double> toDoubleVector(JNIEnv* env, jdoubleArray array) {
  if (array == nullptr) return {};

  const jsize len = env->GetArrayLength(array);
  if (len <= 0) return {};

  std::vector<double> out(static_cast<size_t>(len));
  env->GetDoubleArrayRegion(array, 0, len, reinterpret_cast<jdouble*>(out.data()));
  return out;
}

std::vector<uint16_t> toShortFlagsVector(JNIEnv* env, jshortArray array) {
  if (array == nullptr) return {};

  const jsize len = env->GetArrayLength(array);
  if (len <= 0) return {};

  std::vector<jshort> tmp(static_cast<size_t>(len));
  env->GetShortArrayRegion(array, 0, len, tmp.data());

  std::vector<uint16_t> out;
  out.reserve(static_cast<size_t>(len));
  for (const auto value : tmp) {
    out.push_back(static_cast<uint16_t>(value));
  }

  return out;
}

std::vector<v5pf::Int3> parsePoints(const std::vector<int>& flat) {
  std::vector<v5pf::Int3> out;
  if (flat.empty() || flat.size() % 3 != 0) return out;

  out.reserve(flat.size() / 3);
  for (size_t i = 0; i + 2 < flat.size(); i += 3) {
    out.push_back(v5pf::Int3{flat[i], flat[i + 1], flat[i + 2]});
  }

  return out;
}

std::vector<jint> packPoints(const std::vector<v5pf::Int3>& points) {
  std::vector<jint> packed;
  packed.reserve(points.size() * 3);
  for (const auto& p : points) {
    packed.push_back(static_cast<jint>(p.x));
    packed.push_back(static_cast<jint>(p.y));
    packed.push_back(static_cast<jint>(p.z));
  }
  return packed;
}

std::vector<jint> toJIntVector(const std::vector<int>& values) {
  std::vector<jint> out;
  out.reserve(values.size());
  for (const int value : values) {
    out.push_back(static_cast<jint>(value));
  }
  return out;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_v5_swift_nativepath_NativePathfinderJNI_initNative(JNIEnv*, jclass) {
  return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_com_v5_swift_nativepath_NativePathfinderJNI_setWorld(
  JNIEnv* env,
  jclass,
  jstring worldKey,
  jint minY,
  jint maxY
) {
  const char* keyChars = worldKey != nullptr ? env->GetStringUTFChars(worldKey, nullptr) : nullptr;
  std::string key = keyChars != nullptr ? keyChars : "runtime_memory";
  if (keyChars != nullptr) {
    env->ReleaseStringUTFChars(worldKey, keyChars);
  }

  g_worldState.setWorld(std::move(key), static_cast<int>(minY), static_cast<int>(maxY));
}

JNIEXPORT void JNICALL Java_com_v5_swift_nativepath_NativePathfinderJNI_clearWorld(JNIEnv*, jclass) {
  g_worldState.clear();
}

JNIEXPORT void JNICALL Java_com_v5_swift_nativepath_NativePathfinderJNI_upsertChunk(
  JNIEnv* env,
  jclass,
  jint chunkX,
  jint chunkZ,
  jint minY,
  jint maxY,
  jlong sectionMask,
  jshortArray sectionFlags
) {
  const auto flags = toShortFlagsVector(env, sectionFlags);

  g_worldState.upsertChunk(
    static_cast<int>(chunkX),
    static_cast<int>(chunkZ),
    static_cast<int>(minY),
    static_cast<int>(maxY),
    static_cast<uint64_t>(sectionMask),
    flags
  );
}

JNIEXPORT void JNICALL Java_com_v5_swift_nativepath_NativePathfinderJNI_applyBlockUpdates(
  JNIEnv* env,
  jclass,
  jintArray updates
) {
  const auto flat = toIntVector(env, updates);
  if (flat.empty() || flat.size() % 4 != 0) {
    return;
  }

  std::vector<v5pf::BlockUpdate> parsed;
  parsed.reserve(flat.size() / 4);

  for (size_t i = 0; i + 3 < flat.size(); i += 4) {
    parsed.push_back(v5pf::BlockUpdate{
      flat[i],
      flat[i + 1],
      flat[i + 2],
      static_cast<uint16_t>(flat[i + 3])
    });
  }

  g_worldState.applyUpdates(parsed);
}

JNIEXPORT jobject JNICALL Java_com_v5_swift_nativepath_NativePathfinderJNI_findPath(
  JNIEnv* env,
  jclass,
  jintArray startPoints,
  jintArray endPoints,
  jboolean isFly,
  jint maxIterations,
  jdouble heuristicWeight,
  jdouble nonPrimaryStartPenalty,
  jint moveOrderOffset,
  jintArray avoidMeta,
  jdoubleArray avoidPenalty
) {
  const auto startFlat = toIntVector(env, startPoints);
  const auto endFlat = toIntVector(env, endPoints);

  const auto starts = parsePoints(startFlat);
  const auto goals = parsePoints(endFlat);

  if (starts.empty() || goals.empty()) {
    return nullptr;
  }

  const auto avoidMetaFlat = toIntVector(env, avoidMeta);
  const auto avoidPenaltyFlat = toDoubleVector(env, avoidPenalty);

  std::vector<v5pf::AvoidZone> avoidZones;
  if (!avoidMetaFlat.empty() && avoidMetaFlat.size() % 5 == 0) {
    const size_t count = avoidMetaFlat.size() / 5;
    if (avoidPenaltyFlat.size() == count) {
      avoidZones.reserve(count);
      for (size_t i = 0; i < count; i++) {
        const size_t base = i * 5;
        avoidZones.push_back(v5pf::AvoidZone{
          avoidMetaFlat[base],
          avoidMetaFlat[base + 1],
          avoidMetaFlat[base + 2],
          avoidMetaFlat[base + 3],
          avoidMetaFlat[base + 4],
          avoidPenaltyFlat[i],
        });
      }
    }
  }

  v5pf::SearchParams params;
  params.starts = starts;
  params.goals = goals;
  params.isFly = isFly == JNI_TRUE;
  params.maxIterations = static_cast<int>(maxIterations);
  params.heuristicWeight = static_cast<double>(heuristicWeight);
  params.nonPrimaryStartPenalty = static_cast<double>(nonPrimaryStartPenalty);
  params.moveOrderOffset = static_cast<int>(moveOrderOffset);
  params.avoidZones = std::move(avoidZones);

  g_cancelSearch.store(false);
  const auto worldSnapshot = g_worldState.snapshot();
  auto result = v5pf::findPath(worldSnapshot, params, g_cancelSearch);

  if (!result.has_value() || result->points.empty()) {
    return nullptr;
  }

  const auto packedPath = packPoints(result->points);
  const auto packedKeyPath = packPoints(result->keyPoints);
  const auto packedPathFlags = toJIntVector(result->pathFlags);
  const auto packedKeyNodeFlags = toJIntVector(result->keyNodeFlags);
  const auto packedKeyNodeMetrics = toJIntVector(result->keyNodeMetrics);

  jintArray pathArray = env->NewIntArray(static_cast<jsize>(packedPath.size()));
  if (pathArray == nullptr) {
    return nullptr;
  }
  env->SetIntArrayRegion(pathArray, 0, static_cast<jsize>(packedPath.size()), packedPath.data());

  jintArray keyPathArray = env->NewIntArray(static_cast<jsize>(packedKeyPath.size()));
  if (keyPathArray == nullptr) {
    return nullptr;
  }
  env->SetIntArrayRegion(keyPathArray, 0, static_cast<jsize>(packedKeyPath.size()), packedKeyPath.data());

  jintArray pathFlagsArray = env->NewIntArray(static_cast<jsize>(packedPathFlags.size()));
  if (pathFlagsArray == nullptr) {
    return nullptr;
  }
  if (!packedPathFlags.empty()) {
    env->SetIntArrayRegion(
      pathFlagsArray,
      0,
      static_cast<jsize>(packedPathFlags.size()),
      packedPathFlags.data()
    );
  }

  jintArray keyNodeFlagsArray = env->NewIntArray(static_cast<jsize>(packedKeyNodeFlags.size()));
  if (keyNodeFlagsArray == nullptr) {
    return nullptr;
  }
  if (!packedKeyNodeFlags.empty()) {
    env->SetIntArrayRegion(
      keyNodeFlagsArray,
      0,
      static_cast<jsize>(packedKeyNodeFlags.size()),
      packedKeyNodeFlags.data()
    );
  }

  jintArray keyNodeMetricsArray = env->NewIntArray(static_cast<jsize>(packedKeyNodeMetrics.size()));
  if (keyNodeMetricsArray == nullptr) {
    return nullptr;
  }
  if (!packedKeyNodeMetrics.empty()) {
    env->SetIntArrayRegion(
      keyNodeMetricsArray,
      0,
      static_cast<jsize>(packedKeyNodeMetrics.size()),
      packedKeyNodeMetrics.data()
    );
  }

  jstring signature = env->NewStringUTF(result->signatureHex.c_str());
  if (signature == nullptr) {
    return nullptr;
  }

  jclass resultClass = env->FindClass("com/v5/swift/nativepath/NativePathResult");
  if (resultClass == nullptr) {
    return nullptr;
  }

  jmethodID ctor = env->GetMethodID(resultClass, "<init>", "([I[IJII[I[I[ILjava/lang/String;)V");
  if (ctor == nullptr) {
    return nullptr;
  }

  return env->NewObject(
    resultClass,
    ctor,
    pathArray,
    keyPathArray,
    static_cast<jlong>(result->timeMs),
    static_cast<jint>(result->nodesExplored),
    static_cast<jint>(result->selectedStartIndex),
    pathFlagsArray,
    keyNodeFlagsArray,
    keyNodeMetricsArray,
    signature
  );
}

JNIEXPORT void JNICALL Java_com_v5_swift_nativepath_NativePathfinderJNI_cancelSearch(JNIEnv*, jclass) {
  g_cancelSearch.store(true);
}

} // extern "C"

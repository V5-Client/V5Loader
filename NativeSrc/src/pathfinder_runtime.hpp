#pragma once

#include "lazy_section_array.hpp"
#include "pathfinder.hpp"
#include "world_voxel_cursor.hpp"

#include <array>

namespace v5pf::detail {

constexpr int MAX_DIST = 6;
constexpr int OPEN_SPACE_SOFT_CAP = MAX_DIST;

inline constexpr std::array<double, 7> EDGE_PENALTIES = {
  24.0,
  19.5,
  16.0,
  11.5,
  5.5,
  3.7,
  0.5,
};

inline constexpr std::array<double, 7> WALL_PENALTIES = {
  17.0,
  13.5,
  11.0,
  6.5,
  3.0,
  1.5,
  0.2,
};

inline constexpr std::array<int, 8> DX = {0, 0, 1, -1, 1, -1, 1, -1};
inline constexpr std::array<int, 8> DZ = {-1, 1, 0, 0, -1, -1, 1, 1};

inline constexpr std::array<Int3, 16> WALK_MOVES = {
  Int3{0, 0, -1}, Int3{0, 0, 1}, Int3{1, 0, 0}, Int3{-1, 0, 0},
  Int3{1, 0, -1}, Int3{-1, 0, -1}, Int3{1, 0, 1}, Int3{-1, 0, 1},
  Int3{0, 1, -1}, Int3{0, 1, 1}, Int3{1, 1, 0}, Int3{-1, 1, 0},
  Int3{0, -1, -1}, Int3{0, -1, 1}, Int3{1, -1, 0}, Int3{-1, -1, 0},
};

inline constexpr std::array<Int3, 26> FLY_MOVES = {
  Int3{0, 0, -1}, Int3{0, 0, 1}, Int3{1, 0, 0}, Int3{-1, 0, 0},
  Int3{1, 0, -1}, Int3{-1, 0, -1}, Int3{1, 0, 1}, Int3{-1, 0, 1},

  Int3{0, 1, 0}, Int3{0, 1, -1}, Int3{0, 1, 1}, Int3{1, 1, 0}, Int3{-1, 1, 0},
  Int3{1, 1, -1}, Int3{-1, 1, -1}, Int3{1, 1, 1}, Int3{-1, 1, 1},

  Int3{0, -1, 0}, Int3{0, -1, -1}, Int3{0, -1, 1}, Int3{1, -1, 0}, Int3{-1, -1, 0},
  Int3{1, -1, -1}, Int3{-1, -1, -1}, Int3{1, -1, 1}, Int3{-1, -1, 1},
};

inline bool hasFlag(const uint16_t flags, const uint16_t bit) {
  return (flags & bit) != 0;
}

inline bool isPassableFlags(const uint16_t flags) {
  return hasFlag(flags, VF_PASSABLE) || hasFlag(flags, VF_CARPET_LIKE);
}

inline bool isFlyPassableFlags(const uint16_t flags) {
  return isPassableFlags(flags) || hasFlag(flags, VF_PASSABLE_FLY);
}

struct MoveOut {
  Int3 pos{};
  double cost = ActionCosts::INF_COST;
};

struct FlyEnvironment {
  double groundCost = 0.0;
  double horizontalCost = 0.0;
  double enclosureCost = 0.0;
  bool confined = false;
  uint8_t computed = 0;
};

template<typename T>
struct StampedValue {
  uint32_t generation = 0;
  T value{};
};

struct VoxelClassifications {
  uint8_t computed = 0;
  uint8_t values = 0;
};

struct RuntimeCache {
  std::shared_ptr<const WorldData> snapshotData;
  int snapshotMinY = 0;
  int snapshotMaxY = 0;
  uint32_t worldGeneration = 0;
  uint32_t searchGeneration = 0;
  LazySectionArray<StampedValue<VoxelClassifications>> classifications;
  LazySectionArray<StampedValue<double>> penalties;
  LazySectionArray<StampedValue<FlyEnvironment>> flyEnvironments;
  LazySectionArray<StampedValue<double>> avoidPenalties;

  void begin(const WorldSnapshot& world) {
    if (++searchGeneration == 0) {
      avoidPenalties.clear();
      searchGeneration = 1;
    }

    if (snapshotData == world.data && snapshotMinY == world.minY && snapshotMaxY == world.maxY) return;
    snapshotData = world.data;
    snapshotMinY = world.minY;
    snapshotMaxY = world.maxY;
    if (++worldGeneration == 0) {
      classifications.clear();
      penalties.clear();
      flyEnvironments.clear();
      worldGeneration = 1;
    }
  }
};

inline RuntimeCache& runtimeCache() {
  static thread_local RuntimeCache cache;
  return cache;
}

class Runtime {
 public:
  Runtime(const WorldSnapshot& world, const SearchParams& params);

  [[nodiscard]] bool isAtGoal(int x, int y, int z) const;
  [[nodiscard]] double heuristic(int x, int y, int z) const;
  [[nodiscard]] double transientAvoidPenalty(int x, int y, int z) const;
  [[nodiscard]] double flyHorizontalProgress(int x, int y, int z) const;

  [[nodiscard]] bool walkMove(const Int3& current, const Int3& delta, MoveOut& out);
  [[nodiscard]] bool flyMove(const Int3& current, const Int3& delta, MoveOut& out);
  [[nodiscard]] bool flyMove(const Int3& current, const Int3& delta, double progress, MoveOut& out);

 private:
  const WorldSnapshot& world_;
  const SearchParams& params_;
  mutable WorldVoxelCursor voxelCursor_;
  RuntimeCache& cache_;

  bool avoidPenaltyCacheEnabled_ = false;

  Int3 startFly_{0, 0, 0};
  int flyMinY_ = 0;
  int flyMaxY_ = 0;

  [[nodiscard]] uint16_t flagsAt(int x, int y, int z) const;
  [[nodiscard]] bool isPassable(int x, int y, int z) const;
  [[nodiscard]] bool isPassableForFlying(int x, int y, int z) const;

  [[nodiscard]] bool isSafe(int x, int y, int z);
  [[nodiscard]] bool isFlyColumnClear(int x, int y, int z);
  [[nodiscard]] double fluidPenalty(int x, int y, int z) const;

  [[nodiscard]] double walkHeuristic(int x, int y, int z) const;
  [[nodiscard]] double flyHeuristic(int x, int y, int z) const;
  [[nodiscard]] const Int3& closestFlyGoal(int x, int y, int z) const;
  [[nodiscard]] double calculateProgress(int x, int z, const Int3& goal) const;

  [[nodiscard]] int directionMask(int x, int y, int z);
  [[nodiscard]] bool isStepDirection(int x, int y, int z, int dx, int dz);

  [[nodiscard]] bool isEdge(int x, int y, int z);
  [[nodiscard]] bool isWall(int x, int y, int z);
  [[nodiscard]] int scanForEdge(int x, int y, int z, int dx, int dz);
  [[nodiscard]] int scanForWall(int x, int y, int z, int dx, int dz);

  [[nodiscard]] int edgeDistanceWithMask(int x, int y, int z, int mask);
  [[nodiscard]] int wallDistanceWithMask(int x, int y, int z, int mask);
  [[nodiscard]] double combinedPenalty(int edgeDist, int wallDist) const;
  [[nodiscard]] double pathPenalty(int x, int y, int z);

  [[nodiscard]] bool moveTraverse(const Int3& current, int dx, int dz, MoveOut& out);
  [[nodiscard]] bool moveDiagonal(const Int3& current, int dx, int dz, MoveOut& out);
  [[nodiscard]] bool moveAscend(const Int3& current, int dx, int dz, MoveOut& out);
  [[nodiscard]] bool moveDescend(const Int3& current, int dx, int dz, MoveOut& out);

  [[nodiscard]] bool moveFly(const Int3& current, int dx, int dy, int dz, double progress, MoveOut& out);
  void populateFlyEnvironment(int x, int y, int z, uint8_t needed, FlyEnvironment& environment);
};

} // namespace v5pf::detail

#include "pathfinder_runtime_common.inl"
#include "pathfinder_runtime_walk.inl"
#include "pathfinder_runtime_fly.inl"

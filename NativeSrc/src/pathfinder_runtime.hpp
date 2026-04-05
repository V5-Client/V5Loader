#pragma once

#include "pathfinder.hpp"
#include "world_voxel_cursor.hpp"

#include <array>
#include <unordered_map>

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

struct MoveOut {
  Int3 pos{};
  double cost = ActionCosts::INF_COST;
};

class Runtime {
 public:
  Runtime(const WorldSnapshot& world, const SearchParams& params);

  [[nodiscard]] bool isAtGoal(int x, int y, int z) const;
  [[nodiscard]] double heuristic(int x, int y, int z) const;
  [[nodiscard]] double transientAvoidPenalty(int x, int y, int z) const;
  [[nodiscard]] double flyHorizontalProgress(int x, int z) const;

  [[nodiscard]] bool walkMove(const Int3& current, const Int3& delta, MoveOut& out);
  [[nodiscard]] bool flyMove(const Int3& current, const Int3& delta, MoveOut& out);
  [[nodiscard]] bool flyMove(const Int3& current, const Int3& delta, double progress, MoveOut& out);

 private:
  const WorldSnapshot& world_;
  const SearchParams& params_;
  ActionCosts costs_{};
  mutable WorldVoxelCursor voxelCursor_;

  mutable std::unordered_map<uint64_t, uint16_t> flagsCache_;
  std::unordered_map<uint64_t, uint8_t> safeCache_;
  std::unordered_map<uint64_t, uint8_t> flyClearCache_;
  std::unordered_map<uint64_t, double> penaltyCache_;
  mutable std::unordered_map<uint64_t, double> avoidPenaltyCache_;

  size_t cacheReserve_ = 0;
  bool flagsCacheEnabled_ = false;
  bool avoidPenaltyCacheEnabled_ = false;

  int walkStartX_ = 0;
  int walkStartZ_ = 0;

  Int3 startFly_{0, 0, 0};
  Int3 goalFly_{0, 0, 0};
  int cruiseY_ = 0;
  int flyMinY_ = 0;
  int flyMaxY_ = 0;
  int startToGoalDx_ = 0;
  int startToGoalDz_ = 0;

  [[nodiscard]] uint16_t flagsAt(int x, int y, int z) const;
  [[nodiscard]] bool isSolid(int x, int y, int z) const;
  [[nodiscard]] bool isPassable(int x, int y, int z) const;
  [[nodiscard]] bool isPassableForFlying(int x, int y, int z) const;
  [[nodiscard]] bool isBottomSlab(int x, int y, int z) const;
  [[nodiscard]] bool isTopSlab(int x, int y, int z) const;
  [[nodiscard]] bool isFenceLike(int x, int y, int z) const;
  [[nodiscard]] bool isStairsBottom(int x, int y, int z) const;
  [[nodiscard]] bool isBlockingWall(int x, int y, int z) const;
  [[nodiscard]] bool isFluid(int x, int y, int z) const;

  [[nodiscard]] bool isSafe(int x, int y, int z);
  [[nodiscard]] bool isFlyColumnClear(int x, int y, int z);
  [[nodiscard]] double fluidPenalty(int x, int y, int z) const;

  [[nodiscard]] double walkHeuristic(int x, int y, int z) const;
  [[nodiscard]] double flyHeuristic(int x, int y, int z) const;
  [[nodiscard]] double estimateVerticalCost(int x, int z, int y) const;
  [[nodiscard]] double calculateProgress(int x, int z) const;

  [[nodiscard]] int directionMask(int x, int y, int z);
  [[nodiscard]] bool isStepDirection(int x, int y, int z, int dx, int dz);
  [[nodiscard]] bool isStepSurface(int x, int y, int z);

  [[nodiscard]] bool isEdge(int x, int y, int z);
  [[nodiscard]] bool isWall(int x, int y, int z);
  [[nodiscard]] int scanForEdge(int x, int y, int z, int dx, int dz);
  [[nodiscard]] int scanForWall(int x, int y, int z, int dx, int dz);

  [[nodiscard]] int edgeDistance(int x, int y, int z);
  [[nodiscard]] int wallDistance(int x, int y, int z);
  [[nodiscard]] int edgeDistanceWithMask(int x, int y, int z, int mask);
  [[nodiscard]] int wallDistanceWithMask(int x, int y, int z, int mask);
  [[nodiscard]] double combinedPenalty(int edgeDist, int wallDist) const;
  [[nodiscard]] double pathPenalty(int x, int y, int z);

  [[nodiscard]] bool moveTraverse(const Int3& current, int dx, int dz, MoveOut& out);
  [[nodiscard]] bool moveDiagonal(const Int3& current, int dx, int dz, MoveOut& out);
  [[nodiscard]] bool moveAscend(const Int3& current, int dx, int dz, MoveOut& out);
  [[nodiscard]] bool moveDescend(const Int3& current, int dx, int dz, MoveOut& out);

  [[nodiscard]] bool moveFly(const Int3& current, int dx, int dy, int dz, double progress, MoveOut& out);
  [[nodiscard]] bool shouldRejectConfined(int x, int y, int z, double progress);
  [[nodiscard]] double horizontalClearanceCost(int x, int y, int z, double progress);
  [[nodiscard]] double enclosureCost(int x, int y, int z, double progress);
};

} // namespace v5pf::detail

#include "pathfinder_runtime_common.inl"
#include "pathfinder_runtime_walk.inl"
#include "pathfinder_runtime_fly.inl"

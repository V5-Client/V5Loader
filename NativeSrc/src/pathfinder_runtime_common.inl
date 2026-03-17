#pragma once

#include <algorithm>
#include <cmath>
#include <limits>

namespace v5pf::detail {

inline Runtime::Runtime(const WorldSnapshot& world, const SearchParams& params)
  : world_(world),
    params_(params),
    walkStartX_(params.starts.empty() ? 0 : params.starts.front().x),
    walkStartZ_(params.starts.empty() ? 0 : params.starts.front().z) {
  if (params_.isFly && !params_.starts.empty() && !params_.goals.empty()) {
    startFly_ = params_.starts.front();
    goalFly_ = params_.goals.front();
    cruiseY_ = std::max(startFly_.y, goalFly_.y) + 6;
    startToGoalDx_ = startFly_.x - goalFly_.x;
    startToGoalDz_ = startFly_.z - goalFly_.z;
  }
}

inline bool Runtime::isAtGoal(const int x, const int y, const int z) const {
  for (const auto& goal : params_.goals) {
    if (goal.x == x && goal.y == y && goal.z == z) {
      return true;
    }
  }
  return false;
}

inline double Runtime::heuristic(const int x, const int y, const int z) const {
  return params_.isFly ? flyHeuristic(x, y, z) : walkHeuristic(x, y, z);
}

inline double Runtime::transientAvoidPenalty(const int x, const int y, const int z) const {
  double penalty = 0.0;
  for (const auto& zone : params_.avoidZones) {
    if (std::abs(y - zone.y) > zone.maxYDiff) continue;

    const int dx = x - zone.x;
    const int dz = z - zone.z;
    const long long distSq = static_cast<long long>(dx) * dx + static_cast<long long>(dz) * dz;
    if (distSq > zone.radiusSq) continue;

    const double normalized = zone.radiusSq <= 1 ? 0.0 : static_cast<double>(distSq) / static_cast<double>(zone.radiusSq);
    const double falloff = std::max(0.2, 1.0 - normalized);
    penalty += zone.penalty * falloff;
  }
  return penalty;
}

inline bool Runtime::walkMove(const Int3& current, const Int3& delta, MoveOut& out) {
  if (delta.y == 0) {
    if (delta.x == 0 || delta.z == 0) {
      return moveTraverse(current, delta.x, delta.z, out);
    }
    return moveDiagonal(current, delta.x, delta.z, out);
  }

  if (delta.y > 0) {
    return moveAscend(current, delta.x, delta.z, out);
  }

  return moveDescend(current, delta.x, delta.z, out);
}

inline bool Runtime::flyMove(const Int3& current, const Int3& delta, MoveOut& out) {
  return moveFly(current, delta.x, delta.y, delta.z, out);
}

inline uint16_t Runtime::flagsAt(const int x, const int y, const int z) const {
  return world_.getFlags(x, y, z);
}

inline bool Runtime::isSolid(const int x, const int y, const int z) const {
  return hasFlag(flagsAt(x, y, z), VF_SOLID);
}

inline bool Runtime::isPassable(const int x, const int y, const int z) const {
  const uint16_t flags = flagsAt(x, y, z);
  return hasFlag(flags, VF_PASSABLE) || hasFlag(flags, VF_CARPET_LIKE);
}

inline bool Runtime::isPassableForFlying(const int x, const int y, const int z) const {
  const uint16_t flags = flagsAt(x, y, z);
  return hasFlag(flags, VF_PASSABLE) || hasFlag(flags, VF_PASSABLE_FLY) || hasFlag(flags, VF_CARPET_LIKE);
}

inline bool Runtime::isBottomSlab(const int x, const int y, const int z) const {
  return hasFlag(flagsAt(x, y, z), VF_SLAB_BOTTOM);
}

inline bool Runtime::isTopSlab(const int x, const int y, const int z) const {
  return hasFlag(flagsAt(x, y, z), VF_SLAB_TOP);
}

inline bool Runtime::isFenceLike(const int x, const int y, const int z) const {
  return hasFlag(flagsAt(x, y, z), VF_FENCE_LIKE);
}

inline bool Runtime::isStairsBottom(const int x, const int y, const int z) const {
  return hasFlag(flagsAt(x, y, z), VF_STAIRS_BOTTOM);
}

inline bool Runtime::isBlockingWall(const int x, const int y, const int z) const {
  return hasFlag(flagsAt(x, y, z), VF_BLOCKING_WALL);
}

inline bool Runtime::isFluid(const int x, const int y, const int z) const {
  return hasFlag(flagsAt(x, y, z), VF_FLUID);
}

inline bool Runtime::isSafe(const int x, const int y, const int z) {
  const uint64_t key = coordKey(x, y, z);
  const auto it = safeCache_.find(key);
  if (it != safeCache_.end()) {
    return it->second == 1;
  }

  bool safe = true;
  if (!isSolid(x, y - 1, z)) {
    safe = false;
  } else if (!isPassable(x, y, z)) {
    safe = false;
  } else if (!isPassable(x, y + 1, z)) {
    safe = false;
  }

  safeCache_[key] = safe ? 1 : 0;
  return safe;
}

inline bool Runtime::isFlyColumnClear(const int x, const int y, const int z) {
  const uint64_t key = coordKey(x, y, z);
  const auto it = flyClearCache_.find(key);
  if (it != flyClearCache_.end()) {
    return it->second == 1;
  }

  bool clear = true;
  if (!isPassableForFlying(x, y, z)) {
    clear = false;
  } else if (isTopSlab(x, y + 1, z)) {
    clear = false;
  } else if (!isPassableForFlying(x, y + 1, z)) {
    clear = false;
  }

  flyClearCache_[key] = clear ? 1 : 0;
  return clear;
}

inline double Runtime::fluidPenalty(const int x, const int y, const int z) const {
  double penalty = 0.0;
  if (isFluid(x, y, z)) penalty += 20.0;
  if (isFluid(x, y + 1, z)) penalty += 20.0;
  return penalty;
}

inline double Runtime::walkHeuristic(const int x, const int y, const int z) const {
  if (params_.goals.empty()) return 0.0;

  const double sprintCost = ActionCosts::SPRINT_ONE_BLOCK_TIME;
  const double diagonalCost = ActionCosts::SPRINT_DIAGONAL_TIME;
  const double fallCostPerBlock = costs_.getFallTime(2) * 0.5;
  const double jumpCostPerBlock = ActionCosts::JUMP_UP_ONE_BLOCK_TIME;
  const double verticalReluctance = sprintCost * 0.35;

  double best = std::numeric_limits<double>::infinity();
  for (const auto& goal : params_.goals) {
    const long long dx = std::llabs(static_cast<long long>(x) - goal.x);
    const long long dz = std::llabs(static_cast<long long>(z) - goal.z);
    const long long dy = static_cast<long long>(y) - goal.y;

    const long long minHoriz = std::min(dx, dz);
    const long long maxHoriz = std::max(dx, dz);

    double horizontal = static_cast<double>(minHoriz) * diagonalCost +
      static_cast<double>(maxHoriz - minHoriz) * sprintCost;

    if (dy != 0) {
      const double absDy = static_cast<double>(std::llabs(dy));
      horizontal += (dy > 0 ? static_cast<double>(dy) * fallCostPerBlock : absDy * jumpCostPerBlock);
      horizontal += absDy * verticalReluctance;
    }

    if (horizontal < best) {
      best = horizontal;
    }
  }

  return std::isfinite(best) ? best : 0.0;
}

inline int Runtime::directionMask(const int x, const int y, const int z) {
  int mask = 0;
  for (int dir = 0; dir < 8; dir++) {
    if (!isStepDirection(x, y, z, DX[static_cast<size_t>(dir)], DZ[static_cast<size_t>(dir)])) {
      mask |= (1 << dir);
    }
  }
  return mask == 0 ? ((1 << 8) - 1) : mask;
}

inline bool Runtime::isStepDirection(const int x, const int y, const int z, const int dx, const int dz) {
  const int nx = x + dx;
  const int nz = z + dz;

  if (isStepSurface(nx, y, nz) && isSolid(nx, y, nz) && isPassable(nx, y + 1, nz) && isPassable(nx, y + 2, nz)) {
    return true;
  }

  if (isStepSurface(nx, y - 2, nz) && isSolid(nx, y - 2, nz) && isPassable(nx, y - 1, nz) && isPassable(nx, y, nz)) {
    return true;
  }

  return false;
}

inline bool Runtime::isStepSurface(const int x, const int y, const int z) {
  return isStairsBottom(x, y, z) || isBottomSlab(x, y, z);
}

inline bool Runtime::isEdge(const int x, const int y, const int z) {
  if (isSolid(x, y, z)) return false;
  if (isSolid(x, y - 1, z)) return false;
  if (isSolid(x, y - 2, z)) return false;
  return true;
}

inline bool Runtime::isWall(const int x, const int y, const int z) {
  if (isBlockingWall(x, y + 1, z)) return true;

  if (isSolid(x, y + 1, z)) {
    if (isTopSlab(x, y + 1, z)) return true;
    if (isBottomSlab(x, y, z) || isStairsBottom(x, y, z)) return false;
    return true;
  }

  return isBlockingWall(x, y, z);
}

inline int Runtime::scanForEdge(const int x, const int y, const int z, const int dx, const int dz) {
  int cx = x + dx;
  int cz = z + dz;
  for (int d = 1; d <= MAX_DIST; d++) {
    if (isEdge(cx, y, cz)) return d - 1;
    cx += dx;
    cz += dz;
  }
  return MAX_DIST;
}

inline int Runtime::scanForWall(const int x, const int y, const int z, const int dx, const int dz) {
  int cx = x + dx;
  int cz = z + dz;
  for (int d = 1; d <= MAX_DIST; d++) {
    if (isWall(cx, y, cz)) return d - 1;
    cx += dx;
    cz += dz;
  }
  return MAX_DIST;
}

inline int Runtime::edgeDistance(const int x, const int y, const int z) {
  int dist = MAX_DIST;
  const int mask = directionMask(x, y, z);
  for (int dir = 0; dir < 8; dir++) {
    if ((mask & (1 << dir)) == 0) continue;
    const int d = scanForEdge(x, y, z, DX[static_cast<size_t>(dir)], DZ[static_cast<size_t>(dir)]);
    if (d < dist) dist = d;
    if (dist == 0) break;
  }
  return dist;
}

inline int Runtime::wallDistance(const int x, const int y, const int z) {
  int dist = MAX_DIST;
  const int mask = directionMask(x, y, z);
  for (int dir = 0; dir < 8; dir++) {
    if ((mask & (1 << dir)) == 0) continue;
    const int d = scanForWall(x, y, z, DX[static_cast<size_t>(dir)], DZ[static_cast<size_t>(dir)]);
    if (d < dist) dist = d;
    if (dist == 0) break;
  }
  return dist;
}

inline double Runtime::combinedPenalty(const int edgeDist, const int wallDist) const {
  const int edgeIdx = std::clamp(edgeDist, 0, OPEN_SPACE_SOFT_CAP);
  const int wallIdx = std::clamp(wallDist, 0, OPEN_SPACE_SOFT_CAP);
  return EDGE_PENALTIES[static_cast<size_t>(edgeIdx)] + WALL_PENALTIES[static_cast<size_t>(wallIdx)];
}

inline double Runtime::pathPenalty(const int x, const int y, const int z) {
  const uint64_t key = coordKey(x, y, z);
  const auto cached = penaltyCache_.find(key);
  if (cached != penaltyCache_.end()) {
    return cached->second;
  }

  const int edgeDist = edgeDistance(x, y, z);
  const int wallDist = wallDistance(x, y, z);
  const double value = combinedPenalty(edgeDist, wallDist);
  penaltyCache_[key] = value;
  return value;
}

} // namespace v5pf::detail

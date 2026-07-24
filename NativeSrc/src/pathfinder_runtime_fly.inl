#pragma once

#include <cmath>

namespace v5pf::detail {

constexpr uint8_t FLY_ENV_GROUND = 1u << 0;
constexpr uint8_t FLY_ENV_CONFINED = 1u << 1;
constexpr uint8_t FLY_ENV_HORIZONTAL = 1u << 2;
constexpr uint8_t FLY_ENV_ENCLOSURE = 1u << 3;

constexpr double groundClearanceCost(const int distance) {
  return static_cast<double>(6 - distance) * 2.0;
}
static_assert(groundClearanceCost(1) == 10.0 && groundClearanceCost(5) == 2.0);

inline double Runtime::flyHeuristic(const int x, const int y, const int z) const {
  const auto& goal = closestFlyGoal(x, y, z);
  const double dx = static_cast<double>(x) - static_cast<double>(goal.x);
  const double dy = static_cast<double>(y) - static_cast<double>(goal.y);
  const double dz = static_cast<double>(z) - static_cast<double>(goal.z);

  double h = std::sqrt(dx * dx + dy * dy + dz * dz) * ActionCosts::FLY_ONE_BLOCK_TIME;
  const double crossProduct = std::abs(
    dx * (static_cast<double>(startFly_.z) - static_cast<double>(goal.z)) -
    dz * (static_cast<double>(startFly_.x) - static_cast<double>(goal.x))
  );
  h += crossProduct * 0.001;

  return h;
}

inline const Int3& Runtime::closestFlyGoal(const int x, const int y, const int z) const {
  const auto* best = &params_.goals.front();
  long long bestDistance = std::numeric_limits<long long>::max();
  for (const auto& goal : params_.goals) {
    const long long dx = static_cast<long long>(x) - goal.x;
    const long long dy = static_cast<long long>(y) - goal.y;
    const long long dz = static_cast<long long>(z) - goal.z;
    const long long distance = dx * dx + dy * dy + dz * dz;
    if (distance < bestDistance) {
      best = &goal;
      bestDistance = distance;
    }
  }
  return *best;
}

inline double Runtime::calculateProgress(const int x, const int z, const Int3& goal) const {
  const long long dxStart = static_cast<long long>(x) - startFly_.x;
  const long long dzStart = static_cast<long long>(z) - startFly_.z;
  const long long dxGoal = static_cast<long long>(x) - goal.x;
  const long long dzGoal = static_cast<long long>(z) - goal.z;

  const long long distFromStartSq = dxStart * dxStart + dzStart * dzStart;
  const long long distToGoalSq = dxGoal * dxGoal + dzGoal * dzGoal;
  const long long totalSq = distFromStartSq + distToGoalSq;

  return totalSq > 0 ? static_cast<double>(distFromStartSq) / static_cast<double>(totalSq) : 0.5;
}

inline bool Runtime::moveFly(const Int3& current, const int dx, const int dy, const int dz, const double progress, MoveOut& out) {
  const int destX = current.x + dx;
  const int destY = current.y + dy;
  const int destZ = current.z + dz;

  if (destY < flyMinY_ || destY > flyMaxY_) return false;

  if (!isFlyColumnClear(destX, destY, destZ)) return false;

  if (dy > 0) {
    const int aboveY = current.y + 1;
    if (aboveY < flyMinY_ || aboveY > flyMaxY_) return false;
    if (!isFlyColumnClear(current.x, aboveY, current.z)) return false;
  }

  const bool diagonalHorizontal = dx != 0 && dz != 0;
  if (diagonalHorizontal) {
    if (!isFlyColumnClear(current.x + dx, destY, current.z)) return false;
    if (!isFlyColumnClear(current.x, destY, current.z + dz)) return false;

    if (dy != 0) {
      if (!isFlyColumnClear(current.x + dx, current.y, current.z)) return false;
      if (!isFlyColumnClear(current.x, current.y, current.z + dz)) return false;
    }
  }

  const int axisCount = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
  static constexpr std::array<double, 4> baseDistances = {0.0, 1.0, 1.4142135623730951, 1.7320508075688772};
  double cost = baseDistances[static_cast<size_t>(axisCount)] * ActionCosts::FLY_ONE_BLOCK_TIME;

  auto environmentIt = flyEnvironmentCache_.try_emplace(coordKey(destX, destY, destZ)).first;
  auto& environment = environmentIt->second;
  uint8_t needed = FLY_ENV_GROUND;
  if (progress <= 0.92) needed |= FLY_ENV_CONFINED;
  if (progress <= 0.88) needed |= FLY_ENV_HORIZONTAL;
  if (progress <= 0.94) needed |= FLY_ENV_ENCLOSURE;
  populateFlyEnvironment(destX, destY, destZ, needed, environment);

  if (dy != 0) {
    cost += (diagonalHorizontal || dx != 0 || dz != 0) ? 0.2 : 1.2;
  }

  cost += environment.groundCost;

  if (progress <= 0.88) {
    const double scale = progress > 0.84 ? 0.45 : (progress > 0.72 ? 0.7 : 1.0);
    cost += environment.horizontalCost * scale;
  }

  if (progress <= 0.94) {
    const double scale = progress > 0.84 ? 0.5 : (progress > 0.72 ? 0.75 : 1.0);
    cost += environment.enclosureCost * scale;
  }

  out.pos = {destX, destY, destZ};
  out.cost = cost;
  return true;
}

inline void Runtime::populateFlyEnvironment(
  const int x,
  const int y,
  const int z,
  const uint8_t needed,
  FlyEnvironment& environment
) {
  const uint8_t missing = needed & static_cast<uint8_t>(~environment.computed);
  if (missing == 0) return;

  if ((missing & FLY_ENV_GROUND) != 0) {
    for (int distance = 1; distance <= 5; distance++) {
      if (!isPassableForFlying(x, y - distance, z)) {
        environment.groundCost = groundClearanceCost(distance);
        break;
      }
    }
    environment.computed |= FLY_ENV_GROUND;
  }

  const bool needConfined = (missing & FLY_ENV_CONFINED) != 0;
  const bool needHorizontal = (missing & FLY_ENV_HORIZONTAL) != 0;
  const bool needEnclosure = (missing & FLY_ENV_ENCLOSURE) != 0;
  if (!needConfined && !needHorizontal && !needEnclosure) return;

  const bool ceilingClear = isPassableForFlying(x, y + 2, z);
  const bool scanExtended = needHorizontal || (needConfined && ceilingClear);
  const int scanDistance = scanExtended ? 5 : 1;
  int minClearance = 5;
  int blockedCardinals = 0;
  int blockedImmediate = 0;
  for (int i = 0; i < 4; i++) {
    int nx = x;
    int nz = z;
    for (int d = 1; d <= scanDistance; d++) {
      nx += DX[static_cast<size_t>(i)];
      nz += DZ[static_cast<size_t>(i)];
      if (!isFlyColumnClear(nx, y, nz)) {
        minClearance = std::min(minClearance, d - 1);
        blockedCardinals++;
        if (d == 1) blockedImmediate++;
        break;
      }
    }
  }

  if (needConfined) {
    environment.confined = !ceilingClear ||
      blockedCardinals >= 3 ||
      (blockedCardinals >= 2 && minClearance <= 1);
    environment.computed |= FLY_ENV_CONFINED;
  }

  if (needHorizontal) {
    double diagonalTouchPenalty = 0.0;
    if (minClearance > 0) {
      for (int i = 4; i < 8; i++) {
        if (!isFlyColumnClear(x + DX[static_cast<size_t>(i)], y, z + DZ[static_cast<size_t>(i)])) {
          diagonalTouchPenalty += 3.5;
        }
      }
    }

    switch (minClearance) {
      case 0: environment.horizontalCost = 16.0; break;
      case 1: environment.horizontalCost = 9.0; break;
      case 2: environment.horizontalCost = 4.0; break;
      case 3: environment.horizontalCost = 1.5; break;
      default: environment.horizontalCost = 0.0; break;
    }
    environment.horizontalCost += diagonalTouchPenalty;
    environment.computed |= FLY_ENV_HORIZONTAL;
  }

  if (needEnclosure) {
    if (!ceilingClear) {
      environment.enclosureCost = 10.0;
    } else if (!isPassableForFlying(x, y + 3, z)) {
      environment.enclosureCost = 4.0;
    }
    environment.enclosureCost += static_cast<double>(blockedImmediate) * 2.2;
    if (blockedImmediate >= 3) environment.enclosureCost += 7.5;
    if (blockedImmediate == 4) environment.enclosureCost += 12.0;
    environment.computed |= FLY_ENV_ENCLOSURE;
  }
}

} // namespace v5pf::detail

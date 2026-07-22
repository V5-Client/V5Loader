#pragma once

#include <cmath>

namespace v5pf::detail {

constexpr double groundClearanceCost(const int distance) {
  return static_cast<double>(6 - distance) * 2.0;
}
static_assert(groundClearanceCost(1) == 10.0 && groundClearanceCost(5) == 2.0);

inline double Runtime::flyHeuristic(const int x, const int y, const int z) const {
  const auto& goal = closestFlyGoal(x, y, z);
  const double dx = static_cast<double>(x - goal.x);
  const double dy = static_cast<double>(y - goal.y);
  const double dz = static_cast<double>(z - goal.z);

  double h = std::sqrt(dx * dx + dy * dy + dz * dz) * ActionCosts::FLY_ONE_BLOCK_TIME;
  const double crossProduct = std::abs(
    dx * static_cast<double>(startFly_.z - goal.z) -
    dz * static_cast<double>(startFly_.x - goal.x)
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
  const long long dxStart = static_cast<long long>(x - startFly_.x);
  const long long dzStart = static_cast<long long>(z - startFly_.z);
  const long long dxGoal = static_cast<long long>(x - goal.x);
  const long long dzGoal = static_cast<long long>(z - goal.z);

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

  auto [environmentIt, inserted] = flyEnvironmentCache_.try_emplace(coordKey(destX, destY, destZ));
  auto& environment = environmentIt->second;
  if (inserted) {
    environment.confined = shouldRejectConfined(destX, destY, destZ, 0.0);
    for (int distance = 1; distance <= 5; distance++) {
      if (!isPassableForFlying(destX, destY - distance, destZ)) {
        environment.groundCost = groundClearanceCost(distance);
        break;
      }
    }
    environment.horizontalCost = horizontalClearanceCost(destX, destY, destZ, 0.0);
    environment.enclosureCost = enclosureCost(destX, destY, destZ, 0.0);
  }

  if (progress <= 0.92 && environment.confined) return false;

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

inline bool Runtime::shouldRejectConfined(const int x, const int y, const int z, const double progress) {
  if (progress > 0.92) return false;

  if (!isPassableForFlying(x, y + 2, z)) return true;

  int blockedCardinals = 0;
  int minClearance = 5;
  for (int i = 0; i < 4; i++) {
    bool blocked = false;
    int nx = x;
    int nz = z;
    for (int d = 1; d <= 5; d++) {
      nx += DX[static_cast<size_t>(i)];
      nz += DZ[static_cast<size_t>(i)];
      if (!isFlyColumnClear(nx, y, nz)) {
        const int clearance = d - 1;
        minClearance = std::min(minClearance, clearance);
        blocked = true;
        break;
      }
    }
    if (blocked) blockedCardinals++;
  }

  if (blockedCardinals >= 3) return true;
  return blockedCardinals >= 2 && minClearance <= 1;
}

inline double Runtime::horizontalClearanceCost(const int x, const int y, const int z, const double progress) {
  const double scale = progress > 0.84 ? 0.45 : (progress > 0.72 ? 0.7 : 1.0);

  int minClearance = 5;
  for (int i = 0; i < 4; i++) {
    int nx = x;
    int nz = z;
    for (int d = 1; d <= 5; d++) {
      nx += DX[static_cast<size_t>(i)];
      nz += DZ[static_cast<size_t>(i)];
      if (!isFlyColumnClear(nx, y, nz)) {
        minClearance = std::min(minClearance, d - 1);
        break;
      }
    }
    if (minClearance == 0) {
      return 16.0 * scale;
    }
  }

  double diagonalTouchPenalty = 0.0;
  if (minClearance > 0) {
    for (int i = 4; i < 8; i++) {
      if (!isFlyColumnClear(x + DX[static_cast<size_t>(i)], y, z + DZ[static_cast<size_t>(i)])) {
        diagonalTouchPenalty += 3.5;
      }
    }
  }

  double basePenalty = 0.0;
  switch (minClearance) {
    case 0: basePenalty = 16.0; break;
    case 1: basePenalty = 9.0; break;
    case 2: basePenalty = 4.0; break;
    case 3: basePenalty = 1.5; break;
    default: basePenalty = 0.0; break;
  }

  return (basePenalty + diagonalTouchPenalty) * scale;
}

inline double Runtime::enclosureCost(const int x, const int y, const int z, const double progress) {
  const double scale = progress > 0.84 ? 0.5 : (progress > 0.72 ? 0.75 : 1.0);

  double penalty = 0.0;

  if (!isPassableForFlying(x, y + 2, z)) {
    penalty += 10.0;
  } else if (!isPassableForFlying(x, y + 3, z)) {
    penalty += 4.0;
  }

  int blockedCardinals = 0;
  for (int i = 0; i < 4; i++) {
    if (!isFlyColumnClear(x + DX[static_cast<size_t>(i)], y, z + DZ[static_cast<size_t>(i)])) {
      blockedCardinals++;
    }
  }

  penalty += static_cast<double>(blockedCardinals) * 2.2;
  if (blockedCardinals >= 3) penalty += 7.5;
  if (blockedCardinals == 4) penalty += 12.0;

  return penalty * scale;
}

} // namespace v5pf::detail

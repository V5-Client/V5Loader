#pragma once

#include <cmath>

namespace v5pf::detail {

inline double Runtime::flyHeuristic(const int x, const int y, const int z) const {
  const double dx = static_cast<double>(x - goalFly_.x);
  const double dz = static_cast<double>(z - goalFly_.z);

  const double horizontalDist = std::hypot(dx, dz);
  const double verticalCost = estimateVerticalCost(x, z, y);

  double h = (horizontalDist * ActionCosts::FLY_ONE_BLOCK_TIME) + verticalCost;
  const double crossProduct = std::abs(dx * static_cast<double>(startToGoalDz_) - dz * static_cast<double>(startToGoalDx_));
  h += crossProduct * 0.001;

  return h;
}

inline double Runtime::estimateVerticalCost(const int x, const int z, const int y) const {
  const double progress = calculateProgress(x, z);

  if (progress < 0.3) {
    const double toCruise = y < cruiseY_ ? static_cast<double>(cruiseY_ - y) * 0.5 : 0.0;
    const double cruiseToGoal = cruiseY_ > goalFly_.y ? static_cast<double>(cruiseY_ - goalFly_.y) * 0.3 : 0.0;
    return toCruise + cruiseToGoal;
  }

  if (progress < 0.7) {
    const double deviation = static_cast<double>(std::abs(y - cruiseY_)) * 0.3;
    const double toGoal = cruiseY_ > goalFly_.y
      ? static_cast<double>(cruiseY_ - goalFly_.y) * 0.2
      : static_cast<double>(std::abs(y - goalFly_.y)) * 0.3;
    return deviation + toGoal;
  }

  return static_cast<double>(std::abs(y - goalFly_.y)) * 0.4;
}

inline double Runtime::calculateProgress(const int x, const int z) const {
  const long long dxStart = static_cast<long long>(x - startFly_.x);
  const long long dzStart = static_cast<long long>(z - startFly_.z);
  const long long dxGoal = static_cast<long long>(x - goalFly_.x);
  const long long dzGoal = static_cast<long long>(z - goalFly_.z);

  const long long distFromStartSq = dxStart * dxStart + dzStart * dzStart;
  const long long distToGoalSq = dxGoal * dxGoal + dzGoal * dzGoal;
  const long long totalSq = distFromStartSq + distToGoalSq;

  return totalSq > 0 ? static_cast<double>(distFromStartSq) / static_cast<double>(totalSq) : 0.5;
}

inline bool Runtime::moveFly(const Int3& current, const int dx, const int dy, const int dz, MoveOut& out) {
  const int destX = current.x + dx;
  const int destY = current.y + dy;
  const int destZ = current.z + dz;

  const int flyMinY = world_.minY;
  const int flyMaxY = world_.maxY - 2;
  if (destY < flyMinY || destY > flyMaxY) return false;

  if (!isFlyColumnClear(destX, destY, destZ)) return false;

  if (dy > 0) {
    const int aboveY = current.y + 1;
    if (aboveY < flyMinY || aboveY > flyMaxY) return false;
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

  const long long dxStart = static_cast<long long>(current.x - startFly_.x);
  const long long dzStart = static_cast<long long>(current.z - startFly_.z);
  const long long dxGoal = static_cast<long long>(current.x - goalFly_.x);
  const long long dzGoal = static_cast<long long>(current.z - goalFly_.z);
  const long long distFromStartSq = dxStart * dxStart + dzStart * dzStart;
  const long long distToGoalSq = dxGoal * dxGoal + dzGoal * dzGoal;
  const long long totalSq = distFromStartSq + distToGoalSq;
  const double progress = totalSq > 0 ? static_cast<double>(distFromStartSq) / static_cast<double>(totalSq) : 0.5;

  if (shouldRejectConfined(destX, destY, destZ, progress)) return false;

  if (dy != 0) {
    cost += (diagonalHorizontal || dx != 0 || dz != 0) ? 0.2 : 1.2;

    if (dy > 0) {
      if (destY > cruiseY_ + 2) {
        cost += 7.0 + static_cast<double>(destY - cruiseY_ - 2) * 1.5;
      } else if (progress < 0.25) {
        cost += 0.0;
      } else if (progress < 0.40) {
        cost += 0.8;
      } else if (progress < 0.70) {
        cost += 3.5;
      } else {
        cost += 7.0;
      }
    } else {
      if (progress > 0.7 && destY < goalFly_.y - 1) {
        cost += 2.0 + static_cast<double>(goalFly_.y - 1 - destY);
      } else if (progress < 0.25) {
        cost += 7.0;
      } else if (progress < 0.45) {
        cost += 4.0;
      } else if (progress < 0.70) {
        cost += 2.0;
      } else {
        cost += 0.0;
      }
    }
  } else if (progress > 0.2 && progress < 0.8) {
    cost -= 0.3;
  }

  if (progress < 0.3) {
    if (destY < cruiseY_) {
      cost += static_cast<double>(cruiseY_ - destY) * 0.5;
    }
  } else if (progress < 0.7) {
    const int deviation = destY > cruiseY_ ? destY - cruiseY_ : cruiseY_ - destY;
    cost += static_cast<double>(deviation) * 0.15;
  } else {
    if (destY > cruiseY_ + 2) {
      cost += static_cast<double>(destY - cruiseY_ - 2) * 0.2;
    } else if (destY < goalFly_.y - 2) {
      cost += static_cast<double>(goalFly_.y - 2 - destY) * 0.3;
    }
  }

  if (!isPassableForFlying(destX, destY - 1, destZ)) {
    cost += 10.0;
  } else if (!isPassableForFlying(destX, destY - 2, destZ)) {
    cost += 5.0;
  }

  if (progress <= 0.88) {
    cost += horizontalClearanceCost(destX, destY, destZ, progress);
  }

  if (progress <= 0.94) {
    cost += enclosureCost(destX, destY, destZ, progress);
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

#pragma once

namespace v5pf::detail {

inline bool Runtime::moveTraverse(const int currentX, const int currentY, const int currentZ, const int dx, const int dz, MoveOut& out) {
  const int destX = currentX + dx;
  const int destZ = currentZ + dz;
  if (!isSafe(destX, currentY, destZ)) return false;

  out.pos = {destX, currentY, destZ};
  out.cost = ActionCosts::SPRINT_ONE_BLOCK_TIME +
    pathPenalty(destX, currentY, destZ) +
    fluidPenalty(destX, currentY, destZ);
  return true;
}

inline bool Runtime::moveDiagonal(const int currentX, const int currentY, const int currentZ, const int dx, const int dz, MoveOut& out) {
  const int destX = currentX + dx;
  const int destZ = currentZ + dz;

  if (!isSafe(destX, currentY, destZ)) return false;

  if (!isPassable(destX, currentY, currentZ)) return false;
  if (!isPassable(currentX, currentY, destZ)) return false;
  if (!isPassable(destX, currentY + 1, currentZ)) return false;
  if (!isPassable(currentX, currentY + 1, destZ)) return false;

  out.pos = {destX, currentY, destZ};
  out.cost = ActionCosts::SPRINT_DIAGONAL_TIME +
    pathPenalty(destX, currentY, destZ) +
    fluidPenalty(destX, currentY, destZ);
  return true;
}

inline bool Runtime::moveAscend(const int currentX, const int currentY, const int currentZ, const int dx, const int dz, MoveOut& out) {
  const int destX = currentX + dx;
  const int destZ = currentZ + dz;

  if (!isPassable(currentX, currentY + 2, currentZ)) return false;

  if (!isSolid(destX, currentY, destZ)) return false;
  if (isFenceLike(destX, currentY, destZ)) return false;

  if (!isPassable(destX, currentY + 1, destZ)) return false;
  if (!isPassable(destX, currentY + 2, destZ)) return false;

  const bool srcBottom = isBottomSlab(currentX, currentY - 1, currentZ);
  const bool destBottom = isBottomSlab(destX, currentY, destZ);

  if (srcBottom && !destBottom) return false;

  const bool srcStair = isStairsBottom(currentX, currentY - 1, currentZ);
  const bool destStair = isStairsBottom(destX, currentY, destZ);

  out.pos = {destX, currentY + 1, destZ};
  double baseCost = ActionCosts::JUMP_UP_ONE_BLOCK_TIME;
  if (destBottom || srcStair || destStair) {
    baseCost = ActionCosts::SLAB_ASCENT_TIME;
  }

  out.cost = baseCost +
    pathPenalty(destX, currentY + 1, destZ) +
    fluidPenalty(destX, currentY + 1, destZ);
  return true;
}

inline bool Runtime::moveDescend(const int currentX, const int currentY, const int currentZ, const int dx, const int dz, MoveOut& out) {
  const int destX = currentX + dx;
  const int destZ = currentZ + dz;

  if (!isPassable(destX, currentY + 1, destZ)) return false;
  if (!isPassable(destX, currentY, destZ)) return false;
  if (!isPassable(destX, currentY - 1, destZ)) return false;

  constexpr int maxFallHeight = 20;

  for (int dropBlocks = 1; dropBlocks <= maxFallHeight; dropBlocks++) {
    const int floorY = currentY - dropBlocks - 1;

    if (isPassable(destX, floorY, destZ)) {
      continue;
    }

    if (!isSolid(destX, floorY, destZ)) {
      return false;
    }

    const int destY = floorY + 1;
    if (!isPassable(destX, destY, destZ)) return false;
    if (!isPassable(destX, destY + 1, destZ)) return false;

    double totalCost = ActionCosts::WALK_OFF_EDGE_TIME + costs_.getFallTime(dropBlocks);
    if (dropBlocks > 3) {
      const int excess = dropBlocks - 3;
      totalCost += static_cast<double>(excess * excess) * 2.0;
    }

    totalCost += pathPenalty(destX, destY, destZ) + fluidPenalty(destX, destY, destZ);

    out.pos = {destX, destY, destZ};
    out.cost = totalCost;
    return true;
  }

  return false;
}

} // namespace v5pf::detail

#include "pathfinder_runtime.hpp"

#include <cmath>
#include <vector>

int main() {
  using namespace v5pf;

  WorldState state;
  state.setWorld("fly-cache-test", 0, 32);
  std::vector<uint16_t> voxels(4096, VF_AIR_DEFAULT);
  voxels[(10 << 8) | (8 << 4) | 9] = VF_SOLID | VF_BLOCKING_WALL;
  voxels[(11 << 8) | (8 << 4) | 9] = VF_SOLID | VF_BLOCKING_WALL;
  state.upsertChunk(0, 0, 0, 32, 1, voxels.data(), voxels.size());

  SearchParams params;
  params.isFly = true;
  params.starts = {{1, 10, 1}};
  params.goals = {{14, 10, 14}};

  const auto world = state.snapshot();
  detail::Runtime runtime(world, params);
  detail::MoveOut move;
  if (!runtime.flyMove({7, 10, 8}, {1, 0, 0}, 0.0, move)) return 1;
  if (!runtime.flyMove({8, 10, 7}, {0, 0, 1}, 0.85, move)) return 1;
  if (std::abs(move.cost - (ActionCosts::FLY_ONE_BLOCK_TIME + 7.2 + 1.1)) >= 1e-12) return 1;
  if (!runtime.flyMove({8, 10, 7}, {0, 0, 1}, 0.93, move)) return 1;
  if (std::abs(move.cost - (ActionCosts::FLY_ONE_BLOCK_TIME + 1.1)) >= 1e-12) return 1;
}

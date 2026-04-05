#pragma once

#include "world_state.hpp"

#include <atomic>
#include <optional>

namespace v5pf {

struct EtherwarpSearchParams {
  Int3 start{0, 0, 0};
  Int3 goal{0, 0, 0};
  int maxIterations = 100000;
  int threadCount = 1;
  double yawStep = 5.0;
  double pitchStep = 5.0;
  double newNodeCost = 1.0;
  double heuristicWeight = 1.0;
  double rayLength = 61.0;
  double rewireEpsilon = 1.0;
  double eyeHeight = 2.59;
};

struct EtherwarpSearchResult {
  std::vector<Int3> points;
  std::vector<float> angles;
  long long timeMs = 0;
  int nodesExplored = 0;
  double nanosecondsPerNode = 0.0;
};

std::optional<EtherwarpSearchResult> findEtherwarpPath(
  const WorldSnapshot& world,
  const EtherwarpSearchParams& params,
  std::atomic_bool& cancelFlag
);

} // namespace v5pf

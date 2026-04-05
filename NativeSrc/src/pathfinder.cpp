#include "pathfinder.hpp"

#include "path_annotations.hpp"
#include "path_simplifier.hpp"
#include "path_signature.hpp"
#include "pathfinder_heap.hpp"
#include "pathfinder_runtime.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <limits>
#include <mutex>
#include <thread>
#include <unordered_map>

namespace v5pf {

namespace {

bool isCancelled(const std::atomic_bool& cancelFlag, const std::atomic_bool* localCancelFlag) {
  return cancelFlag.load() || (localCancelFlag != nullptr && localCancelFlag->load());
}

std::optional<SearchResult> findPathSingle(
  const WorldSnapshot& world,
  const SearchParams& params,
  std::atomic_bool& cancelFlag,
  const std::atomic_bool* localCancelFlag
) {
  if (params.starts.empty() || params.goals.empty()) {
    return std::nullopt;
  }

  if (params.maxIterations <= 0) {
    return std::nullopt;
  }

  if (params.isFly && params.starts.size() != 1) {
    return std::nullopt;
  }

  const auto startTime = std::chrono::steady_clock::now();
  detail::Runtime runtime(world, params);

  const int reserveTarget = std::clamp(params.maxIterations / 2, 16384, 262144);
  const size_t reserveSize = static_cast<size_t>(reserveTarget);

  std::vector<int> nodeX;
  std::vector<int> nodeY;
  std::vector<int> nodeZ;
  std::vector<double> nodeG;
  std::vector<double> nodeH;
  std::vector<double> nodeF;
  std::vector<int> nodeParent;
  std::vector<int> nodeStartIndex;
  std::vector<int> nodeHeapPos;

  nodeX.reserve(reserveSize);
  nodeY.reserve(reserveSize);
  nodeZ.reserve(reserveSize);
  nodeG.reserve(reserveSize);
  nodeH.reserve(reserveSize);
  nodeF.reserve(reserveSize);
  nodeParent.reserve(reserveSize);
  nodeStartIndex.reserve(reserveSize);
  nodeHeapPos.reserve(reserveSize);

  std::unordered_map<uint64_t, int> coordToNode;
  coordToNode.reserve(reserveSize);

  auto createNode = [&](const int x, const int y, const int z) {
    const int idx = static_cast<int>(nodeX.size());
    nodeX.push_back(x);
    nodeY.push_back(y);
    nodeZ.push_back(z);
    nodeG.push_back(std::numeric_limits<double>::infinity());
    nodeH.push_back(runtime.heuristic(x, y, z));
    nodeF.push_back(std::numeric_limits<double>::infinity());
    nodeParent.push_back(-1);
    nodeStartIndex.push_back(-1);
    nodeHeapPos.push_back(-1);
    coordToNode.emplace(coordKey(x, y, z), idx);
    return idx;
  };

  detail::Heap heap(nodeF, nodeHeapPos);
  heap.reserve(reserveTarget);

  const double weight = (std::isfinite(params.heuristicWeight) && params.heuristicWeight > 0.0)
    ? params.heuristicWeight
    : 1.0;
  const bool isFly = params.isFly;

  std::array<Int3, 16> walkMovesOrdered = detail::WALK_MOVES;
  const int walkOffset = params.moveOrderOffset >= 0 ? (params.moveOrderOffset % static_cast<int>(walkMovesOrdered.size())) : 0;
  if (walkOffset != 0) {
    for (int i = 0; i < static_cast<int>(walkMovesOrdered.size()); i++) {
      walkMovesOrdered[static_cast<size_t>(i)] =
        detail::WALK_MOVES[static_cast<size_t>((i + walkOffset) % static_cast<int>(walkMovesOrdered.size()))];
    }
  }

  for (size_t i = 0; i < params.starts.size(); i++) {
    const auto& start = params.starts[i];
    const double startPenalty = i == 0 ? 0.0 : std::max(0.0, params.nonPrimaryStartPenalty);

    const uint64_t key = coordKey(start.x, start.y, start.z);
    int nodeIdx = -1;
    const auto it = coordToNode.find(key);
    if (it == coordToNode.end()) {
      nodeIdx = createNode(start.x, start.y, start.z);
    } else {
      nodeIdx = it->second;
    }

    if (startPenalty < nodeG[static_cast<size_t>(nodeIdx)]) {
      nodeParent[static_cast<size_t>(nodeIdx)] = -1;
      nodeStartIndex[static_cast<size_t>(nodeIdx)] = static_cast<int>(i);
      nodeG[static_cast<size_t>(nodeIdx)] = startPenalty;
      nodeF[static_cast<size_t>(nodeIdx)] = startPenalty + nodeH[static_cast<size_t>(nodeIdx)] * weight;
    }

    if (nodeHeapPos[static_cast<size_t>(nodeIdx)] == -1) {
      heap.add(nodeIdx);
    } else {
      heap.relocate(nodeIdx);
    }
  }

  int iterations = 0;
  while (!heap.empty() && iterations < params.maxIterations) {
    if (isCancelled(cancelFlag, localCancelFlag)) {
      return std::nullopt;
    }

    iterations++;

    const int currIdx = heap.poll();
    if (currIdx < 0) break;

    const Int3 curr{nodeX[static_cast<size_t>(currIdx)], nodeY[static_cast<size_t>(currIdx)], nodeZ[static_cast<size_t>(currIdx)]};

    if (runtime.isAtGoal(curr.x, curr.y, curr.z)) {
      std::vector<Int3> path;
      int walk = currIdx;
      while (walk != -1) {
        path.push_back(Int3{nodeX[static_cast<size_t>(walk)], nodeY[static_cast<size_t>(walk)], nodeZ[static_cast<size_t>(walk)]});
        walk = nodeParent[static_cast<size_t>(walk)];
      }
      std::reverse(path.begin(), path.end());
      auto keyPoints = extractKeyPoints(world, path, params.isFly);
      auto pathFlags = encodeNodeFlags(world, path, params.isFly, false);
      auto keyNodeFlags = encodeNodeFlags(world, keyPoints, params.isFly, true);
      auto keyNodeMetrics = encodeKeyMetrics(world, keyPoints);
      auto signatureHex = buildPathSignatureHex(path);

      const auto elapsedNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - startTime
      ).count();

      SearchResult result;
      result.points = std::move(path);
      result.keyPoints = std::move(keyPoints);
      result.pathFlags = std::move(pathFlags);
      result.keyNodeFlags = std::move(keyNodeFlags);
      result.keyNodeMetrics = std::move(keyNodeMetrics);
      result.signatureHex = std::move(signatureHex);
      result.timeMs = elapsedNs / 1000000LL;
      result.nodesExplored = iterations;
      result.nanosecondsPerNode = iterations > 0
        ? static_cast<double>(elapsedNs) / static_cast<double>(iterations)
        : 0.0;
      result.selectedStartIndex = nodeStartIndex[static_cast<size_t>(currIdx)];
      return result;
    }

    const double currCost = nodeG[static_cast<size_t>(currIdx)];

    const int currStartIdx = nodeStartIndex[static_cast<size_t>(currIdx)];

    if (isFly) {
      const double currFlyProgress = runtime.flyHorizontalProgress(curr.x, curr.z);
      for (const auto& move : detail::FLY_MOVES) {
        detail::MoveOut out;
        if (!runtime.flyMove(curr, move, currFlyProgress, out)) continue;
        if (out.cost >= ActionCosts::INF_COST) continue;

        const double newCost = currCost + out.cost + runtime.transientAvoidPenalty(out.pos.x, out.pos.y, out.pos.z);
        const uint64_t key = coordKey(out.pos.x, out.pos.y, out.pos.z);

        int nIdx = -1;
        const auto it = coordToNode.find(key);
        if (it == coordToNode.end()) {
          nIdx = createNode(out.pos.x, out.pos.y, out.pos.z);
          nodeParent[static_cast<size_t>(nIdx)] = currIdx;
          nodeStartIndex[static_cast<size_t>(nIdx)] = currStartIdx;
          nodeG[static_cast<size_t>(nIdx)] = newCost;
          nodeF[static_cast<size_t>(nIdx)] = newCost + nodeH[static_cast<size_t>(nIdx)] * weight;
          heap.add(nIdx);
        } else {
          nIdx = it->second;
          if (newCost < nodeG[static_cast<size_t>(nIdx)]) {
            nodeParent[static_cast<size_t>(nIdx)] = currIdx;
            nodeStartIndex[static_cast<size_t>(nIdx)] = currStartIdx;
            nodeG[static_cast<size_t>(nIdx)] = newCost;
            nodeF[static_cast<size_t>(nIdx)] = newCost + nodeH[static_cast<size_t>(nIdx)] * weight;

            if (nodeHeapPos[static_cast<size_t>(nIdx)] != -1) {
              heap.relocate(nIdx);
            } else {
              heap.add(nIdx);
            }
          }
        }
      }
      continue;
    }

    for (const auto& move : walkMovesOrdered) {
      detail::MoveOut out;
      if (!runtime.walkMove(curr, move, out)) continue;
      if (out.cost >= ActionCosts::INF_COST) continue;

      const double newCost = currCost + out.cost + runtime.transientAvoidPenalty(out.pos.x, out.pos.y, out.pos.z);
      const uint64_t key = coordKey(out.pos.x, out.pos.y, out.pos.z);

      int nIdx = -1;
      const auto it = coordToNode.find(key);
      if (it == coordToNode.end()) {
        nIdx = createNode(out.pos.x, out.pos.y, out.pos.z);
        nodeParent[static_cast<size_t>(nIdx)] = currIdx;
        nodeStartIndex[static_cast<size_t>(nIdx)] = currStartIdx;
        nodeG[static_cast<size_t>(nIdx)] = newCost;
        nodeF[static_cast<size_t>(nIdx)] = newCost + nodeH[static_cast<size_t>(nIdx)] * weight;
        heap.add(nIdx);
      } else {
        nIdx = it->second;
        if (newCost < nodeG[static_cast<size_t>(nIdx)]) {
          nodeParent[static_cast<size_t>(nIdx)] = currIdx;
          nodeStartIndex[static_cast<size_t>(nIdx)] = currStartIdx;
          nodeG[static_cast<size_t>(nIdx)] = newCost;
          nodeF[static_cast<size_t>(nIdx)] = newCost + nodeH[static_cast<size_t>(nIdx)] * weight;

          if (nodeHeapPos[static_cast<size_t>(nIdx)] != -1) {
            heap.relocate(nIdx);
          } else {
            heap.add(nIdx);
          }
        }
      }
    }
  }

  return std::nullopt;
}

} // namespace

std::optional<SearchResult> findPath(
  const WorldSnapshot& world,
  const SearchParams& params,
  std::atomic_bool& cancelFlag
) {
  cancelFlag.store(false);

  if (!params.isFly && params.starts.size() > 1) {
    const size_t startCount = params.starts.size();
    const unsigned int hwThreads = std::max(1u, std::thread::hardware_concurrency());
    const size_t workerCount = std::min(startCount, static_cast<size_t>(hwThreads));

    std::atomic_size_t nextStartIndex{0};
    std::atomic_bool localCancelFlag{false};
    std::mutex resultMutex;
    std::optional<SearchResult> winner;

    auto worker = [&]() {
      while (!isCancelled(cancelFlag, &localCancelFlag)) {
        const size_t startIdx = nextStartIndex.fetch_add(1);
        if (startIdx >= startCount) {
          return;
        }

        SearchParams workerParams = params;
        workerParams.starts = {params.starts[startIdx]};
        workerParams.nonPrimaryStartPenalty = 0.0;

        auto result = findPathSingle(world, workerParams, cancelFlag, &localCancelFlag);
        if (!result.has_value()) {
          continue;
        }

        result->selectedStartIndex = static_cast<int>(startIdx);

        bool expected = false;
        if (!localCancelFlag.compare_exchange_strong(expected, true)) {
          return;
        }

        std::lock_guard lock(resultMutex);
        winner = std::move(result);
        return;
      }
    };

    std::vector<std::thread> workers;
    workers.reserve(workerCount > 0 ? workerCount - 1 : 0);
    for (size_t i = 1; i < workerCount; i++) {
      workers.emplace_back(worker);
    }

    worker();

    for (auto& thread : workers) {
      if (thread.joinable()) {
        thread.join();
      }
    }

    if (winner.has_value()) {
      return winner;
    }

    return std::nullopt;
  }

  return findPathSingle(world, params, cancelFlag, nullptr);
}

} // namespace v5pf

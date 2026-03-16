#include "pathfinder.hpp"

#include "path_annotations.hpp"
#include "path_simplifier.hpp"
#include "path_signature.hpp"
#include "pathfinder_heap.hpp"
#include "pathfinder_runtime.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
#include <unordered_map>

namespace v5pf {

std::optional<SearchResult> findPath(
  const WorldSnapshot& world,
  const SearchParams& params,
  std::atomic_bool& cancelFlag
) {
  cancelFlag.store(false);

  if (params.starts.empty() || params.goals.empty()) {
    return std::nullopt;
  }

  if (params.maxIterations <= 0) {
    return std::nullopt;
  }

  if (params.isFly && params.starts.size() != 1) {
    return std::nullopt;
  }

  detail::Runtime runtime(world, params);

  std::vector<int> nodeX;
  std::vector<int> nodeY;
  std::vector<int> nodeZ;
  std::vector<double> nodeG;
  std::vector<double> nodeH;
  std::vector<double> nodeF;
  std::vector<int> nodeParent;
  std::vector<int> nodeStartIndex;
  std::vector<int> nodeHeapPos;

  nodeX.reserve(16384);
  nodeY.reserve(16384);
  nodeZ.reserve(16384);
  nodeG.reserve(16384);
  nodeH.reserve(16384);
  nodeF.reserve(16384);
  nodeParent.reserve(16384);
  nodeStartIndex.reserve(16384);
  nodeHeapPos.reserve(16384);

  std::unordered_map<uint64_t, int> coordToNode;
  coordToNode.reserve(16384);

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

  const double weight = (std::isfinite(params.heuristicWeight) && params.heuristicWeight > 0.0)
    ? params.heuristicWeight
    : 1.0;

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
  const auto startTime = std::chrono::steady_clock::now();

  while (!heap.empty() && iterations < params.maxIterations) {
    if (cancelFlag.load()) {
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

      const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - startTime
      ).count();

      SearchResult result;
      result.points = std::move(path);
      result.keyPoints = extractKeyPoints(world, result.points, params.isFly);
      result.pathFlags = encodeNodeFlags(world, result.points, params.isFly, false);
      result.keyNodeFlags = encodeNodeFlags(world, result.keyPoints, params.isFly, true);
      result.keyNodeMetrics = encodeKeyMetrics(world, result.keyPoints);
      result.signatureHex = buildPathSignatureHex(result.points);
      result.timeMs = elapsed;
      result.nodesExplored = iterations;
      result.selectedStartIndex = nodeStartIndex[static_cast<size_t>(currIdx)];
      return result;
    }

    const double currCost = nodeG[static_cast<size_t>(currIdx)];

    if (params.isFly) {
      for (const auto& move : detail::FLY_MOVES) {
        detail::MoveOut out;
        if (!runtime.flyMove(curr, move, out)) continue;
        if (out.cost >= ActionCosts::INF_COST) continue;

        const double newCost = currCost + out.cost + runtime.transientAvoidPenalty(out.pos.x, out.pos.y, out.pos.z);
        const uint64_t key = coordKey(out.pos.x, out.pos.y, out.pos.z);

        int nIdx = -1;
        const auto it = coordToNode.find(key);
        if (it == coordToNode.end()) {
          nIdx = createNode(out.pos.x, out.pos.y, out.pos.z);
          nodeParent[static_cast<size_t>(nIdx)] = currIdx;
          nodeStartIndex[static_cast<size_t>(nIdx)] = nodeStartIndex[static_cast<size_t>(currIdx)];
          nodeG[static_cast<size_t>(nIdx)] = newCost;
          nodeF[static_cast<size_t>(nIdx)] = newCost + nodeH[static_cast<size_t>(nIdx)] * weight;
          heap.add(nIdx);
        } else {
          nIdx = it->second;
          if (newCost < nodeG[static_cast<size_t>(nIdx)]) {
            nodeParent[static_cast<size_t>(nIdx)] = currIdx;
            nodeStartIndex[static_cast<size_t>(nIdx)] = nodeStartIndex[static_cast<size_t>(currIdx)];
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

    const int offset = params.moveOrderOffset >= 0 ? (params.moveOrderOffset % static_cast<int>(detail::WALK_MOVES.size())) : 0;
    for (int i = 0; i < static_cast<int>(detail::WALK_MOVES.size()); i++) {
      const auto& move = detail::WALK_MOVES[static_cast<size_t>((i + offset) % static_cast<int>(detail::WALK_MOVES.size()))];

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
        nodeStartIndex[static_cast<size_t>(nIdx)] = nodeStartIndex[static_cast<size_t>(currIdx)];
        nodeG[static_cast<size_t>(nIdx)] = newCost;
        nodeF[static_cast<size_t>(nIdx)] = newCost + nodeH[static_cast<size_t>(nIdx)] * weight;
        heap.add(nIdx);
      } else {
        nIdx = it->second;
        if (newCost < nodeG[static_cast<size_t>(nIdx)]) {
          nodeParent[static_cast<size_t>(nIdx)] = currIdx;
          nodeStartIndex[static_cast<size_t>(nIdx)] = nodeStartIndex[static_cast<size_t>(currIdx)];
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

} // namespace v5pf

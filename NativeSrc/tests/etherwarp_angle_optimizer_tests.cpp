#include "common.hpp"
#include "etherwarp_raymarch.hpp"
#include "etherwarp_search.hpp"
#include "path_voxel_checks.hpp"
#include "world_state.hpp"

#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <memory>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace v5pf {
namespace testhooks {

std::optional<EtherwarpRayDirection> resolveStableAimDirectionForTest(
  const WorldSnapshot& world,
  const Int3& from,
  const Int3& to,
  double eyeHeight,
  double rayLength
);

std::optional<EtherwarpRayDirection> optimizeAimDirectionForHopForTest(
  const WorldSnapshot& world,
  const Int3& from,
  const Int3& to,
  double eyeHeight,
  double rayLength
);

double measureSquareMarginForTest(
  const WorldSnapshot& world,
  const Int3& from,
  const Int3& to,
  double eyeHeight,
  double rayLength,
  const EtherwarpRayDirection& direction
);

} // namespace testhooks
} // namespace v5pf

namespace {

using v5pf::ChunkData;
using v5pf::EtherwarpRayDirection;
using v5pf::EtherwarpSearchParams;
using v5pf::EtherwarpSearchResult;
using v5pf::Int3;
using v5pf::WorldData;
using v5pf::WorldSnapshot;

constexpr double kEyeHeight = 2.59;
constexpr double kRayLength = 61.0;
constexpr double kAngleEpsilon = 1e-4;

[[noreturn]] void fail(const std::string& message) {
  throw std::runtime_error(message);
}

void expect(bool condition, const std::string& message) {
  if (!condition) {
    fail(message);
  }
}

void expectNear(double actual, double expected, double epsilon, const std::string& message) {
  if (std::abs(actual - expected) > epsilon) {
    std::ostringstream out;
    out << message << " expected=" << expected << " actual=" << actual;
    fail(out.str());
  }
}

std::string pointString(const Int3& point) {
  std::ostringstream out;
  out << "(" << point.x << ", " << point.y << ", " << point.z << ")";
  return out.str();
}

EtherwarpRayDirection makeDirectionFromAngles(const float yaw, const float pitch) {
  constexpr double kDegToRad = 3.14159265358979323846 / 180.0;
  const double yawRad = static_cast<double>(yaw) * kDegToRad;
  const double pitchRad = static_cast<double>(pitch) * kDegToRad;
  const double cosPitch = std::cos(pitchRad);

  EtherwarpRayDirection direction;
  direction.yaw = yaw;
  direction.pitch = pitch;
  direction.dirX = -std::sin(yawRad) * cosPitch;
  direction.dirY = -std::sin(pitchRad);
  direction.dirZ = std::cos(yawRad) * cosPitch;
  return direction;
}

class TestWorldBuilder {
 public:
  TestWorldBuilder(int minY, int maxY)
    : minY_(minY), maxY_(maxY) {}

  void setFlags(const int x, const int y, const int z, const uint16_t flags) {
    ensureChunk(x >> 4, z >> 4)->setFlags(x & 15, y, z & 15, flags);
  }

  void setLandingBlock(const int x, const int y, const int z, const uint16_t supportFlags = v5pf::VF_SOLID) {
    setFlags(x, y, z, supportFlags);
    setFlags(x, y + 1, z, v5pf::VF_AIR_DEFAULT);
    setFlags(x, y + 2, z, v5pf::VF_AIR_DEFAULT);
  }

  void setSolidColumn(const int x, const int minY, const int maxY, const int z, const uint16_t flags = v5pf::VF_SOLID) {
    for (int y = minY; y <= maxY; y++) {
      setFlags(x, y, z, flags);
    }
  }

  WorldSnapshot snapshot() const {
    auto data = std::make_shared<WorldData>();
    data->worldKey = "test_world";
    data->minY = minY_;
    data->maxY = maxY_;
    for (const auto& entry : chunks_) {
      data->chunks.emplace(entry.first, std::make_shared<ChunkData>(*entry.second));
    }

    WorldSnapshot snapshot;
    snapshot.data = std::move(data);
    snapshot.worldKey = snapshot.data->worldKey;
    snapshot.minY = minY_;
    snapshot.maxY = maxY_;
    return snapshot;
  }

 private:
  std::shared_ptr<ChunkData> ensureChunk(const int chunkX, const int chunkZ) {
    const auto key = v5pf::chunkKey(chunkX, chunkZ);
    const auto it = chunks_.find(key);
    if (it != chunks_.end()) {
      return it->second;
    }

    auto chunk = std::make_shared<ChunkData>();
    chunk->minY = minY_;
    chunk->maxY = maxY_;
    chunk->ensureLayout();

    std::array<uint16_t, 4096> airSection{};
    airSection.fill(v5pf::VF_AIR_DEFAULT);
    for (int sectionIdx = 0; sectionIdx < chunk->sectionCount(); sectionIdx++) {
      chunk->assignSection(sectionIdx, airSection.data());
    }

    chunks_.emplace(key, chunk);
    return chunk;
  }

  int minY_;
  int maxY_;
  std::unordered_map<int64_t, std::shared_ptr<ChunkData>> chunks_;
};

bool hopHitsTarget(
  const WorldSnapshot& world,
  const Int3& from,
  const Int3& to,
  const float yaw,
  const float pitch,
  const double eyeHeight = kEyeHeight,
  const double rayLength = kRayLength
) {
  const uint16_t fromSupportFlags = world.getFlags(from.x, from.y, from.z);
  const double originX = static_cast<double>(from.x) + 0.5;
  const double originY = static_cast<double>(from.y) + v5pf::etherwarpEyeYOffset(fromSupportFlags, eyeHeight);
  const double originZ = static_cast<double>(from.z) + 0.5;
  const auto direction = makeDirectionFromAngles(yaw, pitch);
  const auto hit = v5pf::raymarchEtherwarp(world, originX, originY, originZ, direction, rayLength);
  return hit.has_value() && hit->x == to.x && hit->y == to.y && hit->z == to.z;
}

void expectRouteAnglesHitTargets(const WorldSnapshot& world, const EtherwarpSearchResult& result) {
  expect(result.points.size() * 2 == result.angles.size(), "route angles must match point count");
  for (size_t i = 1; i < result.points.size(); i++) {
    const float yaw = result.angles[i * 2];
    const float pitch = result.angles[i * 2 + 1];
    expect(std::isfinite(yaw) && std::isfinite(pitch), "route angle must be finite");
    expect(
      hopHitsTarget(world, result.points[i - 1], result.points[i], yaw, pitch),
      "stored route angle must hit the intended block"
    );
  }
}

EtherwarpSearchResult requirePath(
  const WorldSnapshot& world,
  const Int3& start,
  const Int3& goal,
  const int maxIterations = 25000
) {
  EtherwarpSearchParams params;
  params.start = start;
  params.goal = goal;
  params.maxIterations = maxIterations;
  params.threadCount = 1;
  params.yawStep = 5.0;
  params.pitchStep = 5.0;
  params.newNodeCost = 1.0;
  params.heuristicWeight = 1.0;
  params.rayLength = kRayLength;
  params.rewireEpsilon = 1.0;
  params.eyeHeight = kEyeHeight;

  std::atomic_bool cancelFlag{false};
  const auto result = v5pf::findEtherwarpPath(world, params, cancelFlag);
  expect(result.has_value(), "expected etherwarp path");
  return *result;
}

void testDirectUnobstructedHop() {
  TestWorldBuilder builder(0, 16);
  const Int3 start{1, 0, 1};
  const Int3 goal{8, 0, 8};
  builder.setLandingBlock(start.x, start.y, start.z);
  builder.setLandingBlock(goal.x, goal.y, goal.z);
  const auto world = builder.snapshot();

  const auto baseline = v5pf::testhooks::resolveStableAimDirectionForTest(world, start, goal, kEyeHeight, kRayLength);
  const auto optimized = v5pf::testhooks::optimizeAimDirectionForHopForTest(world, start, goal, kEyeHeight, kRayLength);
  expect(baseline.has_value(), "baseline direct hop angle should exist");
  expect(optimized.has_value(), "optimized direct hop angle should exist");

  const double baselineMargin = v5pf::testhooks::measureSquareMarginForTest(world, start, goal, kEyeHeight, kRayLength, *baseline);
  const double optimizedMargin = v5pf::testhooks::measureSquareMarginForTest(world, start, goal, kEyeHeight, kRayLength, *optimized);
  expect(optimizedMargin + kAngleEpsilon >= baselineMargin, "optimized margin should not regress");

  const auto path = requirePath(world, start, goal);
  expect(path.points.size() == 2, "direct unobstructed hop should stay direct");
  expectRouteAnglesHitTargets(world, path);
}

void testEdgeBiasedLandingImprovesMargin() {
  TestWorldBuilder builder(0, 16);
  const Int3 start{1, 0, 8};
  const Int3 goal{8, 0, 8};
  builder.setLandingBlock(start.x, start.y, start.z);
  builder.setLandingBlock(goal.x, goal.y, goal.z);
  builder.setSolidColumn(7, 1, 2, 7);
  builder.setSolidColumn(8, 1, 2, 7);
  const auto world = builder.snapshot();

  const auto baseline = v5pf::testhooks::resolveStableAimDirectionForTest(world, start, goal, kEyeHeight, kRayLength);
  const auto optimized = v5pf::testhooks::optimizeAimDirectionForHopForTest(world, start, goal, kEyeHeight, kRayLength);
  expect(baseline.has_value(), "baseline edge-biased angle should exist");
  expect(optimized.has_value(), "optimized edge-biased angle should exist");

  const double baselineMargin = v5pf::testhooks::measureSquareMarginForTest(world, start, goal, kEyeHeight, kRayLength, *baseline);
  const double optimizedMargin = v5pf::testhooks::measureSquareMarginForTest(world, start, goal, kEyeHeight, kRayLength, *optimized);
  expect(optimizedMargin > baselineMargin + 0.01, "optimizer should widen the safe margin on edge-biased landings");
  expect(hopHitsTarget(world, start, goal, optimized->yaw, optimized->pitch), "optimized edge-biased angle must hit target");
}

void testSimplifiedMultiHopRouteOptimizesFinalAngles() {
  TestWorldBuilder builder(0, 16);
  const Int3 start{1, 0, 1};
  const Int3 goal{8, 0, 8};
  builder.setLandingBlock(start.x, start.y, start.z);
  builder.setLandingBlock(1, 0, 8);
  builder.setLandingBlock(8, 0, 1);
  builder.setLandingBlock(goal.x, goal.y, goal.z);
  for (int z = 1; z <= 7; z++) {
    builder.setSolidColumn(4, 1, 15, z);
  }
  const auto world = builder.snapshot();

  const auto path = requirePath(world, start, goal, 60000);
  expect(path.points.size() > 2, "multi-hop fixture should keep at least one intermediate hop");
  expectRouteAnglesHitTargets(world, path);

  for (size_t i = 1; i < path.points.size(); i++) {
    const auto optimized = v5pf::testhooks::optimizeAimDirectionForHopForTest(
      world,
      path.points[i - 1],
      path.points[i],
      kEyeHeight,
      kRayLength
    );
    expect(
      optimized.has_value(),
      "optimized hop angle should exist for " + pointString(path.points[i - 1]) + " -> " + pointString(path.points[i])
    );
    expectNear(path.angles[i * 2], optimized->yaw, kAngleEpsilon, "route yaw should use optimized angle");
    expectNear(path.angles[i * 2 + 1], optimized->pitch, kAngleEpsilon, "route pitch should use optimized angle");
  }
}

void testNoImprovementKeepsBaselineAngle() {
  TestWorldBuilder builder(0, 16);
  const Int3 start{1, 0, 1};
  const Int3 goal{2, 0, 1};
  builder.setLandingBlock(start.x, start.y, start.z);
  builder.setLandingBlock(goal.x, goal.y, goal.z);
  const auto world = builder.snapshot();

  const auto baseline = v5pf::testhooks::resolveStableAimDirectionForTest(world, start, goal, kEyeHeight, kRayLength);
  const auto optimized = v5pf::testhooks::optimizeAimDirectionForHopForTest(world, start, goal, kEyeHeight, kRayLength);
  expect(baseline.has_value(), "baseline fallback angle should exist");
  expect(optimized.has_value(), "optimized fallback angle should exist");

  const double baselineMargin = v5pf::testhooks::measureSquareMarginForTest(world, start, goal, kEyeHeight, kRayLength, *baseline);
  const double optimizedMargin = v5pf::testhooks::measureSquareMarginForTest(world, start, goal, kEyeHeight, kRayLength, *optimized);
  expectNear(optimizedMargin, baselineMargin, kAngleEpsilon, "unobstructed path should keep the same margin");
  expectNear(optimized->yaw, baseline->yaw, kAngleEpsilon, "baseline yaw should be preserved when there is no improvement");
  expectNear(optimized->pitch, baseline->pitch, kAngleEpsilon, "baseline pitch should be preserved when there is no improvement");
}

void testFenceLikeLandingOffset() {
  TestWorldBuilder builder(0, 16);
  const Int3 start{1, 0, 3};
  const Int3 goal{8, 0, 3};
  builder.setLandingBlock(start.x, start.y, start.z);
  builder.setLandingBlock(goal.x, goal.y, goal.z, v5pf::VF_SOLID | v5pf::VF_FENCE_LIKE);
  const auto world = builder.snapshot();

  expect(v5pf::isEtherwarpLandingBlockVoxel(world, goal.x, goal.y, goal.z), "fence-like goal must be a valid landing block");
  const auto path = requirePath(world, start, goal);
  expect(path.points.size() == 2, "fence-like fixture should stay direct");
  expectRouteAnglesHitTargets(world, path);
  const auto finalDirection = makeDirectionFromAngles(path.angles[2], path.angles[3]);
  const double finalMargin = v5pf::testhooks::measureSquareMarginForTest(world, start, goal, kEyeHeight, kRayLength, finalDirection);
  expect(finalMargin >= 0.0, "fence-like final angle should be measurable");
}

void testDiagonalTieTraversalMatchesReferenceHelpers() {
  TestWorldBuilder builder(0, 16);
  builder.setLandingBlock(0, 3, 1);
  builder.setLandingBlock(1, 3, 1);
  const auto world = builder.snapshot();

  EtherwarpRayDirection direction{};
  direction.dirX = 1.0 / 3.0;
  direction.dirY = -1.0 / 12.0;
  direction.dirZ = 1.0 / 3.0;

  const auto hit = v5pf::raymarchEtherwarp(world, 0.5, 3.5, 0.5, direction, kRayLength);
  expect(hit.has_value(), "diagonal tie traversal should hit a landing block");
  expect(hit->x == 0 && hit->y == 3 && hit->z == 1, "diagonal tie traversal should prefer the Z step like RSA/Noamm");
}

int runAllTests() {
  testDirectUnobstructedHop();
  testEdgeBiasedLandingImprovesMargin();
  testSimplifiedMultiHopRouteOptimizesFinalAngles();
  testNoImprovementKeepsBaselineAngle();
  testFenceLikeLandingOffset();
  testDiagonalTieTraversalMatchesReferenceHelpers();
  return 0;
}

} // namespace

int main() {
  try {
    return runAllTests();
  } catch (const std::exception& e) {
    std::cerr << "V5PathTests failed: " << e.what() << '\n';
    return 1;
  }
}

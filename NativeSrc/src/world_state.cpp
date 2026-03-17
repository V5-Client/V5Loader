#include "world_state.hpp"

#include <algorithm>
#include <cstddef>

namespace v5pf {

void ChunkData::ensureLayout() {
  const int count = sectionCount();
  if (count <= 0) {
    sections.clear();
    return;
  }

  if (static_cast<int>(sections.size()) != count) {
    sections.resize(static_cast<size_t>(count));
  }
}

uint16_t ChunkData::getFlags(const int localX, const int y, const int localZ) const {
  if (y < minY || y >= maxY) return VF_AIR_DEFAULT;

  const int sectionIdx = (y - minY) >> 4;
  if (sectionIdx < 0 || sectionIdx >= static_cast<int>(sections.size())) return VF_AIR_DEFAULT;

  const auto& section = sections[static_cast<size_t>(sectionIdx)];
  if (section.empty()) return VF_AIR_DEFAULT;

  const int index = ((y & 15) << 8) | ((localZ & 15) << 4) | (localX & 15);
  return section[static_cast<size_t>(index)];
}

void ChunkData::setFlags(const int localX, const int y, const int localZ, const uint16_t flags) {
  if (y < minY || y >= maxY) return;

  ensureLayout();

  const int sectionIdx = (y - minY) >> 4;
  if (sectionIdx < 0 || sectionIdx >= static_cast<int>(sections.size())) return;

  auto& section = sections[static_cast<size_t>(sectionIdx)];
  if (section.empty()) {
    section.assign(4096, VF_AIR_DEFAULT);
  }

  const int index = ((y & 15) << 8) | ((localZ & 15) << 4) | (localX & 15);
  section[static_cast<size_t>(index)] = flags;
}

uint16_t WorldSnapshot::getFlags(const int x, const int y, const int z) const {
  if (y < minY || y >= maxY) return VF_AIR_DEFAULT;

  const int chunkX = x >> 4;
  const int chunkZ = z >> 4;
  const auto it = chunks.find(chunkKey(chunkX, chunkZ));
  if (it == chunks.end()) {
    return VF_SOLID | VF_BLOCKING_WALL;
  }

  return it->second.getFlags(x & 15, y, z & 15);
}

void WorldState::setWorld(std::string worldKey, const int minY, const int maxY) {
  std::lock_guard lock(mutex_);
  worldKey_ = std::move(worldKey);
  minY_ = minY;
  maxY_ = maxY;
  chunks_.clear();
}

void WorldState::clear() {
  std::lock_guard lock(mutex_);
  chunks_.clear();
}

void WorldState::upsertChunk(
  const int chunkX,
  const int chunkZ,
  const int minY,
  const int maxY,
  const uint64_t sectionMask,
  const std::vector<uint16_t>& sectionFlags
) {
  ChunkData chunk;
  chunk.minY = minY;
  chunk.maxY = maxY;
  chunk.ensureLayout();

  const int sectionCount = chunk.sectionCount();
  size_t readOffset = 0;

  for (int i = 0; i < sectionCount; i++) {
    const bool hasSection = (sectionMask & (1ULL << i)) != 0ULL;
    if (!hasSection) continue;

    if (readOffset + 4096 > sectionFlags.size()) {
      break;
    }

    auto& section = chunk.sections[static_cast<size_t>(i)];
    section.assign(
      sectionFlags.begin() + static_cast<std::ptrdiff_t>(readOffset),
      sectionFlags.begin() + static_cast<std::ptrdiff_t>(readOffset + 4096)
    );
    readOffset += 4096;
  }

  std::lock_guard lock(mutex_);
  minY_ = minY;
  maxY_ = maxY;
  chunks_[chunkKey(chunkX, chunkZ)] = std::move(chunk);
}

void WorldState::applyUpdates(const std::vector<BlockUpdate>& updates) {
  std::lock_guard lock(mutex_);

  for (const auto& update : updates) {
    const int chunkX = update.x >> 4;
    const int chunkZ = update.z >> 4;
    const auto key = chunkKey(chunkX, chunkZ);
    auto it = chunks_.find(key);
    if (it == chunks_.end()) {
      continue;
    }

    it->second.setFlags(update.x & 15, update.y, update.z & 15, update.flags);
  }
}

WorldSnapshot WorldState::snapshot() const {
  std::lock_guard lock(mutex_);

  WorldSnapshot snapshot;
  snapshot.worldKey = worldKey_;
  snapshot.minY = minY_;
  snapshot.maxY = maxY_;
  snapshot.chunks = chunks_;
  return snapshot;
}

} // namespace v5pf

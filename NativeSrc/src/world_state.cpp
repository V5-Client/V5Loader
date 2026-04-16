#include "world_state.hpp"

#include <algorithm>
#include <cstddef>
#include <cstring>

namespace v5pf {

namespace {

constexpr int kMinAllowedWorldY = -2048;
constexpr int kMaxAllowedWorldY = 2048;
constexpr int64_t kMaxAllowedWorldSpan = 4096;

[[nodiscard]] bool isValidWorldBounds(const int minY, const int maxY) {
  if (minY >= maxY) {
    return false;
  }
  if (minY < kMinAllowedWorldY || maxY > kMaxAllowedWorldY) {
    return false;
  }

  const int64_t span = static_cast<int64_t>(maxY) - static_cast<int64_t>(minY);
  return span > 0 && span <= kMaxAllowedWorldSpan;
}

} // namespace

void ChunkData::ensureLayout() {
  const int count = sectionCount();
  if (count <= 0) {
    sectionOffsets.clear();
    voxels.clear();
    return;
  }

  if (static_cast<int>(sectionOffsets.size()) != count) {
    sectionOffsets.assign(static_cast<size_t>(count), -1);
  }
}

bool ChunkData::hasSection(const int sectionIdx) const {
  return sectionIdx >= 0 &&
    sectionIdx < static_cast<int>(sectionOffsets.size()) &&
    sectionOffsets[static_cast<size_t>(sectionIdx)] >= 0;
}

const uint16_t* ChunkData::sectionData(const int sectionIdx) const {
  if (!hasSection(sectionIdx)) {
    return nullptr;
  }

  return voxels.data() + static_cast<size_t>(sectionOffsets[static_cast<size_t>(sectionIdx)]);
}

uint16_t* ChunkData::sectionData(const int sectionIdx) {
  if (!hasSection(sectionIdx)) {
    return nullptr;
  }

  return voxels.data() + static_cast<size_t>(sectionOffsets[static_cast<size_t>(sectionIdx)]);
}

void ChunkData::assignSection(const int sectionIdx, const uint16_t* source) {
  if (source == nullptr) return;

  ensureLayout();
  if (sectionIdx < 0 || sectionIdx >= static_cast<int>(sectionOffsets.size())) {
    return;
  }

  int32_t& offset = sectionOffsets[static_cast<size_t>(sectionIdx)];
  if (offset < 0) {
    offset = static_cast<int32_t>(voxels.size());
    voxels.resize(voxels.size() + 4096, VF_AIR_DEFAULT);
  }

  std::memcpy(
    voxels.data() + static_cast<size_t>(offset),
    source,
    4096 * sizeof(uint16_t)
  );
}

namespace {

void ensureMutableSection(ChunkData& chunk, const int sectionIdx) {
  chunk.ensureLayout();
  if (sectionIdx < 0 || sectionIdx >= static_cast<int>(chunk.sectionOffsets.size())) {
    return;
  }

  int32_t& offset = chunk.sectionOffsets[static_cast<size_t>(sectionIdx)];
  if (offset >= 0) {
    return;
  }

  offset = static_cast<int32_t>(chunk.voxels.size());
  chunk.voxels.resize(chunk.voxels.size() + 4096, VF_AIR_DEFAULT);
}

} // namespace

uint16_t ChunkData::getFlags(const int localX, const int y, const int localZ) const {
  if (y < minY || y >= maxY) return VF_AIR_DEFAULT;

  const int sectionIdx = (y - minY) >> 4;
  const uint16_t* section = sectionData(sectionIdx);
  if (section == nullptr) return VF_AIR_DEFAULT;

  const int index = ((y & 15) << 8) | ((localZ & 15) << 4) | (localX & 15);
  return section[static_cast<size_t>(index)];
}

void ChunkData::setFlags(const int localX, const int y, const int localZ, const uint16_t flags) {
  if (y < minY || y >= maxY) return;

  const int sectionIdx = (y - minY) >> 4;
  ensureMutableSection(*this, sectionIdx);
  uint16_t* section = sectionData(sectionIdx);
  if (section == nullptr) return;

  const int index = ((y & 15) << 8) | ((localZ & 15) << 4) | (localX & 15);
  section[static_cast<size_t>(index)] = flags;
}

const ChunkMap& WorldSnapshot::chunks() const {
  static const ChunkMap kEmptyChunks;
  return data != nullptr ? data->chunks : kEmptyChunks;
}

uint16_t WorldSnapshot::getFlags(const int x, const int y, const int z) const {
  if (y < minY || y >= maxY) return VF_AIR_DEFAULT;

  const int chunkX = x >> 4;
  const int chunkZ = z >> 4;
  const auto& chunkMap = chunks();
  const auto it = chunkMap.find(chunkKey(chunkX, chunkZ));
  if (it == chunkMap.end() || it->second == nullptr) {
    return VF_SOLID | VF_BLOCKING_WALL;
  }

  return it->second->getFlags(x & 15, y, z & 15);
}

void WorldState::setWorld(std::string worldKey, const int minY, const int maxY) {
  if (!isValidWorldBounds(minY, maxY)) {
    return;
  }

  std::lock_guard lock(mutex_);
  auto next = std::make_shared<WorldData>();
  next->worldKey = std::move(worldKey);
  next->minY = minY;
  next->maxY = maxY;
  data_ = std::move(next);
}

void WorldState::clear() {
  std::lock_guard lock(mutex_);
  auto next = std::make_shared<WorldData>(*data_);
  next->chunks.clear();
  data_ = std::move(next);
}

void WorldState::upsertChunk(
  const int chunkX,
  const int chunkZ,
  const int minY,
  const int maxY,
  const uint64_t sectionMask,
  const uint16_t* sectionFlags,
  const size_t sectionFlagCount
) {
  if (!isValidWorldBounds(minY, maxY)) {
    return;
  }

  ChunkData chunk;
  chunk.minY = minY;
  chunk.maxY = maxY;
  chunk.ensureLayout();

  const int sectionCount = chunk.sectionCount();
  size_t readOffset = 0;

  const int maskedSectionCount = std::min(sectionCount, 64);
  for (int i = 0; i < maskedSectionCount; i++) {
    const bool hasSection = (sectionMask & (1ULL << i)) != 0ULL;
    if (!hasSection) continue;

    if (sectionFlags == nullptr || readOffset + 4096 > sectionFlagCount) {
      break;
    }

    chunk.assignSection(i, sectionFlags + readOffset);
    readOffset += 4096;
  }

  std::lock_guard lock(mutex_);
  auto next = std::make_shared<WorldData>(*data_);
  next->minY = minY;
  next->maxY = maxY;
  next->chunks[chunkKey(chunkX, chunkZ)] = std::make_shared<ChunkData>(std::move(chunk));
  data_ = std::move(next);
}

void WorldState::applyUpdates(const std::vector<BlockUpdate>& updates) {
  if (updates.empty()) {
    return;
  }

  std::lock_guard lock(mutex_);
  auto next = std::make_shared<WorldData>(*data_);
  std::unordered_map<uint64_t, std::shared_ptr<ChunkData>> mutableChunks;
  mutableChunks.reserve(updates.size());

  for (const auto& update : updates) {
    const int chunkX = update.x >> 4;
    const int chunkZ = update.z >> 4;
    const auto key = chunkKey(chunkX, chunkZ);
    const auto it = next->chunks.find(key);
    if (it == next->chunks.end() || it->second == nullptr) {
      continue;
    }

    std::shared_ptr<ChunkData> chunk;
    const auto mutableIt = mutableChunks.find(key);
    if (mutableIt != mutableChunks.end()) {
      chunk = mutableIt->second;
    } else {
      chunk = std::make_shared<ChunkData>(*it->second);
      mutableChunks.emplace(key, chunk);
      next->chunks[key] = chunk;
    }

    chunk->setFlags(update.x & 15, update.y, update.z & 15, update.flags);
  }

  data_ = std::move(next);
}

WorldSnapshot WorldState::snapshot() const {
  std::lock_guard lock(mutex_);

  WorldSnapshot snapshot;
  snapshot.data = data_;
  snapshot.worldKey = data_->worldKey;
  snapshot.minY = data_->minY;
  snapshot.maxY = data_->maxY;
  return snapshot;
}

} // namespace v5pf

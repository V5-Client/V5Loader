#pragma once

#include "world_state.hpp"

#include <limits>

namespace v5pf {

class WorldVoxelCursor {
 public:
  explicit WorldVoxelCursor(const WorldSnapshot& world)
    : world_(world) {
  }

  [[nodiscard]] uint16_t getFlags(const int x, const int y, const int z) const {
    if (y < world_.minY || y >= world_.maxY) {
      return VF_AIR_DEFAULT;
    }

    const int chunkX = x >> 4;
    const int chunkZ = z >> 4;
    if (chunkX != cachedChunkX_ || chunkZ != cachedChunkZ_) {
      cachedChunkX_ = chunkX;
      cachedChunkZ_ = chunkZ;
      cachedChunk_ = nullptr;
      cachedSectionIdx_ = std::numeric_limits<int>::min();
      cachedSection_ = nullptr;

      const auto& chunks = world_.chunks();
      const auto it = chunks.find(chunkKey(chunkX, chunkZ));
      if (it == chunks.end() || it->second == nullptr) {
        return VF_SOLID | VF_BLOCKING_WALL;
      }

      cachedChunk_ = it->second.get();
    }

    if (cachedChunk_ == nullptr) {
      return VF_SOLID | VF_BLOCKING_WALL;
    }

    if (y < cachedChunk_->minY || y >= cachedChunk_->maxY) {
      return VF_AIR_DEFAULT;
    }

    const int sectionIdx = (y - cachedChunk_->minY) >> 4;
    if (sectionIdx != cachedSectionIdx_) {
      cachedSectionIdx_ = sectionIdx;
      cachedSection_ = cachedChunk_->sectionData(sectionIdx);
    }

    if (cachedSection_ == nullptr) {
      return VF_AIR_DEFAULT;
    }

    const int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    return cachedSection_[static_cast<size_t>(index)];
  }

 private:
  const WorldSnapshot& world_;
  mutable int cachedChunkX_ = std::numeric_limits<int>::min();
  mutable int cachedChunkZ_ = std::numeric_limits<int>::min();
  mutable const ChunkData* cachedChunk_ = nullptr;
  mutable int cachedSectionIdx_ = std::numeric_limits<int>::min();
  mutable const uint16_t* cachedSection_ = nullptr;
};

} // namespace v5pf

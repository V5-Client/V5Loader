#pragma once

#include "common.hpp"

#include <memory>
#include <mutex>
#include <optional>

namespace v5pf {

struct BlockUpdate {
  int x;
  int y;
  int z;
  uint16_t flags;
};

struct ChunkData {
  int minY = -64;
  int maxY = 320; // exclusive
  std::vector<int32_t> sectionOffsets;
  std::vector<uint16_t> voxels;

  [[nodiscard]] int sectionCount() const {
    return static_cast<int>((maxY - minY + 15) >> 4);
  }

  void ensureLayout();

  [[nodiscard]] bool hasSection(int sectionIdx) const;
  [[nodiscard]] const uint16_t* sectionData(int sectionIdx) const;
  [[nodiscard]] uint16_t* sectionData(int sectionIdx);
  void assignSection(int sectionIdx, const uint16_t* source);
  [[nodiscard]] uint16_t getFlags(int localX, int y, int localZ) const;
  void setFlags(int localX, int y, int localZ, uint16_t flags);
};

using SharedChunkData = std::shared_ptr<const ChunkData>;
using ChunkMap = std::unordered_map<int64_t, SharedChunkData>;

struct WorldData {
  std::string worldKey = "runtime_memory";
  int minY = -64;
  int maxY = 320; // exclusive
  ChunkMap chunks;
};

struct WorldSnapshot {
  std::shared_ptr<const WorldData> data;
  std::string worldKey;
  int minY = -64;
  int maxY = 320; // exclusive

  [[nodiscard]] const ChunkMap& chunks() const;
  [[nodiscard]] uint16_t getFlags(int x, int y, int z) const;
};

class WorldState {
 public:
  void setWorld(std::string worldKey, int minY, int maxY);
  void clear();

  void upsertChunk(
    int chunkX,
    int chunkZ,
    int minY,
    int maxY,
    uint64_t sectionMask,
    const uint16_t* sectionFlags,
    size_t sectionFlagCount
  );

  void applyUpdates(const std::vector<BlockUpdate>& updates);

  [[nodiscard]] WorldSnapshot snapshot() const;

 private:
  mutable std::mutex mutex_;
  std::shared_ptr<const WorldData> data_ = std::make_shared<WorldData>();
};

} // namespace v5pf

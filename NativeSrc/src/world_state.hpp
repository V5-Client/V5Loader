#pragma once

#include "common.hpp"

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
  std::vector<std::vector<uint16_t>> sections;

  [[nodiscard]] int sectionCount() const {
    return static_cast<int>((maxY - minY + 15) >> 4);
  }

  void ensureLayout();

  [[nodiscard]] uint16_t getFlags(int localX, int y, int localZ) const;
  void setFlags(int localX, int y, int localZ, uint16_t flags);
};

struct WorldSnapshot {
  std::string worldKey;
  int minY = -64;
  int maxY = 320; // exclusive
  std::unordered_map<int64_t, ChunkData> chunks;

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
    const std::vector<uint16_t>& sectionFlags
  );

  void applyUpdates(const std::vector<BlockUpdate>& updates);

  [[nodiscard]] WorldSnapshot snapshot() const;

 private:
  mutable std::mutex mutex_;
  std::string worldKey_ = "runtime_memory";
  int minY_ = -64;
  int maxY_ = 320;
  std::unordered_map<int64_t, ChunkData> chunks_;
};

} // namespace v5pf

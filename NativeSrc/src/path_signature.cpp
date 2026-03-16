#include "path_signature.hpp"

#include <sstream>

namespace v5pf {

std::string buildPathSignatureHex(const std::vector<Int3>& points) {
  constexpr uint64_t FNV_OFFSET_BASIS = static_cast<uint64_t>(-3750763034362895579LL);
  constexpr uint64_t FNV_PRIME = 1099511628211ULL;

  uint64_t hash = FNV_OFFSET_BASIS;
  for (const auto& p : points) {
    const int64_t pointHash =
      (static_cast<int64_t>(p.x) * 73856093LL) ^
      (static_cast<int64_t>(p.y) * 19349663LL) ^
      (static_cast<int64_t>(p.z) * 83492791LL);
    hash = (hash ^ static_cast<uint64_t>(pointHash)) * FNV_PRIME;
  }

  std::ostringstream oss;
  oss << std::hex << hash;
  return oss.str();
}

} // namespace v5pf

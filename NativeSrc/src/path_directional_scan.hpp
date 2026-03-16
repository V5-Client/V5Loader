#pragma once

#include "world_state.hpp"

namespace v5pf {

using DirectionMatcher = bool (*)(const WorldSnapshot&, int, int, int);

int directionalDistance(
  const WorldSnapshot& world,
  int x,
  int y,
  int z,
  DirectionMatcher matcher
);

} // namespace v5pf

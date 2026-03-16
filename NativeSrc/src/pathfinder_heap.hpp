#pragma once

#include <vector>

namespace v5pf::detail {

class Heap {
 public:
  explicit Heap(std::vector<double>& fCost, std::vector<int>& heapPos)
    : fCost_(fCost), heapPos_(heapPos) {
    items_.resize(1);
  }

  [[nodiscard]] bool empty() const {
    return size_ == 0;
  }

  void add(const int nodeIdx) {
    if (static_cast<int>(items_.size()) <= size_ + 1) {
      items_.resize(items_.size() * 2 + 1, -1);
    }

    size_++;
    items_[static_cast<size_t>(size_)] = nodeIdx;
    heapPos_[static_cast<size_t>(nodeIdx)] = size_;
    siftUp(size_);
  }

  void relocate(const int nodeIdx) {
    const int pos = heapPos_[static_cast<size_t>(nodeIdx)];
    if (pos <= 0) return;
    siftUp(pos);
  }

  int poll() {
    if (size_ <= 0) return -1;

    const int result = items_[1];
    heapPos_[static_cast<size_t>(result)] = -1;

    if (size_ == 1) {
      size_ = 0;
      return result;
    }

    const int last = items_[static_cast<size_t>(size_)];
    size_--;

    items_[1] = last;
    heapPos_[static_cast<size_t>(last)] = 1;
    siftDown(1);

    return result;
  }

 private:
  std::vector<int> items_;
  int size_ = 0;

  std::vector<double>& fCost_;
  std::vector<int>& heapPos_;

  void siftUp(int pos) {
    const int node = items_[static_cast<size_t>(pos)];
    const double cost = fCost_[static_cast<size_t>(node)];

    while (pos > 1) {
      const int parent = pos >> 1;
      const int parentNode = items_[static_cast<size_t>(parent)];
      const double parentCost = fCost_[static_cast<size_t>(parentNode)];
      if (cost >= parentCost) break;

      items_[static_cast<size_t>(pos)] = parentNode;
      heapPos_[static_cast<size_t>(parentNode)] = pos;
      pos = parent;
    }

    items_[static_cast<size_t>(pos)] = node;
    heapPos_[static_cast<size_t>(node)] = pos;
  }

  void siftDown(int pos) {
    const int node = items_[static_cast<size_t>(pos)];
    const double cost = fCost_[static_cast<size_t>(node)];
    const int half = size_ >> 1;

    while (pos <= half) {
      int child = pos << 1;
      int childNode = items_[static_cast<size_t>(child)];
      double childCost = fCost_[static_cast<size_t>(childNode)];

      const int right = child + 1;
      if (right <= size_) {
        const int rightNode = items_[static_cast<size_t>(right)];
        const double rightCost = fCost_[static_cast<size_t>(rightNode)];
        if (rightCost < childCost) {
          child = right;
          childNode = rightNode;
          childCost = rightCost;
        }
      }

      if (cost <= childCost) break;

      items_[static_cast<size_t>(pos)] = childNode;
      heapPos_[static_cast<size_t>(childNode)] = pos;
      pos = child;
    }

    items_[static_cast<size_t>(pos)] = node;
    heapPos_[static_cast<size_t>(node)] = pos;
  }
};


} // namespace v5pf::detail

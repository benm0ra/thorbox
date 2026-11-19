// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
// Lock-free single-producer / single-consumer ring buffer.
// The ONLY primitive used to talk to the audio thread. It never allocates after construction,
// never blocks, and needs no system calls, so it is safe to use inside the Oboe callback.
//
// Invariant: exactly one thread calls push() and exactly one thread calls pop() for a given queue.
//   cmd queue  : producer = Android main thread (all JNI calls), consumer = audio callback
//   event queue: producer = audio callback, consumer = main thread (pollEvent)
#include <atomic>
#include <array>
#include <cstddef>

template <typename T, size_t Capacity>
class SpscQueue {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");
public:
    bool push(const T& item) {
        const size_t head = mHead.load(std::memory_order_relaxed);
        const size_t tail = mTail.load(std::memory_order_acquire);
        if (head - tail >= Capacity) return false;          // full
        mItems[head & (Capacity - 1)] = item;
        mHead.store(head + 1, std::memory_order_release);
        return true;
    }
    bool pop(T& out) {
        const size_t tail = mTail.load(std::memory_order_relaxed);
        const size_t head = mHead.load(std::memory_order_acquire);
        if (tail == head) return false;                      // empty
        out = mItems[tail & (Capacity - 1)];
        mTail.store(tail + 1, std::memory_order_release);
        return true;
    }
private:
    alignas(64) std::atomic<size_t> mHead{0};
    alignas(64) std::atomic<size_t> mTail{0};
    alignas(64) std::array<T, Capacity> mItems{};
};

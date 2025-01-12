// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#pragma once

#include <array>
#include <deque>

namespace starrocks {

template <int NUM_PRIORITY, class T, class Container = std::deque<T>>
class PriorityQueue {
    static_assert(NUM_PRIORITY > 0, "NUM_PRIORITY must greater than 0");

public:
    using reference = T&;
    using const_reference = const T&;
    using size_type = typename Container::size_type;

    ~PriorityQueue() = default;

    // Checks if the container has no elements
    [[nodiscard]] bool empty() const noexcept;

    // Returns the number of elements in the container
    [[nodiscard]] size_type size() const noexcept;

    // Returns a reference to the first element of the nonempty container with
    // a higher priority.
    //
    // Calling front on an empty container is undefined
    reference front();

    const_reference front() const;

    // Appends the given element |value| to the end of the container associated with the priority |pri| .
    // The new element is initialized as a copy of value.
    //
    // If |pri| is less than zero or greater than NUM_PRIORITY, the behavior is undefined.
    void push_back(int pri, const T& value);

    // Appends the given element |value| to the end of the container associated with the priority |pri|.
    // |value| is moved into the new element.
    //
    // If |pri| is less than zero or greater than NUM_PRIORITY, the behavior is undefined.
    void push_back(int pri, T&& value);

    // Appends a new element to the end of the container associated with the priority |pri|. The element
    // is constructed through std::allocator_traits::construct, which typically uses placement-new to
    // construct the element in-place at the location provided by the container.
    // The arguments args... are forwarded to the constructor as std::forward<Args>(args)....
    //
    // If |pri| is less than zero or greater than NUM_PRIORITY, the behavior is undefined.
    template <class... Args>
    reference emplace_back(int pri, Args&&... args);

    // Removes the first element of the nonempty container with a higher priority.
    //
    // If there are no elements in all the containers, the behavior is undefined.
    void pop_front();

private:
    std::array<Container, NUM_PRIORITY> _queues;
};

} // namespace starrocks

#include "util/priority_queue_inl.h"

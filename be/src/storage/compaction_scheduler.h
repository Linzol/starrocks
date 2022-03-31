// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#pragma once

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <vector>

#include "storage/olap_common.h"
#include "storage/rowset/rowset.h"
#include "storage/tablet.h"
#include "util/threadpool.h"

namespace starrocks {

class DataDir;
class CompactionTask;
class CompactionCandidate;

// to schedule tablet to run compaction task
class CompactionScheduler {
public:
    CompactionScheduler();
    ~CompactionScheduler() = default;

    void schedule();

    void notify();

private:
    // wait until current running tasks are below max_concurrent_num
    void _wait_to_run();

    bool _can_schedule_next();

    std::shared_ptr<CompactionTask> _try_get_next_compaction_task();

    bool _can_do_compaction(const CompactionCandidate& candidate, bool* need_reschedule,
                            std::shared_ptr<CompactionTask>* compaction_task);

    // if check fails, should not reschedule the tablet
    bool _check_precondition(const CompactionCandidate& candidate);

    bool _can_do_compaction_task(Tablet* tablet, CompactionTask* compaction_task);

private:
    std::unique_ptr<ThreadPool> _compaction_pool;

    std::mutex _mutex;
    std::condition_variable _cv;
    uint64_t _round;
};

} // namespace starrocks
// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#include <set>
#include <string>

#include "storage/lake/group_assigner.h"

namespace starrocks::lake {

class TestGroupAssigner : public GroupAssigner {
public:
    TestGroupAssigner(std::string path) : _path(std::move(path)) {}

    ~TestGroupAssigner() override = default;

    StatusOr<std::string> get_group(int64_t tablet_id) override { return _path; }

    Status list_group(std::set<std::string>* groups) override {
        groups->emplace(_path);
        return Status::OK();
    }

private:
    std::string _path;
};

} // namespace starrocks::lake
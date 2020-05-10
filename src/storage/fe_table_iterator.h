/*
 * table_iterator.h
 * Copyright (C) 4paradigm.com 2020 wangtaize <wangtaize@4paradigm.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef SRC_STORAGE_TABLE_ITERATOR_H_
#define SRC_STORAGE_TABLE_ITERATOR_H_

#include <memory>
#include <string>
#include "base/iterator.h"
#include "codec/list_iterator_codec.h"
#include "glog/logging.h"
#include "storage/fe_segment.h"
#include "base/fe_slice.h"
#include "storage/fe_table.h"
#include "vm/catalog.h"

namespace fesql {
namespace storage {
using fesql::codec::Row;
using fesql::codec::IteratorV;
using fesql::codec::WindowIterator;
class WindowTableIterator;
class FullTableIterator;
class WindowInternalIterator;
class EmptyWindowIterator;

class EmptyWindowIterator : public IteratorV<uint64_t, Row> {
 public:
    EmptyWindowIterator() : value_() {}

    ~EmptyWindowIterator() {}

    inline void Seek(uint64_t ts) {}

    inline void SeekToFirst() {}

    inline bool Valid() { return false; }

    inline void Next() {}

    inline const Row& GetValue() { return value_; }

    inline const uint64_t GetKey() { return 0; }

 private:
    Row value_;
};

class WindowInternalIterator : public IteratorV<uint64_t, Row> {
 public:
    explicit WindowInternalIterator(
        std::unique_ptr<base::Iterator<uint64_t, DataBlock*>> ts_it);
    ~WindowInternalIterator();

    inline void Seek(uint64_t ts);

    inline void SeekToFirst();

    bool Valid();

    void Next();

    const Row& GetValue();

    const uint64_t GetKey();

 private:
    std::unique_ptr<base::Iterator<uint64_t, DataBlock*>> ts_it_;
    Row value_;
};

class WindowTableIterator : public WindowIterator {
 public:
    WindowTableIterator(Segment*** segments, uint32_t seg_cnt, uint32_t index,
                        std::shared_ptr<Table> table);
    ~WindowTableIterator();

    void Seek(const std::string& key);
    void SeekToFirst();
    void Next();
    bool Valid();
    std::unique_ptr<vm::RowIterator> GetValue();
    const Row GetKey();

 private:
    void GoToStart();
    void GoToNext();

 private:
    Segment*** segments_;
    uint32_t seg_cnt_;
    uint32_t index_;
    uint32_t seg_idx_;
    std::unique_ptr<base::Iterator<Slice, void*>> pk_it_;
    // hold the reference
    std::shared_ptr<Table> table_;
};

// the full table iterator
class FullTableIterator : public IteratorV<uint64_t, Row> {
 public:
    FullTableIterator() : seg_cnt_(0), seg_idx_(0), segments_(NULL) {}

    explicit FullTableIterator(Segment*** segments, uint32_t seg_cnt,
                               std::shared_ptr<Table> table);

    ~FullTableIterator() {
    }

    inline void Seek(uint64_t ts) {}

    inline void SeekToFirst() {}

    bool Valid();

    void Next();

    const Row& GetValue();

    // the key maybe the row num
    const uint64_t GetKey() { return 0; }

 private:
    void GoToStart();
    void GoToNext();

 private:
    uint32_t seg_cnt_;
    uint32_t seg_idx_;
    Segment*** segments_;
    std::unique_ptr<base::Iterator<uint64_t, DataBlock*>> ts_it_;
    std::unique_ptr<base::Iterator<Slice, void*>> pk_it_;
    std::shared_ptr<Table> table_;
    Row value_;
};

}  // namespace storage
}  // namespace fesql

#endif  // SRC_STORAGE_TABLE_ITERATOR_H_

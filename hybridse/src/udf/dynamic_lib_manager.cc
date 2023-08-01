/*
 * Copyright 2021 4Paradigm
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

#include "udf/dynamic_lib_manager.h"

#include <dlfcn.h>
#include <unistd.h>

namespace hybridse {
namespace udf {

DynamicLibManager::~DynamicLibManager() {
    for (const auto& kv : handle_map_) {
        auto so_handle = kv.second;
        if(so_handle != nullptr) {
            dlclose(so_handle);
        }
    }
    handle_map_.clear();
}

base::Status DynamicLibManager::ExtractFunction(const std::string& name, bool is_aggregate, const std::string& file,
                                                std::vector<void*>* funs) {
    CHECK_TRUE(funs != nullptr, common::kExternalUDFError, "funs is nullptr")
    void* handle = dlopen(file.c_str(), RTLD_LAZY);
    if (handle == nullptr) {
        std::string err_msg;
        err_msg = "can not open the dynamic library: " + file + ", error: " + dlerror() + ", try to use abs path";
        LOG(WARNING) << err_msg;
        // try to use abs path to avoid dlopen failed but it only works in the same path(e.g. in yarn mode)
        char abs_path_buff[PATH_MAX];
        if (realpath(file.c_str(), abs_path_buff) == NULL) {
            err_msg.append(", can not get real path, error: ").append(strerror(errno));
            return {common::kExternalUDFError, err_msg};
        }

        handle = dlopen(abs_path_buff, RTLD_LAZY);
        if (handle == nullptr) {
            err_msg.append("dlopen abs path failed, error: ").append(dlerror());
            return {common::kExternalUDFError, err_msg};
        }
    }

    {
        std::lock_guard<std::mutex> lock(mu_);
        handle_map_[file] = handle;
    }
    if (is_aggregate) {
        std::string init_fun_name = name + "_init";
        auto init_fun = dlsym(handle, init_fun_name.c_str());
        if (init_fun == nullptr) {
            RemoveHandler(file);
            return {common::kExternalUDFError, "can not find the init function: " + init_fun_name};
        }
        funs->emplace_back(init_fun);
        std::string update_fun_name = name + "_update";
        auto update_fun = dlsym(handle, update_fun_name.c_str());
        if (update_fun == nullptr) {
            RemoveHandler(file);
            return {common::kExternalUDFError, "can not find the update function: " + update_fun_name};
        }
        funs->emplace_back(update_fun);
        std::string output_fun_name = name + "_output";
        auto output_fun = dlsym(handle, output_fun_name.c_str());
        if (output_fun == nullptr) {
            RemoveHandler(file);
            return {common::kExternalUDFError, "can not find the output function: " + output_fun_name};
        }
        funs->emplace_back(output_fun);
    } else {
        auto fun = dlsym(handle, name.c_str());
        if (fun == nullptr) {
            RemoveHandler(file);
            return {common::kExternalUDFError, "can not find the function: " + name};
        }
        funs->emplace_back(fun);
    }
    return base::Status::OK();
}

base::Status DynamicLibManager::RemoveHandler(const std::string& file) {
    void* handle = nullptr;
    {
        std::lock_guard<std::mutex> lock(mu_);
        if (auto iter = handle_map_.find(file); iter != handle_map_.end()) {
            if (iter->second != nullptr) {
                handle = iter->second;
            }
        }
    }
    if (handle != nullptr) {
        CHECK_TRUE(dlclose(handle) == 0, common::kExternalUDFError,
                   "can not close the dynamic library: " + file + ", error: " + dlerror())
    }
    return {};
}

}  // namespace udf
}  // namespace hybridse

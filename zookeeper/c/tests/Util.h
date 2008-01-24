/*
 * Copyright 2008, Yahoo! Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef UTIL_H_
#define UTIL_H_

#include <map>

#include <zookeeper.h>
#include "src/zk_log.h"
#include "src/zk_adaptor.h"

// number of elements in array
#define COUNTOF(array) sizeof(array)/sizeof(array[0])

#define DECLARE_WRAPPER(ret,sym,sig) \
    extern "C" ret __real_##sym sig; \
    extern "C" ret __wrap_##sym sig

#define CALL_REAL(sym,params) \
    __real_##sym params

// must include "src/zk_log.h" to be able to use this macro
#define TEST_TRACE(x) \
    log_message(LOG_LEVEL_DEBUG,__LINE__,__func__,format_log_message x)

// *****************************************************************************
// A bit of wizardry to get to the bare type from a reference or a pointer 
// to the type
template <class T>
struct TypeOp {
    typedef T BareT;
    typedef T ArgT;
};

// partial specialization for reference types
template <class T>
struct TypeOp<T&>{
    typedef T& ArgT;
    typedef typename TypeOp<T>::BareT BareT;
};

// partial specialization for pointers
template <class T>
struct TypeOp<T*>{
    typedef T* ArgT;
    typedef typename TypeOp<T>::BareT BareT;
};

// *****************************************************************************
// Container utilities

template <class K, class V>
void putValue(std::map<K,V>& map,const K& k, const V& v){
    typedef std::map<K,V> Map;
    typename Map::const_iterator it=map.find(k);
    if(it==map.end())
        map.insert(typename Map::value_type(k,v));
    else
        map[k]=v;
}

template <class K, class V>
bool getValue(const std::map<K,V>& map,const K& k,V& v){
    typedef std::map<K,V> Map;
    typename Map::const_iterator it=map.find(k);
    if(it==map.end())
        return false;
    v=it->second;
    return true;
}

// *****************************************************************************
// misc utils
void millisleep(int ms);


// *****************************************************************************
// Abstract watcher action
class WatcherAction{
public:
    virtual ~WatcherAction(){}
    
    virtual void onSessionExpired(zhandle_t*) =0;
    // TODO: add the rest of the events
};
// zh->context is a pointer to a WatcherAction instance
// based on the event type and state, the watcher calls a specific watcher 
// action method
void activeWatcher(zhandle_t *zh, int type, int state, const char *path);

#endif /*UTIL_H_*/

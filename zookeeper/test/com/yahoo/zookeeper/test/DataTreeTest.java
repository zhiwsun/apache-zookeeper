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
package com.yahoo.zookeeper.test;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.WatcherEvent;
import com.yahoo.zookeeper.server.DataTree;

public class DataTreeTest {
    DataTree dt;
    
    @Before
    public void setUp() throws Exception {
        dt=new DataTree();
    }

    @After
    public void tearDown() throws Exception {
        dt=null;
    }

    @Test
    public void testRootWatchTriggered() throws Exception {
        class MyWatcher implements Watcher{
            boolean fired=false;
            public void process(WatcherEvent event) {
                if(event.getPath().equals("/"))
                    fired=true;
            }
        };
        MyWatcher watcher=new MyWatcher();
        // set a watch on the root node
        dt.getChildren("/", new Stat(), watcher);
        // add a new node, should trigger a watch
        dt.createNode("/xyz", new byte[0], null, 0, 1, 1);
        assertFalse("Root node watch not triggered",!watcher.fired);
    }

}

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class SessionTest extends ZKTestCase {
    protected static final Logger LOG = Logger.getLogger(SessionTest.class);

    private static final String HOSTPORT = "127.0.0.1:" +
            PortAssignment.unique();
    
    private ServerCnxnFactory serverFactory;

    private CountDownLatch startSignal;

    File tmpDir;

    private final int TICK_TIME = 3000;

    @Before
    public void setUp() throws Exception {
        if (tmpDir == null) {
            tmpDir = ClientBase.createTmpDir();
        }

        ClientBase.setupTestEnv();
        ZooKeeperServer zs = new ZooKeeperServer(tmpDir, tmpDir, TICK_TIME);

        final int PORT = Integer.parseInt(HOSTPORT.split(":")[1]);
        serverFactory = ServerCnxnFactory.createFactory(PORT, -1);
        serverFactory.startup(zs);

        Assert.assertTrue("waiting for server up",
                   ClientBase.waitForServerUp(HOSTPORT,
                                              CONNECTION_TIMEOUT));
    }

    @After
    public void tearDown() throws Exception {
        serverFactory.shutdown();
        Assert.assertTrue("waiting for server down",
                   ClientBase.waitForServerDown(HOSTPORT,
                                                CONNECTION_TIMEOUT));
    }

    private static class CountdownWatcher implements Watcher {
        volatile CountDownLatch clientConnected = new CountDownLatch(1);

        public void process(WatchedEvent event) {
            if (event.getState() == KeeperState.SyncConnected) {
                clientConnected.countDown();
            }
        }
    }

    private DisconnectableZooKeeper createClient()
        throws IOException, InterruptedException
    {
        CountdownWatcher watcher = new CountdownWatcher();
        return createClient(CONNECTION_TIMEOUT, watcher);
    }

    private DisconnectableZooKeeper createClient(int timeout)
        throws IOException, InterruptedException
    {
        CountdownWatcher watcher = new CountdownWatcher();
        return createClient(timeout, watcher);
    }

    private DisconnectableZooKeeper createClient(int timeout,
            CountdownWatcher watcher)
        throws IOException, InterruptedException
    {
        DisconnectableZooKeeper zk =
                new DisconnectableZooKeeper(HOSTPORT, timeout, watcher);
        if(!watcher.clientConnected.await(timeout, TimeUnit.MILLISECONDS)) {
            Assert.fail("Unable to connect to server");
        }

        return zk;
    }

// FIXME this test is Assert.failing due to client close race condition fixing in separate patch for ZOOKEEPER-63
//    /**
//     * this test checks to see if the sessionid that was created for the
//     * first zookeeper client can be reused for the second one immidiately
//     * after the first client closes and the new client resues them.
//     * @throws IOException
//     * @throws InterruptedException
//     * @throws KeeperException
//     */
//    public void testSessionReuse() throws IOException, InterruptedException {
//        ZooKeeper zk = createClient();
//
//        long sessionId = zk.getSessionId();
//        byte[] passwd = zk.getSessionPasswd();
//        zk.close();
//
//        zk.close();
//
//        LOG.info("Closed first session");
//
//        startSignal = new CountDownLatch(1);
//        zk = new ZooKeeper(HOSTPORT, CONNECTION_TIMEOUT, this,
//                sessionId, passwd);
//        startSignal.await();
//
//        LOG.info("Opened reuse");
//
//        Assert.assertEquals(sessionId, zk.getSessionId());
//
//        zk.close();
//    }

    private class MyWatcher implements Watcher {
        private String name;
        public MyWatcher(String name) {
            this.name = name;
        }
        public void process(WatchedEvent event) {
            LOG.info(name + " event:" + event.getState() + " "
                    + event.getType() + " " + event.getPath());
            if (event.getState() == KeeperState.SyncConnected
                    && startSignal != null && startSignal.getCount() > 0)
            {
                startSignal.countDown();
            }
        }
    }

    /**
     * This test verifies that when the session id is reused, and the original
     * client is disconnected, but not session closed, that the server
     * will remove ephemeral nodes created by the original session.
     */
    @Test
    public void testSession()
        throws IOException, InterruptedException, KeeperException
    {
        DisconnectableZooKeeper zk = createClient();
        zk.create("/e", new byte[0], Ids.OPEN_ACL_UNSAFE,
                        CreateMode.EPHEMERAL);
        LOG.info("zk with session id 0x" + Long.toHexString(zk.getSessionId())
                + " was destroyed!");

        // disconnect the client by killing the socket, not sending the
        // session disconnect to the server as usual. This allows the test
        // to verify disconnect handling
        zk.disconnect();

        Stat stat = new Stat();
        startSignal = new CountDownLatch(1);
        zk = new DisconnectableZooKeeper(HOSTPORT, CONNECTION_TIMEOUT,
                new MyWatcher("testSession"), zk.getSessionId(),
                zk.getSessionPasswd());
        startSignal.await();

        LOG.info("zk with session id 0x" + Long.toHexString(zk.getSessionId())
                 + " was created!");
        zk.getData("/e", false, stat);
        LOG.info("After get data /e");
        zk.close();

        zk = createClient();
        Assert.assertEquals(null, zk.exists("/e", false));
        LOG.info("before close zk with session id 0x"
                + Long.toHexString(zk.getSessionId()) + "!");
        zk.close();
    }

    private List<Thread> findThreads(String name) {
        int threadCount = Thread.activeCount();
        Thread threads[] = new Thread[threadCount*2];
        threadCount = Thread.enumerate(threads);
        ArrayList<Thread> list = new ArrayList<Thread>();
        for(int i = 0; i < threadCount; i++) {
            if (threads[i].getName().indexOf(name) != -1) {
                list.add(threads[i]);
            }
        }
        return list;
    }

    /**
     * Make sure ephemerals get cleaned up when a session times out.
     */
    @Test
    public void testSessionTimeout() throws Exception {
        final int TIMEOUT = 5000;
        List<Thread> etBefore = findThreads("EventThread");
        List<Thread> stBefore = findThreads("SendThread");
        DisconnectableZooKeeper zk = createClient(TIMEOUT);
        zk.create("/stest", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);

        // Find the new event and send threads
        List<Thread> etAfter = findThreads("EventThread");
        List<Thread> stAfter = findThreads("SendThread");
        Thread eventThread = null;
        Thread sendThread = null;
        for(Thread t: etAfter) {
            if (!etBefore.contains(t)) {
                eventThread = t;
                break;
            }
        }
        for(Thread t: stAfter) {
            if (!stBefore.contains(t)) {
                sendThread = t;
                break;
            }
        }
        sendThread.suspend();
        //zk.disconnect();

        Thread.sleep(TIMEOUT*2);
        sendThread.resume();
        eventThread.join(TIMEOUT);
        Assert.assertFalse("EventThread is still running", eventThread.isAlive());

        zk = createClient(TIMEOUT);
        zk.create("/stest", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);
        tearDown();
        zk.close();
        zk.disconnect();
        setUp();

        zk = createClient(TIMEOUT);
        Assert.assertTrue(zk.exists("/stest", false) != null);
        Thread.sleep(TIMEOUT*2);
        Assert.assertTrue(zk.exists("/stest", false) == null);
        zk.close();
    }

    /**
     * Make sure that we cannot have two connections with the same
     * session id.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws KeeperException
     */
    @Test
    @Ignore
    public void testSessionMove() throws Exception {
        String hostPorts[] = HOSTPORT.split(",");
        DisconnectableZooKeeper zk = new DisconnectableZooKeeper(hostPorts[0],
                CONNECTION_TIMEOUT, new MyWatcher("0"));
        zk.create("/sessionMoveTest", new byte[0], Ids.OPEN_ACL_UNSAFE,
                CreateMode.EPHEMERAL);
        // we want to loop through the list twice
        for(int i = 0; i < hostPorts.length*2; i++) {
            zk.dontReconnect();
            // This should stomp the zk handle
            DisconnectableZooKeeper zknew = new DisconnectableZooKeeper(
                    hostPorts[(i+1)%hostPorts.length],
                    CONNECTION_TIMEOUT,
                    new MyWatcher(Integer.toString(i+1)),
                    zk.getSessionId(),
                    zk.getSessionPasswd());
            zknew.setData("/", new byte[1], -1);
            try {
                zk.setData("/", new byte[1], -1);
                Assert.fail("Should have lost the connection");
            } catch(KeeperException.ConnectionLossException e) {
                LOG.info("Got connection loss exception as expected");
            }
            //zk.close();
            zk = zknew;
        }
        zk.close();
    }
    /**
     * This test makes sure that duplicate state changes are not communicated
     * to the client watcher. For example we should not notify state as
     * "disconnected" if the watch has already been disconnected. In general
     * we don't consider a dup state notification if the event type is
     * not "None" (ie non-None communicates an event).
     */
    @Test
    public void testSessionStateNoDupStateReporting()
        throws IOException, InterruptedException, KeeperException
    {
        final int TIMEOUT = 3000;
        DupWatcher watcher = new DupWatcher();
        ZooKeeper zk = createClient(TIMEOUT, watcher);

        // shutdown the server
        serverFactory.shutdown();

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            // ignore
        }

        // verify that the size is just 2 - ie connect then disconnect
        // if the client attempts reconnect and we are not handling current
        // state correctly (ie eventing on duplicate disconnects) then we'll
        // see a disconnect for each Assert.failed connection attempt
        Assert.assertEquals(2, watcher.states.size());

        zk.close();
    }

    /**
     * Verify access to the negotiated session timeout.
     */
    @Test
    public void testSessionTimeoutAccess() throws Exception {
        // validate typical case - requested == negotiated
        DisconnectableZooKeeper zk = createClient(TICK_TIME * 4);
        Assert.assertEquals(TICK_TIME * 4, zk.getSessionTimeout());
        // make sure tostring works in both cases
        LOG.info(zk.toString());
        zk.close();
        LOG.info(zk.toString());

        // validate lower limit
        zk = createClient(TICK_TIME);
        Assert.assertEquals(TICK_TIME * 2, zk.getSessionTimeout());
        LOG.info(zk.toString());
        zk.close();
        LOG.info(zk.toString());

        // validate upper limit
        zk = createClient(TICK_TIME * 30);
        Assert.assertEquals(TICK_TIME * 20, zk.getSessionTimeout());
        LOG.info(zk.toString());
        zk.close();
        LOG.info(zk.toString());
    }

    private class DupWatcher extends CountdownWatcher {
        public LinkedList<WatchedEvent> states = new LinkedList<WatchedEvent>();
        public void process(WatchedEvent event) {
            super.process(event);
            if (event.getType() == EventType.None) {
                states.add(event);
            }
        }
    }

    @Test
    public void testMinMaxSessionTimeout() throws Exception {
        // override the defaults
        final int MINSESS = 20000;
        final int MAXSESS = 240000;
        {
            ZooKeeperServer zs = ClientBase.getServer(serverFactory);
            zs.setMinSessionTimeout(MINSESS);
            zs.setMaxSessionTimeout(MAXSESS);
        }

        // validate typical case - requested == negotiated
        int timeout = 120000;
        DisconnectableZooKeeper zk = createClient(timeout);
        Assert.assertEquals(timeout, zk.getSessionTimeout());
        // make sure tostring works in both cases
        LOG.info(zk.toString());
        zk.close();
        LOG.info(zk.toString());

        // validate lower limit
        zk = createClient(MINSESS/2);
        Assert.assertEquals(MINSESS, zk.getSessionTimeout());
        LOG.info(zk.toString());
        zk.close();
        LOG.info(zk.toString());

        // validate upper limit
        zk = createClient(MAXSESS * 2);
        Assert.assertEquals(MAXSESS, zk.getSessionTimeout());
        LOG.info(zk.toString());
        zk.close();
        LOG.info(zk.toString());
    }
}

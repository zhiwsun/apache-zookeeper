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

package org.apache.zookeeper.server.quorum;

import static org.apache.zookeeper.test.ClientBase.CONNECTION_TIMEOUT;

import java.io.ByteArrayOutputStream;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.regex.Pattern;

import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.PortAssignment;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.test.ClientBase;
import org.junit.Assert;
import org.junit.Test;


/**
 * Test stand-alone server.
 *
 */
public class QuorumPeerMainTest extends QuorumPeerTestBase {
    /**
     * Verify the ability to start a cluster.
     */
    @Test
    public void testQuorum() throws Exception {
        ClientBase.setupTestEnv();

        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();

        String quorumCfgSection =
            "server.1=127.0.0.1:" + PortAssignment.unique()
            + ":" + PortAssignment.unique()
            + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
            + ":" + PortAssignment.unique();

        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q1.start();
        q2.start();

        Assert.assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                        CONNECTION_TIMEOUT));


        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP1,
                ClientBase.CONNECTION_TIMEOUT, this);

        zk.create("/foo_q1", "foobar1".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
        zk.close();

        zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP2,
                ClientBase.CONNECTION_TIMEOUT, this);

        zk.create("/foo_q2", "foobar2".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q2", null, null)), "foobar2");
        zk.close();

        q1.shutdown();
        q2.shutdown();

        Assert.assertTrue("waiting for server 1 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                        ClientBase.CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 down",
                ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                        ClientBase.CONNECTION_TIMEOUT));
    }

    /**
     * Verify handling of bad quorum address
     */
    @Test
    public void testBadPeerAddressInQuorum() throws Exception {
        ClientBase.setupTestEnv();

        // setup the logger to capture all logs
        Layout layout =
            Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setThreshold(Level.WARN);
        Logger qlogger = Logger.getLogger("org.apache.zookeeper.server.quorum");
        qlogger.addAppender(appender);

        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();

            String quorumCfgSection =
                "server.1=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique()
                + "\nserver.2=fee.fii.foo.fum:" + PortAssignment.unique()
                + ":" + PortAssignment.unique();

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            q1.start();

            boolean isup =
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        5000);

            Assert.assertFalse("Server never came up", isup);

            q1.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            qlogger.removeAppender(appender);
        }

        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean found = false;
        Pattern p =
            Pattern.compile(".*Cannot open channel to .* at election address .*");
        while ((line = r.readLine()) != null) {
            found = p.matcher(line).matches();
            if (found) {
                break;
            }
        }
        Assert.assertTrue("complains about host", found);
    }

    /**
     * Verify handling of inconsistent peer type
     */
    @Test
    public void testInconsistentPeerType() throws Exception {
        ClientBase.setupTestEnv();

        // setup the logger to capture all logs
        Layout layout =
            Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setThreshold(Level.INFO);
        Logger qlogger = Logger.getLogger("org.apache.zookeeper.server.quorum");
        qlogger.addAppender(appender);

        // test the most likely situation only: server is stated as observer in 
        // servers list, but there's no "peerType=observer" token in config
        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();
            final int CLIENT_PORT_QP3 = PortAssignment.unique();

            String quorumCfgSection =
                "server.1=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique()
                + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique()
                + "\nserver.3=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique() + ":observer";

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
            MainThread q3 = new MainThread(3, CLIENT_PORT_QP3, quorumCfgSection);
            q1.start();
            q2.start();
            q3.start();

            Assert.assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                            CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 3 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP3,
                            CONNECTION_TIMEOUT));

            q1.shutdown();
            q2.shutdown();
            q3.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 3 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP3,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            qlogger.removeAppender(appender);
        }

        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean warningPresent = false;
        boolean defaultedToObserver = false;
        Pattern pWarn =
            Pattern.compile(".*Peer type from servers list.* doesn't match peerType.*");
        Pattern pObserve = Pattern.compile(".*OBSERVING.*");
        while ((line = r.readLine()) != null) {
            if (pWarn.matcher(line).matches()) { 
                warningPresent = true;
            }
            if (pObserve.matcher(line).matches()) {
                defaultedToObserver = true;
            }
            if (warningPresent && defaultedToObserver) {
                break;
            }
        }
        Assert.assertTrue("Should warn about inconsistent peer type", 
                warningPresent && defaultedToObserver);
    }

    /**
     * verify if bad packets are being handled properly 
     * at the quorum port
     * @throws Exception
     */
    @Test
    public void testBadPackets() throws Exception {
        ClientBase.setupTestEnv();
        final int CLIENT_PORT_QP1 = PortAssignment.unique();
        final int CLIENT_PORT_QP2 = PortAssignment.unique();
        int electionPort1 = PortAssignment.unique();
        int electionPort2 = PortAssignment.unique();
        String quorumCfgSection =
            "server.1=127.0.0.1:" + PortAssignment.unique()
            + ":" + electionPort1
            + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
            + ":" +  electionPort2;
        
        MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
        MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
        q1.start();
        q2.start();
        
        Assert.assertTrue("waiting for server 1 being up",
                ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                        CONNECTION_TIMEOUT));
        Assert.assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));
            
        byte[] b = new byte[4];
        int length = 1024*1024*1024;
        ByteBuffer buff = ByteBuffer.wrap(b);
        buff.putInt(length);
        buff.position(0);
        SocketChannel s = SocketChannel.open(new InetSocketAddress("127.0.0.1", electionPort1));
        s.write(buff);
        s.close();
        buff.position(0);
        s = SocketChannel.open(new InetSocketAddress("127.0.0.1", electionPort2));
        s.write(buff);
        s.close();
        
        ZooKeeper zk = new ZooKeeper("127.0.0.1:" + CLIENT_PORT_QP1,
                ClientBase.CONNECTION_TIMEOUT, this);

        zk.create("/foo_q1", "foobar1".getBytes(), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertEquals(new String(zk.getData("/foo_q1", null, null)), "foobar1");
        zk.close();
        q1.shutdown();
        q2.shutdown();
    }


    /**
     * Verify handling of quorum defaults
     * * default electionAlg is fast leader election
     */
    @Test
    public void testQuorumDefaults() throws Exception {
        ClientBase.setupTestEnv();

        // setup the logger to capture all logs
        Layout layout =
            Logger.getRootLogger().getAppender("CONSOLE").getLayout();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setImmediateFlush(true);
        appender.setThreshold(Level.INFO);
        Logger zlogger = Logger.getLogger("org.apache.zookeeper");
        zlogger.addAppender(appender);

        try {
            final int CLIENT_PORT_QP1 = PortAssignment.unique();
            final int CLIENT_PORT_QP2 = PortAssignment.unique();

            String quorumCfgSection =
                "server.1=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique()
                + "\nserver.2=127.0.0.1:" + PortAssignment.unique()
                + ":" + PortAssignment.unique();

            MainThread q1 = new MainThread(1, CLIENT_PORT_QP1, quorumCfgSection);
            MainThread q2 = new MainThread(2, CLIENT_PORT_QP2, quorumCfgSection);
            q1.start();
            q2.start();

            Assert.assertTrue("waiting for server 1 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP1,
                            CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 being up",
                    ClientBase.waitForServerUp("127.0.0.1:" + CLIENT_PORT_QP2,
                            CONNECTION_TIMEOUT));

            q1.shutdown();
            q2.shutdown();

            Assert.assertTrue("waiting for server 1 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP1,
                            ClientBase.CONNECTION_TIMEOUT));
            Assert.assertTrue("waiting for server 2 down",
                    ClientBase.waitForServerDown("127.0.0.1:" + CLIENT_PORT_QP2,
                            ClientBase.CONNECTION_TIMEOUT));

        } finally {
            zlogger.removeAppender(appender);
        }
        os.close();
        LineNumberReader r = new LineNumberReader(new StringReader(os.toString()));
        String line;
        boolean found = false;
        Pattern p =
            Pattern.compile(".*FastLeaderElection.*");
        while ((line = r.readLine()) != null) {
            found = p.matcher(line).matches();
            if (found) {
                break;
            }
        }
        Assert.assertTrue("fastleaderelection used", found);
    }
   
}

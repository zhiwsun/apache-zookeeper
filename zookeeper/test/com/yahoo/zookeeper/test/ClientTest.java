package com.yahoo.zookeeper.test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.junit.Test;

import com.yahoo.zookeeper.KeeperException;
import com.yahoo.zookeeper.Watcher;
import com.yahoo.zookeeper.ZooKeeper;
import com.yahoo.zookeeper.ZooDefs.CreateFlags;
import com.yahoo.zookeeper.ZooDefs.Ids;
import com.yahoo.zookeeper.data.Stat;
import com.yahoo.zookeeper.proto.WatcherEvent;
import com.yahoo.zookeeper.server.NIOServerCnxn;
import com.yahoo.zookeeper.server.ServerStats;
import com.yahoo.zookeeper.server.ZooKeeperServer;

public class ClientTest extends TestCase implements Watcher {
    private static final Logger LOG = Logger.getLogger(ClientTest.class);

    private static final int CONNECTION_TIMEOUT=30000;
    protected static String hostPort = "127.0.0.1:33221";
    LinkedBlockingQueue<WatcherEvent> events = new LinkedBlockingQueue<WatcherEvent>();
    static File baseTest = new File(System.getProperty("build.test.dir", "build"));
    NIOServerCnxn.Factory f = null;
    File tmpDir = null;
    volatile private CountDownLatch clientConnected;

    protected void setUp() throws Exception {
        LOG.error("Client test setup");
        tmpDir = File.createTempFile("test", ".junit", baseTest);
        tmpDir = new File(tmpDir + ".dir");
        tmpDir.mkdirs();
    	ServerStats.registerAsConcrete();
        ZooKeeperServer zks = new ZooKeeperServer(tmpDir, tmpDir, 3000);
        hostPort = "127.0.0.1:33221";
        f = new NIOServerCnxn.Factory(33221);
        f.startup(zks);
        Thread.sleep(5000);
        LOG.error("Client test setup finished");
    }

    protected void tearDown() throws Exception {
        LOG.error("Clent test shutdown");
        if (tmpDir != null) {
            recursiveDelete(tmpDir);
        }
        if (f != null) {
            f.shutdown();
        }
    	ServerStats.unregister();
        clientConnected=null;
        LOG.error("Client test shutdown finished");
    }
    
    static void recursiveDelete(File d) {
        if (d.isDirectory()) {
            File children[] = d.listFiles();
            for (File f : children) {
                recursiveDelete(f);
            }
        }
        d.delete();
    }

    private ZooKeeper createClient() throws IOException,InterruptedException{
        clientConnected=new CountDownLatch(1);
		ZooKeeper zk = new ZooKeeper(hostPort, 20000, this);
		if(!clientConnected.await(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)){
			fail("Unable to connect to server");
		}
		return zk;
    }
    
    @Test
    public void testPing() throws Exception {
        ZooKeeper zkIdle = null;
        ZooKeeper zkWatchCreator = null;
        try {
            zkIdle = createClient();
            zkWatchCreator = createClient();
            for (int i = 0; i < 30; i++) {
                zkWatchCreator.create("/" + i, new byte[0], Ids.OPEN_ACL_UNSAFE, 0);
            }
            for (int i = 0; i < 30; i++) {
                zkIdle.exists("/" + i, true);
            }
            for (int i = 0; i < 30; i++) {
                Thread.sleep(1000);
                zkWatchCreator.delete("/" + i, -1);
            }
            // The bug will manifest itself here because zkIdle will expire
            zkIdle.exists("/0", false);
        } finally {
            if (zkIdle != null) {
                zkIdle.close();
            }
            if (zkWatchCreator != null) {
                zkWatchCreator.close();
            }
        }
    }

    @Test
    public void testClient() throws IOException,
            InterruptedException, KeeperException {
        ZooKeeper zk = null;
        try {
    		zk =createClient();
            //System.out.println("Created client: " + zk.describeCNXN());
            System.out.println("Before create /benwashere");
            zk.create("/benwashere", "".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
            System.out.println("After create /benwashere");
            try {
            	zk.setData("/benwashere", "hi".getBytes(), 57);
        		fail("Should have gotten BadVersion exception");
            } catch(KeeperException.BadVersionException e) {
                // expected that
            } catch (KeeperException e) {
                fail("Should have gotten BadVersion exception");
            }
            System.out.println("Before delete /benwashere");
            zk.delete("/benwashere", 0);
            System.out.println("Before delete /benwashere");
            zk.close();
            //System.out.println("Closed client: " + zk.describeCNXN());
            Thread.sleep(2000);
            zk = createClient();
            //System.out.println("Created a new client: " + zk.describeCNXN());
            System.out.println("Before delete /");
            
            try {
                zk.delete("/", -1);
                fail("deleted root!");
            } catch(KeeperException.BadArgumentsException e) {
                // good, expected that
            }
            Stat stat = new Stat();
            // Test basic create, ls, and getData
            System.out.println("Before create /ben");
            zk.create("/ben", "Ben was here".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
            System.out.println("Before getChildren /");
            ArrayList<String> children = zk.getChildren("/", false);
            assertEquals(1, children.size());
            assertEquals("ben", children.get(0));
            String value = new String(zk.getData("/ben", false, stat));
            assertEquals("Ben was here", value);
            // Test stat and watch of non existent node
            try {
                assertEquals(null, zk.exists("/frog", true));
                System.out.println("Comment: asseting passed for frog setting /");
            } catch (KeeperException.NoNodeException e) {
                // OK, expected that
            }
            zk.create("/frog", "hi".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
            // the first poll is just a sesssion delivery
            System.out.println("Comment: checking for events length " + events.size());
            WatcherEvent event = events.poll(10, TimeUnit.SECONDS);
            assertEquals("/frog", event.getPath());
            assertEquals(Event.EventNodeCreated, event.getType());
            assertEquals(Event.KeeperStateSyncConnected, event.getState());
            // Test child watch and create with sequence
            zk.getChildren("/ben", true);
            for (int i = 0; i < 10; i++) {
                zk.create("/ben/" + i + "-", Integer.toString(i).getBytes(),
                        Ids.OPEN_ACL_UNSAFE, CreateFlags.SEQUENCE);
            }
            children = zk.getChildren("/ben", false);
            Collections.sort(children);
            assertEquals(10, children.size());
            for (int i = 0; i < 10; i++) {
                final String name = children.get(i);
                assertTrue(name.startsWith(i + "-"));
                byte b[] = zk.getData("/ben/" + name, true, stat);
                assertEquals(Integer.toString(i), new String(b));
                zk.setData("/ben/" + name, "new".getBytes(), stat.getVersion());
                stat = zk.exists("/ben/" + name, true);
                zk.delete("/ben/" + name, stat.getVersion());
            }
            event = events.poll(10, TimeUnit.SECONDS);
            assertEquals("/ben", event.getPath());
            assertEquals(Event.EventNodeChildrenChanged, event.getType());
            assertEquals(Event.KeeperStateSyncConnected, event.getState());
            for (int i = 0; i < 10; i++) {
                event = events.poll(10, TimeUnit.SECONDS);
                final String name = children.get(i);
                assertEquals("/ben/" + name, event.getPath());
                assertEquals(Event.EventNodeDataChanged, event.getType());
                assertEquals(Event.KeeperStateSyncConnected, event.getState());
                event = events.poll(10, TimeUnit.SECONDS);
                assertEquals("/ben/" + name, event.getPath());
                assertEquals(Event.EventNodeDeleted, event.getType());
                assertEquals(Event.KeeperStateSyncConnected, event.getState());
            }
            zk.create("/good\u0001path", "".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
            //try {
            //    zk.create("/bad\u0000path", "".getBytes(), null, 0);
            //    fail("created an invalid path");
            //} catch(KeeperException e) {
            //    assertEquals(KeeperException.Code.BadArguments, e.getCode());
            //}
            
            zk.create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
            try {
                zk.create("/duplicate", "".getBytes(), Ids.OPEN_ACL_UNSAFE, 0);
                fail("duplicate create allowed");
            } catch(KeeperException.NodeExistsException e) {
                // OK, expected that
            }
        } finally {
            if (zk != null) {
                zk.close();
            }
        }
    }

    private void notestConnections() throws IOException, InterruptedException, KeeperException {
        ZooKeeper zk;
        for(int i = 0; i < 2000; i++) {
            if (i % 100 == 0) {
                System.out.println("Testing " + i + " connections");
            }
            // We want to make sure socket descriptors are going away
            zk = new ZooKeeper(hostPort, 30000, this);
            zk.getData("/", false, new Stat());
            zk.close();
        }
    }

    static class HammerThread extends Thread {
        ZooKeeper zk;
        String prefix;
        int count;

        HammerThread(ZooKeeper zk, String prefix, int count) {
            this.zk = zk;
            this.prefix = prefix;
            this.count = count;
            start();
        }

        public void run() {
            byte b[] = new byte[256];
            try {
                for (int i = 0; i < count; i++) {
                    // Simulate a bit of network latency...
                    Thread.sleep(5);
                    zk.create(prefix + i, b, Ids.OPEN_ACL_UNSAFE, 0);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void close() throws IOException, InterruptedException {
            zk.close();
        }
    }

    @Test
    public void testDeleteWithChildren() throws Exception {
        File tmpDir = File.createTempFile("test", ".junit", baseTest);
        tmpDir = new File(tmpDir + ".dir");
        tmpDir.mkdirs();
        ZooKeeper zk = createClient();
        zk.create("/parent", new byte[0], Ids.OPEN_ACL_UNSAFE, 0);
        zk.create("/parent/child", new byte[0], Ids.OPEN_ACL_UNSAFE, 0);
        try {
            zk.delete("/parent", -1);
            fail("Should have received a not equals message");
        } catch (KeeperException e) {
            assertEquals(KeeperException.Code.NotEmpty, e.getCode());
        }
        zk.delete("/parent/child", -1);
        zk.delete("/parent", -1);
        zk.close();
    }

    @Test
    public void testHammer() throws IOException,
            InterruptedException, KeeperException {
        File tmpDir = File.createTempFile("test", ".junit", baseTest);
        tmpDir = new File(tmpDir + ".dir");
        tmpDir.mkdirs();
        try {
            final int threadCount = 10;
            final int childCount = 1000;
            ArrayList<HammerThread> threads = new ArrayList<HammerThread>(
                    threadCount);
            long start = System.currentTimeMillis();
            for (int i = 0; i < threadCount; i++) {
                Thread.sleep(10);
                ZooKeeper zk = createClient();
                String prefix = "/test-" + i;
                zk.create(prefix, new byte[0], Ids.OPEN_ACL_UNSAFE, 0);
                prefix += "/";
                threads.add(new HammerThread(zk, prefix, childCount));
            }
            for (HammerThread h : threads) {
                h.join();
                h.close();
            }
            System.err.println(new Date() + " Total time "
                    + (System.currentTimeMillis() - start));
            ZooKeeper zk = createClient();
            LOG.error("******************* Connected to ZooKeeper" + new Date());
            for (int i = 0; i < threadCount; i++) {
                System.err.println("Doing thread: " + i + " " + new Date());
                ArrayList<String> children = zk
                        .getChildren("/test-" + i, false);
                assertEquals(childCount, children.size());
            }
            for (int i = 0; i < threadCount; i++) {
                ArrayList<String> children = zk
                        .getChildren("/test-" + i, false);
                assertEquals(childCount, children.size());
            }
        } finally {
            //  recursiveDelete(tmpDir);
        }
    }

    public void process(WatcherEvent event) {
		if (event.getState() == Event.KeeperStateSyncConnected) {
			clientConnected.countDown();
		}
		if (event.getType() != Event.EventNone) {
			try {
				events.put(event);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}

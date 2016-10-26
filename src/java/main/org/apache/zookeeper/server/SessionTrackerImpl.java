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

package org.apache.zookeeper.server;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;

/**
 * This is a full featured SessionTracker.
 * It tracks session in grouped by tick interval.
 * It always rounds up the tick interval to provide a sort of grace period.
 * Sessions are thus expired in batches made up of sessions that expire in a given interval.
 */
public class SessionTrackerImpl extends ZooKeeperCriticalThread implements SessionTracker {

    private static final Logger LOG = LoggerFactory.getLogger(SessionTrackerImpl.class);

    /** SessionId - SessionImpl */
    HashMap<Long, SessionImpl>       sessionsById        = new HashMap<Long, SessionImpl>();
    /** TickTime(ExpireTime) - HashSet<SessionImpl> */
    HashMap<Long, SessionSet>        sessionSets         = new HashMap<Long, SessionSet>();
    /** SessionId - Timeout */
    ConcurrentHashMap<Long, Integer> sessionsWithTimeout;

    /** 下个SessionID - SessionId(64) = [ServerId(8)] - [InitTimeMillis(40)] - [Value(16)] */
    long nextSessionId      = 0;
    /** 下个Session过期检查点 */
    long nextExpirationTime;
    /** Session过期周期 */
    int  expirationInterval;

    SessionExpirer expirer;

    volatile boolean running = true;
    volatile long    currentTime;

    /** Session: sessionId, timeout, expireTime, isClosing, owner */
    public static class SessionImpl implements Session {
        SessionImpl(long sessionId, int timeout, long expireTime) {
            this.sessionId = sessionId;
            this.timeout = timeout;
            this.tickTime = expireTime;
            isClosing = false;
        }

        final long sessionId;
        final int  timeout;
        /** 当前Session的失效时间 */
        long       tickTime;
        boolean    isClosing;

        Object owner;

        public long getSessionId() { return sessionId; }
        public int getTimeout() { return timeout; }
        public boolean isClosing() { return isClosing; }
    }

    /** SessionSet: HashSet<SessionImpl> */
    static class SessionSet {
        HashSet<SessionImpl> sessions = new HashSet<SessionImpl>();
    }

    /** 根据ServerId和CurrentTimeMillis构造初始化SessionId */
    public static long initializeNextSession(long serverId) {
        long nextSid = 0;
        nextSid = (System.currentTimeMillis() << 24) >>> 8;
        nextSid =  nextSid | (serverId << 56);
        return nextSid;
    }

    /** 获取入参时间time下一个Session失效检查点 */
    private long roundToInterval(long time) {
        return (time / expirationInterval + 1) * expirationInterval;
    }

    /** 构造方法 */
    public SessionTrackerImpl(SessionExpirer expirer, ConcurrentHashMap<Long, Integer> sessionsWithTimeout,
                              int tickTime, long serverId, ZooKeeperServerListener listener) {
        super("SessionTracker", listener);
        this.expirer = expirer;
        this.expirationInterval = tickTime;
        this.sessionsWithTimeout = sessionsWithTimeout;
        nextExpirationTime = roundToInterval(System.currentTimeMillis());
        this.nextSessionId = initializeNextSession(serverId);
        for (Entry<Long, Integer> e : sessionsWithTimeout.entrySet()) {
            addSession(e.getKey(), e.getValue());
        }
    }

    synchronized public void dumpSessions(PrintWriter writer) {
        writer.print("Session Sets (");
        writer.print(sessionSets.size());
        writer.println("):");
        ArrayList<Long> keys = new ArrayList<Long>(sessionSets.keySet());
        Collections.sort(keys);
        for (long time : keys) {
            writer.print(sessionSets.get(time).sessions.size());
            writer.print(" expire at ");
            writer.print(new Date(time));
            writer.println(":");
            for (SessionImpl s : sessionSets.get(time).sessions) {
                writer.print("\t0x");
                writer.println(Long.toHexString(s.sessionId));
            }
        }
    }

    @Override
    synchronized public String toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pwriter = new PrintWriter(sw);
        dumpSessions(pwriter);
        pwriter.flush();
        pwriter.close();
        return sw.toString();
    }

    @Override
    synchronized public void run() {
        try {
            while (running) {
                currentTime = System.currentTimeMillis();
                if (nextExpirationTime > currentTime) {
                    this.wait(nextExpirationTime - currentTime);
                    continue;
                }
                SessionSet set;
                set = sessionSets.remove(nextExpirationTime);
                if (set != null) {
                    for (SessionImpl s : set.sessions) {
                        setSessionClosing(s.sessionId);
                        expirer.expire(s);
                    }
                }
                nextExpirationTime += expirationInterval;
            }
        } catch (InterruptedException e) {
            handleException(this.getName(), e);
        }
        LOG.info("SessionTrackerImpl exited loop!");
    }

    /**
     * 此处处理Session的有效时间
     * 对比的时间点有两个：Session本身已经存储的过期时间 和 （CurrentTime + Timeout）时间
     * 会选取比较大的时间点作为新的Session失效时间
     * */
    synchronized public boolean touchSession(long sessionId, int timeout) {
        SessionImpl s = sessionsById.get(sessionId);
        if (s == null || s.isClosing()) {
            return false;
        }

        /** 大于（CurrentTime + Timeout）时间点的下个Session检查点 */
        long expireTime = roundToInterval(System.currentTimeMillis() + timeout);

        if (s.tickTime >= expireTime) {
            return true;
        }

        SessionSet set = sessionSets.get(s.tickTime);
        if (set != null) {
            set.sessions.remove(s);
        }

        s.tickTime = expireTime;
        set = sessionSets.get(s.tickTime);
        if (set == null) {
            set = new SessionSet();
            sessionSets.put(expireTime, set);
        }
        set.sessions.add(s);
        return true;
    }

    synchronized public void setSessionClosing(long sessionId) {
        if (LOG.isTraceEnabled()) {
            LOG.info("Session closing: 0x" + Long.toHexString(sessionId));
        }
        SessionImpl s = sessionsById.get(sessionId);
        if (s == null) {
            return;
        }
        s.isClosing = true;
    }

    synchronized public void removeSession(long sessionId) {
        SessionImpl s = sessionsById.remove(sessionId);
        sessionsWithTimeout.remove(sessionId);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                    "SessionTrackerImpl --- Removing session 0x"
                    + Long.toHexString(sessionId));
        }
        if (s != null) {
            SessionSet set = sessionSets.get(s.tickTime);
            // Session expiration has been removing the sessions   
            if(set != null){
                set.sessions.remove(s);
            }
        }
    }

    public void shutdown() {
        LOG.info("Shutting down");
        running = false;
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(), "Shutdown SessionTrackerImpl!");
        }
    }

    /** 返回当前的SessionId，并自增nextSessionId */
    synchronized public long createSession(int sessionTimeout) {
        addSession(nextSessionId, sessionTimeout);
        return nextSessionId++;
    }

    synchronized public void addSession(long id, int sessionTimeout) {
        sessionsWithTimeout.put(id, sessionTimeout);
        if (sessionsById.get(id) == null) {
            /** 这里设置的tickTime = 0，注意下方的touchSession方法，更新tickTime */
            SessionImpl s = new SessionImpl(id, sessionTimeout, 0);
            sessionsById.put(id, s);
        }
        touchSession(id, sessionTimeout);
    }

    synchronized public void checkSession(long sessionId, Object owner)
            throws KeeperException.SessionExpiredException, KeeperException.SessionMovedException {
        SessionImpl session = sessionsById.get(sessionId);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        if (session.owner == null) {
            session.owner = owner;
        } else if (session.owner != owner) {
            throw new KeeperException.SessionMovedException();
        }
    }

    synchronized public void setOwner(long id, Object owner) throws SessionExpiredException {
        SessionImpl session = sessionsById.get(id);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        session.owner = owner;
    }
}

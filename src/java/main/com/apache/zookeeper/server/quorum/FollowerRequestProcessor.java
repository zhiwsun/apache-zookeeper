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

package com.yahoo.zookeeper.server.quorum;

import java.util.concurrent.LinkedBlockingQueue;

import org.apache.log4j.Logger;

import com.yahoo.zookeeper.ZooDefs.OpCode;
import com.yahoo.zookeeper.server.RequestProcessor;
import com.yahoo.zookeeper.server.Request;
import com.yahoo.zookeeper.server.ZooTrace;

/**
 * This RequestProcessor forwards any requests that modify the state of the
 * system to the Leader.
 */
public class FollowerRequestProcessor extends Thread implements
        RequestProcessor {
    private static final Logger LOG = Logger.getLogger(FollowerRequestProcessor.class);

    FollowerZooKeeperServer zks;

    RequestProcessor nextProcessor;

    LinkedBlockingQueue<Request> queuedRequests = new LinkedBlockingQueue<Request>();

    boolean finished = false;

    public FollowerRequestProcessor(FollowerZooKeeperServer zks,
            RequestProcessor nextProcessor) {
        this.zks = zks;
        this.nextProcessor = nextProcessor;
        start();
    }

    public void run() {
        try {
            while (!finished) {
                Request request = queuedRequests.take();
                ZooTrace.logRequest(LOG, ZooTrace.CLIENT_REQUEST_TRACE_MASK,
                        'F', request, "");
                if (request == Request.requestOfDeath) {
                    break;
                }
                // We want to queue the request to be processed before we submit
                // the request to the leader so that we are ready to receive
                // the response
                nextProcessor.processRequest(request);
                switch (request.type) {
                case OpCode.create:
                case OpCode.delete:
                case OpCode.setData:
                case OpCode.sync:
                case OpCode.setACL:
                case OpCode.createSession:
                case OpCode.closeSession:
                    zks.getFollower().request(request);
                    break;
                }
            }
        } catch (Exception e) {
            LOG.error("FIXMSG",e);
        }
        ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(),
                                 "FollowerRequestProcessor exited loop!");
    }

    public void processRequest(Request request) {
        if (!finished) {
            queuedRequests.add(request);
        }
    }

    public void shutdown() {
        finished = true;
        queuedRequests.clear();
        queuedRequests.add(Request.requestOfDeath);
        nextProcessor.shutdown();
    }

}

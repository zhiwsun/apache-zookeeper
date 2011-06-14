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

import org.apache.zookeeper.server.quorum.QuorumPeer.ServerState;


public class Vote {
    
    public Vote(long id, long zxid) {
        this.id = id;
        this.zxid = zxid;
    }
    
    public Vote(long id, long zxid, long peerEpoch) {
        this.id = id;
        this.zxid = zxid;
        this.peerEpoch = peerEpoch;
    }

    public Vote(long id, long zxid, long electionEpoch, long peerEpoch) {
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = electionEpoch;
        this.peerEpoch = peerEpoch;
    }
    
    public Vote(long id, long zxid, long electionEpoch, long peerEpoch, ServerState state) {
        this.id = id;
        this.zxid = zxid;
        this.electionEpoch = electionEpoch;
        this.state = state;
        this.peerEpoch = peerEpoch;
    }
    
    public long id;
    
    public long zxid;
    
    public long electionEpoch = -1;
    
    public long peerEpoch = -1;
    
    public ServerState state = ServerState.LOOKING;
    
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Vote)) {
            return false;
        }
        Vote other = (Vote) o;
        return (id == other.id && zxid == other.zxid && electionEpoch == other.electionEpoch && peerEpoch == other.peerEpoch);

    }

    @Override
    public int hashCode() {
        return (int) (id & zxid);
    }

    public String toString() {
        return "(" + id + ", " + Long.toHexString(zxid) + ", " + Long.toHexString(peerEpoch) + ")";
    }
}

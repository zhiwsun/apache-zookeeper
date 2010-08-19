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
package org.apache.hedwig.client.handlers;

import org.apache.log4j.Logger;
import org.jboss.netty.channel.Channel;

import org.apache.hedwig.client.data.PubSubData;
import org.apache.hedwig.client.netty.HedwigClient;
import org.apache.hedwig.client.netty.ResponseHandler;
import org.apache.hedwig.exceptions.PubSubException.ServiceDownException;
import org.apache.hedwig.protocol.PubSubProtocol.PubSubResponse;

public class PublishResponseHandler {

    private static Logger logger = Logger.getLogger(PublishResponseHandler.class);

    private final ResponseHandler responseHandler;

    public PublishResponseHandler(ResponseHandler responseHandler) {
        this.responseHandler = responseHandler;
    }

    // Main method to handle Publish Response messages from the server.
    public void handlePublishResponse(PubSubResponse response, PubSubData pubSubData, Channel channel) throws Exception {
        if (logger.isDebugEnabled())
            logger.debug("Handling a Publish response: " + response + ", pubSubData: " + pubSubData + ", host: "
                    + HedwigClient.getHostFromChannel(channel));
        switch (response.getStatusCode()) {
        case SUCCESS:
            // Response was success so invoke the callback's operationFinished
            // method.
            pubSubData.callback.operationFinished(pubSubData.context, null);
            break;
        case SERVICE_DOWN:
            // Response was service down failure so just invoke the callback's
            // operationFailed method.
            pubSubData.callback.operationFailed(pubSubData.context, new ServiceDownException(
                    "Server responded with a SERVICE_DOWN status"));
            break;
        case NOT_RESPONSIBLE_FOR_TOPIC:
            // Redirect response so we'll need to repost the original Publish
            // Request
            responseHandler.handleRedirectResponse(response, pubSubData, channel);
            break;
        default:
            // Consider all other status codes as errors, operation failed
            // cases.
            logger.error("Unexpected error response from server for PubSubResponse: " + response);
            pubSubData.callback.operationFailed(pubSubData.context, new ServiceDownException(
                    "Server responded with a status code of: " + response.getStatusCode()));
            break;
        }
    }
}

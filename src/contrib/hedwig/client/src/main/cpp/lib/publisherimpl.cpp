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

#include "publisherimpl.h"
#include "channel.h"

#include <log4cpp/Category.hh>

static log4cpp::Category &LOG = log4cpp::Category::getInstance("hedwig."__FILE__);

using namespace Hedwig;

PublishWriteCallback::PublishWriteCallback(ClientImplPtr& client, const PubSubDataPtr& data) : client(client), data(data) {}

void PublishWriteCallback::operationComplete() {
  if (LOG.isDebugEnabled()) {
    LOG.debugStream() << "Successfully wrote transaction: " << data->getTxnId();
  }
}

void PublishWriteCallback::operationFailed(const std::exception& exception) {
  LOG.errorStream() << "Error writing to publisher " << exception.what();
  
  //remove txn from channel pending list
  #warning "Actually do something here"
}

PublisherImpl::PublisherImpl(ClientImplPtr& client) 
  : client(client) {
}

void PublisherImpl::publish(const std::string& topic, const std::string& message) {
  SyncOperationCallback* cb = new SyncOperationCallback();
  OperationCallbackPtr callback(cb);
  asyncPublish(topic, message, callback);
  cb->wait();
  
  cb->throwExceptionIfNeeded();  
}

void PublisherImpl::asyncPublish(const std::string& topic, const std::string& message, const OperationCallbackPtr& callback) {
  DuplexChannelPtr channel = client->getChannelForTopic(topic);

  // use release after callback to release the channel after the callback is called
  PubSubDataPtr data = PubSubData::forPublishRequest(client->counter().next(), topic, message, callback);
  
  doPublish(channel, data);
}

void PublisherImpl::doPublish(const DuplexChannelPtr& channel, const PubSubDataPtr& data) {
  channel->storeTransaction(data);
  
  OperationCallbackPtr writecb(new PublishWriteCallback(client, data));
  LOG.debugStream() << "dopublish";
  channel->writeRequest(data->getRequest(), writecb);
}

void PublisherImpl::messageHandler(const PubSubResponse& m, const PubSubDataPtr& txn) {
  switch (m.statuscode()) {
  case SUCCESS:
    txn->getCallback()->operationComplete();
    break;
  case SERVICE_DOWN:
    LOG.errorStream() << "Server responsed with SERVICE_DOWN for " << txn->getTxnId();
    txn->getCallback()->operationFailed(ServiceDownException());
    break;
  default:
    LOG.errorStream() << "Unexpected response " << m.statuscode() << " for " << txn->getTxnId();
    txn->getCallback()->operationFailed(UnexpectedResponseException());
    break;
  }
}

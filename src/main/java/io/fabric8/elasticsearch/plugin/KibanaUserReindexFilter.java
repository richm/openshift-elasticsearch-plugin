/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.elasticsearch.plugin;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringUtils;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.http.netty.NettyHttpRequest;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestFilter;
import org.elasticsearch.rest.RestFilterChain;
import org.elasticsearch.rest.RestRequest;
import org.jboss.netty.buffer.BigEndianHeapChannelBuffer;
import org.jboss.netty.handler.codec.http.HttpRequest;

import io.fabric8.elasticsearch.util.RequestUtils;

public class KibanaUserReindexFilter extends RestFilter implements ConfigurationSettings {

    private final ESLogger logger;
    private final String kibanaIndex;
    private final RequestUtils utils;

    public KibanaUserReindexFilter(final Settings settings, final ESLogger logger, RequestUtils utils) {
        this.logger = Loggers.getLogger(KibanaUserReindexFilter.class);
        this.utils = utils;
        this.kibanaIndex = settings.get(KIBANA_CONFIG_INDEX_NAME, DEFAULT_USER_PROFILE_PREFIX);
    }

    @Override
    public void process(RestRequest request, RestChannel channel, RestFilterChain chain) throws Exception {
        try {
            logger.debug("Handling Request in Kibana User Reindex filter...");
            final String user = utils.getUser(request);
            final String requestedIndex = getRequestedIndex(request);

            logger.debug("Received user '{}' and index '{}', checking for kibana index '{}'", user, requestedIndex,
                    kibanaIndex);

            if (StringUtils.isNotEmpty(user) && StringUtils.isNotEmpty(requestedIndex)
                    && requestedIndex.equalsIgnoreCase(kibanaIndex)) {

                String userIndex = requestedIndex + "." + getUsernameHash(user);
                logger.debug("Matched for a kibana user header, will set to '{}' for user '{}'", userIndex, user);

                // update the request URI here
                request = updateRequestIndex(request, requestedIndex, userIndex);

                logger.debug("URI for request is '{}' after update", request.uri());
            } else {
                if (StringUtils.isNotEmpty(user) && StringUtils.isNotEmpty(requestedIndex)
                        && requestedIndex.startsWith("_mget")) {
                    String userIndex = ".kibana." + getUsernameHash(user);
                    logger.debug("Matched for a kibana user header, will set to '{}' for user '{}'", userIndex, user);

                    request = updateMGetRequest(request, ".kibana", userIndex);

                    logger.debug("URI for request is '{}' after update", request.uri());
                }
            }

        } catch (Exception e) {
            logger.error("Error handling request in OpenShift SearchGuard filter", e);
        } finally {
            chain.continueProcessing(request, channel);
        }
    }

    private RestRequest updateMGetRequest(RestRequest request, String oldIndex, String newIndex) {

        BytesReference content = request.content();
        String stringContent = content.toUtf8();

        String replaced = stringContent.replaceAll("_index\":\"" + oldIndex + "\"", "_index\":\"" + newIndex + "\"");

        NettyHttpRequest nettyRequest = (NettyHttpRequest) request;
        HttpRequest httpRequest = nettyRequest.request();

        BytesReference replacedContent = new BytesArray(replaced);
        BigEndianHeapChannelBuffer buffer = new BigEndianHeapChannelBuffer(replacedContent.array());

        httpRequest.setContent(buffer);

        RestRequest updatedRequest = new NettyHttpRequest(httpRequest, nettyRequest.getChannel());
        updatedRequest.copyContextAndHeadersFrom(request);

        return updatedRequest;
    }

    private RestRequest updateRequestIndex(RestRequest request, String oldIndex, String newIndex) {
        String uri = request.uri();
        String replaced = uri.replace(oldIndex, newIndex);

        NettyHttpRequest nettyRequest = (NettyHttpRequest) request;
        HttpRequest httpRequest = nettyRequest.request();
        httpRequest.setUri(replaced);

        RestRequest updatedRequest = new NettyHttpRequest(httpRequest, nettyRequest.getChannel());
        updatedRequest.copyContextAndHeadersFrom(request);

        return updatedRequest;
    }

    private String getRequestedIndex(RestRequest request) {
        String uri = StringUtils.defaultIfBlank(request.uri(), "");
        String[] splitUri = uri.split("/");

        if (splitUri.length > 0) {
            return uri.split("/")[1];
        } else {
            return "";
        }
    }

    @Override
    public int order() {
        // need to run last
        return Integer.MAX_VALUE;
    }

    public static String getUsernameHash(String username) {
        return DigestUtils.sha1Hex(username);
    }
}
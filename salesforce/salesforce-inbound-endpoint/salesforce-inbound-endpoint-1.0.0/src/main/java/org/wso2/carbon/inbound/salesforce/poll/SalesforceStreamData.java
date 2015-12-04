/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.inbound.salesforce.poll;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.SynapseException;
import org.apache.synapse.core.SynapseEnvironment;
import org.wso2.carbon.inbound.endpoint.protocol.generic.GenericPollingConsumer;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.client.ClientSessionChannel.MessageListener;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;


import java.lang.Exception;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Salesforce streaming api Inbound.
 */
public class SalesforceStreamData extends GenericPollingConsumer {

    private static final Log log = LogFactory.getLog(SalesforceStreamData.class);

    // Mandatory parameters.
    private static final boolean version = false;
    private static final boolean useCookies = version;
    protected static String loginEndpoint;

    private String userName;
    private String password;
    private String salesforceObject;
    private String channel;
    private String packageVersion;
    private String packageName;
    private String streamingEndpointUri;
    private String injectingSeq;

    // optional parameters.
    private static int connectionTimeout;
    private static int readTimeout;
    private static int waitTime;


    private BayeuxClient client = null;
    private HttpClient httpClient;

    private boolean isPolled = false;

    public SalesforceStreamData(Properties salesforceProperties, String name,
                                SynapseEnvironment synapseEnvironment, long scanInterval,
                                String injectingSeq, String onErrorSeq, boolean coordination,
                                boolean sequential) {
        super(salesforceProperties, name, synapseEnvironment, scanInterval,
                injectingSeq, onErrorSeq, coordination, sequential);
        loadMandatoryParameters(salesforceProperties);
        loadOptionalParameters(salesforceProperties);
        streamingEndpointUri = version ? "/" + packageName : "/" + packageName + "/" + packageVersion;
        channel = version ? "/" + salesforceObject : "/topic/" + salesforceObject;
        this.injectingSeq = injectingSeq;
        httpClient = new HttpClient();
    }

    private void makeConnect() {
        try {
            client = makeClient();
        } catch (Exception e) {
            handleException(e.getMessage());
        }
        client.getChannel(Channel.META_HANDSHAKE).addListener(new ClientSessionChannel.MessageListener() {
            public void onMessage(ClientSessionChannel channel, Message message) {
                log.info("[CHANNEL:META_HANDSHAKE]: " + message);
                boolean success = message.isSuccessful();
                if (!success) {
                    String error = (String) message.get("error");
                    if (error != null) {
                        handleException("Error during HANDSHAKE: " + error);
                    }
                    Exception exception = (Exception) message.get("exception");
                    if (exception != null) {
                        handleException("Exception during HANDSHAKE: ", exception);
                    }
                }
            }
        });

        client.getChannel(Channel.META_CONNECT).addListener(new ClientSessionChannel.MessageListener() {
            public void onMessage(ClientSessionChannel channel, Message message) {
                log.info("[CHANNEL:META_CONNECT]: " + message);
                boolean success = message.isSuccessful();
                if (!success) {
                    client.disconnect();
                    log.info("Waiting to Connect with Salesforce Streaming API......");
                    makeConnect();
                    String error = (String) message.get("error");
                    if (error != null) {
                        handleException("Error during CONNECT: " + error);
                    }
                }
            }
        });

        client.getChannel(Channel.META_SUBSCRIBE).addListener(new ClientSessionChannel.MessageListener() {
            public void onMessage(ClientSessionChannel channel, Message message) {
                log.info("[CHANNEL:META_SUBSCRIBE]: " + message);
                boolean success = message.isSuccessful();
                if (!success) {
                    String error = (String) message.get("error");
                    if (error != null) {
                        handleException("Error during SUBSCRIBE: " + error);
                    }
                }
            }
        });

        client.handshake();
        boolean handshaken = client.waitFor(waitTime, BayeuxClient.State.CONNECTED);
        if (!handshaken) {
            handleException("Failed to handshake: " + client);
        }

        client.getChannel(channel).subscribe(new MessageListener() {
            public void onMessage(ClientSessionChannel channel, Message message) {
                injectSalesforceMessage(message);
            }
        });
    }

    /**
     * Create a http client.
     *
     * @return
     * @throws Exception
     */
    private BayeuxClient makeClient() throws Exception {
        httpClient.setConnectTimeout(connectionTimeout);
        httpClient.setTimeout(readTimeout);
        httpClient.start();

        SoapLoginUtil.login(httpClient, userName, password);
        final String sessionId = SoapLoginUtil.getSessionId();
        String endpoint = SoapLoginUtil.getEndpoint();
        log.info("Login successful! to Salesforce Streaming Endpoint: " + endpoint);

        Map<String, Object> options = new HashMap<String, Object>();
        options.put(ClientTransport.TIMEOUT_OPTION, readTimeout);
        LongPollingTransport transport = new LongPollingTransport(options, httpClient) {

            @Override
            protected void customize(ContentExchange exchange) {
                super.customize(exchange);
                exchange.addRequestHeader("Authorization", "OAuth " + sessionId);
            }
        };

        BayeuxClient client = new BayeuxClient(salesforceStreamingEndpoint(endpoint), transport);
        if (useCookies) establishCookies(client, userName, sessionId);
        return client;
    }

    private String salesforceStreamingEndpoint(String endpoint) throws MalformedURLException {
        return new URL(endpoint + streamingEndpointUri).toExternalForm();
    }

    /**
     * establish the Cookies fot the http client.
     *
     * @param client
     * @param user
     * @param sid
     */
    private static void establishCookies(BayeuxClient client, String user, String sid) {
        client.setCookie(SalesforceConstant.COOKIE_LOCALEINFO_KEY, SalesforceConstant.COOKIE_LOCALEINFO_DEFAULT_VALUE, waitTime);
        client.setCookie(SalesforceConstant.COOKIE_LOGIN_KEY, user, waitTime);
        client.setCookie(SalesforceConstant.COOKIE_SESSION_ID_KEY, sid, waitTime);
        client.setCookie(SalesforceConstant.COOKIE_LANGUAGE_KEY, SalesforceConstant.COOKIE_LANGUAGE_DEFAULT_VALUE, waitTime);
    }

    /**
     * load essential property for salesforce inbound endpoint.
     *
     * @param properties
     */
    private void loadMandatoryParameters(Properties properties) {
        if (log.isDebugEnabled()) {
            log.debug("Starting to load the salesforce credentials");
        }

        userName = properties.getProperty(SalesforceConstant.USER_NAME);
        salesforceObject = properties.getProperty(SalesforceConstant.SOBJECT);
        password = properties.getProperty(SalesforceConstant.PASSWORD);
        loginEndpoint = properties.getProperty(SalesforceConstant.LOGIN_ENDPOINT);
        packageName = properties.getProperty(SalesforceConstant.PACKAGE_NAME);
        packageVersion = properties.getProperty(SalesforceConstant.PACKAGE_VERSION);

        if (StringUtils.isEmpty(userName) && StringUtils.isEmpty(salesforceObject) &&
                StringUtils.isEmpty(password) && StringUtils.isEmpty(loginEndpoint) &&
                StringUtils.isEmpty(packageName) && StringUtils.isEmpty(packageVersion)) {
            handleException("Mandatory Parameters can't be Empty...");
        }
    }

    /**
     * Load optional parameters for salesforce inbound endpoint file.
     *
     * @param properties
     */
    private void loadOptionalParameters(Properties properties) {
        if (log.isDebugEnabled()) {
            log.debug("Starting to load the salesforce credentials");
        }
        if (properties.getProperty(SalesforceConstant.CONNECTION_TIMEOUT) == null) {
            connectionTimeout = SalesforceConstant.CONNECTION_TIMEOUT_DEFAULT;
        } else {
            connectionTimeout = Integer.parseInt(properties.getProperty(SalesforceConstant.CONNECTION_TIMEOUT));
        }

        if (properties.getProperty(SalesforceConstant.READ_TIMEOUT) == null) {
            readTimeout = SalesforceConstant.READ_TIMEOUT_DEFAULT;
        } else {
            readTimeout = Integer.parseInt(properties.getProperty(SalesforceConstant.READ_TIMEOUT));
        }

        if (properties.getProperty(SalesforceConstant.WAIT_TIME) == null) {
            connectionTimeout = SalesforceConstant.WAIT_TIME_DEFAULT;
        } else {
            waitTime = Integer.parseInt(properties.getProperty(SalesforceConstant.WAIT_TIME));
        }
    }

    public Object poll() {
        //Establishing connection with Salesforce streaming api.
        try {
            if (!isPolled) {
                makeConnect();
                isPolled = true;
            }
        } catch (Exception se) {
            handleException("Error while setup the Salesforce connection.", se);
        }
        return null;
    }

    /**
     * Injecting the salesforce Stream messages to the ESB sequence.
     *
     * @param message the salesforce response status
     */
    private void injectSalesforceMessage(Message message) {
        if (injectingSeq != null) {
            injectMessage(message.getJSON(), SalesforceConstant.CONTENT_TYPE);
            if (log.isDebugEnabled()) {
                log.debug("injecting salesforce message to the sequence : "
                        + injectingSeq);
            }
        } else {
            handleException("the Sequence is not found");
        }
    }

    private void handleException(String msg, Exception ex) {
        log.error(msg, ex);
        throw new SynapseException(ex);
    }

    private void handleException(String msg) {
        log.error(msg);
        throw new SynapseException(msg);
    }

}

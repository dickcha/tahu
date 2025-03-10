/********************************************************************************
 * Copyright (c) 2022 Cirrus Link Solutions and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Cirrus Link Solutions - initial implementation
 ********************************************************************************/

package org.eclipse.tahu.mqtt;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.internal.NetworkModuleService;
import org.eclipse.tahu.exception.TahuErrorCode;
import org.eclipse.tahu.exception.TahuException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An Custom MQTT client.
 */
public class TahuClient implements MqttCallbackExtended {

	private static Logger logger = LoggerFactory.getLogger(TahuClient.class.getName());

	private static final long DEFAULT_CONNECT_RETRY_INTERVAL = 1000;
	private static final long DEFAULT_CONNECT_MONITOR_INTERVAL = 10000;

	private Thread connectRunnableThread;
	private ConnectRunnable connectRunnable;
	private long connectRetryInterval;

	/*
	 * Tracks the state of the connection attempts.
	 */
	private ConnectingState state = new ConnectingState();

	/*
	 * birth/death properties
	 */
	private String birthTopic;
	private byte[] birthPayload;
	private boolean birthRetain;
	private String lwtTopic;
	private byte[] lwtPayload;
	private int lwtQoS;
	private boolean lwtRetain;
	private IMqttDeliveryToken lwtDeliveryToken;
	private Object lwtDeliveryLock = new Object();

	/*
	 * The Asynchronous MQTT Client and MQTTConnectOptions
	 */
	private MqttAsyncClient client = null;
	MqttConnectOptions connectOptions = null;

	/*
	 * Other standard MQTT parameters.
	 */
	private MqttServerUrl mqttServerUrl;
	private MqttServerName mqttServerName;
	private final MqttClientId clientId;
	private String username;
	private String password;
	private boolean cleanSession;
	private int keepAlive;

	/*
	 * The callback client
	 */
	private ClientCallback callback;

	/**
	 * A list of topics the client has subscribed on
	 */
	private SortedMap<String, Integer> subscriptions = new TreeMap<>();

	/*
	 * Odds/ends
	 */
	private boolean autoReconnect;
	private RandomStartupDelay randomStartupDelay;

	/*
	 * The maximum number of in-flight (pending) messages for the client to store. If this maximum is, publishes will
	 * fail with and INTERNAL_ERROR: Caused by: org.eclipse.paho.client.mqttv3.MqttException: Too many publishes in
	 * progress
	 */
	private int maxInFlightMessages = 10;

	private Date connectTime;
	private Date disconnectTime;
	private Date onlineDate;
	private Date offlineDate;
	private double totalUptime;
	private double totalDowntime;
	private int connectionCount = 0; // # of Directors connected to this MQTT Client's Broker
	private boolean doLatencyCheck = false;
	private long numMesgsArrived = 0;
	private long lastNumMesgsArrived = 0;

	private boolean disconnectInProgress = false;

	private Object clientLock = new Object();
	private ConnectionMonitorThread connectionMonitorThread;

	private boolean trackFirstConnection = false;
	private boolean firstConnection = true;

	public TahuClient(final MqttClientId clientId, final MqttServerName mqttServerName,
			final MqttServerUrl mqttServerUrl, final String username, final String password, boolean cleanSession,
			int keepAlive, ClientCallback callback, RandomStartupDelay randomStartupDelay) {
		this.mqttServerUrl = mqttServerUrl;
		this.mqttServerName = mqttServerName;
		this.clientId = clientId;
		this.username = username;
		this.password = password;
		this.cleanSession = cleanSession;
		this.keepAlive = keepAlive;
		this.callback = callback;
		this.randomStartupDelay = randomStartupDelay;
		this.lwtRetain = false;
		this.birthRetain = false;
		this.autoReconnect = true;
		this.setConnectRetryInterval(DEFAULT_CONNECT_RETRY_INTERVAL);
		this.renewDisconnectTime();
		this.renewOnlineDate();
		this.renewOfflineDate();
	}

	public TahuClient(final MqttClientId clientId, final MqttServerName mqttServerName,
			final MqttServerUrl mqttServerUrl, String username, String password, boolean cleanSession, int keepAlive,
			ClientCallback callback, RandomStartupDelay randomStartupDelay, String birthTopic, byte[] birthPayload,
			String lwtTopic, byte[] lwtPayload, int lwtQoS) {
		this(clientId, mqttServerName, mqttServerUrl, username, password, cleanSession, keepAlive, callback,
				randomStartupDelay);
		this.setLifecycleProps(birthTopic, birthPayload, false, lwtTopic, lwtPayload, lwtQoS, false);
	}

	public TahuClient(final MqttClientId clientId, final MqttServerName mqttServerName,
			final MqttServerUrl mqttServerUrl, String username, String password, boolean cleanSession, int keepAlive,
			ClientCallback callback, RandomStartupDelay randomStartupDelay, String birthTopic, byte[] birthPayload,
			boolean birthRetain, String lwtTopic, byte[] lwtPayload, int lwtQoS, boolean lwtRetain) {
		this(clientId, mqttServerName, mqttServerUrl, username, password, cleanSession, keepAlive, callback,
				randomStartupDelay);
		this.setLifecycleProps(birthTopic, birthPayload, birthRetain, lwtTopic, lwtPayload, lwtQoS, lwtRetain);
	}

	/**
	 * Sets the properties relating to client life cycle events such as LWT and Birth topics and payloads.
	 * 
	 * @param birthTopic the topic to publish birth certificates on
	 * @param birthPayload the payload of a birth certificate
	 * @param birthRetain whether to retain birth certificate messages
	 * @param lwtTopic the topic to publish LWT on
	 * @param lwtPayload the payload of an LWT
	 * @param lwtRetain whether to retain LWT messages
	 */
	private void setLifecycleProps(String birthTopic, byte[] birthPayload, boolean birthRetain, String lwtTopic,
			byte[] lwtPayload, int lwtQoS, boolean lwtRetain) {
		this.birthTopic = birthTopic;
		this.birthPayload = birthPayload;
		this.birthRetain = birthRetain;
		this.lwtTopic = lwtTopic;
		this.lwtPayload = lwtPayload;
		this.lwtQoS = lwtQoS;
		this.lwtRetain = lwtRetain;

	}

	protected MqttConnectOptions getMqttConnectOptions() {
		return connectOptions;
	}

	protected void setMqttConnectOptions(MqttConnectOptions connectOptions) {
		this.connectOptions = connectOptions;
	}

	public long getNumMesgsArrived() {
		return numMesgsArrived;
	}

	public long getMesgsArrivedDelta() {
		// Returns the number of messages arrived since last called.
		long delta = numMesgsArrived - lastNumMesgsArrived;
		lastNumMesgsArrived = numMesgsArrived;
		return delta;
	}

	public void clearMesgArrivedCount() {
		numMesgsArrived = 0;
		lastNumMesgsArrived = 0;
	}

	public void setMaxInflightMessages(int max) {
		this.maxInFlightMessages = max;
	}

	public int getMaxInflightMessages() {
		return this.maxInFlightMessages;
	}

	public void setDoLatencyCheck(boolean state) {
		doLatencyCheck = state;
	}

	public boolean getDoLatencyCheck() {
		return doLatencyCheck;
	}

	public void clearConnectionCount() {
		connectionCount = 0;
	}

	public void incrementConnectionCount() {
		connectionCount++;
	}

	public int getConnectionCount() {
		return connectionCount;
	}

	public MqttServerUrl getMqttServerUrl() {
		return mqttServerUrl;
	}

	public MqttServerName getMqttServerName() {
		return mqttServerName;
	}

	public MqttClientId getClientId() {
		return clientId;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassord(String password) {
		this.password = password;
	}

	public int getKeepAlive() {
		return keepAlive;
	}

	public boolean isCleanSession() {
		return cleanSession;
	}

	public Map<String, Integer> getSubscriptions() {
		return Collections.unmodifiableMap(subscriptions);
	}

	public ClientCallback getCallback() {
		// If callback is null, return a no-op implementation
		return this.callback != null ? this.callback : new ClientCallback() {
			@Override
			public void shutdown() {
				return;
			}

			@Override
			public void messageArrived(MqttServerName mqttServerName, MqttServerUrl mqttServerUrl,
					MqttClientId clientId, String topic, MqttMessage message) {
			}

			@Override
			public void connectionLost(MqttServerName mqttServerName, MqttServerUrl mqttServerUrl,
					MqttClientId clientId, Throwable cause) {
			}

			@Override
			public void connectComplete(boolean reconnect, MqttServerName mqttServerName, MqttServerUrl mqttServerUrl,
					MqttClientId clientId) {
			}
		};
	}

	public void setAutoReconnect(boolean autoReconnect) {
		this.autoReconnect = autoReconnect;
	}

	public boolean getAutoReconnect() {
		return autoReconnect;
	}

	public String getLwtTopic() {
		return lwtTopic;
	}

	public void setLwtRetain(boolean retain) {
		this.lwtRetain = retain;
	}

	public boolean getLwtRetain() {
		return lwtRetain;
	}

	public boolean isConnected() {
		if (client != null) {
			return client.isConnected();
		} else {
			return false;
		}
	}

	public long getConnectDuration() throws TahuException {
		if (getConnectTime() != null) {
			Date now = new Date();
			return now.getTime() - getConnectTime().getTime();
		} else if (getDisconnectTime() != null) {
			Date now = new Date();
			return -(now.getTime() - getDisconnectTime().getTime());
		} else {
			throw new TahuException(TahuErrorCode.INTERNAL_ERROR, "Connect time is unknown");
		}
	}

	/**
	 * Returns the availability as a percentage, calculated by uptime/(uptime+downtime).
	 * 
	 * @return a double representing the percentage of availability
	 * @throws TahuException
	 */
	public double getAvailability() throws TahuException {
		if (getConnectTime() != null) {
			Date now = new Date();
			totalUptime = totalUptime + (now.getTime() - getConnectTime().getTime());
		}
		if (getDisconnectTime() != null) {
			Date now = new Date();
			totalDowntime = totalDowntime + (now.getTime() - getDisconnectTime().getTime());
		}

		if ((totalUptime + totalDowntime == 0)) {
			throw new TahuException(TahuErrorCode.INTERNAL_ERROR, "Connect time is unknown");
		}

		return (totalUptime / (totalUptime + totalDowntime)) * 100.0;
	}

	public void resetAvailability() {
		totalUptime = 0;
		totalDowntime = 0;
	}

	/**
	 * Returns a {@link Date} instance representing the online date.
	 * 
	 * @return the online date.
	 */
	public Date getOnlineDateTime() {
		return this.onlineDate;
	}

	/**
	 * Renews the online date.
	 */
	public void renewOnlineDate() {
		this.onlineDate = new Date();
	}

	/**
	 * Returns a {@link Date} instance representing the offline date.
	 * 
	 * @return the offline date.
	 */
	public Date getOfflineDateTime() {
		return this.offlineDate;
	}

	/**
	 * Renews the offline date.
	 */
	public void renewOfflineDate() {
		this.offlineDate = new Date();
	}

	public IMqttDeliveryToken publish(String topic, byte[] payload, int qos, boolean retained) throws TahuException {
		try {
			if (client == null) {
				throw new TahuException(TahuErrorCode.INTERNAL_ERROR,
						"MQTT client: " + clientId.getMqttClientId() + " is null");
			} else if (client.isConnected()) {
				logger.debug("{}: Publishing on Topic {}, Payload Size = {}", getClientId(), topic, payload.length);
				return client.publish(topic, payload, qos, retained);
			} else {
				throw new TahuException(TahuErrorCode.INTERNAL_ERROR,
						"MQTT client: " + clientId.getMqttClientId() + " is not connected");
			}
		} catch (Exception e) {
			throw new TahuException(TahuErrorCode.INTERNAL_ERROR, e);
		}
	}

	public void asyncPublish(String topic, byte[] payload, int qos, boolean retained) throws TahuException {
		Thread t = new Thread(new AsyncPublisher(topic, payload, qos, retained, false, 0, 0));
		t.start();
	}

	public void asyncPublish(String topic, byte[] payload, int qos, boolean retained, boolean retry, long retryDelay,
			int numAttempts) throws TahuException {
		Thread t = new Thread(new AsyncPublisher(topic, payload, qos, retained, retry, retryDelay, numAttempts));
		t.start();
	}

	/**
	 * Subscribes to a topic.
	 * 
	 * @param topic the topic.
	 * @param qos the quality of service (0, 1, or 2)
	 * 
	 * @return the granted QoS for the subscription
	 * @throws TahuException
	 */
	public int subscribe(String topic, int qos) throws TahuException {
		synchronized (clientLock) {
			if (client != null) {
				if (client.isConnected()) {
					try {
						logger.debug("{}: on connection to {} - Attempting to subscribe on topic {} with QoS={}",
								getClientId(), getMqttServerName(), topic, qos);
						IMqttToken token = client.subscribe(topic, qos);
						logger.trace("Waiting for subscription on {}", topic);
						token.waitForCompletion();
						logger.trace("Done waiting for subscription on {}", topic);
						subscriptions.put(topic, qos);
						int[] grantedQos = token.getGrantedQos();
						logger.debug("Granted QoS for subcription on {}: {}", topic, grantedQos[0]);
						if (grantedQos != null && grantedQos.length == 1) {
							return grantedQos[0];
						} else {
							String errorMessage = getClientId() + ": on connection to " + getMqttServerName()
									+ " - Failed to subscribe to " + topic;
							logger.error(errorMessage);
							throw new TahuException(TahuErrorCode.NOT_AUTHORIZED, errorMessage);
						}
					} catch (MqttException e) {
						logger.error(getClientId() + ": on connection to " + getMqttServerName()
								+ " - Failed to subscribe to " + topic);
						throw new TahuException(TahuErrorCode.INTERNAL_ERROR, e);
					}
				}
			}
			logger.debug("Not connected and not subscribing to {} - just storing the subscription for now", topic);
			subscriptions.put(topic, qos);
			return qos;
		}
	}

	/**
	 * Subscribes to a set of topic.
	 * 
	 * @param topics the topics.
	 * @param qos the quality of service (0, 1, or 2)
	 * 
	 * @return the granted QoS levels for the subscriptions
	 * @throws TahuException
	 */
	public int[] subscribe(String[] topics, int[] qos) throws TahuException {
		synchronized (clientLock) {
			try {
				if (client != null) {
					if (client.isConnected()) {
						logger.debug("{}: Attempting to subscribe on topics {} with QoS={}", getClientId(), topics,
								qos);
						IMqttToken token = client.subscribe(topics, qos);
						logger.trace("Waiting for subscription on {}", Arrays.toString(topics));
						token.waitForCompletion();
						logger.trace("Done waiting for subscription on {}", Arrays.toString(topics));
						int[] grantedQos = token.getGrantedQos();
						if (grantedQos != null && grantedQos.length > 0) {
							for (int i = 0; i < topics.length; i++) {
								if (grantedQos[i] == qos[i]) {
									subscriptions.put(topics[i], qos[i]);
								} else {
									throw new TahuException(TahuErrorCode.NOT_AUTHORIZED,
											"Failed to subscribe to " + topics[i]);
								}
							}

							return grantedQos;
						} else {
							throw new TahuException(TahuErrorCode.NOT_AUTHORIZED, "Failed to subscribe to " + topics);
						}
					}
				}

				for (int i = 0; i < topics.length; i++) {
					subscriptions.put(topics[i], qos[i]);
				}
				logger.debug("Not connected and not subscribing to {} - just storing the subscription for now",
						Arrays.asList(topics));
				return qos;
			} catch (Exception e) {
				throw new TahuException(TahuErrorCode.INTERNAL_ERROR, e);
			}
		}
	}

	/**
	 * Unsubsribes from a topic.
	 * 
	 * @param topic the topic.
	 * @throws TahuException
	 */
	public void unsubscribe(String topic) throws TahuException {
		synchronized (clientLock) {
			if (client != null) {
				if (client.isConnected()) {
					try {
						logger.debug("{}: Attempting to unsubscribe on topic {}", getClientId(), topic);
						client.unsubscribe(topic);
					} catch (MqttException e) {
						throw new TahuException(TahuErrorCode.INTERNAL_ERROR, e);
					}
				}
			}
			subscriptions.remove(topic);
		}
	}

	@Override
	public void connectionLost(Throwable cause) {
		logger.debug("{}: MQTT connectionLost() to {} :: {}", getClientId(), getMqttServerName(), getMqttServerUrl());

		// reset the timers if needed
		if (getDisconnectTime() == null) {
			this.clearConnectTime();
			this.renewDisconnectTime();
			this.renewOfflineDate();
		}

		if (cause != null) {
			// We don't need to see all of the connection lost callbacks for clients
			logger.debug("{}: Connection lost due to {}", getClientId(), cause.getMessage(), cause);
		}

		// Trigger the connection lost event on the callback client
		getCallback().connectionLost(getMqttServerName(), getMqttServerUrl(), getClientId(), cause);
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
		synchronized (lwtDeliveryLock) {
			if (lwtDeliveryToken != null && lwtDeliveryToken.getMessageId() == token.getMessageId()) {
				logger.info("{}: LWT Delivery complete for {}", getClientId(), token.getMessageId());
				lwtDeliveryToken = null;
			} else {
				logger.debug("{}: Delivery complete for {}", getClientId(), token.getMessageId());
			}
		}
	}

	@Override
	public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
		logger.debug("{}: MQTT message arrived on topic {}", getClientId(), topic);
		numMesgsArrived++;
		getCallback().messageArrived(getMqttServerName(), getMqttServerUrl(), getClientId(), topic, mqttMessage);
	}

	/**
	 * Attempt to connect the TahuClient
	 */
	public void connect() {
		try {
			NetworkModuleService.validateURI(mqttServerUrl.getMqttServerUrl());
		} catch (Exception e) {
			logger.error("Invalid MQTT Server URL: {}", mqttServerUrl.getMqttServerUrl());
			return;
		}

		logger.debug("{}: Starting new connect, autoReconnect: {}", getClientId(), autoReconnect);
		synchronized (clientLock) {
			logger.debug("{}: Got lock for new connect", getClientId());
			try {
				// reset the timers if needed
				if (getDisconnectTime() == null) {
					this.clearConnectTime();
					this.renewDisconnectTime();
				}

				if (getAutoReconnect() && state.inProgress()) {
					logger.debug("{}: Connect attempt already in progress", getClientId());
					return;
				} else {
					disconnect(0, 0, false, true);
					state.setInProgress(true);
					logger.debug("{}: Starting ConnectThread", getClientId());
					connectRunnable = new ConnectRunnable(this);
					connectRunnableThread = new Thread(connectRunnable);
					connectRunnableThread.start();
				}
			} catch (Throwable t) {
				logger.error("{}: Error connectiong", getClientId(), t);
			}
		}
	}

	public boolean isDisconnectInProgress() {
		return disconnectInProgress;
	}

	/**
	 * Attempt to disconnect the TahuClient.
	 * 
	 * @param retryConnect true if the client should attempt to reconnect.
	 */
	public void disconnect(long disconnectQuieseTime, long disconnectTimeout, boolean sendDisconnect,
			boolean waitForLwt) throws TahuException {
		this.disconnect(disconnectQuieseTime, disconnectTimeout, sendDisconnect, true, waitForLwt);
	}

	/**
	 * Attempt to disconnect the TahuClient.
	 * 
	 * @param retryConnect true if the client should attempt to reconnect.
	 */
	public void disconnect(long disconnectQuieseTime, long disconnectTimeout, boolean sendDisconnect,
			boolean publishLwt, boolean waitForLwt) throws TahuException {
		synchronized (clientLock) {
			disconnectInProgress = true;

			try {
				shutdownConnectionMonitorThread();
			} catch (Exception e) {
				logger.error("Failed to shutdown connection monitor thread");
			}

			try {
				if (connectRunnable != null && connectRunnableThread != null) {
					connectRunnable.stopConnectAttempts();
					connectRunnableThread.interrupt();
				}
			} catch (Exception e) {
				logger.error("Failed to shut down the connect runnable");
			}

			if (client != null) {
				try {
					boolean clientConnected = client.isConnected();
					boolean lwtDeliveryComplete = false;
					if (publishLwt && lwtTopic != null && clientConnected) {
						logger.info("{}: Publishing LWT on {} with qos={} and retain={}", getClientId(), lwtTopic,
								lwtQoS, lwtRetain);
						synchronized (lwtDeliveryLock) {
							/* 
							 * Synchronization with the deliveryComplete() callback is needed to ensure that
							 * the publish() call is fully completed and the lwtDeliveryToken is set before
							 * it is being nullified in the Paho callback.
							*/
							lwtDeliveryToken = publish(lwtTopic, lwtPayload, lwtQoS, lwtRetain);
							logger.debug("published on LWT Topic={}, messageId={}", lwtTopic,
									lwtDeliveryToken.getMessageId());
						}

						if (waitForLwt) {
							lwtDeliveryComplete = isLwtDeliveryComplete();
							logger.trace("Completed LWT Delivery? {}", lwtDeliveryComplete);
						} else {
							logger.trace("Not waiting for LWT");
						}
					} else {
						logger.debug("{}: Not publishing LWT, client connected state: {}", getClientId(),
								clientConnected);
					}

					logger.debug("Disconnecting...");
					client.disconnectForcibly(disconnectQuieseTime, disconnectTimeout, sendDisconnect);
					logger.debug("Done disconecting");
					client.close();
					logger.debug("Client closed");
				} catch (MqttException e) {
					throw new TahuException(TahuErrorCode.INTERNAL_ERROR, e);
				} finally {
					client = null;
					state.setInProgress(false);
					disconnectInProgress = false;
					lwtDeliveryToken = null;
				}
			} else {
				logger.debug("Disconnect: Client is already null");
			}

			// reset the timers if needed
			if (getDisconnectTime() == null) {
				this.clearConnectTime();
				this.renewDisconnectTime();
				this.renewOfflineDate();
			}

			disconnectInProgress = false;
		}
	}

	/*
	 * Attempt to connect.
	 */
	private void attemptConnect(MqttAsyncClient client, MqttConnectOptions options, String ctx)
			throws MqttSecurityException, MqttException {
		synchronized (clientLock) {
			if (isConnected()) {
				logger.trace("{} is already connected - not trying again", getClientId());
				return;
			}
			if (randomStartupDelay != null && randomStartupDelay.isValid()) {
				long randomDelay = randomStartupDelay.getRandomDelay();
				logger.debug("Waiting random delay of {} ms before reconnect attempt", randomDelay);
				try {
					Thread.sleep(randomDelay);
				} catch (InterruptedException e) {
					logger.warn("Sleep interrupted", e);
				}
			}

			logger.debug("{}: Attempting {} to {}", getClientId(), ctx, getMqttServerUrl());
			logger.trace("{}: Thread {} :: {}", getClientId(), Thread.currentThread().getName(),
					Thread.currentThread().getId());

			// Make the call to connect (this is asynchronous)
			client.connect(options, ctx, new IMqttActionListener() {

				@Override
				public void onSuccess(IMqttToken token) {
					logger.info("{}: {} succeeded", getClientId(), token.getUserContext());
					state.setInProgress(false);
				}

				@Override
				public void onFailure(IMqttToken token, Throwable throwable) {
					logger.warn("{}: {} failed due to {}", getClientId(), token.getUserContext(),
							throwable != null ? throwable.getMessage() : "?", throwable);
					logger.warn("MQTT Client details: {}", getTahuClientDetails());
					state.setInProgress(false);
				}

				private String getTahuClientDetails() {
					StringBuilder sb = new StringBuilder();
					sb.append("MQTT Server Name = ").append(mqttServerName).append(" :: ");
					sb.append("MQTT Server URL = ").append(mqttServerUrl).append(" :: ");
					sb.append("MQTT Client ID = ").append(clientId).append(" :: ");
					sb.append("Using Birth = ").append(birthTopic == null || birthTopic.isEmpty() ? "false" : "true")
							.append(" :: ");
					sb.append("Using LWT = ").append(lwtTopic == null || lwtTopic.isEmpty() ? "false" : "true");
					return sb.toString();
				}
			});
		}
	}

	/**
	 * A class for tracking the connect in-progress state.
	 */
	private class ConnectingState {

		private boolean inProgress = false;

		protected void setInProgress(boolean inProgress) {
			this.inProgress = inProgress;
		}

		protected boolean inProgress() {
			return this.inProgress;
		}
	}

	/**
	 * A Runnable implementation for connecting the client to a broker. Will continue to attempt to connect on failure
	 * until the client is disconnected (setting the keepConnected flag to false).
	 */
	protected class ConnectRunnable implements Runnable {

		private MqttCallback callback;

		private boolean attemptConnects = true;

		public ConnectRunnable(final MqttCallback callback) {
			this.callback = callback;
		}

		public void stopConnectAttempts() {
			attemptConnects = false;
		}

		@Override
		public void run() {
			// ensure we are disconnected and null
			if (client != null) {
				try {
					if (client.isConnected()) {
						client.disconnectForcibly(0, 1, false);
						shutdownConnectionMonitorThread();
					}
					// client.setCallback(null);
					client.close();
				} catch (MqttException e) {
					logger.error("{}: Error while disconnecting client", getClientId(), e);
				} finally {
					client = null;
				}
			}

			try {
				if (connectOptions == null) {
					connectOptions = new MqttConnectOptions();
				}
				connectOptions.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
				connectOptions.setCleanSession(cleanSession);
				connectOptions.setConnectionTimeout(30);
				if (getUsername() != null && !getUsername().trim().isEmpty()) {
					logger.debug("{}: Setting username to {}", getClientId(), getUsername());
					connectOptions.setUserName(getUsername());
				}
				if (getPassword() != null && !getPassword().trim().isEmpty()) {
					logger.debug("{}: Setting password to ****", getClientId());
					connectOptions.setPassword(getPassword().toCharArray());
				}
				connectOptions.setKeepAliveInterval(keepAlive);
				if (lwtTopic != null) {
					logger.debug("{}: Setting WILL on {} with retain {}", getClientId(), lwtTopic, lwtRetain);
					connectOptions.setWill(lwtTopic, lwtPayload, MqttOperatorDefs.QOS1, lwtRetain);
				}
				connectOptions.setMaxInflight(getMaxInflightMessages());

				// Create the client instance
				logger.info("{}: Creating the MQTT Client to {} on thread {}", getClientId(), getMqttServerUrl(),
						Thread.currentThread().getName());
				client = new MqttAsyncClient(getMqttServerUrl().toString(), getClientId().toString(), null);

				// Set the callback handler
				client.setCallback(callback);

				if (autoReconnect) {
					try {
						while (!isConnected() && attemptConnects) {
							try {
								synchronized (clientLock) {
									if (!attemptConnects) {
										logger.info("{}: No longer attempting to connect", getClientId());
										return;
									}

									attemptConnect(client, connectOptions, "connect with retry");
								}

								// Sleep for the connect retry interval
								Thread.sleep(getConnectRetryInterval());
							} catch (InterruptedException ie) {
								logger.info("{}: Connect thread {} interrupted - giving up",
										Thread.currentThread().getName(), getClientId());
								return;
							} catch (MqttException e) {
								if (e.getReasonCode() == MqttException.REASON_CODE_CONNECT_IN_PROGRESS) {
									logger.debug("{}: Still trying to connect", getClientId());
									Thread.sleep(500);
								} else {
									logger.debug("{}: Unable to connect due to {}, next connect attempt in {} ms",
											getClientId(), e.getMessage(), getConnectRetryInterval());
									Thread.sleep(getConnectRetryInterval());
								}
							}
						}

						logger.info("{}: MQTT Client connected to {} on thread {}", getClientId(), getMqttServerUrl(),
								Thread.currentThread().getName());
					} catch (InterruptedException ie) {
						logger.info("{}: Connect thread 2 interrupted - giving up", getClientId());
						return;
					} catch (Throwable throwable) {
						logException("Error while attempting connect to " + getMqttServerUrl(), throwable);
					} finally {
						logger.debug("{}: Setting connectAttemptInProgress to false", getClientId());
						state.setInProgress(false);
					}
				} else {
					try {
						synchronized (clientLock) {
							if (!attemptConnects) {
								logger.info("{}: No longer attempting to connect", getClientId());
								return;
							}

							// Attempt to connect
							attemptConnect(client, connectOptions, "connect");
						}
					} catch (Throwable throwable) {
						logException("Error while attempting connect to " + getMqttServerUrl(), throwable);
					}
				}
			} catch (Exception e) {
				logger.error("{}: Error while connecting client", getClientId(), e);
				state.setInProgress(false);

				if (autoReconnect) {
					logger.warn("{}: Connect failed - retrying", getClientId());
					try {
						if (randomStartupDelay != null && randomStartupDelay.isValid()) {
							long randomDelay = randomStartupDelay.getRandomDelay();
							logger.info("Sleeping {} before reconnect attempt", randomDelay);
							Thread.sleep(randomDelay);
						} else {
							Thread.sleep(getConnectRetryInterval());
						}
					} catch (InterruptedException ie) {
						logger.warn("{}: InterruptedException while preparing to reconnect", getClientId(), ie);
						return;
					}
					if (autoReconnect) {
						connect();
					} else {
						logger.warn("{}: AutoReconnect canceled - No longer going to retry", getClientId());
						return;
					}
				}
			}
		}
	}

	private class AsyncPublisher implements Runnable {

		private String topic;
		private byte[] payload;
		private int qos;
		private boolean retained;

		// Retry params
		private boolean retry = false;
		private long retryDelay;
		private int numAttempts;

		public AsyncPublisher(String topic, byte[] payload, int qos, boolean retained, boolean retry, long retryDelay,
				int numAttempts) {
			this.topic = topic;
			this.payload = payload;
			this.qos = qos;
			this.retained = retained;
			this.retry = retry;
			this.retryDelay = retryDelay;
			this.numAttempts = numAttempts;
		}

		@Override
		public void run() {
			try {
				if (retry) {
					for (int i = 0; i < numAttempts; i++) {
						if (client == null || !client.isConnected()) {
							Thread.sleep(retryDelay);
						} else {
							logger.debug("{}: Publishing on {}, Payload size = {}", getClientId(), topic,
									payload.length);
							client.publish(topic, payload, qos, retained);
						}
					}

					logger.error("{}: Failed to publish message on {} after {} attempts", getClientId(), topic,
							numAttempts);
					throw new TahuException(TahuErrorCode.INTERNAL_ERROR,
							"Failed to publish message on " + topic + " after " + numAttempts + " attempts");
				} else {
					if (client == null) {
						throw new TahuException(TahuErrorCode.INTERNAL_ERROR, "MQTT client is null");
					} else if (client.isConnected()) {
						logger.debug("{}: Publishing on {}, Payload size = {}", getClientId(), topic, payload.length);
						client.publish(topic, payload, qos, retained);
					} else {
						throw new TahuException(TahuErrorCode.INTERNAL_ERROR, "MQTT client not connected");
					}
				}
			} catch (Exception e) {
				logger.error("{}: Failed to publish", getClientId(), e);
			}
		}
	}

	private void shutdownConnectionMonitorThread() {
		if (connectionMonitorThread == null) {
			logger.debug("{}: Not shutting down ConnectionMonitorThread - its null", getClientId());
			return;
		}
		if (connectionMonitorThread.isAlive()) {
			logger.debug("{}: Shutting down ConnectionMonitorThread", getClientId());
			connectionMonitorThread.shutdown();
			connectionMonitorThread = null;
		} else {
			logger.debug("{}: Not shutting down ConnectionMonitorThread - its not alive", getClientId());
		}
	}

	private class ConnectionMonitorThread extends Thread {
		private ConnectionMonitor connectionMonitor;

		public ConnectionMonitorThread(ConnectionMonitor connectionMonitor) {
			super(connectionMonitor);
			this.connectionMonitor = connectionMonitor;
		}

		public void shutdown() {
			connectionMonitor.setKeepRunning(false);
			this.interrupt();
		}
	}

	private class ConnectionMonitor implements Runnable {

		private final MqttAsyncClient monitoredClient;
		private final MqttClientId monitoredClientId;
		private boolean keepRunning = true;

		public ConnectionMonitor(MqttAsyncClient client, MqttClientId clientId) {
			this.monitoredClient = client;
			this.monitoredClientId = clientId;
		}

		public void setKeepRunning(boolean keepRunning) {
			this.keepRunning = keepRunning;
		}

		public void run() {
			try {
				int connectionLostCounter = 0;
				while (keepRunning) {
					synchronized (clientLock) {
						if (monitoredClient != null) {
							if (!monitoredClient.isConnected()) {
								if (state.inProgress()) {
									logger.debug("{}: ConnectionMonitor - Attempting to connect", monitoredClientId);
									connectionLostCounter = 0;
								} else {
									logger.debug("{}: ConnectionMonitor - Not connected, incrementing counter",
											monitoredClientId);
									connectionLostCounter++;
								}
							} else {
								logger.trace("{}: ConnectionMonitor - Already connected", monitoredClientId);
								connectionLostCounter = 0;
							}
						} else {
							logger.debug("{}: ConnectionMonitor - Client is null - Uncaught connectionLost",
									getClientId());
							connectionLostCounter = 5;
						}
					}

					if (connectionLostCounter == 5 && callback != null) {
						callback.connectionLost(mqttServerName, mqttServerUrl, monitoredClientId,
								new Throwable(monitoredClientId + ": Uncaught paho disconnect"));
					}

					try {
						Thread.sleep(DEFAULT_CONNECT_MONITOR_INTERVAL);
					} catch (InterruptedException ie) {
						logger.debug("{}: ConnectionMonitor interrupted", monitoredClientId);
					}
				}
			} catch (Exception e) {
				logger.error("{}: ConnectionMonitor failed to keep running", monitoredClientId, e);
			}
		}
	}

	@Override
	public void connectComplete(boolean reconnect, String serverURI) {
		synchronized (clientLock) {
			if (reconnect) {
				logger.debug("{}: SUCCESSFULLY RECONNECTED to {}", getClientId(), getMqttServerUrl());
			}

			if (autoReconnect) {
				if (connectionMonitorThread == null || !connectionMonitorThread.isAlive()) {
					connectionMonitorThread = new ConnectionMonitorThread(new ConnectionMonitor(client, getClientId()));
					connectionMonitorThread.start();
				}
			}

			// The client is connected - renew online date, renew the connect time, clear disconnect time
			this.renewOnlineDate();
			this.renewConnectTime();
			this.clearDisconnectTime();

			logger.info("{}: Connected to {}", getClientId(), getMqttServerUrl());

			// Call connectComplete() with the callback
			getCallback().connectComplete(reconnect, getMqttServerName(), getMqttServerUrl(), getClientId());

			// Subscribe (or re-subscribe)
			if (!subscriptions.isEmpty()) {
				// Build up the arrays of topics and QoS levels
				int subscriptionSize = subscriptions.size();
				String[] topics = new String[subscriptionSize];
				int[] qosLevels = new int[subscriptionSize];
				int i = 0;
				for (String topic : subscriptions.keySet()) {
					topics[i] = topic;
					qosLevels[i] = subscriptions.get(topic);
					i++;
				}

				logger.debug("{}: on connection to {} - Attempting to subscribe on topic {} with QoS={}", getClientId(),
						getMqttServerName(), Arrays.toString(topics), Arrays.toString(qosLevels));
				try {
					IMqttToken token = client.subscribe(topics, qosLevels);
					logger.trace("Waiting for subscription on {}", Arrays.toString(topics));
					token.waitForCompletion();
					logger.trace("Done waiting for subscription on {}", Arrays.toString(topics));
					int[] grantedQos = token.getGrantedQos();
					if (Arrays.equals(qosLevels, grantedQos)) {
						logger.debug("{}: on connection to {} - Successfully subscribed to {} on QoS={}", getClientId(),
								getMqttServerName(), Arrays.toString(topics), Arrays.toString(qosLevels));
					} else {
						try {
							logger.error("{}: on connection to {} - Failed to subscribe to {} - forcing disconnect",
									getClientId(), getMqttServerName(), Arrays.toString(topics));

							// FIXME - remove This sleep is necessary due to:
							// https://github.com/eclipse/paho.mqtt.java/issues/850
							Thread.sleep(1000);

							// Force the disconnect and return
							client.disconnectForcibly(0, 1, false);
							return;
						} catch (Exception e) {
							logger.error("{}: on connection to {} - Failed to disconnect on failed subscription",
									getClientId(), getMqttServerName(), e);
						}
					}
				} catch (MqttException e) {
					logger.error("{}: on connection to {} - Failed to subscribe on topic {} with QoS={}", getClientId(),
							getMqttServerName(), Arrays.toString(topics), Arrays.toString(qosLevels), e);
				}
			} else {
				if (trackFirstConnection && !firstConnection) {
					logger.warn("No subscriptions for {}", getClientId());
				}
			}

			// Publish a standard Birth/Death Certificate if a baseTopic has been defined.
			if (birthTopic != null) {
				try {
					logger.debug("{}: Publishing BIRTH on {} with retain {}", getClientId(), birthTopic, birthRetain);
					publish(birthTopic, birthPayload, MqttOperatorDefs.QOS1, birthRetain);
				} catch (TahuException ce) {
					logger.error("{}: Error in birth topic publish on connect", getClientId(), ce);
					try {
						client.disconnectForcibly(0, 1, false);
					} catch (Exception e) {
						logger.error("{}: Failed to disconnect after failed BIRTH publish", getClientId(), e);
					}
				}
			}
			firstConnection = false;
		}
	}

	/**
	 * Sets the 'track first connection' flag
	 * 
	 * @param trackFirstConnection - the 'track first connection' flag as {@link boolean}
	 */
	public void setTrackFirstConnection(boolean trackFirstConnection) {
		synchronized (clientLock) {
			this.trackFirstConnection = trackFirstConnection;
		}
	}

	private Date getConnectTime() {
		return this.connectTime;
	}

	private Date getDisconnectTime() {
		return this.disconnectTime;
	}

	private void clearConnectTime() {
		this.connectTime = null;
	}

	private void clearDisconnectTime() {
		this.disconnectTime = null;
	}

	private void renewConnectTime() {
		this.connectTime = new Date();
	}

	private void renewDisconnectTime() {
		this.disconnectTime = new Date();
	}

	private long getConnectRetryInterval() {
		return connectRetryInterval;
	}

	public void setConnectRetryInterval(long connectRetryInterval) {
		this.connectRetryInterval = connectRetryInterval;
	}

	public boolean isAttemptingConnect() {
		return state.inProgress();
	}

	private String getErrorMessage(String prefix, Throwable throwable) {
		return new StringBuilder(prefix).append(": ").append(getErrorMessage(throwable)).toString();
	}

	private String getErrorMessage(Throwable throwable) {
		StringBuilder sb = new StringBuilder(throwable.getMessage());
		Throwable cause = throwable.getCause();
		if (cause != null) {
			sb.append(": ").append(getErrorMessage(cause));
		}
		return sb.toString();
	}

	private void logException(String message, Throwable throwable) {
		String errorMessage = getErrorMessage(message, throwable);
		if (logger.isTraceEnabled()) {
			// Only log the stack trace if trace is enabled
			logger.error("{}: {}", getClientId(), errorMessage, throwable);
		} else {
			logger.error("{}: {}", getClientId(), errorMessage);
		}
	}

	/*
	 * This method waits to ensure that the LWT gets published before graceful disconnect.
	 * It uses the 'keepAlive' to timeout if the lwtDeliveryToken is not cleared by the deliveryComplete() 
	 * Paho callback.
	 */
	private boolean isLwtDeliveryComplete() {
		int counter = keepAlive * 4;
		for (int i = 0; i < counter; i++) {
			try {
				if (lwtDeliveryToken == null) {
					logger.info("LWT delivery confirmation - done waiting");
					return true;
				} else {
					Thread.sleep(250);
				}
			} catch (InterruptedException e) {
				logger.warn("Interrupted while waiting for LWT");
			}
		}
		lwtDeliveryToken = null;
		logger.warn("LWT delivery confirmation - timeout");
		return false;
	}
}

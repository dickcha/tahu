/*
 * Licensed Materials - Property of Cirrus Link Solutions
 * Copyright (c) 2022 Cirrus Link Solutions LLC - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 */
package org.eclipse.tahu.host.manager;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.tahu.exception.TahuErrorCode;
import org.eclipse.tahu.exception.TahuException;
import org.eclipse.tahu.message.model.EdgeNodeDescriptor;
import org.eclipse.tahu.mqtt.MqttClientId;
import org.eclipse.tahu.mqtt.MqttServerName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SparkplugEdgeNode {

	private static Logger logger = LoggerFactory.getLogger(SparkplugEdgeNode.class.getName());

	// Static variables
	private final EdgeNodeDescriptor edgeNodeDescriptor;
	private final String groupId;
	private final String edgeNodeId;
	private final Map<String, SparkplugDevice> sparkplugDevices;

	// Dynamic variables
	private MqttServerName mqttServerName;
	private MqttClientId hostAppMqttClientId;
	private boolean online;
	private Date onlineTimestamp;
	private Date offlineTimestamp;

	// Sequence number tracking
	private Long birthBdSeqNum;
	private Long lastSeqNum;

	private final Object lock = new Object();

	SparkplugEdgeNode(String groupId, String edgeNodeId, MqttServerName mqttServerName,
			MqttClientId hostAppMqttClientId) {
		this(new EdgeNodeDescriptor(groupId, edgeNodeId), mqttServerName, hostAppMqttClientId);
	}

	SparkplugEdgeNode(EdgeNodeDescriptor edgeNodeDescriptor, MqttServerName mqttServerName,
			MqttClientId hostAppMqttClientId) {
		this.edgeNodeDescriptor = edgeNodeDescriptor;
		this.groupId = edgeNodeDescriptor.getGroupId();
		this.edgeNodeId = edgeNodeDescriptor.getEdgeNodeId();
		this.sparkplugDevices = new ConcurrentHashMap<>();

		this.mqttServerName = mqttServerName;
		this.hostAppMqttClientId = hostAppMqttClientId;
	}

	public EdgeNodeDescriptor getEdgeNodeDescriptor() {
		return edgeNodeDescriptor;
	}

	public String getGroupId() {
		return groupId;
	}

	public String getEdgeNodeId() {
		return edgeNodeId;
	}

	public MqttServerName getMqttServerName() {
		return mqttServerName;
	}

	public void setMqttServerName(MqttServerName mqttServerName) {
		this.mqttServerName = mqttServerName;
	}

	public MqttClientId getHostAppMqttClientId() {
		return hostAppMqttClientId;
	}

	public void setHostAppMqttClientId(MqttClientId hostAppMqttClientId) {
		this.hostAppMqttClientId = hostAppMqttClientId;
	}

	public boolean isOnline() {
		return online;
	}

	public void setOnline(boolean online, Date timestamp, Long incomingBdSeq, Long incomingSeq) throws TahuException {
		synchronized (lock) {
			if (online) {
				if (timestamp == null) {
					throw new TahuException(TahuErrorCode.INVALID_ARGUMENT,
							"The timestamp can not be missing from an NBIRTH message");
				}
				if (incomingBdSeq == null) {
					throw new TahuException(TahuErrorCode.INVALID_ARGUMENT,
							"The bdSeq can not be missing from an NBIRTH message");
				}
				if (incomingSeq == null) {
					throw new TahuException(TahuErrorCode.INVALID_ARGUMENT,
							"The seqNum can not be missing from an NBIRTH message");
				}

				this.online = online;
				this.onlineTimestamp = timestamp;
				this.birthBdSeqNum = incomingBdSeq;
				this.lastSeqNum = incomingSeq;
			} else {
				if (incomingBdSeq == null) {
					throw new TahuException(TahuErrorCode.INVALID_ARGUMENT,
							"The bdSeq can not be missing from an NDEATH message");
				}

				// Check the bdSeq
				if (birthBdSeqNum != incomingBdSeq) {
					logger.debug("Mismatched bdSeq number - got {} expected {} - ignoring", incomingBdSeq,
							birthBdSeqNum);
					return;
				} else {
					this.online = online;
					this.offlineTimestamp = timestamp;
				}
			}
		}
	}

	public Date getOnlineTimestamp() {
		return onlineTimestamp;
	}

	public Date getOfflineTimestamp() {
		return offlineTimestamp;
	}

	public void handleSeq(Long incomingSeq) throws TahuException {
		synchronized (lock) {
			if (lastSeqNum != null) {
				lastSeqNum++;
				if (lastSeqNum.equals(256L)) {
					lastSeqNum = 0L;
				}

				if (!lastSeqNum.equals(incomingSeq)) {
					throw new TahuException(TahuErrorCode.INVALID_ARGUMENT,
							"The sequence number check did not pass - expected " + lastSeqNum + " but received "
									+ incomingSeq);
				}
			} else {
				throw new TahuException(TahuErrorCode.INVALID_ARGUMENT,
						"The sequence number check did not pass - expected " + lastSeqNum + " but received "
								+ incomingSeq);
			}
		}
	}
}

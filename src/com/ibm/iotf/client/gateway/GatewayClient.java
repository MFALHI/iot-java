/**
 *****************************************************************************
 * Copyright (c) 2016 IBM Corporation and other Contributors.

 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Sathiskumar Palaniappan - Initial Contribution
 *****************************************************************************
 */

package com.ibm.iotf.client.gateway;

import java.nio.charset.Charset;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.iotf.client.AbstractClient;
import com.ibm.iotf.client.api.APIClient;
import com.ibm.iotf.util.LoggerUtility;

/**
 * A client, used by Gateway, that handles connections with the IBM Watson IoT Platform. <br>
 * 
 * This is a derived class from AbstractClient and can be used by Gateways 
 * to handle connections with IBM Watson IoT Platform.
 */
public class GatewayClient extends AbstractClient implements MqttCallback{
	
	private static final String CLASS_NAME = GatewayClient.class.getName();
	
	//private static final Pattern GATEWAY_NOTIFICATION_PATTERN = Pattern.compile("iotdm-1/notify");
	private static final Pattern GATEWAY_COMMAND_PATTERN = Pattern.compile("iot-2/type/(.+)/id/(.+)/cmd/(.+)/fmt/(.+)");
	
	private CommandCallback gwCommandCallback = null;
	
	private HashMap<String, Integer> subscriptions = new HashMap<String, Integer>();
	
	private APIClient apiClient = null;
	
	/**
	 * Create a Gateway client for the IBM Watson IoT Platform. 
	 * Connecting to specific org on IBM Watson IoT Platform
	 * @param options
	 * 					An object of the class Properties
	 * @throws Exception 
	 */
	public GatewayClient(Properties options) throws Exception {
		super(options);
		if(getOrgId()==null){
			
			throw new Exception("Invalid Auth Key");
		} else if(getOrgId().equalsIgnoreCase("quickstart")) {
			throw new Exception("There is no quickstart support for Gateways");
		}
		
		this.clientId = "g" + CLIENT_ID_DELIMITER + getOrgId() + 
				CLIENT_ID_DELIMITER + this.getGWDeviceType()  
				+ CLIENT_ID_DELIMITER + getGwDeviceId();
		
		if (getAuthMethod() == null) {
			this.clientUsername = null;
			this.clientPassword = null;
		}
		else if (!getAuthMethod().equals("token")) {
			throw new Exception("Unsupported Authentication Method: " + getAuthMethod());
		}
		else {
			// use-token-auth is the only authentication method currently supported
			this.clientUsername = "use-token-auth";
			this.clientPassword = getAuthToken();
		}
		createClient(this);
		options.setProperty("auth-method", "gateway");
		this.apiClient = new APIClient(options);
	}
	
	public APIClient api() {
		return this.apiClient;
	}
	
	/**
	 * Returns the orgid for this client
	 * 
	 * @return orgid
	 * 						String orgid
	 */
	public String getOrgId() {
		// Check if org id is provided by the user
		String orgid = super.getOrgId();
		if(orgid == null || orgid.equals("")) {
			String authKeyPassed = getAuthKey();
			if(authKeyPassed != null && ! authKeyPassed.trim().equals("") && ! authKeyPassed.equals("quickstart")) {
				if(authKeyPassed.length() >=8){
					return authKeyPassed.substring(2, 8);}
				else {
					return null;
				}
			} else {
				return "quickstart";
			}
		}
		return orgid;

	}
	
	/*
	 * old style - id
	 * new style - Device-ID
	 */
	private String getGwDeviceId() {
		String id = null;
		id = options.getProperty("id");
		if(id == null) {
			id = options.getProperty("Device-ID");
		}
		return trimedValue(id);
	}

	/**
	 * Accessor method to retrieve auth key
	 * @return authKey
	 * 					String authKey
	 */
	public String getAuthKey() {
		String authKeyPassed = options.getProperty("auth-key");
		if(authKeyPassed == null) {
			authKeyPassed = options.getProperty("API-Key");
		}
		return trimedValue(authKeyPassed);
	}
	
	
	@Override
	public void connect() {
		super.connect();
		subscribeToGWCommands();
		//subscribeToGWNotification();
	}
	
	/**
	 * While Gateway publishes events on behalf of the devices connected behind, 
	 * the can publish its own events as well. This method publishes event with the 
	 * specified name and specified QOS.<br>
	 * 
	 * Note that data is published at Quality of Service (QoS) 0, which means that 
	 * a successful send does not guarantee receipt even if the publish has been successful.
	 * 
	 * @param event
	 *            Name of the dataset under which to publish the data
	 * @param data
	 *            Object to be added to the payload as the dataset
	 * @return Whether the send was successful.
	 */
	public boolean publishGatewayEvent(String event, Object data) {
		return publishDeviceEvent(this.getGWDeviceType(), this.getGwDeviceId(), event, data, 0);
	}

	/**
	 * While Gateway publishes events on behalf of the devices connected behind, 
	 * the can publish its own events as well. This method publishes event with the 
	 * specified name and specified QOS.
	 * 
	 * This method allows QoS to be passed as an argument
	 * 
	 * @param event
	 *            Name of the dataset under which to publish the data
	 * @param data
	 *            Object to be added to the payload as the dataset
	 * @param qos
	 *            Quality of Service - should be 0, 1 or 2
	 * @return Whether the send was successful.
	 */	
	public boolean publishGatewayEvent(String event, Object data, int qos) {
		return publishDeviceEvent(this.getGWDeviceType(), this.getGwDeviceId(), event, data, qos);
	}
	
	/**
	 * Publish event, on the behalf of a device, to the IBM Watson IoT Platform. <br> 
	 * Note that data is published
	 * at Quality of Service (QoS) 0, which means that a successful send does not guarantee
	 * receipt even if the publish is successful.
	 * 
	 * @param deviceType
	 *            object of String which denotes deviceType 
	 * @param deviceId
	 *            object of String which denotes deviceId
	 * @param event
	 *            object of String which denotes event
	 * @param data
	 *            Payload data
	 * @return Whether the send was successful.
	 */
	public boolean publishDeviceEvent(String deviceType, String deviceId, String event, Object data) {
		return publishDeviceEvent(deviceType, deviceId, event, data, 0);
	}
	
	/**
	 * Publish event, on the behalf of a device, to the IBM Watson IoT Platform. <br>
	 * This method will attempt to create a JSON obejct out of the payload
	 * 
	 * @param deviceType
	 *            object of String which denotes deviceType 
	 * @param deviceId
	 *            object of String which denotes deviceId
	 * @param event
	 *            object of String which denotes event
	 * @param data
	 *            Payload data
	 * @param qos
	 *            Quality of Service, in int - can have values 0,1,2
	 * @return Whether the send was successful.
	 */
	public boolean publishDeviceEvent(String deviceType, String deviceId, String event, Object data, int qos) {
		if (!isConnected()) {
			return false;
		}
		final String METHOD = "publishEvent(5)";
		JsonObject payload = new JsonObject();
		
		String timestamp = ISO8601_DATE_FORMAT.format(new Date());
		payload.addProperty("ts", timestamp);
		
		JsonElement dataElement = gson.toJsonTree(data);
		payload.add("d", dataElement);
		
		String topic = "iot-2/type/" + deviceType + "/id/" + deviceId + "/evt/" + event + "/fmt/json";
		
		LoggerUtility.fine(CLASS_NAME, METHOD, "Topic   = " + topic);
		LoggerUtility.fine(CLASS_NAME, METHOD, "Payload = " + payload.toString());
		
		MqttMessage msg = new MqttMessage(payload.toString().getBytes(Charset.forName("UTF-8")));
		msg.setQos(0);
		msg.setRetained(false);
		
		try {
			mqttAsyncClient.publish(topic, msg).waitForCompletion();
		} catch (MqttPersistenceException e) {
			e.printStackTrace();
			return false;
		} catch (MqttException e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	private void subscribeToGWCommands() {
		subscribeToDeviceCommands(this.getGWDeviceType(), this.getGwDeviceId());
	}
	
	/**
	 * Subscribe to device commands, on the behalf of a device, from the IBM Watson IoT Platform. <br>
	 * Quality of Service is set to 0 <br>
	 * All commands, for a given device type and device id , are subscribed to
	 * @param deviceType
	 *            object of String which denotes deviceType 
	 * @param deviceId
	 *            object of String which denotes deviceId
	 */
	public void subscribeToDeviceCommands(String deviceType, String deviceId) {
		subscribeToDeviceCommands(deviceType, deviceId, "+", 0);
	}
		
	/**
	 * Subscribe to device commands, on the behalf ofa device, for the IBM Watson IoT Platform. <br>
	 * Quality of Service is set to 0
	 * 
	 * @param deviceType
	 *            object of String which denotes deviceType 
	 * @param deviceId
	 *            object of String which denotes deviceId
	 * @param command
	 *            object of String which denotes command
	 */
	public void subscribeToDeviceCommands(String deviceType, String deviceId, String command) {
		subscribeToDeviceCommands(deviceType, deviceId, command, 0);
	}
	
	/**
	 * Subscribe to device commands, on the behalf of a device, of the IBM Watson IoT Platform. <br>
	 * 
	 * @param deviceType
	 *            object of String which denotes deviceType 
	 * @param deviceId
	 *            object of String which denotes deviceId
	 * @param command
	 *            object of String which denotes command
	 * @param qos
	 *            Quality of Service, in int - can have values 0,1,2
	 */
	public void subscribeToDeviceCommands(String deviceType, String deviceId, String command, int qos) {
		try {
			String newTopic = "iot-2/type/"+deviceType+"/id/"+deviceId+"/cmd/" + command + "/fmt/json";
			subscriptions.put(newTopic, new Integer(qos));
			mqttAsyncClient.subscribe(newTopic, qos);
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}
	
	/*private void subscribeToGWNotification() {
		String newTopic = "iotdm-1/notify";
		subscriptions.put(newTopic, 2);
		try {
			mqttAsyncClient.subscribe(newTopic, 2);
		} catch (MqttException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}*/

	/**
	 * Subscribe to device commands, on the behalf of a device, for the IBM Watson IoT Platform. <br>
	 * Quality of Service is set to 0
	 * @param deviceType
	 *            object of String which denotes deviceType 
	 * @param deviceId
	 *            object of String which denotes deviceId
	 * @param command
	 *            object of String which denotes command
	 * @param format
	 *            object of String which denotes format, typical example of format could be json
	 */

	public void subscribeToDeviceCommands(String deviceType, String deviceId, String command, String format) {
		try {
			String newTopic = "iot-2/type/"+deviceType+"/id/"+deviceId+"/cmd/" + command + "/fmt/" + format;
			subscriptions.put(newTopic, new Integer(0));
			mqttAsyncClient.subscribe(newTopic, 0);
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Subscribe to device commands, on the behalf of a device, of the IBM Watson IoT Platform. <br>
	 * 
	 * @param deviceType
	 *            object of String which denotes deviceType 
	 * @param deviceId
	 *            object of String which denotes deviceId
	 * @param command
	 *            object of String which denotes command
	 * @param format
	 *            object of String which denotes format, typical example of format could be json
	 * @param qos
	 *            Quality of Service, in int - can have values 0,1,2
	 */
	public void subscribeToDeviceCommands(String deviceType, String deviceId, String command, String format, int qos) {
		try {
			String newTopic = "iot-2/type/"+deviceType+"/id/"+deviceId+"/cmd/"+ command +"/fmt/" + format;
			subscriptions.put(newTopic, new Integer(qos));			
			mqttAsyncClient.subscribe(newTopic, qos);
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * If we lose connection, trigger the connect logic to attempt to
	 * reconnect to the IBM Watson IoT Platform.
	 */
	public void connectionLost(Throwable e) {
		final String METHOD = "connectionLost";
		LoggerUtility.info(CLASS_NAME, METHOD, "Connection lost: " + e.getMessage());
		connect();
	    Iterator<Entry<String, Integer>> iterator = subscriptions.entrySet().iterator();
	    LoggerUtility.info(CLASS_NAME, METHOD, "Resubscribing....");
	    while (iterator.hasNext()) {
	        //Map.Entry pairs = (Map.Entry)iterator.next();
	        Entry<String, Integer> pairs = iterator.next();
	        LoggerUtility.info(CLASS_NAME, METHOD, pairs.getKey() + " = " + pairs.getValue());
	        try {
	        	mqttAsyncClient.subscribe(pairs.getKey().toString(), Integer.parseInt(pairs.getValue().toString()));
			} catch (NumberFormatException | MqttException e1) {
				e1.printStackTrace();
			}
//	        iterator.remove(); // avoids a ConcurrentModificationException
	    }
	}
	
	/**
	 * A completed deliver does not guarantee that the message is recieved by the service
	 * because devices send messages with Quality of Service (QoS) 0. The message count
	 * represents the number of messages that were sent by the device without an error on
	 * from the perspective of the device.
	 */
	public void deliveryComplete(IMqttDeliveryToken token) {
		final String METHOD = "deliveryComplete";
		LoggerUtility.fine(CLASS_NAME, METHOD, "token = "+token.getMessageId());
		messageCount++;
	}
	
	/**
	 * The Application client does not currently support subscriptions.
	 */
	public void messageArrived(String topic, MqttMessage msg) throws Exception {
		final String METHOD = "messageArrived";
		if (gwCommandCallback != null) {
			/* Only check whether the message is a application command if a callback 
			 * has been defined for commands, otherwise it is a waste of time
			 * as without a callback there is nothing to process the generated
			 * event.
			 */
			Matcher matcher = GATEWAY_COMMAND_PATTERN.matcher(topic);
			if (matcher.matches()) {
				String type = matcher.group(1);
				String id = matcher.group(2);
				String command = matcher.group(3);
				String format = matcher.group(4);
				Command cmd = new Command(type, id, command, format, msg);
			
				if(cmd.getTimestamp() != null ) {
					LoggerUtility.fine(CLASS_NAME, METHOD, "Command received: " + cmd.toString());	
					gwCommandCallback.processCommand(cmd);					
				} else {
					LoggerUtility.warn(CLASS_NAME, METHOD, "Command is not formatted properly, so not processing");					
				}

				return;
		    }

		}
	}

	public void setCommandCallback(CommandCallback callback) {
		this.gwCommandCallback  = callback;
	}

	private String getGWDeviceType() {
		String type = null;
		type = options.getProperty("type");
		if(type == null) {
			type = options.getProperty("Device-Type");
		}
		return trimedValue(type);
	}


}

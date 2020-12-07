/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.connector;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.util.Utf8;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.AxisFault;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseException;
import org.apache.synapse.SynapseLog;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.Value;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.connector.core.ConnectException;
import org.wso2.carbon.connector.core.util.ConnectorUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Produce the messages to the kafka brokers.
 */
public class KafkaProduceConnector extends AbstractConnector {

    protected static Log log = LogFactory.getLog(KafkaProduceConnector.class);

    private static final DecoderFactory decoderFactory = DecoderFactory.get();
    private Object key = null;
    private Object message = null;

    public void connect(MessageContext messageContext) throws ConnectException {

        SynapseLog log = getLog(messageContext);
        log.auditLog("SEND : send message to  Broker lists");
        try {
            // Get the maximum pool size
            String maxPoolSize = (String) messageContext
                    .getProperty(KafkaConnectConstants.CONNECTION_POOL_MAX_SIZE);
            // Read the topic from the parameter
            String topic = lookupTemplateParameter(messageContext, KafkaConnectConstants.PARAM_TOPIC);
            String keySerializerClass = (String) messageContext.getProperty(KafkaConnectConstants.KAFKA_KEY_SERIALIZER_CLASS);
            String valueSerializerClass = (String) messageContext.getProperty(KafkaConnectConstants.KAFKA_VALUE_SERIALIZER_CLASS);
            // Read schema registry url the parameter
            String schemaRegistryUrl = (String) messageContext.getProperty(KafkaConnectConstants.KAFKA_SCHEMA_REGISTRY_URL);

            Schema keySchema = null;
            Schema valueSchema = null;
            String keyPayload = null;
            String valuePayload = null;
            if (keySerializerClass.contains("KafkaAvroSerializer")) {
                // Read keySchemaId, keySchema and key from the parameters
                String keySchemaId = lookupTemplateParameter(messageContext, KafkaConnectConstants.KAFKA_KEY_SCHEMA_ID);
                String keySchemaString = lookupTemplateParameter(messageContext, KafkaConnectConstants.KAFKA_KEY_SCHEMA);
                keyPayload = lookupTemplateParameter(messageContext, KafkaConnectConstants.KAFKA_KEY);

                keySchema = parseSchema(schemaRegistryUrl, keySchemaId, keySchemaString, messageContext);
            }
            if (valueSerializerClass.contains("KafkaAvroSerializer")) {
                // Read valueSchemaId, valueSchema and value from the parameters
                String valueSchemaId = lookupTemplateParameter(messageContext, KafkaConnectConstants.KAFKA_VALUE_SCHEMA_ID);
                String valueSchemaString = lookupTemplateParameter(messageContext, KafkaConnectConstants.KAFKA_VALUE_SCHEMA);
                valuePayload = lookupTemplateParameter(messageContext, KafkaConnectConstants.KAFKA_VALUE);

                valueSchema = parseSchema(schemaRegistryUrl, valueSchemaId, valueSchemaString, messageContext);
            }

            if (keySchema != null && keySchema.getType() == Schema.Type.RECORD) {
                key = jsonToAvro(keyPayload, keySchema);
            } else {
                key = keyPayload;
            }
            if (valueSchema != null && valueSchema.getType() == Schema.Type.RECORD) {
                message = jsonToAvro(valuePayload, valueSchema);
            } else {
                message = valuePayload;
            }

            //Generate the key.
            if (key == null) {
                key = String.valueOf(UUID.randomUUID());
            }
            //Read the partition No from the parameter
            String partitionNo = lookupTemplateParameter(messageContext, KafkaConnectConstants.PARTITION_NO);
            if (message == null) {
                message = getMessage(messageContext);
            }
            org.apache.kafka.common.header.Headers headers = getDynamicParameters(messageContext, topic);
            if (StringUtils.isEmpty(maxPoolSize) || KafkaConnectConstants.DEFAULT_CONNECTION_POOL_MAX_SIZE
                    .equals(maxPoolSize)) {
                //Make the producer connection without connection pool
                sendWithoutPool(messageContext, topic, partitionNo, key, message, headers);
            } else {
                //Make the producer connection with connection pool
                sendWithPool(messageContext, topic, partitionNo, key, message, headers);
            }
        } catch (AxisFault axisFault) {
            handleException("Kafka producer connector " +
                    ": Error sending the message to broker lists", axisFault, messageContext);
        } catch (IOException e) {
            handleException("Error sending avro message to broker", e, messageContext);
        }
    }

    /**
     * Method to parse the json schema. If the schema is not provided, then the schema
     * is obtained from the confluence schema registry
     *
     * @param schemaRegistryUrl The confluence schema registry url
     * @param schemaId          The schema id
     * @param schemaString      The schema string
     * @return Avro Schema from the provided json schema
     */
    public Schema parseSchema(String schemaRegistryUrl, String schemaId, String schemaString,
                              MessageContext messageContext) {
        Schema.Parser parser = new Schema.Parser();
        Schema schema = null;
        if (schemaString != null) {
            schema = parser.parse(schemaString);
        } else if (schemaId != null) {
            SchemaRegistryReader reader = new SchemaRegistryReader();
            schema = reader.getSchemaFromID(schemaRegistryUrl, schemaId);
        } else {
            handleException("Error parsing json schema.", new SynapseException("Either schema or schemaId must be " +
                    "provided. Both schema and schemaId can not be null."), messageContext);
        }
        return schema;
    }

    /**
     * Get the messages from the message context and format the messages.
     */
    private String getMessage(MessageContext messageContext) throws AxisFault {
        Axis2MessageContext axisMsgContext = (Axis2MessageContext) messageContext;
        org.apache.axis2.context.MessageContext msgContext = axisMsgContext.getAxis2MessageContext();
        return formatMessage(msgContext);
    }

    /**
     * Will generate the dynamic parameters from message context parameter
     *
     * @param messageContext The message contest
     * @param topicName      The topicName to generate the dynamic parameters
     * @return extract the value's from properties and make its as header
     */
    private org.apache.kafka.common.header.Headers getDynamicParameters(MessageContext messageContext,
                                                                        String topicName) {
        org.apache.kafka.common.header.Headers headers = new RecordHeaders();
        String key = KafkaConnectConstants.METHOD_NAME + topicName;
        Map<String, Object> propertiesMap = (((Axis2MessageContext) messageContext).getProperties());
        for (String keyValue : propertiesMap.keySet()) {
            if (keyValue.startsWith(key)) {
                Value propertyValue = (Value) propertiesMap.get(keyValue);
                headers.add(keyValue.substring(key.length() + 1, keyValue.length()), propertyValue
                        .getKeyValue().getBytes());
            }
        }
        return headers;
    }

    /**
     * Format the messages when the messages are sent to the kafka broker
     */
    private static String formatMessage(org.apache.axis2.context.MessageContext messageContext)
            throws AxisFault {
        OMOutputFormat format = BaseUtils.getOMOutputFormat(messageContext);
        MessageFormatter messageFormatter = MessageProcessorSelector.getMessageFormatter(messageContext);
        StringWriter stringWriter = new StringWriter();
        OutputStream out = new WriterOutputStream(stringWriter, format.getCharSetEncoding());
        try {
            messageFormatter.writeTo(messageContext, format, out, true);
        } catch (IOException e) {
            throw new AxisFault("The Error occurs while formatting the message", e);
        } finally {
            try {
                out.close();
            } catch (Exception e) {
                throw new AxisFault("The Error occurs while closing the output stream", e);
            }
        }
        return stringWriter.toString();
    }

    /**
     * Send the messages to the kafka broker with topic and the key that is optional.
     *
     * @param producer    The instance of the Kafka producer.
     * @param topic       The topic to send the message.
     * @param partitionNo The partition Number of the broker.
     * @param key         The key.
     * @param message     The message that send to kafka broker.
     * @param headers     The kafka headers
     */
    private void send(KafkaProducer<Object, Object> producer, String topic, String partitionNo, Object key,
                      Object message, org.apache.kafka.common.header.Headers headers, MessageContext messageContext)
            throws ExecutionException, InterruptedException {

        Integer partitionNumber = null;
        try {
            if (!StringUtils.isEmpty(partitionNo)) {
                partitionNumber = Integer.parseInt(partitionNo);
            }
        } catch (NumberFormatException e) {
            log.error("Invalid Partition Number, hence passing null as the partition number", e);
        }

        Future<RecordMetadata> metaData;
        metaData = producer.send(new ProducerRecord<>(topic, partitionNumber, key, message, headers));
        messageContext.setProperty("topic", metaData.get().topic());
        messageContext.setProperty("offset", metaData.get().offset());
        messageContext.setProperty("partition", metaData.get().partition());
        producer.flush();

    }

    /**
     * Send the messages with connection pool.
     *
     * @param messageContext The message context.
     * @param topic          The topic.
     * @param partitionNo    The partition Number of the broker.
     * @param key            The key.
     * @param message        The message.
     * @param headers        The custom header.
     * @throws ConnectException The Exception while create the connection from the Connection pool.
     */
    private void sendWithPool(MessageContext messageContext, String topic, String partitionNo, Object key,
                              Object message, org.apache.kafka.common.header.Headers headers)
            throws ConnectException {

        KafkaProducer<Object, Object> producer = KafkaConnectionPool.getConnectionFromPool();
        if (producer == null) {
            KafkaConnectionPool.initialize(messageContext);
        }

        try {
            if (producer != null) {
                send(producer, topic, partitionNo, key, message, headers, messageContext);
            } else {
                //If any error occurs while getting the connection from the pool.
                sendWithoutPool(messageContext, topic, partitionNo, key, message, headers);
            }
        } catch (Exception e) {
            handleException("Kafka producer connector:" +
                    "Error sending the message to broker lists with connection Pool", e, messageContext);
        } finally {
            //Close the producer pool connections to all kafka brokers.
            // Also closes the zookeeper client connection if any
            if (producer != null) {
                KafkaConnectionPool.returnConnectionToPool(producer);
            }
        }
    }

    /**
     * Send the messages without connection pool.
     *
     * @param messageContext The message context.
     * @param topic          The topic.
     * @param partitionNo    The partition number of the broker.
     * @param key            The key.
     * @param message        The message.
     * @param headers        The Kafka headers
     * @throws ConnectException The Exception while create the Kafka Connection.
     */
    private void sendWithoutPool(MessageContext messageContext, String topic, String partitionNo, Object key,
                                 Object message, org.apache.kafka.common.header.Headers headers)
            throws ConnectException {
        KafkaConnection kafkaConnection = new KafkaConnection();
        KafkaProducer<Object, Object> producer = kafkaConnection.createNewConnection(messageContext);
        try {
            send(producer, topic, partitionNo, key, message, headers, messageContext);
        } catch (Exception e) {
            handleException("Kafka producer connector:" +
                    "Error sending the message to broker lists without connection Pool", e, messageContext);
        } finally {
            //Close the producer pool connections to all kafka brokers.
            // Also closes the zookeeper client connection if any
            if (producer != null) {
                producer.close();
            }
        }
    }

    /**
     * Read the value from the input parameter
     */
    private static String lookupTemplateParameter(MessageContext messageContext, String paramName) {
        return (String) ConnectorUtils.lookupTemplateParamater(messageContext, paramName);
    }

    /**
     * Method to validate and deserialize json payload to avro object.
     *
     * @param jsonString The json payload.
     * @param schema     The schema.
     * @throws IOException On error.
     * @returns The avro object.
     */
    public static Object jsonToAvro(String jsonString, Schema schema) throws IOException {
        try {
            DatumReader<Object> reader = new GenericDatumReader(schema);
            Object object = reader.read(null, decoderFactory.jsonDecoder(schema, jsonString));

            if (schema.getType().equals(Schema.Type.STRING)) {
                object = ((Utf8) object).toString();
            }
            return object;
        } catch (IOException e) {
            log.error(String.format("Error deserializing json %s to Avro of schema %s", jsonString, schema));
            throw new IOException("Error deserializing json to Avro schema", e);
        }
    }
}
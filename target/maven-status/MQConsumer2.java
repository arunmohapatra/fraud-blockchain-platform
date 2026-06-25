/*Here's a complete production-style example using:

JNDI InitialContext
ConnectionFactory lookup
JMSContext with SESSION_TRANSACTED
IBM MQ Queue consumption
Commit on success
Rollback on failure
Poison message handling using JMSXDeliveryCount
Dead Letter Queue (optional)

*/


import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;

import javax.naming.Context;
import javax.naming.InitialContext;
import java.util.Properties;

public class IBMMQTransactionalConsumer {

    private static final String CONNECTION_FACTORY_JNDI_NAME = "jms/ConnectionFactory";

    // Queue Names
    private static final String MAIN_QUEUE_NAME = "queue:///TRADE.INPUT.Q";
    private static final String DEAD_LETTER_QUEUE_NAME = "queue:///TRADE.INPUT.DLQ";

    // Maximum retry count before moving to DLQ
    private static final int MAX_RETRY_COUNT = 5;

    public static void main(String[] args) {

        Context ctx = null;

        try {

            // ------------------------------------------------------------------
            // JNDI Initialization
            // ------------------------------------------------------------------
            Properties props = new Properties();

            // Add if required
            // props.put(Context.INITIAL_CONTEXT_FACTORY, "...");
            // props.put(Context.PROVIDER_URL, "...");

            ctx = new InitialContext(props);

            ConnectionFactory cf =
                    (ConnectionFactory) ctx.lookup(CONNECTION_FACTORY_JNDI_NAME);

            System.out.println("Connection Factory Type : "
                    + cf.getClass().getName());

            // ------------------------------------------------------------------
            // Create Transacted JMS Context
            // ------------------------------------------------------------------
            try (JMSContext jmsContext =
                         cf.createContext(JMSContext.SESSION_TRANSACTED)) {

                Queue mainQueue =
                        jmsContext.createQueue(MAIN_QUEUE_NAME);

                Queue deadLetterQueue =
                        jmsContext.createQueue(DEAD_LETTER_QUEUE_NAME);

                JMSConsumer consumer =
                        jmsContext.createConsumer(mainQueue);

                JMSProducer producer =
                        jmsContext.createProducer();

                System.out.println("Consumer Started...");
                System.out.println("Listening on : " + MAIN_QUEUE_NAME);

                while (true) {

                    try {

                        Message message = consumer.receive();

                        if (message == null) {
                            continue;
                        }

                        String payload = "";

                        if (message instanceof TextMessage) {
                            payload =
                                    ((TextMessage) message).getText();
                        }

                        int deliveryCount = 1;

                        try {
                            deliveryCount =
                                    message.getIntProperty("JMSXDeliveryCount");
                        } catch (Exception ignored) {
                        }

                        System.out.println(
                                "Received Message : "
                                        + payload
                                        + " | Delivery Count : "
                                        + deliveryCount);

                        // ------------------------------------------------------
                        // Business Processing
                        // ------------------------------------------------------
                        processMessage(payload);

                        // ------------------------------------------------------
                        // SUCCESS
                        // Remove message permanently
                        // ------------------------------------------------------
                        jmsContext.commit();

                        System.out.println(
                                "Message processed successfully.");

                    } catch (Exception processingException) {

                        System.err.println(
                                "Processing Error : "
                                        + processingException.getMessage());

                        try {

                            Message failedMessage =
                                    consumer.receiveNoWait();

                            int deliveryCount = 1;

                            if (failedMessage != null) {

                                try {
                                    deliveryCount =
                                            failedMessage.getIntProperty(
                                                    "JMSXDeliveryCount");
                                } catch (Exception ignored) {
                                }

                                if (deliveryCount >= MAX_RETRY_COUNT) {

                                    System.err.println(
                                            "Max retry reached. Moving to DLQ.");

                                    producer.send(
                                            deadLetterQueue,
                                            failedMessage);

                                    jmsContext.commit();

                                    continue;
                                }
                            }

                        } catch (Exception ignored) {
                        }

                        // ------------------------------------------------------
                        // FAILURE
                        // Rollback transaction
                        // Message remains on queue
                        // ------------------------------------------------------
                        jmsContext.rollback();

                        System.err.println(
                                "Transaction rolled back. Message will be redelivered.");
                    }
                }
            }

        } catch (Exception e) {

            System.err.println("Fatal Error:");
            e.printStackTrace();

        } finally {

            try {
                if (ctx != null) {
                    ctx.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Your business logic goes here
     */
    private static void processMessage(String payload) throws Exception {

        System.out.println("Processing payload : " + payload);

        // Example validation failure
        if (payload != null && payload.contains("ERROR")) {

            throw new RuntimeException(
                    "Business validation failed");
        }

        // ------------------------------------------------------
        // Examples:
        // Call Fircosoft
        // Update Database
        // Call REST APIs
        // Publish Event
        // Trade Processing
        // ------------------------------------------------------

        System.out.println("Business Processing Completed");
    }
}
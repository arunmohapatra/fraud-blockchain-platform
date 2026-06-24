package com.example.mq;

import jakarta.jms.*;
import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * IBM MQ Message Consumer using SESSION_TRANSACTED mode.
 * Ensures messages are removed from queue ONLY after successful processing.
 * On failure, messages are rolled back and redelivered.
 */
public class IBMMQConsumer {

    // Maximum number of times a message can be retried before sending to Dead Letter Queue
    private static final int MAX_RETRY_COUNT = 5;

    // Queue names
    private static final String MAIN_QUEUE_NAME = "MYQUEUE";
    private static final String DEAD_LETTER_QUEUE_NAME = "MYQUEUE.DEADLETTER";

    // JNDI name registered by IBM MQ for ConnectionFactory
    private static final String CONNECTION_FACTORY_JNDI_NAME = "MYQUE";

    public static void main(String[] args) {

        // -------------------------------------------------------------------------
        // STEP 1 — Connect to JNDI Naming Service
        // InitialContext reads jndi.properties file from classpath to know
        // where the JNDI registry is running (IBM MQ registers its resources there)
        // -------------------------------------------------------------------------
        InitialContext ctx = null;
        try {
            ctx = new InitialContext();
        } catch (NamingException e) {
            System.err.println("FATAL: Could not connect to JNDI registry. " +
                    "Check jndi.properties file. Error: " + e.getMessage());
            return; // Cannot proceed without JNDI context
        }

        // -------------------------------------------------------------------------
        // STEP 2 — Lookup ConnectionFactory from JNDI
        // ctx.lookup() searches JNDI directory by name "MYQUE"
        // Returns a ConnectionFactory pre-configured with IBM MQ host, port,
        // channel, queue manager details — all set up by your IBM MQ admin
        // -------------------------------------------------------------------------
        ConnectionFactory cf = null;
        try {
            cf = (ConnectionFactory) ctx.lookup(CONNECTION_FACTORY_JNDI_NAME);
        } catch (NamingException e) {
            System.err.println("FATAL: Could not find ConnectionFactory '" +
                    CONNECTION_FACTORY_JNDI_NAME + "' in JNDI registry. " +
                    "Error: " + e.getMessage());
            return; // Cannot proceed without ConnectionFactory
        }

        // -------------------------------------------------------------------------
        // STEP 3 — Create JMSContext in SESSION_TRANSACTED mode
        // cf.createContext() physically opens a connection to IBM MQ broker
        // JMSContext wraps both Connection + Session into a single object (JMS 2.0)
        // SESSION_TRANSACTED means:
        //   - Message is NOT removed from queue on receive()
        //   - Message is removed ONLY when commit() is called
        //   - Message is put back to queue when rollback() is called
        // try-with-resources ensures jmsContext.close() is called automatically
        // when block exits — even if an exception occurs — releasing IBM MQ connection
        // -------------------------------------------------------------------------
        try (JMSContext jmsContext = cf.createContext(JMSContext.SESSION_TRANSACTED)) {

            // -------------------------------------------------------------------------
            // STEP 4 — Create Queue Reference
            // createQueue() does NOT physically create a queue on IBM MQ
            // It simply creates a Java Queue object that holds the name "MYQUEUE"
            // Think of it as writing the address on a slip of paper
            // The queue MUST already exist on IBM MQ server
            // -------------------------------------------------------------------------
            Queue mainQueue = jmsContext.createQueue(MAIN_QUEUE_NAME);

            // Dead Letter Queue — where poison messages go after max retries
            Queue deadLetterQueue = jmsContext.createQueue(DEAD_LETTER_QUEUE_NAME);

            // -------------------------------------------------------------------------
            // STEP 5 — Create JMSConsumer
            // THIS is where IBM MQ queue is physically opened and connected
            // JMSConsumer is your listener/reader handle on MYQUEUE
            // If MYQUEUE does not exist on IBM MQ, error will surface here
            // -------------------------------------------------------------------------
            JMSConsumer consumer = jmsContext.createConsumer(mainQueue);

            System.out.println("Consumer started. Listening on queue: " + MAIN_QUEUE_NAME);

            // -------------------------------------------------------------------------
            // STEP 6 — Continuous Polling Loop
            // Keep running forever, picking up messages one by one
            // In production this would be managed by a thread pool or
            // application server (like Liberty, WebSphere) lifecycle
            // -------------------------------------------------------------------------
            while (true) {

                // -------------------------------------------------------------------------
                // STEP 7 — Receive Message from Queue (Blocking call with timeout)
                // Thread waits here until a message arrives or timeout occurs
                // Message arrives in application memory BUT stays in IBM MQ queue
                // in a "pending/in-flight" state because of SESSION_TRANSACTED mode
                // receive(5000) = wait max 5 seconds, returns null if no message
                // -------------------------------------------------------------------------
                Message message = consumer.receive(5000);

                // If no message arrived within timeout, loop back and keep waiting
                if (message == null) {
                    System.out.println("No message received. Waiting...");
                    continue;
                }

                System.out.println("Message received from queue. Starting processing...");

                // -------------------------------------------------------------------------
                // STEP 8 — Read Delivery Count (IBM MQ tracks how many times
                // this message has been delivered/retried)
                // JMSXDeliveryCount starts at 1 on first delivery
                // Increments by 1 on every rollback + redeliver
                // -------------------------------------------------------------------------
                int deliveryCount = 1; // Default to 1 if property not available
                try {
                    deliveryCount = message.getIntProperty("JMSXDeliveryCount");
                } catch (JMSException e) {
                    System.err.println("Could not read JMSXDeliveryCount. Defaulting to 1.");
                }

                System.out.println("Message delivery attempt #" + deliveryCount);

                // -------------------------------------------------------------------------
                // STEP 9 — Check if message has exceeded max retry limit
                // If this message has already failed MAX_RETRY_COUNT times,
                // it is a poison message — send it to Dead Letter Queue
                // instead of processing again (to avoid infinite retry loop)
                // -------------------------------------------------------------------------
                if (deliveryCount > MAX_RETRY_COUNT) {
                    System.err.println("Message exceeded max retry count of " +
                            MAX_RETRY_COUNT + ". Sending to Dead Letter Queue.");

                    try {
                        // Send the failed message to Dead Letter Queue for manual inspection
                        sendToDeadLetterQueue(jmsContext, deadLetterQueue, message);

                        // Commit here to remove poison message from main queue
                        // and confirm the dead letter queue send
                        jmsContext.commit();

                        System.out.println("Poison message moved to Dead Letter Queue successfully.");

                    } catch (JMSException e) {
                        System.err.println("Failed to send to Dead Letter Queue. " +
                                "Rolling back. Error: " + e.getMessage());
                        jmsContext.rollback();
                    }

                    // Skip normal processing for this message
                    continue;
                }

                // -------------------------------------------------------------------------
                // STEP 10 — Process the Message inside try-catch
                // All your business logic goes inside the try block
                // Success path  → commit()  → message permanently removed from IBM MQ
                // Failure path  → rollback() → message put back to IBM MQ for redelivery
                // -------------------------------------------------------------------------
                try {

                    // Your actual business logic — parse message, save to DB, call API etc.
                    processMessage(message);

                    // -----------------------------------------------------------------
                    // COMMIT — Processing was successful
                    // This is the instruction to IBM MQ:
                    //   "Everything went fine — you can now permanently
                    //    remove this message from the queue"
                    // Only at this point the message is deleted from IBM MQ queue
                    // -----------------------------------------------------------------
                    jmsContext.commit();

                    System.out.println("Message processed successfully. Committed — " +
                            "message removed from queue.");

                } catch (Exception e) {

                    // -----------------------------------------------------------------
                    // ROLLBACK — Processing failed, exception was thrown
                    // This is the instruction to IBM MQ:
                    //   "Something went wrong — put this message back
                    //    into the queue for redelivery"
                    // IBM MQ restores the message as if it was never picked up
                    // JMSXDeliveryCount is incremented by 1 on next delivery
                    // -----------------------------------------------------------------
                    System.err.println("Processing failed on attempt #" + deliveryCount +
                            ". Rolling back message for redelivery. Error: " + e.getMessage());

                    jmsContext.rollback();

                    System.out.println("Rollback complete. Message will be redelivered.");
                }

            } // end of while loop

        } catch (JMSException e) {
            // Catches any unexpected JMS-level errors outside the message loop
            // e.g., IBM MQ connection dropped, queue manager went down etc.
            System.err.println("FATAL JMS Error: " + e.getMessage());
            e.printStackTrace();
        }

    } // end of main()


    // =============================================================================
    // processMessage() — Your Business Logic
    // Parse the incoming JMS message and do whatever your application needs:
    // save to database, call REST API, transform data, publish to another queue etc.
    // Throws Exception on any failure so the caller can rollback the transaction
    // =============================================================================
    private static void processMessage(Message message) throws JMSException {

        // Read message body as a TextMessage (most common type)
        if (message instanceof TextMessage) {
            TextMessage textMessage = (TextMessage) message;
            String messageBody = textMessage.getText();

            System.out.println("Processing message body: " + messageBody);

            // -------------------------------------------------------------------
            // Your business logic goes here
            // Example: saveToDatabase(messageBody);
            // Example: callRestAPI(messageBody);
            // Example: parseAndTransform(messageBody);
            // -------------------------------------------------------------------

        } else if (message instanceof BytesMessage) {
            // Handle binary messages if needed
            BytesMessage bytesMessage = (BytesMessage) message;
            System.out.println("Received BytesMessage. Length: " + bytesMessage.getBodyLength());

            // -------------------------------------------------------------------
            // Your binary processing logic goes here
            // -------------------------------------------------------------------

        } else {
            // Unknown message type — throw exception to trigger rollback
            throw new JMSException("Unsupported message type: " +
                    message.getClass().getName());
        }
    }


    // =============================================================================
    // sendToDeadLetterQueue() — Move Poison Message to Dead Letter Queue
    // Called when a message has exceeded the max retry count
    // Creates a new TextMessage on the Dead Letter Queue with the original content
    // preserving the original JMS MessageID as a header for traceability
    // =============================================================================
    private static void sendToDeadLetterQueue(JMSContext jmsContext,
                                               Queue deadLetterQueue,
                                               Message originalMessage) throws JMSException {

        // Read original message body
        String originalBody = "";
        if (originalMessage instanceof TextMessage) {
            originalBody = ((TextMessage) originalMessage).getText();
        }

        // Create a new message for Dead Letter Queue
        TextMessage dlqMessage = jmsContext.createTextMessage(originalBody);

        // Preserve original MessageID as a custom property for traceability
        dlqMessage.setStringProperty("OriginalMessageID", originalMessage.getJMSMessageID());

        // Preserve original delivery count for audit trail
        dlqMessage.setIntProperty("FailedDeliveryCount",
                originalMessage.getIntProperty("JMSXDeliveryCount"));

        // Send to Dead Letter Queue
        jmsContext.createProducer().send(deadLetterQueue, dlqMessage);

        System.out.println("Message sent to Dead Letter Queue: " + DEAD_LETTER_QUEUE_NAME);
    }

} // end of class
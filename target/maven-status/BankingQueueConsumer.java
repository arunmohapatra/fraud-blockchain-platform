import jakarta.jms.*;
import javax.naming.InitialContext;

public class SimpleConsumer {

    // Maximum retries before moving to Dead Letter Queue
    private static final int MAX_RETRY = 3;

    public static void main(String[] args) throws Exception {

        // ---------------------------------------------------------------
        // STEP 1 — Get ConnectionFactory from JNDI
        // ---------------------------------------------------------------
        InitialContext ctx = new InitialContext();
        ConnectionFactory cf = (ConnectionFactory) ctx.lookup("MYQUE");

        // ---------------------------------------------------------------
        // STEP 2 — Open IBM MQ connection in SESSION_TRANSACTED mode
        //
        // SESSION_TRANSACTED means:
        //   receive() → message stays in queue (pending state)
        //   commit()  → message removed from queue (success)
        //   rollback()→ message goes back to queue (retry)
        // ---------------------------------------------------------------
        try (JMSContext jmsContext = cf.createContext(JMSContext.SESSION_TRANSACTED)) {

            // ---------------------------------------------------------------
            // STEP 3 — Create references to Main Queue and Dead Letter Queue
            // These do NOT physically create queues on IBM MQ
            // Queues must already exist on IBM MQ server (created by admin)
            // ---------------------------------------------------------------
            Queue mainQueue = jmsContext.createQueue("MYQUEUE");
            Queue dlq       = jmsContext.createQueue("MYQUEUE.DEADLETTER");

            // ---------------------------------------------------------------
            // STEP 4 — Open consumer on Main Queue
            // This physically connects to MYQUEUE on IBM MQ broker
            // ---------------------------------------------------------------
            JMSConsumer consumer = jmsContext.createConsumer(mainQueue);

            System.out.println("Listening on MYQUEUE...");

            // ---------------------------------------------------------------
            // STEP 5 — Poll continuously for messages
            // ---------------------------------------------------------------
            while (true) {

                // -----------------------------------------------------------
                // STEP 6 — Receive message
                // Thread blocks here until message arrives or 5 sec timeout
                // Message is in PENDING state in queue — not yet removed
                // -----------------------------------------------------------
                Message message = consumer.receive(5000);

                // No message in queue — loop back and keep waiting
                if (message == null) {
                    System.out.println("No message. Waiting...");
                    continue;
                }

                // -----------------------------------------------------------
                // STEP 7 — Read delivery count
                // IBM MQ auto increments this on every rollback + redeliver
                // Starts at 1 on first delivery
                // Becomes 2 on first rollback and redeliver
                // Becomes 3 on second rollback and redeliver — and so on
                // -----------------------------------------------------------
                int deliveryCount = message.getIntProperty("JMSXDeliveryCount");
                String messageId  = message.getJMSMessageID();

                System.out.println("Message received | ID: " + messageId
                        + " | Attempt: " + deliveryCount);

                // -----------------------------------------------------------
                // STEP 8 — Check if message is a poison message
                //
                // If deliveryCount > MAX_RETRY it means message has already
                // failed MAX_RETRY times and keeps causing exceptions
                // No point retrying again — move it to Dead Letter Queue
                // so main queue is unblocked for other messages
                // -----------------------------------------------------------
                if (deliveryCount > MAX_RETRY) {

                    System.err.println("Poison message detected. Attempt "
                            + deliveryCount + " exceeds MAX_RETRY of "
                            + MAX_RETRY);

                    try {
                        // Create new message for Dead Letter Queue
                        // Copy original body into it
                        TextMessage dlqMessage = jmsContext.createTextMessage(
                                ((TextMessage) message).getText());

                        // Attach metadata so ops team knows what failed
                        // and where it originally came from
                        dlqMessage.setStringProperty(
                                "OriginalMessageID", messageId);
                        dlqMessage.setIntProperty(
                                "FailedAttempts", deliveryCount);
                        dlqMessage.setStringProperty(
                                "OriginalQueue", "MYQUEUE");

                        // Send to Dead Letter Queue
                        jmsContext.createProducer().send(dlq, dlqMessage);

                        // ---------------------------------------------------
                        // COMMIT here does TWO things together:
                        //   1. Removes poison message from MYQUEUE permanently
                        //   2. Confirms the send to MYQUEUE.DEADLETTER
                        // Both happen in one transaction
                        // ---------------------------------------------------
                        jmsContext.commit();

                        System.out.println("Poison message moved to DLQ. "
                                + "MYQUEUE unblocked.");

                    } catch (Exception e) {
                        System.err.println("Failed to send to DLQ: "
                                + e.getMessage());
                        // Rollback — message stays in MYQUEUE
                        // Will try DLQ routing again next time
                        jmsContext.rollback();
                    }

                    // Skip normal processing — go to next message
                    continue;
                }

                // -----------------------------------------------------------
                // STEP 9 — Normal processing inside try-catch
                //
                // SUCCESS   → commit()  → message removed from queue ✅
                // EXCEPTION → rollback()→ message back to queue for retry ❌
                // -----------------------------------------------------------
                try {

                    // Your business logic here
                    // Read message body and process it
                    String body = ((TextMessage) message).getText();
                    System.out.println("Processing message body: " + body);

                    // Simulate your actual processing
                    // e.g. save to DB, call API, transform data
                    processMessage(body);

                    // -------------------------------------------------------
                    // COMMIT — Processing successful
                    // Tells IBM MQ to permanently delete message from MYQUEUE
                    // This is the ONLY point where message leaves the queue
                    // -------------------------------------------------------
                    jmsContext.commit();
                    System.out.println("SUCCESS — Message committed "
                            + "and removed from queue.");

                } catch (Exception e) {

                    // -------------------------------------------------------
                    // ROLLBACK — Processing failed
                    // Tells IBM MQ to put message BACK into MYQUEUE
                    // Message will be redelivered on next receive() call
                    // IBM MQ increments JMSXDeliveryCount by 1
                    // When count exceeds MAX_RETRY → Step 8 sends it to DLQ
                    // -------------------------------------------------------
                    System.err.println("FAILED on attempt " + deliveryCount
                            + " — " + e.getMessage());

                    jmsContext.rollback();

                    System.out.println("ROLLBACK done. Message back in queue."
                            + " Next attempt will be #" + (deliveryCount + 1));
                }

            } // end while loop

        } // JMSContext auto closed here by try-with-resources

    } // end main


    // Simple processing method
    // Throws exception to simulate failure — triggers rollback in caller
    private static void processMessage(String body) throws Exception {
        if (body == null || body.isEmpty()) {
            // This exception bubbles up to catch block in main
            // which calls rollback() and returns message to queue
            throw new Exception("Message body is empty — cannot process.");
        }
        // Your real logic here — save to DB, call REST API etc
        System.out.println("Message processed successfully: " + body);
    }

} // end class
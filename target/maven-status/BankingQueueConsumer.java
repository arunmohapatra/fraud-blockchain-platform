package com.bank.mq;

import jakarta.jms.*;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Properties;

/**
 * =============================================================================
 * BankingQueueConsumer — Enterprise Grade Plain Java JMS Consumer
 *
 * This class reads payment messages from IBM MQ queue and saves them
 * to Oracle database in ONE atomic transaction using plain Java.
 *
 * Key Features:
 *   - SESSION_TRANSACTED mode for JMS
 *   - Manual JDBC transaction management
 *   - Queue + Database rolled back together on failure
 *   - Duplicate detection using JMS Message ID
 *   - Poison message handling with Dead Letter Queue
 *   - Continuous polling with proper shutdown hook
 * =============================================================================
 */
public class BankingQueueConsumer {

    // -------------------------------------------------------------------------
    // Constants — Queue and Database configuration
    // In production these come from a config file or environment variables
    // -------------------------------------------------------------------------

    // IBM MQ Queue Names
    private static final String MAIN_QUEUE_NAME        = "MYQUEUE";
    private static final String DEAD_LETTER_QUEUE_NAME = "MYQUEUE.DEADLETTER";

    // JNDI name for ConnectionFactory registered by IBM MQ
    private static final String CONNECTION_FACTORY_JNDI = "MYQUE";

    // Maximum delivery attempts before treating as poison message
    private static final int MAX_RETRY_COUNT = 5;

    // How long to wait for a message before looping again (milliseconds)
    private static final int RECEIVE_TIMEOUT_MS = 5000;

    // Oracle Database connection details
    private static final String DB_URL      = "jdbc:oracle:thin:@localhost:1521:BANKDB";
    private static final String DB_USER     = "bankuser";
    private static final String DB_PASSWORD = "bankpassword";

    // Flag to control the main polling loop
    // volatile ensures visibility across threads
    private static volatile boolean running = true;


    public static void main(String[] args) {

        System.out.println("==============================================");
        System.out.println("  Banking Queue Consumer Starting...");
        System.out.println("==============================================");

        // -------------------------------------------------------------------------
        // Shutdown Hook — Runs when application receives CTRL+C or kill signal
        // Sets running = false so the while loop exits cleanly
        // without abruptly closing IBM MQ connection mid-processing
        // -------------------------------------------------------------------------
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown signal received. Stopping consumer...");
            running = false;
        }));

        // -------------------------------------------------------------------------
        // STEP 1 — Connect to JNDI Naming Service
        // JNDI is a directory where IBM MQ registers its ConnectionFactory
        // InitialContext reads jndi.properties file from classpath which contains
        // the JNDI provider URL and factory class pointing to IBM MQ
        //
        // Sample jndi.properties:
        //   java.naming.factory.initial=com.sun.jndi.fscontext.RefFSContextFactory
        //   java.naming.provider.url=file:/C:/JNDI-Directory
        // -------------------------------------------------------------------------
        InitialContext jndiContext = null;
        try {
            jndiContext = new InitialContext();
            System.out.println("STEP 1 — Connected to JNDI registry successfully.");
        } catch (NamingException e) {
            System.err.println("FATAL — Could not connect to JNDI registry: "
                    + e.getMessage());
            System.err.println("Check jndi.properties file in classpath.");
            return;
        }

        // -------------------------------------------------------------------------
        // STEP 2 — Lookup IBM MQ ConnectionFactory from JNDI
        // ctx.lookup("MYQUE") searches the JNDI directory by the name "MYQUE"
        // Returns a ConnectionFactory pre-configured by IBM MQ admin with:
        //   - Queue Manager name
        //   - IBM MQ Host and Port
        //   - Channel name
        // You do not hardcode any IBM MQ connection details here
        // -------------------------------------------------------------------------
        ConnectionFactory connectionFactory = null;
        try {
            connectionFactory = (ConnectionFactory) jndiContext.lookup(
                    CONNECTION_FACTORY_JNDI);
            System.out.println("STEP 2 — ConnectionFactory lookup successful.");
        } catch (NamingException e) {
            System.err.println("FATAL — Could not find ConnectionFactory '"
                    + CONNECTION_FACTORY_JNDI + "' in JNDI: " + e.getMessage());
            return;
        }

        // -------------------------------------------------------------------------
        // STEP 3 — Create IBM MQ Database Connection
        // This is a plain JDBC connection to Oracle database
        // autoCommit is set to FALSE because we manually control
        // when the database transaction commits or rolls back
        // This is critical — if autoCommit is true, DB saves happen
        // immediately even if the JMS message later fails to commit
        // -------------------------------------------------------------------------
        Connection dbConnection = null;
        try {
            dbConnection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);

            // Turn off autoCommit — we will commit manually after JMS commit
            // This ensures DB and Queue are in sync
            dbConnection.setAutoCommit(false);

            System.out.println("STEP 3 — Database connection established. "
                    + "AutoCommit disabled.");
        } catch (SQLException e) {
            System.err.println("FATAL — Could not connect to Oracle database: "
                    + e.getMessage());
            return;
        }

        // -------------------------------------------------------------------------
        // STEP 4 — Open IBM MQ Connection in SESSION_TRANSACTED mode
        // createContext(SESSION_TRANSACTED) means:
        //   - Message is received but NOT removed from queue on receive()
        //   - Message is removed ONLY when jmsContext.commit() is called
        //   - Message is returned to queue when jmsContext.rollback() is called
        // try-with-resources closes JMSContext automatically when block exits
        // -------------------------------------------------------------------------
        try (JMSContext jmsContext = connectionFactory.createContext(
                JMSContext.SESSION_TRANSACTED)) {

            System.out.println("STEP 4 — IBM MQ JMSContext created in "
                    + "SESSION_TRANSACTED mode.");

            // -------------------------------------------------------------------------
            // STEP 5 — Create Queue References
            // createQueue() does NOT physically create queue on IBM MQ
            // It just creates a Java object holding the queue name
            // The queues MUST already exist on IBM MQ server
            // -------------------------------------------------------------------------
            Queue mainQueue       = jmsContext.createQueue(MAIN_QUEUE_NAME);
            Queue deadLetterQueue = jmsContext.createQueue(DEAD_LETTER_QUEUE_NAME);

            System.out.println("STEP 5 — Queue references created.");
            System.out.println("         Main Queue        : " + MAIN_QUEUE_NAME);
            System.out.println("         Dead Letter Queue : " + DEAD_LETTER_QUEUE_NAME);

            // -------------------------------------------------------------------------
            // STEP 6 — Create JMSConsumer
            // THIS is where IBM MQ queue is physically opened and connected
            // If MYQUEUE does not exist on IBM MQ broker, error surfaces here
            // -------------------------------------------------------------------------
            JMSConsumer consumer = jmsContext.createConsumer(mainQueue);

            System.out.println("STEP 6 — JMSConsumer created. Listening on: "
                    + MAIN_QUEUE_NAME);
            System.out.println("==============================================");
            System.out.println("  Consumer Ready. Waiting for messages...");
            System.out.println("==============================================");

            // -------------------------------------------------------------------------
            // STEP 7 — Main Polling Loop
            // Keeps running until shutdown signal is received (CTRL+C)
            // Each iteration picks up one message and processes it fully
            // before moving to the next message
            // -------------------------------------------------------------------------
            while (running) {

                // -----------------------------------------------------------------
                // STEP 8 — Receive Message from IBM MQ Queue
                // This is a BLOCKING call — thread waits here until:
                //   a) A message arrives in the queue → returns the message
                //   b) Timeout (5 seconds) expires   → returns null
                //
                // Message arrives in memory but STAYS in IBM MQ queue
                // in PENDING / IN-FLIGHT state because of SESSION_TRANSACTED
                // It will be removed only after commit() is called
                // -----------------------------------------------------------------
                Message message = consumer.receive(RECEIVE_TIMEOUT_MS);

                // No message arrived within timeout — loop back and keep waiting
                if (message == null) {
                    System.out.println("No message in queue. Waiting...");
                    continue;
                }

                // -----------------------------------------------------------------
                // STEP 9 — Read JMS System Headers
                // These are automatically set by IBM MQ on every message
                // JMSMessageID   — Unique ID of this message (for duplicate check)
                // JMSTimestamp   — When message was put into queue
                // JMSXDeliveryCount — How many times this message was delivered
                //                    Starts at 1, increments on every rollback
                // -----------------------------------------------------------------
                String jmsMessageId  = null;
                int    deliveryCount = 1;

                try {
                    jmsMessageId  = message.getJMSMessageID();
                    deliveryCount = message.getIntProperty("JMSXDeliveryCount");
                } catch (JMSException e) {
                    System.err.println("Warning — Could not read JMS headers: "
                            + e.getMessage());
                }

                System.out.println("----------------------------------------------");
                System.out.println("Message received from IBM MQ.");
                System.out.println("JMS Message ID   : " + jmsMessageId);
                System.out.println("Delivery Attempt : " + deliveryCount);
                System.out.println("Timestamp        : "
                        + new java.util.Date(message.getJMSTimestamp()));
                System.out.println("----------------------------------------------");

                // -----------------------------------------------------------------
                // STEP 10 — Poison Message Check
                // If delivery count exceeds MAX_RETRY_COUNT this message has
                // failed too many times and is a POISON MESSAGE
                // Send it to Dead Letter Queue instead of processing again
                // This avoids an infinite retry loop blocking the main queue
                // -----------------------------------------------------------------
                if (deliveryCount > MAX_RETRY_COUNT) {

                    System.err.println("POISON MESSAGE DETECTED — Delivery count "
                            + deliveryCount + " exceeds max retry limit of "
                            + MAX_RETRY_COUNT);
                    System.err.println("Moving to Dead Letter Queue: "
                            + DEAD_LETTER_QUEUE_NAME);

                    try {
                        // Send to Dead Letter Queue for manual inspection later
                        sendToDeadLetterQueue(jmsContext, deadLetterQueue,
                                message, jmsMessageId, deliveryCount);

                        // Commit JMS — removes poison message from main queue
                        // and confirms the dead letter queue send
                        jmsContext.commit();

                        System.out.println("Poison message moved to Dead Letter Queue "
                                + "and committed.");

                    } catch (JMSException e) {
                        System.err.println("Failed to send to Dead Letter Queue: "
                                + e.getMessage());
                        // Rollback so message stays in main queue
                        // to try dead letter routing again next time
                        jmsContext.rollback();
                    }

                    // Skip normal processing for this poison message
                    continue;
                }

                // -----------------------------------------------------------------
                // STEP 11 — Process Message inside try-catch
                // This is the main processing block
                // Both JMS and DB are controlled here manually
                //
                // SUCCESS PATH:
                //   processPayment() succeeds
                //   → dbConnection.commit()  — DB save confirmed
                //   → jmsContext.commit()    — Message removed from queue
                //   Both succeed together
                //
                // FAILURE PATH:
                //   processPayment() throws exception
                //   → dbConnection.rollback() — DB save cancelled
                //   → jmsContext.rollback()   — Message returned to queue
                //   Both roll back together
                // -----------------------------------------------------------------
                try {

                    // Parse and process the message — save payment to database
                    processPayment(message, jmsMessageId, dbConnection);

                    // -------------------------------------------------------------
                    // COMMIT DATABASE FIRST
                    // Save the payment record permanently to Oracle DB
                    // -------------------------------------------------------------
                    dbConnection.commit();
                    System.out.println("DB committed — payment saved to database.");

                    // -------------------------------------------------------------
                    // COMMIT JMS AFTER DB SUCCESS
                    // Only now tell IBM MQ to permanently remove the message
                    // DB committed first ensures data is safe before message removal
                    // -------------------------------------------------------------
                    jmsContext.commit();
                    System.out.println("JMS committed — message removed from queue.");
                    System.out.println("Message processed successfully: "
                            + jmsMessageId);

                } catch (Exception e) {

                    // -------------------------------------------------------------
                    // ROLLBACK BOTH — DB and JMS
                    // Something went wrong during processPayment()
                    // Roll back DB first — cancel any partial DB saves
                    // Roll back JMS  — return message to IBM MQ queue
                    // Both are undone — system is back to original state
                    // -------------------------------------------------------------
                    System.err.println("PROCESSING FAILED for message: "
                            + jmsMessageId);
                    System.err.println("Error: " + e.getMessage());

                    // Rollback database — cancel partial saves
                    try {
                        dbConnection.rollback();
                        System.out.println("DB rolled back successfully.");
                    } catch (SQLException sqlEx) {
                        System.err.println("DB rollback failed: "
                                + sqlEx.getMessage());
                    }

                    // Rollback JMS — return message to IBM MQ queue for redelivery
                    try {
                        jmsContext.rollback();
                        System.out.println("JMS rolled back — message returned "
                                + "to queue for redelivery.");
                        System.out.println("Next delivery attempt will be #"
                                + (deliveryCount + 1));
                    } catch (JMSException jmsEx) {
                        System.err.println("JMS rollback failed: "
                                + jmsEx.getMessage());
                    }
                }

            } // end of while(running) loop

            System.out.println("Consumer loop exited. Shutting down.");

        } catch (JMSException e) {
            // Catches fatal JMS errors outside the message loop
            // e.g., IBM MQ connection dropped, queue manager went down
            System.err.println("FATAL JMS Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Close DB connection in finally block — always executes
            // even if an exception occurs inside the try block
            closeDatabase(dbConnection);
        }

        System.out.println("==============================================");
        System.out.println("  Banking Queue Consumer Stopped.");
        System.out.println("==============================================");

    } // end of main()


    // =========================================================================
    // processPayment() — Parse Message and Save to Oracle Database
    //
    // Reads the JSON text from the JMS TextMessage
    // Parses it into payment fields manually (without any framework)
    // Validates the payment data
    // Checks for duplicate using JMS Message ID
    // Saves the payment record to Oracle DB using JDBC PreparedStatement
    //
    // Throws Exception on any failure — caller will rollback both DB and JMS
    // =========================================================================
    private static void processPayment(Message message,
                                       String jmsMessageId,
                                       Connection dbConnection)
            throws Exception {

        // -----------------------------------------------------------------
        // Parse JMS Message Body
        // Banking messages are typically JSON or XML in TextMessage format
        // -----------------------------------------------------------------
        if (!(message instanceof TextMessage)) {
            throw new IllegalArgumentException(
                    "Unsupported message type: "
                    + message.getClass().getSimpleName()
                    + ". Expected TextMessage.");
        }

        TextMessage textMessage = (TextMessage) message;
        String messageBody = textMessage.getText();

        System.out.println("Raw Message Body: " + messageBody);

        // -----------------------------------------------------------------
        // Parse JSON manually without any framework
        // Expected JSON format:
        // {
        //   "fromAccount" : "ACC001",
        //   "toAccount"   : "ACC002",
        //   "amount"      : 5000.00,
        //   "currency"    : "INR"
        // }
        // -----------------------------------------------------------------
        String fromAccount = extractJsonField(messageBody, "fromAccount");
        String toAccount   = extractJsonField(messageBody, "toAccount");
        String amountStr   = extractJsonField(messageBody, "amount");
        String currency    = extractJsonField(messageBody, "currency");

        System.out.println("Parsed Payment Details:");
        System.out.println("  From Account : " + fromAccount);
        System.out.println("  To Account   : " + toAccount);
        System.out.println("  Amount       : " + amountStr);
        System.out.println("  Currency     : " + currency);

        // -----------------------------------------------------------------
        // Business Validation 1 — All fields must be present
        // -----------------------------------------------------------------
        if (fromAccount == null || fromAccount.isEmpty()) {
            throw new IllegalArgumentException("fromAccount is missing in message.");
        }
        if (toAccount == null || toAccount.isEmpty()) {
            throw new IllegalArgumentException("toAccount is missing in message.");
        }
        if (amountStr == null || amountStr.isEmpty()) {
            throw new IllegalArgumentException("amount is missing in message.");
        }
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("currency is missing in message.");
        }

        // -----------------------------------------------------------------
        // Business Validation 2 — Amount must be a valid positive number
        // -----------------------------------------------------------------
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid amount format: " + amountStr);
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Payment amount must be positive. Got: " + amount);
        }

        // -----------------------------------------------------------------
        // Business Validation 3 — From and To accounts must be different
        // -----------------------------------------------------------------
        if (fromAccount.equals(toAccount)) {
            throw new IllegalArgumentException(
                    "fromAccount and toAccount cannot be the same: "
                    + fromAccount);
        }

        // -----------------------------------------------------------------
        // Duplicate Detection — Check if this JMS Message ID was already
        // processed and saved to DB
        // Critical in banking — same payment must never be processed twice
        // If duplicate found → return normally so message gets committed
        // and removed from queue (we don't want to keep redelivering it)
        // -----------------------------------------------------------------
        if (isDuplicateMessage(jmsMessageId, dbConnection)) {
            System.out.println("DUPLICATE MESSAGE DETECTED — Already processed: "
                    + jmsMessageId + ". Skipping.");
            return; // Return normally — JMS will commit and remove message
        }

        // -----------------------------------------------------------------
        // Save Payment to Oracle Database using JDBC PreparedStatement
        // PreparedStatement prevents SQL injection attacks
        // Note: We do NOT call dbConnection.commit() here
        // The caller (main method) calls commit() after this method returns
        // This keeps DB commit and JMS commit in sync
        // -----------------------------------------------------------------
        String sql = "INSERT INTO PAYMENT_TRANSACTIONS "
                   + "(JMS_MESSAGE_ID, FROM_ACCOUNT, TO_ACCOUNT, "
                   + " AMOUNT, CURRENCY, STATUS, PROCESSED_AT) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {

            pstmt.setString   (1, jmsMessageId);          // Unique JMS Message ID
            pstmt.setString   (2, fromAccount);            // Sender account
            pstmt.setString   (3, toAccount);              // Receiver account
            pstmt.setBigDecimal(4, amount);                // Payment amount
            pstmt.setString   (5, currency);               // Currency code
            pstmt.setString   (6, "PROCESSED");            // Status
            pstmt.setTimestamp(7, Timestamp.valueOf(       // Current timestamp
                    LocalDateTime.now()));

            // Execute the INSERT — data is staged but NOT yet committed to DB
            // Actual commit happens in main() after this method returns
            int rowsInserted = pstmt.executeUpdate();

            if (rowsInserted != 1) {
                throw new SQLException(
                        "Expected 1 row inserted but got: " + rowsInserted);
            }

            System.out.println("Payment record staged in DB (not yet committed). "
                    + "Rows inserted: " + rowsInserted);
        }

    } // end of processPayment()


    // =========================================================================
    // isDuplicateMessage() — Check if JMS Message ID Already Exists in DB
    //
    // Queries PAYMENT_TRANSACTIONS table for the given JMS Message ID
    // Returns true if found (duplicate), false if not found (new message)
    // =========================================================================
    private static boolean isDuplicateMessage(String jmsMessageId,
                                              Connection dbConnection)
            throws SQLException {

        String sql = "SELECT COUNT(*) FROM PAYMENT_TRANSACTIONS "
                   + "WHERE JMS_MESSAGE_ID = ?";

        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, jmsMessageId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0; // true if count > 0 (duplicate)
                }
            }
        }
        return false;
    }


    // =========================================================================
    // sendToDeadLetterQueue() — Move Poison Message to Dead Letter Queue
    //
    // Creates a new TextMessage on the Dead Letter Queue
    // Preserves original message body and metadata for manual investigation
    // The original JMS Message ID is stored as a custom property
    // so operations team can trace the failed message back to its source
    // =========================================================================
    private static void sendToDeadLetterQueue(JMSContext jmsContext,
                                              Queue deadLetterQueue,
                                              Message originalMessage,
                                              String jmsMessageId,
                                              int deliveryCount)
            throws JMSException {

        // Read original message body
        String originalBody = "";
        if (originalMessage instanceof TextMessage) {
            originalBody = ((TextMessage) originalMessage).getText();
        }

        // Create new message for Dead Letter Queue
        TextMessage dlqMessage = jmsContext.createTextMessage(originalBody);

        // Store original message metadata as properties for traceability
        dlqMessage.setStringProperty("OriginalMessageID",   jmsMessageId);
        dlqMessage.setIntProperty   ("FailedDeliveryCount", deliveryCount);
        dlqMessage.setStringProperty("FailedTimestamp",
                LocalDateTime.now().toString());
        dlqMessage.setStringProperty("OriginalQueue",       MAIN_QUEUE_NAME);

        // Send to Dead Letter Queue
        jmsContext.createProducer().send(deadLetterQueue, dlqMessage);

        System.out.println("Message sent to Dead Letter Queue: "
                + DEAD_LETTER_QUEUE_NAME);
        System.out.println("Original Message ID preserved: " + jmsMessageId);

    } // end of sendToDeadLetterQueue()


    // =========================================================================
    // extractJsonField() — Simple Manual JSON Parser
    //
    // Extracts a value from a simple flat JSON string by field name
    // Example: extractJsonField({"amount":"5000"}, "amount") → "5000"
    //
    // Note: In production use a proper JSON library like Jackson or Gson
    // This is a simple implementation for demonstration purposes only
    // =========================================================================
    private static String extractJsonField(String json, String fieldName) {

        // Look for "fieldName" : "value" pattern in JSON string
        String searchKey = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(searchKey);

        if (keyIndex == -1) return null; // Field not found

        // Find the colon after the field name
        int colonIndex = json.indexOf(":", keyIndex);
        if (colonIndex == -1) return null;

        // Find the value after the colon
        int valueStart = colonIndex + 1;

        // Skip whitespace
        while (valueStart < json.length()
                && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        // Check if value is a quoted string or a number
        if (json.charAt(valueStart) == '"') {
            // Quoted string value — find closing quote
            int valueEnd = json.indexOf("\"", valueStart + 1);
            if (valueEnd == -1) return null;
            return json.substring(valueStart + 1, valueEnd);
        } else {
            // Numeric value — find end by comma or closing brace
            int valueEnd = valueStart;
            while (valueEnd < json.length()
                    && json.charAt(valueEnd) != ','
                    && json.charAt(valueEnd) != '}') {
                valueEnd++;
            }
            return json.substring(valueStart, valueEnd).trim();
        }

    } // end of extractJsonField()


    // =========================================================================
    // closeDatabase() — Safely Close JDBC Connection
    //
    // Always called in the finally block of main()
    // Ensures DB connection is released back to the pool
    // even if an exception occurs during processing
    // =========================================================================
    private static void closeDatabase(Connection dbConnection) {
        if (dbConnection != null) {
            try {
                dbConnection.close();
                System.out.println("Database connection closed.");
            } catch (SQLException e) {
                System.err.println("Error closing database connection: "
                        + e.getMessage());
            }
        }
    } // end of closeDatabase()

} // end of class BankingQueueConsumer
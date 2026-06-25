import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;

import javax.naming.Context;
import javax.naming.InitialContext;
import java.util.Properties;

public class MQConsumer {

    private static final String CONNECTION_FACTORY_JNDI_NAME = "jms/ConnectionFactory";
    private static final String QUEUE_JNDI_NAME = "jms/InputQueue";

    public static void main(String[] args) {

        Context ctx = null;

        try {

            // Initialize JNDI Context
            Properties props = new Properties();

            // Add your JNDI properties here if required
            // props.put(Context.INITIAL_CONTEXT_FACTORY, "...");
            // props.put(Context.PROVIDER_URL, "...");

            ctx = new InitialContext(props);

            // Lookup ConnectionFactory and Queue
            ConnectionFactory connectionFactory =
                    (ConnectionFactory) ctx.lookup(CONNECTION_FACTORY_JNDI_NAME);

            Queue queue =
                    (Queue) ctx.lookup(QUEUE_JNDI_NAME);

            // Create transactional JMS Context
            try (JMSContext jmsContext =
                         connectionFactory.createContext(JMSContext.SESSION_TRANSACTED)) {

                JMSConsumer consumer =
                        jmsContext.createConsumer(queue);

                System.out.println("IBM MQ Consumer Started...");

                while (true) {

                    try {

                        Message message = consumer.receive();

                        if (message == null) {
                            continue;
                        }

                        String payload = null;

                        if (message instanceof TextMessage) {
                            payload = ((TextMessage) message).getText();
                        }

                        System.out.println("Received Message: " + payload);

                        // Business Processing
                        processMessage(payload);

                        // SUCCESS
                        jmsContext.commit();

                        System.out.println(
                                "Message processed successfully. Transaction committed.");

                    } catch (Exception processingException) {

                        System.err.println(
                                "Processing failed: "
                                        + processingException.getMessage());

                        // FAILURE
                        jmsContext.rollback();

                        System.err.println(
                                "Transaction rolled back. Message will be redelivered.");
                    }
                }
            }

        } catch (Exception e) {

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

    private static void processMessage(String payload) throws Exception {

        // Simulate business logic

        if (payload != null && payload.contains("ERROR")) {
            throw new RuntimeException("Business validation failed");
        }

        // Example:
        // 1. Call Fircosoft
        // 2. Update DB
        // 3. Invoke REST API
        // 4. Send downstream MQ message

        System.out.println("Processing: " + payload);
    }
}
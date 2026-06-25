import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.util.Properties;

public class MQConsumer {

    private static final String CONNECTION_FACTORY_JNDI_NAME = "jms/ConnectionFactory";
    private static final String QUEUE_JNDI_NAME = "jms/InputQueue";

    public static void main(String[] args) {

        Context context = null;
        Connection connection = null;
        Session session = null;

        try {

            // JNDI Properties (adjust as per your environment)
            Properties props = new Properties();

            context = new InitialContext(props);

            ConnectionFactory connectionFactory =
                    (ConnectionFactory) context.lookup(CONNECTION_FACTORY_JNDI_NAME);

            Queue queue =
                    (Queue) context.lookup(QUEUE_JNDI_NAME);

            connection = connectionFactory.createConnection();

            // IMPORTANT:
            // Create a transacted session
            session = connection.createSession(true, Session.SESSION_TRANSACTED);

            MessageConsumer consumer = session.createConsumer(queue);

            connection.start();

            System.out.println("Consumer started...");

            while (true) {

                Message message = consumer.receive();

                if (message == null) {
                    continue;
                }

                try {

                    if (message instanceof TextMessage textMessage) {

                        String payload = textMessage.getText();

                        System.out.println("Received Message: " + payload);

                        // Your business logic
                        processMessage(payload);
                    }

                    // SUCCESS
                    session.commit();

                    System.out.println("Message processed successfully. Transaction committed.");

                } catch (Exception processingException) {

                    System.err.println("Error processing message: "
                            + processingException.getMessage());

                    // FAILURE
                    session.rollback();

                    System.err.println("Transaction rolled back. Message will be redelivered.");
                }
            }

        } catch (Exception e) {

            e.printStackTrace();

        } finally {

            try {
                if (session != null) {
                    session.close();
                }
            } catch (Exception ignored) {
            }

            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (Exception ignored) {
            }

            try {
                if (context != null) {
                    context.close();
                }
            } catch (NamingException ignored) {
            }
        }
    }

    private static void processMessage(String payload) throws Exception {

        // Simulate business processing

        if (payload.contains("ERROR")) {
            throw new RuntimeException("Business processing failed");
        }

        // Database update
        // API call
        // Trade processing
        // etc.

        System.out.println("Processing payload: " + payload);
    }
}
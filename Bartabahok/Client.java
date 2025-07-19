import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.Socket;
import java.util.function.Consumer;

public class Client {
    private final String hostname;
    private final int port;
    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    // CHANGE 1: The 'final' keyword has been REMOVED.
    private Consumer<Serializable> onMessageReceived;
    
    private volatile boolean isRunning = false;


    public Client(String hostname, int port, Consumer<Serializable> onMessageReceived) {
        this.hostname = hostname;
        this.port = port;
        this.onMessageReceived = onMessageReceived; // Sets the initial listener (LoginController)
    }

    public void connect() throws IOException {
        socket = new Socket(hostname, port);
        out = new ObjectOutputStream(socket.getOutputStream());
        in = new ObjectInputStream(socket.getInputStream());

        isRunning = true;
        // The listener thread runs in the background for the life of the connection
        Thread listenerThread = new Thread(this::listenToServer);
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listenToServer() {
        while (isRunning) {
            try {
                Serializable message = (Serializable) in.readObject();
                // Pass the received message to the currently active handler
                if (onMessageReceived != null) {
                    onMessageReceived.accept(message);
                }
            } catch (IOException | ClassNotFoundException e) {
                if (isRunning) {
                    System.err.println("Connection to server lost.");
                    // Notify the UI about the disconnection
                    if (onMessageReceived != null) {
                        onMessageReceived.accept("SERVER_DISCONNECTED");
                    }
                }
                stop();
            }
        }
    }

    public void sendMessage(Serializable message) throws IOException {
        if (out != null) {
            out.writeObject(message);
            out.flush();
        }
    }

    public void stop() {
        isRunning = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // CHANGE 2: The NEW public method to switch message handlers.
    /**
     * Sets or updates the callback that will process messages from the server.
     * @param onMessageReceived The Consumer function to handle incoming messages.
     */
    public void setOnMessageReceived(Consumer<Serializable> onMessageReceived) {
        this.onMessageReceived = onMessageReceived;
    }
}
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final Map<String, ClientHandler> clients;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private String username;

    public ClientHandler(Socket socket, Map<String, ClientHandler> clients) {
        this.socket = socket;
        this.clients = clients;
    }

    @Override
    public void run() {
        try {
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());
            while (true) {
                handleMessage((Message) in.readObject());
            }
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("Client " + (username != null ? username : "") + " disconnected.");
        } finally {
            cleanupAndBroadcast();
        }
    }

    private void handleMessage(Message msg) throws IOException {
        switch (msg.getType()) {
            case LOGIN: handleLogin(msg); break;
            case SIGNUP: handleSignup(msg); break;
            case GET_USER_LIST: broadcastUserListToSingleClient(this); break;
            case TEXT: case FILE: case VOICE: handlePrivateMessage(msg); break;
            case CREATE_GROUP: handleCreateGroup(msg); break;
            case JOIN_GROUP:
                DatabaseManager.joinGroup(Integer.parseInt(msg.getContent()), msg.getSender());
                break;
            case LEAVE_GROUP:
                DatabaseManager.leaveGroup(Integer.parseInt(msg.getContent()), msg.getSender());
                break;
            case GET_ALL_GROUPS: handleGetAllGroups(msg); break;
            case GET_GROUP_MEMBERS: handleGetGroupMembers(msg); break;
            case GET_MY_GROUPS: 
             handleGetMyGroups(msg); 
             break;
            case GROUP_MESSAGE: case GROUP_FILE: case GROUP_VOICE: handleGroupMessage(msg); break;
            case FILE_TRANSFER_REQUEST:
            case FILE_TRANSFER_ACCEPT:
            case FILE_TRANSFER_REJECT:
            case FILE_CHUNK_ACK:
                sendMessageToRecipient(msg);
                break;
            
            case FILE_CHUNK:
                if (Math.random() < 0.2) {
                    System.out.println("SIMULATED PACKET LOSS: Dropping file chunk for transfer ID: " + msg.getContent().split(";")[1]);
                    return;
                }
                sendMessageToRecipient(msg);
                break;
            case LOGOUT:
                cleanupAndBroadcast();
                sendMessage(new Message(Message.MessageType.LOGOUT, "Server", username, "You have been logged out.", null, 0));
                break;
            default: System.out.println("Unknown message type: " + msg.getType());
        }
    }
    private void sendMessageToRecipient(Message msg) {
        ClientHandler recipientHandler = clients.get(msg.getReceiver());
        if (recipientHandler != null) {
            try {
                recipientHandler.sendMessage(msg); // Calls the low-level send method on the RECIPIENT'S handler
            } catch (IOException e) {
                // If sending fails, that client likely disconnected.
                System.err.println("Failed to send message to " + msg.getReceiver() + ". Removing client.");
                clients.remove(msg.getReceiver());
                broadcastUserAndGroupLists(); // Update everyone
            }
        } else {
            System.out.println("Recipient " + msg.getReceiver() + " is offline. Message will be in history.");
            // For a real-world app, you might save this as an "undelivered" message.
        }
    }
    private void handleLogin(Message msg) throws IOException {
        String user = msg.getSender();
        if (DatabaseManager.authenticateUser(user, msg.getContent()) && !clients.containsKey(user)) {
            this.username = user;
            clients.put(username, this);
            sendMessage(new Message(Message.MessageType.LOGIN_SUCCESS, "Server", user, "Login successful!", null, 0));
            System.out.println(username + " has logged in.");
            broadcastUserAndGroupLists();
        } else {
            sendMessage(new Message(Message.MessageType.LOGIN_FAIL, "Server", user, "Authentication failed.", null, 0));
        }
    }

    private void handleSignup(Message msg) throws IOException {
        if (DatabaseManager.addUser(msg.getSender(), msg.getContent())) {
            sendMessage(new Message(Message.MessageType.SIGNUP_SUCCESS, "Server", msg.getSender(), "Signup successful!", null, 0));
            broadcastUserAndGroupLists();
        } else {
            sendMessage(new Message(Message.MessageType.SIGNUP_FAIL, "Server", msg.getSender(), "Signup failed.", null, 0));
        }
    }
    
    private void handlePrivateMessage(Message msg) throws IOException {
        if (Math.random() < 0.2) {
            System.out.println("SIMULATED LOSS: Dropping private message from " + msg.getSender());
            return;
        }
        DatabaseManager.saveMessage(msg);
        ClientHandler recipientHandler = clients.get(msg.getReceiver());
        if (recipientHandler != null) {
            recipientHandler.sendMessage(msg);
        }
        sendMessage(new Message(Message.MessageType.ACK, "Server", msg.getSender(), "DELIVERED_TO_SERVER", msg.getMessageId(), 0));
    }
    
    private void handleCreateGroup(Message msg) throws IOException {
        int groupId = DatabaseManager.createGroup(msg.getContent(), msg.getSender());
        if (groupId != -1) {
            sendMessage(new Message(Message.MessageType.CREATE_GROUP_SUCCESS, "Server", msg.getSender(), groupId + ":" + msg.getContent(), null, 0));
            broadcastUserAndGroupLists();
        } else {
            sendMessage(new Message(Message.MessageType.CREATE_GROUP_FAIL, "Server", msg.getSender(), "Group name taken.", null, 0));
        }
    }

    private void handleGetAllGroups(Message msg) throws IOException {
        String groupList = String.join(",", DatabaseManager.getAllGroups());
        sendMessage(new Message(Message.MessageType.ALL_GROUPS_LIST, "Server", msg.getSender(), groupList, null, 0));
    }

    private void handleGetGroupMembers(Message msg) throws IOException {
        int groupId = Integer.parseInt(msg.getContent());
        List<String> members = DatabaseManager.getGroupMembers(groupId);
        sendMessage(new Message(Message.MessageType.GROUP_MEMBERS_LIST, "Server", msg.getSender(), String.join(",", members), String.valueOf(groupId), 0));
    }

    private void handleGroupMessage(Message msg) {
        if (Math.random() < 0.2) {
            System.out.println("SIMULATED LOSS: Dropping group message from " + msg.getSender());
            return;
        }
        DatabaseManager.saveMessage(msg);
        try {
            sendMessage(new Message(Message.MessageType.ACK, "Server", msg.getSender(), "DELIVERED_TO_SERVER", msg.getMessageId(), 0));
        } catch (IOException e) { e.printStackTrace(); }

        int groupId = Integer.parseInt(msg.getReceiver());
        for (String memberName : DatabaseManager.getGroupMembers(groupId)) {
            if (!memberName.equals(msg.getSender()) && clients.containsKey(memberName)) {
                try {
                    clients.get(memberName).sendMessage(msg);
                } catch (IOException e) { e.printStackTrace(); }
            }
        }
    }
    private void handleGetMyGroups(Message msg) throws IOException {
        List<Integer> groupIds = DatabaseManager.getGroupIdsForUser(msg.getSender());
        String content = groupIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        sendMessage(new Message(Message.MessageType.MY_GROUPS_LIST, "Server", msg.getSender(), content, null, 0));
    }
    
    private void cleanupAndBroadcast() {
        if (username != null) {
            clients.remove(username);
            broadcastUserAndGroupLists();
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) { e.printStackTrace(); }
    }
    
    private void broadcastUserAndGroupLists() {
        for (ClientHandler handler : clients.values()) {
            broadcastUserListToSingleClient(handler);
        }
    }

    private void broadcastUserListToSingleClient(ClientHandler handler) {
        String onlineUsers = String.join(",", clients.keySet());
        String allUsers = String.join(",", DatabaseManager.getAllUsernames());
        String allGroups = String.join(",", DatabaseManager.getAllGroups());
        
        try {
            handler.sendMessage(new Message(Message.MessageType.USER_LIST_UPDATE, "Server", handler.username, onlineUsers + "#" + allUsers, null, 0));
            handler.sendMessage(new Message(Message.MessageType.ALL_GROUPS_LIST, "Server", handler.username, allGroups, null, 0));
        } catch (IOException e) {
            System.err.println("Failed to broadcast lists to " + handler.username);
        }
    }

    private synchronized void sendMessage(Message msg) throws IOException {
        if (out != null) {
            out.writeObject(msg);
            out.flush();
        }
    }
}
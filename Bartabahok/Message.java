import java.io.Serializable;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum MessageType {
        // User & Session
        LOGIN, LOGIN_SUCCESS, LOGIN_FAIL,
        SIGNUP, SIGNUP_SUCCESS,  SIGNUP_FAIL,
        GET_USER_LIST, USER_LIST_UPDATE,

        // Private Chat
        TEXT, FILE, VOICE, ACK,

        // Group Chat & Management
        CREATE_GROUP, CREATE_GROUP_SUCCESS, CREATE_GROUP_FAIL,
        JOIN_GROUP, LEAVE_GROUP,
        GET_ALL_GROUPS, ALL_GROUPS_LIST,
        GET_GROUP_MEMBERS, GROUP_MEMBERS_LIST,
        GROUP_MESSAGE, GROUP_FILE, GROUP_VOICE, GET_MY_GROUPS, MY_GROUPS_LIST, LOGOUT, FILE_TRANSFER_REQUEST,  // C->S->C: Initial request to send a file. Content: "fileName;fileSize;totalChunks;transferId"
        FILE_TRANSFER_ACCEPT,   // C->S->C: Receiver accepts the file. Content: "transferId"
        FILE_TRANSFER_REJECT,   // C->S->C: Receiver rejects the file. Content: "transferId"
        FILE_CHUNK,             // C->S->C: A single chunk of the file. Content: "sequenceNumber;transferId"
        FILE_CHUNK_ACK,
        // Misc
        ERROR
    }

    private final MessageType type;
    private final String sender;
    private final String receiver;
    private final String content;
    private final byte[] fileData;
    private final String messageId;
    private final long timestamp;
    private int chunkIndex = -1;
    private int totalChunks = -1;
    private String transferId;
    private byte[] fileChunkData;
    public Message(MessageType type, String sender, String receiver, String content, String messageId, long timestamp) {
        this.type = type;
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
        this.messageId = messageId;
        this.timestamp = timestamp;
        this.fileData = null;
    }

    public Message(MessageType type, String sender, String receiver, String content, byte[] fileData, String messageId, long timestamp) {
        this.type = type;
        this.sender = sender;
        this.receiver = receiver;
        this.content = content;
        this.fileData = fileData;
        this.messageId = messageId;
        this.timestamp = timestamp;
    }

    public MessageType getType() { return type; }
    public String getSender() { return sender; }
    public String getReceiver() { return receiver; }
    public String getContent() { return content; }
    public byte[] getFileData() { return fileData; }
    public String getMessageId() { return messageId; }
    public long getTimestamp() { return timestamp; }
}
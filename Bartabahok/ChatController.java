import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class ChatController {

    @FXML private Label chattingWithLabel;
    @FXML private VBox chatVBox;
    @FXML private TextArea messageField;
    @FXML private ScrollPane scrollPane;
    @FXML private Button emojiButton;
    @FXML private Button sendButton;
    @FXML private Button groupOptionsButton;
    private Client client;
    private String sender;
    private String recipient;
    private int groupId;

    private boolean isGroupChat = false;
    private List<String> currentChatMembers = new ArrayList<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Message> unacknowledgedMessages = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<String, HBox> messageUIElements = new ConcurrentHashMap<>();
    private static final int NORMAL_RETRANSMIT_LIMIT = 3;
    private static final int MAX_TOTAL_RETRIES = 10;
    private static final long RETRANSMIT_DELAY_SECONDS = 15;
    private final Map<String, Integer> retryCounts = new ConcurrentHashMap<>();
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private boolean isRecording = false;
    private TargetDataLine audioLine;
    private File voiceMemoFile;

    private final ContextMenu mentionPopup = new ContextMenu();
    private final Popup emojiPopup = new Popup();

    @FXML
    public void initialize() {
        // Common setup that can happen before we know who we're chatting with.
        scrollPane.vvalueProperty().bind(chatVBox.heightProperty());
        setupEmojiPicker();
    }
    
    // --- THIS IS THE NEW, CORRECTED WAY TO INITIALIZE ---
    // This is called from the DashboardController AFTER the FXML is loaded.
    public void setupController(Client client, String sender, String recipient, boolean isGroup) {
        this.client = client;
        this.sender = sender;
        this.isGroupChat = isGroup;

        if (isGroupChat) {
            this.groupId = Integer.parseInt(recipient); // For groups, recipient string is the ID
            this.recipient = recipient; // Keep it as string for message sending
            this.chattingWithLabel.setText("Group ID: " + recipient); // Placeholder name
             groupOptionsButton.setVisible(true);
            
            try {
                // Request the group name and members
                client.sendMessage(new Message(Message.MessageType.GET_GROUP_MEMBERS, sender, "Server", String.valueOf(groupId), String.valueOf(groupId), 0));
            } catch (IOException e) { e.printStackTrace(); }
        } else {
            this.recipient = recipient;
            this.chattingWithLabel.setText(recipient);
            groupOptionsButton.setVisible(false);
            groupOptionsButton.setManaged(false); // Make sure it doesn't take up space
        }
        
        this.client.setOnMessageReceived(this::processIncomingMessage);
        setupMentionHandling();
        setupGroupOptionsMenu();
        loadChatHistory();
    }

    private void loadChatHistory() {
        List<Message> history = isGroupChat ? 
            DatabaseManager.getGroupChatHistory(groupId) : 
            DatabaseManager.getChatHistory(sender, recipient);
        
        for (Message msg : history) {
            displayMessage(msg, "✔✔");
        }
    }
    
    public void processIncomingMessage(Serializable serializable) {
        if (serializable instanceof Message) {
            Message msg = (Message) serializable;
            Platform.runLater(() -> {
                if (msg.getType() == Message.MessageType.ACK) {
                    handleAck(msg.getMessageId());
                } else if (isGroupChat && msg.getType() == Message.MessageType.GROUP_MEMBERS_LIST && msg.getMessageId() != null && msg.getMessageId().equals(String.valueOf(groupId))) {
                    currentChatMembers = Arrays.asList(msg.getContent().split(","));
                    // We can also update the chat title with a real name if needed
                } else if (isMessageForThisChat(msg)) {
                    displayMessage(msg, "");
                }
            });
        } else if ("SERVER_DISCONNECTED".equals(serializable)) {
            handleServerDisconnect();
        }
    }
    
    private boolean isMessageForThisChat(Message msg) {
        if (isGroupChat) {
            return msg.getReceiver().equals(String.valueOf(groupId));
        } else {
            return (msg.getSender().equals(recipient) && msg.getReceiver().equals(sender)) || 
                   (msg.getSender().equals(sender) && msg.getReceiver().equals(recipient));
        }
    }
    
    @FXML
    private void handleGroupOptionsButton() {
        ContextMenu contextMenu = (ContextMenu) groupOptionsButton.getUserData();
        contextMenu.show(groupOptionsButton, Side.BOTTOM, 0, 0);
    }
    
    private void setupGroupOptionsMenu() {
        ContextMenu contextMenu = new ContextMenu();
        
        MenuItem viewMembers = new MenuItem("View Members");
        viewMembers.setOnAction(e -> showGroupMembersDialog());

        MenuItem leaveGroup = new MenuItem("Leave Group");
        leaveGroup.setStyle("-fx-text-fill: red;");
        leaveGroup.setOnAction(e -> handleLeaveGroup());
        
        contextMenu.getItems().addAll(viewMembers, leaveGroup);
        groupOptionsButton.setUserData(contextMenu);
    }
    
    private void showGroupMembersDialog() {
        // Request an updated list of members from the server before showing
        try {
            client.sendMessage(new Message(Message.MessageType.GET_GROUP_MEMBERS, sender, "Server", String.valueOf(groupId), String.valueOf(groupId), 0));
        } catch (IOException ex) { ex.printStackTrace(); }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Group Members");
        alert.setHeaderText("Members of '" + chattingWithLabel.getText() + "'");
        alert.getDialogPane().getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        alert.setResizable(true);
        ListView<String> membersListView = new ListView<>();
        membersListView.setItems(FXCollections.observableArrayList(currentChatMembers));
        alert.getDialogPane().setContent(membersListView);
        alert.showAndWait();
    }

    private void handleLeaveGroup() {
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("Leave Group");
        confirmAlert.setHeaderText("Are you sure you want to leave this group?");
        confirmAlert.getDialogPane().getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        Optional<ButtonType> result = confirmAlert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            try {
                client.sendMessage(new Message(Message.MessageType.LEAVE_GROUP, sender, "Server", String.valueOf(groupId), null, 0));
                ((Stage) chattingWithLabel.getScene().getWindow()).close();
            } catch (IOException e) { e.printStackTrace(); }
        }
    }
    
    @FXML
    private void handleSendButton() {
        String text = messageField.getText().trim();
        if (text.isEmpty()) return;

        String messageId = UUID.randomUUID().toString();
        Message.MessageType type = isGroupChat ? Message.MessageType.GROUP_MESSAGE : Message.MessageType.TEXT;
        Message msg = new Message(type, sender, recipient, text, messageId, System.currentTimeMillis());
        
        sendMessageWithRetransmit(msg);
        messageField.clear();
    }


    @FXML
    private void handleFileButton() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select File/s to Send");
        File file = fileChooser.showOpenDialog(chatVBox.getScene().getWindow());
        if (file != null) {
            try {
                byte[] fileBytes = Files.readAllBytes(file.toPath());
                String messageId = UUID.randomUUID().toString();
                Message.MessageType type = isGroupChat ? Message.MessageType.GROUP_FILE : Message.MessageType.FILE;
                Message fileMsg = new Message(type, sender, recipient, file.getName(), fileBytes, messageId, System.currentTimeMillis());
                sendMessageWithRetransmit(fileMsg);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @FXML
    private void handleVoiceButton() {
        if (isRecording) {
            isRecording = false;
            sendButton.setGraphic(null);
            
            audioLine.stop();
            audioLine.close();
            
            try {
                byte[] voiceBytes = Files.readAllBytes(voiceMemoFile.toPath());
                String messageId = UUID.randomUUID().toString();
                Message.MessageType type = isGroupChat ? Message.MessageType.GROUP_VOICE : Message.MessageType.VOICE;
                Message voiceMsg = new Message(type, sender, recipient, "voice_memo.wav", voiceBytes, messageId, System.currentTimeMillis());
                sendMessageWithRetransmit(voiceMsg);
                voiceMemoFile.delete();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try {
                AudioFormat format = new AudioFormat(16000, 8, 2, true, true);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

                if (!AudioSystem.isLineSupported(info)) return;
                
                audioLine = (TargetDataLine) AudioSystem.getLine(info);
                audioLine.open(format);
                audioLine.start();
                
                isRecording = true;
                sendButton.setGraphic(new Label("🔴"));
                
                voiceMemoFile = new File("temp_voice_memo.wav");

                Thread recordingThread = new Thread(() -> {
                    try {
                        AudioSystem.write(new AudioInputStream(audioLine), AudioFileFormat.Type.WAVE, voiceMemoFile);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
                recordingThread.setDaemon(true);
                recordingThread.start();
            } catch (LineUnavailableException e) {
                e.printStackTrace();
            }
        }
    }

private void sendMessageWithRetransmit(final Message msg) {
    Platform.runLater(() -> displayMessage(msg, "✔"));
    unacknowledgedMessages.put(msg.getMessageId(), msg);
    retryCounts.put(msg.getMessageId(), 0);
    sendOrRetryMessage(msg, false);
}

private void sendOrRetryMessage(final Message msg, boolean fastRetransmit) {
    try {
        client.sendMessage(msg);
        int retries = retryCounts.getOrDefault(msg.getMessageId(), 0);

        Runnable retransmitTask = () -> {
            int currentRetries = retryCounts.getOrDefault(msg.getMessageId(), 0) + 1;
            retryCounts.put(msg.getMessageId(), currentRetries);

            if (unacknowledgedMessages.containsKey(msg.getMessageId())) {
                if (currentRetries < NORMAL_RETRANSMIT_LIMIT) {
                    Platform.runLater(() -> updateMessageStatus(msg.getMessageId(), "⏳ Retrying (" + currentRetries + ")"));
                    sendOrRetryMessage(msg, false); // Normal retransmit after delay
                } else if (currentRetries < MAX_TOTAL_RETRIES) {
                    Platform.runLater(() -> updateMessageStatus(msg.getMessageId(), "⚡ Fast Retransmit (" + currentRetries + ")"));
                    sendOrRetryMessage(msg, true); // Fast retransmit immediately
                } else {
                    Platform.runLater(() -> updateMessageStatus(msg.getMessageId(), "❌ Failed"));
                    unacknowledgedMessages.remove(msg.getMessageId());
                    retryCounts.remove(msg.getMessageId());
                    scheduledTasks.remove(msg.getMessageId());
                }
            }
        };

        ScheduledFuture<?> task;
        if (fastRetransmit && retries >= NORMAL_RETRANSMIT_LIMIT) {
            // Fast retransmit: no delay
            retransmitTask.run();
            task = null;
        } else {
            // Normal retransmit: 15s delay
            task = scheduler.schedule(retransmitTask, RETRANSMIT_DELAY_SECONDS, TimeUnit.SECONDS);
        }
        if (task != null) {
            scheduledTasks.put(msg.getMessageId(), task);
        }
    } catch (IOException e) {
        Platform.runLater(() -> updateMessageStatus(msg.getMessageId(), "❌ Failed"));
        unacknowledgedMessages.remove(msg.getMessageId());
        retryCounts.remove(msg.getMessageId());
        scheduledTasks.remove(msg.getMessageId());
    }
}

private void handleAck(String messageId) {
    if (unacknowledgedMessages.remove(messageId) != null) {
        ScheduledFuture<?> task = scheduledTasks.remove(messageId);
        if (task != null) task.cancel(false);
        retryCounts.remove(messageId);
        Platform.runLater(() -> updateMessageStatus(messageId, "✔✔"));
    }
}
    private void updateMessageStatus(String messageId, String status) {
        HBox messageContainer = messageUIElements.get(messageId);
        if (messageContainer != null) {
            VBox bubble = (VBox) messageContainer.getChildren().get(0);
            HBox bottomRow = (HBox) bubble.lookup(".bottom-row");
            if (bottomRow != null) {
                Label statusLabel = (Label) bottomRow.lookup(".status-label");
                if (statusLabel != null) statusLabel.setText(status);
            }
        }
    }
    
    private void displayMessage(Message msg, String initialStatus) {
        HBox messageContainer = new HBox();
        VBox messageBubble = new VBox(5);
        messageBubble.getStyleClass().add("message-bubble");

        if (isGroupChat && !msg.getSender().equals(sender)) {
            Label senderLabel = new Label(msg.getSender());
            senderLabel.getStyleClass().add("sender-name-label"); // Define this style in css
            messageBubble.getChildren().add(senderLabel);
        }

        if (msg.getFileData() == null) {
            Label textLabel = new Label(msg.getContent());
            textLabel.setWrapText(true);
            messageBubble.getChildren().add(textLabel);
        } else {
            Hyperlink fileLink = new Hyperlink(msg.getContent());
            fileLink.getStyleClass().add("file-link");
            fileLink.setOnAction(e -> {
                if (msg.getType() == Message.MessageType.FILE || msg.getType() == Message.MessageType.GROUP_FILE) {
                    saveFile(msg);
                } else {
                    playVoiceMessage(msg);
                }
            });
            messageBubble.getChildren().add(fileLink);
        }

        HBox bottomRow = new HBox(5);
        bottomRow.setAlignment(Pos.CENTER_RIGHT);
        bottomRow.getStyleClass().add("bottom-row");

        LocalDateTime ldt = LocalDateTime.ofInstant(Instant.ofEpochMilli(msg.getTimestamp()), ZoneId.systemDefault());
        Label timestampLabel = new Label(ldt.format(TIME_FORMATTER));
        timestampLabel.getStyleClass().add("timestamp-label");

        Label statusLabel = new Label(initialStatus);
        statusLabel.getStyleClass().add("status-label");
        statusLabel.setId("status-label");

        bottomRow.getChildren().addAll(timestampLabel, statusLabel);
        messageBubble.getChildren().add(bottomRow);

        if (msg.getSender().equals(sender)) {
            messageBubble.getStyleClass().add("sent");
            messageContainer.setAlignment(Pos.CENTER_RIGHT);
        } else {
            messageBubble.getStyleClass().add("received");
            statusLabel.setVisible(false);
            messageContainer.setAlignment(Pos.CENTER_LEFT);
        }
        
        messageContainer.getChildren().add(messageBubble);
        chatVBox.getChildren().add(messageContainer);
        messageUIElements.put(msg.getMessageId(), messageContainer);
    }
    
    private void setupMentionHandling() {
        messageField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (isGroupChat && mentionPopup.isShowing()) {
                int atIndex = newVal.lastIndexOf('@');
                if (atIndex != -1) {
                    updateMentionPopup(newVal.substring(atIndex + 1));
                } else {
                    mentionPopup.hide();
                }
            }
        });
        messageField.setOnKeyTyped(event -> {
            if (isGroupChat && event.getCharacter().equals("@")) {
                updateMentionPopup("");
                mentionPopup.show(messageField, Side.TOP, 0, 0);
            }
        });
    }

    private void updateMentionPopup(String query) {
        List<String> suggestions = currentChatMembers.stream()
            .filter(m -> m.toLowerCase().startsWith(query.toLowerCase()) && !m.equals(sender))
            .collect(Collectors.toList());
        
        if (suggestions.isEmpty()) {
            mentionPopup.hide();
            return;
        }

        mentionPopup.getItems().clear();
        for (String member : suggestions) {
            MenuItem item = new MenuItem(member);
            item.setOnAction(e -> {
                int atIndex = messageField.getText().lastIndexOf('@');
                messageField.replaceText(atIndex, messageField.getCaretPosition(), "@" + member + " ");
                mentionPopup.hide();
            });
            mentionPopup.getItems().add(item);
        }
    }
    
    private void setupEmojiPicker() {
        FlowPane emojiPane = new FlowPane();
        emojiPane.setHgap(5);
        emojiPane.setVgap(5);
        emojiPane.setPadding(new Insets(10));
        emojiPane.getStyleClass().add("emoji-picker");

        String[] emojis = {"😀", "😂", "😍", "😭", "👍", "❤️", "🙏", "🔥", "🤔", "🎉", "💯", "🤯"};
        for(String emoji : emojis) {
            Button btn = new Button(emoji);
            btn.getStyleClass().add("emoji-button");
            btn.setOnAction(e -> {
                messageField.appendText(emoji);
                emojiPopup.hide();
            });
            emojiPane.getChildren().add(btn);
        }
        emojiPopup.getContent().add(emojiPane);
        emojiPopup.setAutoHide(true);
    }
    
    @FXML
    private void handleEmojiButton() {
        if (emojiPopup.isShowing()) {
            emojiPopup.hide();
        } else {
            // --- THIS IS THE CORRECTED PART ---
            // Get the window that owns the button
            Window owner = emojiButton.getScene().getWindow();
            // Get the button's position on the screen
            Bounds screenBounds = emojiButton.localToScreen(emojiButton.getBoundsInLocal());
            double x = screenBounds.getMinX();
            // Position the popup right above the button
            double y = screenBounds.getMinY() - 40; // Adjust the Y offset as needed
            
            // Use the correct show method with calculated coordinates
            emojiPopup.show(owner, x, y);
        }
    }

    private void saveFile(Message msg) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save File");
        fileChooser.setInitialFileName(msg.getContent());
        File file = fileChooser.showSaveDialog(chatVBox.getScene().getWindow());
        if (file != null) {
            try {
                Files.write(file.toPath(), msg.getFileData());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
    
    private void playVoiceMessage(Message msg) {
        try {
            File tempAudioFile = Files.createTempFile("voice_play_", ".wav").toFile();
            Files.write(tempAudioFile.toPath(), msg.getFileData());
            
            Media media = new Media(tempAudioFile.toURI().toString());
            MediaPlayer mediaPlayer = new MediaPlayer(media);
            mediaPlayer.setOnEndOfMedia(tempAudioFile::delete);
            mediaPlayer.play();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleServerDisconnect() {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Connection to the server has been lost.", ButtonType.OK);
            alert.setHeaderText("Connection Lost");
            scheduler.shutdownNow();
            alert.showAndWait();
            Platform.exit();
        });
    }
}
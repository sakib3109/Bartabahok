import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

public class DashboardController {

    @FXML private Label welcomeLabel;
    @FXML private TextField searchField;
    @FXML private ListView<String> onlineUsersListView;
    @FXML private ListView<String> allUsersListView;
    @FXML private ListView<String> groupsListView;
    @FXML private Button createGroupButton;
    @FXML private HBox groupActionBox;
    @FXML private Button joinGroupButton;
    @FXML private Button enterChatButton;
    @FXML private Button leaveGroupButton;
    @FXML private TabPane tabPane;

    private Client client;
    private String currentUser;

    private final ObservableList<String> onlineUsers = FXCollections.observableArrayList();
    private final ObservableList<String> allRegisteredUsers = FXCollections.observableArrayList();
    private final ObservableList<String> allGroups = FXCollections.observableArrayList();
    private final Map<String, Integer> groupNameToIdMap = new HashMap<>();
    private final List<Integer> myGroupMemberships = new ArrayList<>();
    private final Map<String, ChatController> openChats = new HashMap<>();

    public void initialize(Client client, String username) {
        this.client = client;
        this.currentUser = username;
        this.welcomeLabel.setText("Welcome, " + username + "!");
        client.setOnMessageReceived(this::processMessage);
        onlineUsersListView.setCellFactory(lv -> new UserCell(onlineUsers));
        allUsersListView.setCellFactory(lv -> new UserCell(onlineUsers));
        setupUserListView(onlineUsersListView, onlineUsers);
        setupUserListView(allUsersListView, allRegisteredUsers);
        setupGroupListView();

        searchField.textProperty().addListener((obs, ov, nv) -> filterLists(nv));

        groupActionBox.setVisible(false);
        groupActionBox.setManaged(false);
        tabPane.widthProperty().addListener((obs, oldVal, newVal) -> {
            Side side = tabPane.getSide();
            if (side.isVertical()) return;
            double newWidth = newVal.doubleValue() - 20; // -20 is a small padding adjustment
            for(Tab tab : tabPane.getTabs()){
                // The -1 is for a small border between tabs
                tab.getStyleableNode().prefWidth((newWidth / tabPane.getTabs().size()) - 1);
            }
        });
        
        try {
            client.sendMessage(new Message(Message.MessageType.GET_USER_LIST, currentUser, "Server", null, null, 0));
            client.sendMessage(new Message(Message.MessageType.GET_ALL_GROUPS, currentUser, "Server", null, null, 0));
            client.sendMessage(new Message(Message.MessageType.GET_MY_GROUPS, currentUser, "Server", null, null, 0));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private class UserCell extends ListCell<String> {
        private HBox hbox = new HBox(10);
        private Label nameLabel = new Label();
        private Pane spacer = new Pane();
        private VBox activeBox = new VBox(3);
        private Pane activeIndicator = new Pane();
        private Label activeNowLabel = new Label("Active now");
        private final ObservableList<String> onlineUserList;

        public UserCell(ObservableList<String> onlineUserList) {
            this.onlineUserList = onlineUserList;
            
            HBox.setHgrow(spacer, Priority.ALWAYS);
            activeIndicator.getStyleClass().add("active-indicator");
            activeNowLabel.getStyleClass().add("active-now-label");
            activeBox.setAlignment(Pos.CENTER);
            activeBox.getChildren().addAll(activeIndicator, activeNowLabel);
            hbox.setAlignment(Pos.CENTER_LEFT);
            hbox.getChildren().addAll(nameLabel, spacer, activeBox);
        }

        @Override
        protected void updateItem(String username, boolean empty) {
            super.updateItem(username, empty);
            if (empty || username == null) {
                setText(null);
                setGraphic(null);
            } else {
                nameLabel.setText(username);
                // Show the active indicator only if the user is in the online list
                if (onlineUserList.contains(username)) {
                    activeBox.setVisible(true);
                    activeBox.setManaged(true);
                } else {
                    activeBox.setVisible(false);
                    activeBox.setManaged(false);
                }
                setGraphic(hbox);
            }
        }
    }
    private void processMessage(Serializable serializable) {
    if (!(serializable instanceof Message)) return;
    
    Message msg = (Message)serializable;
    
    // Check if this message belongs to an open chat window first
    if (msg.getType() == Message.MessageType.TEXT || msg.getType() == Message.MessageType.FILE || 
        msg.getType() == Message.MessageType.VOICE || msg.getType() == Message.MessageType.GROUP_MESSAGE ||
        msg.getType() == Message.MessageType.GROUP_FILE || msg.getType() == Message.MessageType.GROUP_VOICE) {
        
        String chatKey = msg.getType().name().startsWith("GROUP_") ? 
            "group-" + msg.getReceiver() : 
            "private-" + msg.getSender();
            
        if (openChats.containsKey(chatKey)) {
            openChats.get(chatKey).processIncomingMessage(msg);
            return;
        }
    }
    
    Platform.runLater(() -> {
        switch (msg.getType()) {
            case USER_LIST_UPDATE: updateUserLists(msg.getContent()); break;
            case ALL_GROUPS_LIST: updateGroupList(msg.getContent()); break;
            case MY_GROUPS_LIST:
                myGroupMemberships.clear();
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    Arrays.stream(msg.getContent().split(","))
                        .map(Integer::parseInt)
                        .forEach(myGroupMemberships::add);
                }
                updateGroupButtonState(groupsListView.getSelectionModel().getSelectedItem());
                break;
            default: break;
        }
    });
}

    private void updateUserLists(String content) {
        String[] lists = content.split("#");
        onlineUsers.clear();
        if (lists.length > 0 && !lists[0].isEmpty()) onlineUsers.addAll(Arrays.asList(lists[0].split(",")));
        onlineUsers.remove(currentUser);

        allRegisteredUsers.clear();
        if (lists.length > 1 && !lists[1].isEmpty()) allRegisteredUsers.addAll(Arrays.asList(lists[1].split(",")));
        allRegisteredUsers.remove(currentUser);
    }
    
    private void updateGroupList(String content) {
        allGroups.clear();
        groupNameToIdMap.clear();
        if (content != null && !content.isEmpty()) {
            for (String groupInfo : content.split(",")) {
                String[] parts = groupInfo.split(":", 2);
                if (parts.length == 2) {
                    int id = Integer.parseInt(parts[0]);
                    String name = parts[1];
                    allGroups.add(name);
                    groupNameToIdMap.put(name, id);
                }
            }
        }
    }
    
    private void setupUserListView(ListView<String> listView, ObservableList<String> list) {
        listView.setItems(list);
        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String recipient = listView.getSelectionModel().getSelectedItem();
                if (recipient != null) openPrivateChat(recipient);
            }
        });
    }
    
    private void setupGroupListView() {
        groupsListView.setItems(allGroups);
        groupsListView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> updateGroupButtonState(newVal));
        groupsListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                handleEnterChatButton();
            }
        });
    }

    private void updateGroupButtonState(String selectedGroup) {
        if (selectedGroup == null) {
            groupActionBox.setVisible(false);
            groupActionBox.setManaged(false);
            return;
        }
        
        groupActionBox.setVisible(true);
        groupActionBox.setManaged(true);

        Integer groupId = groupNameToIdMap.get(selectedGroup);
        if (groupId == null) return; 

        boolean isMember = myGroupMemberships.contains(groupId);

        joinGroupButton.setVisible(!isMember);
        joinGroupButton.setManaged(!isMember);

        enterChatButton.setVisible(isMember);
        enterChatButton.setManaged(isMember);
        leaveGroupButton.setVisible(isMember);
        leaveGroupButton.setManaged(isMember);
    }

    @FXML
    private void handleCreateGroupButton() {
        TextInputDialog dialog = new TextInputDialog("New Group");
        dialog.setTitle("Create New Group");
        dialog.setHeaderText("Enter a name for your new group.");
        dialog.setContentText("Name:");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("styles.css").toExternalForm());
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            if (!name.trim().isEmpty()) {
                try {
                    client.sendMessage(new Message(Message.MessageType.CREATE_GROUP, currentUser, "Server", name, null, 0));
                } catch (IOException e) { e.printStackTrace(); }
            }
        });
    }

    @FXML
    private void handleJoinGroupButton() {
        String groupName = groupsListView.getSelectionModel().getSelectedItem();
        if (groupName != null) {
            int groupId = groupNameToIdMap.get(groupName);
            try {
                client.sendMessage(new Message(Message.MessageType.JOIN_GROUP, currentUser, "Server", String.valueOf(groupId), null, 0));
                myGroupMemberships.add(groupId);
                updateGroupButtonState(groupName);
            } catch (IOException e) { e.printStackTrace(); }
        }
    }
    
    @FXML
    private void handleEnterChatButton() {
        String groupName = groupsListView.getSelectionModel().getSelectedItem();
        if (groupName != null) {
            int groupId = groupNameToIdMap.get(groupName);
            if (myGroupMemberships.contains(groupId)) {
                openGroupChat(groupId, groupName);
            }
        }
    }

    @FXML
    private void handleLeaveGroupButton() {
        String groupName = groupsListView.getSelectionModel().getSelectedItem();
        if (groupName != null) {
            int groupId = groupNameToIdMap.get(groupName);
            try {
                client.sendMessage(new Message(Message.MessageType.LEAVE_GROUP, currentUser, "Server", String.valueOf(groupId), null, 0));
                myGroupMemberships.remove(Integer.valueOf(groupId));
                updateGroupButtonState(groupName);
            } catch (IOException e) { e.printStackTrace(); }
        }
    }

    private void filterLists(String query) {
        String lowerCaseQuery = query.toLowerCase();
        onlineUsersListView.setItems(onlineUsers.filtered(u -> u.toLowerCase().contains(lowerCaseQuery)));
        allUsersListView.setItems(allRegisteredUsers.filtered(u -> u.toLowerCase().contains(lowerCaseQuery)));
        groupsListView.setItems(allGroups.filtered(g -> g.toLowerCase().contains(lowerCaseQuery)));
    }
    
    // In DashboardController.java

    private void openPrivateChat(String recipient) {
        String chatKey = "private-" + recipient;
        if (openChats.containsKey(chatKey)) return;

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("chat.fxml"));
            Parent root = loader.load();
            ChatController controller = loader.getController();
            openChats.put(chatKey, controller);
            
            // --- THIS IS THE CORRECTED CALL ---
            controller.setupController(client, currentUser, recipient, false); // false for private chat
            
            showChatStage("Chat with " + recipient, root, chatKey);
        } catch (IOException e) { e.printStackTrace(); }
    }
    
    private void openGroupChat(int groupId, String groupName) {
        String chatKey = "group-" + groupId;
        if (openChats.containsKey(chatKey)) return;

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("chat.fxml"));
            Parent root = loader.load();
            ChatController controller = loader.getController();
            openChats.put(chatKey, controller);

            // --- THIS IS THE CORRECTED CALL ---
            controller.setupController(client, currentUser, String.valueOf(groupId), true); // true for group chat
            
            showChatStage("Group: " + groupName, root, chatKey);
        } catch (IOException e) { e.printStackTrace(); }
    }
    
    private void showChatStage(String title, Parent root, String chatKey) {
    Stage chatStage = new Stage();
    chatStage.setTitle(title);
    chatStage.setScene(new Scene(root, 800, 600));
    chatStage.initModality(Modality.NONE);
    chatStage.initOwner(((Stage) welcomeLabel.getScene().getWindow()));
    chatStage.setOnCloseRequest(event -> {
        openChats.remove(chatKey);
        try {
            client.sendMessage(new Message(Message.MessageType.GET_MY_GROUPS, currentUser, "Server", null, null, 0));
        } catch (IOException e) {
            e.printStackTrace();
        }
    });
    chatStage.show();
}

    @FXML
    private void handleSearchFieldAction() {
        filterLists(searchField.getText());
    }

    @FXML
    private void handleLogoutButton() {
        try {
            client.sendMessage(new Message(Message.MessageType.LOGOUT, currentUser, "Server", null, null, 0));
        } catch (IOException e) {
            e.printStackTrace();
}
 }
}
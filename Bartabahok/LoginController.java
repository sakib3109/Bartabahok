import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.Serializable;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;
    @FXML private Button signupButton;
    @FXML private Label statusLabel;

    private Client client;

    @FXML
    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();
        if (username.isEmpty() || password.isEmpty()) {
            updateStatus("Username and password cannot be empty.", true);
            return;
        }

        setButtonsDisabled(true);
        updateStatus("Connecting to server...", false);

        client = new Client("localhost", 5000, this::processMessage);
        try {
            client.connect();
            Message loginMsg = new Message(Message.MessageType.LOGIN, username, "Server", password, null, 0);
            client.sendMessage(loginMsg);
        } catch (IOException e) {
            updateStatus("Error: Could not connect to the server.", true);
            setButtonsDisabled(false);
        }
    }

    @FXML
    private void handleSignup() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();
        if (username.isEmpty() || password.isEmpty()) {
            updateStatus("Username and password are required for signup.", true);
            return;
        }

        setButtonsDisabled(true);
        updateStatus("Attempting to sign up...", false);
        
        // This client is temporary, just for the signup transaction.
        Client signupClient = new Client("localhost", 5000, this::processMessage);
        try {
            signupClient.connect();
            Message signupMsg = new Message(Message.MessageType.SIGNUP, username, "Server", password, null, 0);
            signupClient.sendMessage(signupMsg);
        } catch (IOException e) {
            updateStatus("Error: Could not connect to the server for signup.", true);
            setButtonsDisabled(false);
        }
    }

    private void processMessage(Serializable message) {
        if (message instanceof Message) {
            Message msg = (Message) message;
            Platform.runLater(() -> {
                switch (msg.getType()) {
                    case LOGIN_SUCCESS:
                        switchToDashboard(msg.getReceiver());
                        break;
                    case LOGIN_FAIL:
                        updateStatus("Login failed. Please check credentials.", true);
                        setButtonsDisabled(false);
                        if (client != null) client.stop();
                        break;
                    
                    // --- CORRECTED SIGNUP LOGIC ---
                    case SIGNUP_SUCCESS:
                        updateStatus("Signup successful! Please log in.", false);
                        setButtonsDisabled(false);
                        break;
                    case SIGNUP_FAIL:
                        updateStatus("Signup failed. Username already exists.", true);
                        setButtonsDisabled(false);
                        break;

                    default:
                        // Ignore other message types on the login screen
                        break;
                }
            });
        }
    }

    private void switchToDashboard(String username) {
        try {
            Stage stage = (Stage) loginButton.getScene().getWindow();
            
            FXMLLoader loader = new FXMLLoader(getClass().getResource("dashboard.fxml"));
            Parent root = loader.load();

            DashboardController dashboardController = loader.getController();
            dashboardController.initialize(this.client, username);

            stage.setScene(new Scene(root, 450, 700)); // Adjusted size for new layout
            stage.setTitle("Bartabahok - Dashboard");

            stage.setOnCloseRequest(e -> {
                client.stop();
                Platform.exit();
            });

        } catch (IOException e) {
            e.printStackTrace();
            updateStatus("Fatal Error: Could not load dashboard.", true);
        }
    }

    private void updateStatus(String text, boolean isError) {
        statusLabel.setText(text);
        if (isError) {
            statusLabel.setStyle("-fx-text-fill: #d9534f;");
        } else {
            statusLabel.setStyle("-fx-text-fill: #a7aeb3;");
        }
    }

    private void setButtonsDisabled(boolean disabled) {
        loginButton.setDisable(disabled);
        signupButton.setDisable(disabled);
    }
}
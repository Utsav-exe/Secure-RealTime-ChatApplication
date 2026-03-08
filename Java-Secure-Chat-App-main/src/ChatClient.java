import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;
import javafx.animation.PauseTransition;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class ChatClient extends Application {
    private SSLSocket socket;
    private PrintWriter out;
    private BufferedReader in;
    private final Gson gson = new Gson();

    private Stage primaryStage;
    private int userId;
    private User currentUser;
    private final ObservableList<Chat> chats = FXCollections.observableArrayList();
    private final Map<Integer, ObservableList<Message>> chatMessages = new HashMap<>();
    private ListView<Chat> chatListView;
    private ListView<Message> messageListView;
    private BorderPane rightPanel;
    private Label typingIndicatorLabel;

    // --- UI Styling Constants ---
    private static final String COLOR_PRIMARY = "#007AFF";
    private static final String COLOR_BACKGROUND = "#F4F6F8";
    private static final String CONTACT_LIST_BACKGROUND = "#FFFFFF";
    private static final String COLOR_DARK_TEXT = "#333333";
    private static final String SENT_BUBBLE_COLOR = "#D9FDD3";
    private static final String RECEIVED_BUBBLE_COLOR = "#FFFFFF";
    private static final String CHAT_AREA_BACKGROUND = "#E5DDD5";

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("Secure Chat");

        connectToServer();

        primaryStage.setScene(createLoginScreen());
        primaryStage.setOnCloseRequest(e -> System.exit(0));
        primaryStage.show();
    }

    private void connectToServer() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream("server_keystore.jks"), "password".toCharArray());
            tmf.init(ks);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);

            SSLSocketFactory sf = sslContext.getSocketFactory();
            socket = (SSLSocket) sf.createSocket("localhost", 9999);

            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            new Thread(this::listenToServer).start();

        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Connection Error", "Could not connect to the server. Please ensure the server is running and the keystore file is present."));
        }
    }

    private void listenToServer() {
        try {
            String serverMessage;
            while ((serverMessage = in.readLine()) != null) {
                final String finalMessage = serverMessage;
                Platform.runLater(() -> handleServerMessage(finalMessage));
            }
        } catch (IOException e) {
            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Connection Lost", "Disconnected from server."));
        } finally {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void handleServerMessage(String jsonMessage) {
        try {
            JsonObject jsonObject = gson.fromJson(jsonMessage, JsonObject.class);
            String type = jsonObject.get("type").getAsString();

            switch (type) {
                case "LOGIN_SUCCESS": onLoginSuccess(jsonObject); break;
                case "LOGIN_FAIL": showAlert(Alert.AlertType.ERROR, "Login Failed", "Invalid email or password."); break;
                case "SIGNUP_SUCCESS": showAlert(Alert.AlertType.INFORMATION, "Signup Successful", "You can now log in."); break;
                case "SIGNUP_FAIL": showAlert(Alert.AlertType.ERROR, "Signup Failed", jsonObject.get("reason").getAsString()); break;
                case "RECEIVE_MESSAGE": onReceiveMessage(jsonObject); break;
                case "ADD_CONTACT_SUCCESS": onAddContactSuccess(jsonObject); break;
                case "NEW_CHAT_STARTED": onNewChatStarted(jsonObject); break;
                case "DELETE_CONTACT_SUCCESS": onDeleteContactSuccess(jsonObject.get("chatId").getAsInt()); break;
                case "CHAT_REMOVED": onChatRemoved(jsonObject.get("chatId").getAsInt()); break;
                case "MESSAGE_DELETED": onMessageDeleted(jsonObject); break;
                case "TYPING_STARTED": onTypingUpdate(jsonObject, true); break;
                case "TYPING_STOPPED": onTypingUpdate(jsonObject, false); break;
                case "USER_ONLINE": onUserStatusUpdate(jsonObject.get("userId").getAsInt(), true); break;
                case "USER_OFFLINE": onUserStatusUpdate(jsonObject.get("userId").getAsInt(), false); break;
                case "CHAT_HISTORY": onReceiveChatHistory(jsonObject); break;
                case "USER_PROFILE_DATA": onReceiveUserProfile(jsonObject); break;
                case "UPDATE_PROFILE_RESULT": onProfileUpdateResult(jsonObject); break;
                case "PROFILE_UPDATED": onProfileUpdated(jsonObject); break;
                case "GROUP_CREATED_SUCCESS": onGroupCreatedSuccess(jsonObject); break;
                case "NEW_GROUP_CHAT": onNewGroupChat(jsonObject); break;
                case "CREATE_GROUP_FAIL": showAlert(Alert.AlertType.ERROR, "Group Error", "Failed to create group."); break;
                case "GROUP_MEMBERS_LIST": onReceiveGroupMembers(jsonObject); break;
                case "REMOVE_MEMBER_FAIL": showAlert(Alert.AlertType.ERROR, "Error", jsonObject.get("reason").getAsString()); break;
                case "REMOVED_FROM_GROUP": onRemovedFromGroup(jsonObject.get("chatId").getAsInt()); break;
                case "LEFT_GROUP_SUCCESS": onLeftGroupSuccess(jsonObject.get("chatId").getAsInt()); break;
                case "LEAVE_GROUP_FAIL": showAlert(Alert.AlertType.ERROR, "Error", "Failed to leave group."); break;
                case "MEMBER_LEFT_GROUP": onMemberLeftOrRemoved(jsonObject); break;
                default:
                    System.err.println("Unknown message type received: " + type);
            }
        } catch (JsonSyntaxException e) {
            System.err.println("Received invalid JSON from server: " + jsonMessage);
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Error handling server message: " + jsonMessage);
            e.printStackTrace();
        }
    }

    private Scene createLoginScreen() {
        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(10);
        grid.setVgap(12);
        grid.setPadding(new Insets(40, 40, 40, 40));
        grid.setStyle("-fx-background-color: " + COLOR_BACKGROUND + ";");

        Text scenetitle = new Text("Welcome to Secure Chat");
        scenetitle.setFont(Font.font("Helvetica", FontWeight.NORMAL, 22));
        scenetitle.setFill(Color.web(COLOR_DARK_TEXT));
        grid.add(scenetitle, 0, 0, 2, 1);

        TextField nameField = createStyledTextField("Your Name (for signup)");
        TextField emailField = createStyledTextField("Email Address");
        PasswordField passwordField = createStyledPasswordField();

        Button signInButton = createStyledButton("Sign In");
        Button signUpButton = createStyledButton("Sign Up");

        grid.add(nameField, 0, 1, 2, 1);
        grid.add(emailField, 0, 2, 2, 1);
        grid.add(passwordField, 0, 3, 2, 1);

        HBox hbButtons = new HBox(10);
        hbButtons.setAlignment(Pos.BOTTOM_RIGHT);
        hbButtons.getChildren().addAll(signUpButton, signInButton);
        grid.add(hbButtons, 1, 4);

        signInButton.setOnAction(e -> {
            JsonObject loginDetails = new JsonObject();
            loginDetails.addProperty("type", "LOGIN");
            loginDetails.addProperty("email", emailField.getText());
            loginDetails.addProperty("password", passwordField.getText());
            sendMessageToServer(loginDetails.toString());
        });

        signUpButton.setOnAction(e -> {
            JsonObject signupDetails = new JsonObject();
            signupDetails.addProperty("type", "SIGNUP");
            signupDetails.addProperty("name", nameField.getText());
            signupDetails.addProperty("email", emailField.getText());
            signupDetails.addProperty("password", passwordField.getText());
            sendMessageToServer(signupDetails.toString());
        });

        return new Scene(grid, 450, 400);
    }

    private Scene createMainChatScreen() {
        BorderPane mainLayout = new BorderPane();
        mainLayout.setStyle("-fx-background-color: " + COLOR_BACKGROUND + ";");

        VBox leftPanel = new VBox();
        leftPanel.setStyle("-fx-background-color: " + CONTACT_LIST_BACKGROUND + ";");
        leftPanel.setPrefWidth(300);
        leftPanel.setEffect(new DropShadow(2, Color.gray(0, 0.1)));

        HBox topHeader = new HBox(10);
        topHeader.setAlignment(Pos.CENTER_LEFT);
        topHeader.setPadding(new Insets(10));

        Button profileButton = new Button();
        profileButton.setGraphic(createAvatar(currentUser.name, currentUser.profile_picture_url, 20));
        profileButton.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        profileButton.setOnAction(e -> showProfileEditor());
        profileButton.setTooltip(new Tooltip("Edit Your Profile"));

        Label conversationsLabel = new Label("Conversations");
        conversationsLabel.setFont(Font.font("Helvetica", FontWeight.BOLD, 18));
        Pane spacer = new Pane(); HBox.setHgrow(spacer, Priority.ALWAYS);

        Button newGroupButton = new Button();
        newGroupButton.setGraphic(createSVGIcon("M17 14v-4h-4v-2h4v-4h2v4h4v2h-4v4zM10 16c-3.31 0-6-2.69-6-6s2.69-6 6-6 6 2.69 6 6-2.69 6-6 6z")); // Group icon
        newGroupButton.setStyle("-fx-background-color: transparent;");
        newGroupButton.setOnAction(e -> showCreateGroupDialog());
        newGroupButton.setTooltip(new Tooltip("Create New Group"));

        Button newChatButton = new Button();
        newChatButton.setGraphic(createSVGIcon("M12 5v14m-7-7h14")); // Plus
        newChatButton.setStyle("-fx-background-color: transparent;");
        newChatButton.setOnAction(e -> handleNewChat());
        newChatButton.setTooltip(new Tooltip("Add New Contact"));

        topHeader.getChildren().addAll(profileButton, conversationsLabel, spacer, newGroupButton, newChatButton);

        chatListView = new ListView<>(chats);
        chatListView.setPlaceholder(new Label("No conversations yet."));
        chatListView.getSelectionModel().selectedItemProperty().addListener((obs, old, newChat) -> {
            if (newChat != null) displayChat(newChat);
        });
        chatListView.setCellFactory(lv -> new ChatListCell());
        VBox.setVgrow(chatListView, Priority.ALWAYS);
        leftPanel.getChildren().addAll(topHeader, new Separator(), chatListView);

        rightPanel = new BorderPane();
        showPlaceholder();

        mainLayout.setLeft(leftPanel);
        mainLayout.setCenter(rightPanel);

        return new Scene(mainLayout, 1000, 700);
    }

    private void onLoginSuccess(JsonObject data) {
        this.currentUser = gson.fromJson(data.get("user"), User.class);
        this.userId = currentUser.id;

        Set<Integer> onlineContactIds = new HashSet<>();
        JsonArray onlineContactsJson = data.get("onlineContacts").getAsJsonArray();
        for (JsonElement element : onlineContactsJson) {
            onlineContactIds.add(element.getAsInt());
        }

        JsonArray initialChatsJson = data.get("chats").getAsJsonArray();
        chats.clear();
        chatMessages.clear();
        for (JsonElement chatElement : initialChatsJson) {
            JsonObject chatObj = chatElement.getAsJsonObject();
            Chat chat = new Chat();
            chat.id = chatObj.get("id").getAsInt();
            chat.name = chatObj.get("name").getAsString();
            chat.contactId = chatObj.has("contactId") ? chatObj.get("contactId").getAsInt() : -1;
            chat.isGroupChat = chat.contactId == -1;
            if(chatObj.has("ownerId")) {
                chat.ownerId = chatObj.get("ownerId").getAsInt();
            }
            if (!chat.isGroupChat) {
                chat.setOnline(onlineContactIds.contains(chat.contactId));
            }
            chats.add(chat);
            chatMessages.put(chat.id, FXCollections.observableArrayList());
        }

        primaryStage.setScene(createMainChatScreen());
    }

    private void onReceiveMessage(JsonObject data) {
        Message message = gson.fromJson(data.get("message"), Message.class);
        if (chatMessages.containsKey(message.chatId)) {
            ObservableList<Message> messageList = chatMessages.get(message.chatId);
            messageList.add(message);
            if (messageListView != null && messageListView.getItems() == messageList) {
                messageListView.scrollTo(messageList.size() - 1);
            }
        }
    }

    private void onReceiveChatHistory(JsonObject data) {
        int chatId = data.get("chatId").getAsInt();
        if (chatMessages.containsKey(chatId)) {
            ObservableList<Message> messages = chatMessages.get(chatId);
            messages.clear();
            JsonArray historyJson = data.get("history").getAsJsonArray();
            for (JsonElement msgElement : historyJson) {
                messages.add(gson.fromJson(msgElement, Message.class));
            }
            if (messageListView != null && messageListView.getItems() == messages && !messages.isEmpty()) {
                Platform.runLater(() -> messageListView.scrollTo(messages.size() - 1));
            }
        }
    }

    private void onAddContactSuccess(JsonObject data) {
        JsonObject chatObj = data.get("chat").getAsJsonObject();
        Chat newChat = new Chat();
        newChat.id = chatObj.get("id").getAsInt();
        newChat.name = chatObj.get("name").getAsString();
        newChat.contactId = chatObj.get("contactId").getAsInt();
        newChat.isGroupChat = false;
        newChat.setOnline(true);
        chats.add(newChat);
        chatMessages.put(newChat.id, FXCollections.observableArrayList());
    }

    private void onNewChatStarted(JsonObject data) {
        onAddContactSuccess(data);
    }

    private void onGroupCreatedSuccess(JsonObject data) {
        // ** FIX for duplication: Check if chat already exists **
        Chat newChat = gson.fromJson(data.get("chat"), Chat.class);
        if (chats.stream().noneMatch(c -> c.id == newChat.id)) {
            newChat.isGroupChat = true;
            chats.add(newChat);
            chatMessages.put(newChat.id, FXCollections.observableArrayList());
            chatListView.getSelectionModel().select(newChat);
        }
    }

    private void onNewGroupChat(JsonObject data) {
        // ** FIX for duplication: Check if chat already exists **
        Chat newChat = gson.fromJson(data.get("chat"), Chat.class);
        if (chats.stream().noneMatch(c -> c.id == newChat.id)) {
            newChat.isGroupChat = true;
            chats.add(newChat);
            chatMessages.put(newChat.id, FXCollections.observableArrayList());
        }
    }


    private void onDeleteContactSuccess(int chatId) {
        chats.removeIf(chat -> chat.id == chatId);
        chatMessages.remove(chatId);
        showPlaceholder();
    }

    private void onChatRemoved(int chatId) {
        onDeleteContactSuccess(chatId);
        showAlert(Alert.AlertType.INFORMATION, "Chat Removed", "A contact has removed this conversation.");
    }

    private void onMessageDeleted(JsonObject data) {
        int messageId = data.get("messageId").getAsInt();
        int chatId = data.get("chatId").getAsInt();
        if (chatMessages.containsKey(chatId)) {
            chatMessages.get(chatId).removeIf(msg -> msg.id == messageId);
        }
    }

    private void onTypingUpdate(JsonObject data, boolean isTyping) {
        int chatId = data.get("chatId").getAsInt();
        Chat currentChat = chatListView.getSelectionModel().getSelectedItem();
        if (currentChat != null && currentChat.id == chatId && typingIndicatorLabel != null) {
            typingIndicatorLabel.setVisible(isTyping);
        }
    }

    private void onUserStatusUpdate(int updatedUserId, boolean isOnline) {
        for (Chat chat : chats) {
            if (!chat.isGroupChat && chat.contactId == updatedUserId) {
                chat.setOnline(isOnline);
                chatListView.refresh();
                break;
            }
        }
    }

    private void onReceiveUserProfile(JsonObject data) {
        User user = gson.fromJson(data.get("user"), User.class);
        showProfileViewer(user);
    }

    private void onProfileUpdateResult(JsonObject data) {
        if (data.get("success").getAsBoolean()) {
            this.currentUser = gson.fromJson(data.get("user"), User.class);
            showAlert(Alert.AlertType.INFORMATION, "Success", "Profile updated successfully.");
            primaryStage.setScene(createMainChatScreen());
        } else {
            showAlert(Alert.AlertType.ERROR, "Error", "Failed to update profile.");
        }
    }

    private void onProfileUpdated(JsonObject data) {
        User updatedUser = gson.fromJson(data.get("user"), User.class);
        for (Chat chat : chats) {
            if (!chat.isGroupChat && chat.contactId == updatedUser.id) {
                chat.name = updatedUser.name;
                chatListView.refresh();

                Chat selectedChat = chatListView.getSelectionModel().getSelectedItem();
                if (selectedChat != null && selectedChat.id == chat.id) {
                    displayChat(chat);
                }
                break;
            }
        }
    }

    private void showPlaceholder() {
        Label placeholder = new Label("Select a conversation to start chatting");
        placeholder.setFont(Font.font("Helvetica", 18));
        placeholder.setTextFill(Color.GRAY);
        VBox placeholderBox = new VBox(placeholder);
        placeholderBox.setAlignment(Pos.CENTER);
        placeholderBox.setStyle("-fx-background-color: " + CHAT_AREA_BACKGROUND + ";");
        rightPanel.setTop(null);
        rightPanel.setCenter(placeholderBox);
        rightPanel.setBottom(null);
    }

    private void displayChat(Chat chat) {
        JsonObject request = new JsonObject();
        request.addProperty("type", "GET_CHAT_HISTORY");
        request.addProperty("chatId", chat.id);
        sendMessageToServer(request.toString());

        BorderPane header = new BorderPane();
        header.setPadding(new Insets(10, 15, 10, 15));
        header.setStyle("-fx-background-color: #FFFFFF;");

        Button backButton = new Button();
        backButton.setGraphic(createSVGIcon("M15 19l-7-7 7-7")); // Back arrow
        backButton.setStyle("-fx-background-color: transparent;");
        backButton.setOnAction(e -> {
            showPlaceholder();
            chatListView.getSelectionModel().clearSelection();
        });

        Button chatNameButton = new Button(chat.name);
        chatNameButton.setFont(Font.font("Helvetica", FontWeight.BOLD, 18));
        chatNameButton.setStyle("-fx-background-color: transparent; -fx-padding: 5;");
        if (!chat.isGroupChat) {
            chatNameButton.setOnAction(e -> requestUserProfile(chat.contactId));
        }

        typingIndicatorLabel = new Label("typing...");
        typingIndicatorLabel.setFont(Font.font("Helvetica", 12));
        typingIndicatorLabel.setTextFill(Color.GRAY);
        typingIndicatorLabel.setVisible(false);

        VBox nameAndStatus = new VBox(-2, chatNameButton, typingIndicatorLabel);
        HBox headerContent = new HBox(10, backButton, nameAndStatus);
        headerContent.setAlignment(Pos.CENTER_LEFT);
        header.setLeft(headerContent);

        if (chat.isGroupChat) {
            Button groupInfoButton = new Button();
            groupInfoButton.setGraphic(createSVGIcon("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z")); // Info icon
            groupInfoButton.setStyle("-fx-background-color: transparent;");
            groupInfoButton.setTooltip(new Tooltip("View Group Info"));
            groupInfoButton.setOnAction(e -> requestGroupMembers(chat));
            header.setRight(groupInfoButton);
        }

        rightPanel.setTop(header);

        messageListView = new ListView<>(chatMessages.get(chat.id));
        messageListView.setCellFactory(param -> new MessageCell());
        messageListView.setStyle("-fx-background-color: " + CHAT_AREA_BACKGROUND + ";");
        rightPanel.setCenter(messageListView);

        HBox messageInputArea = createMessageInputArea(chat);
        rightPanel.setBottom(messageInputArea);
    }

    private void showCreateGroupDialog() {
        Stage dialogStage = new Stage();
        dialogStage.initModality(Modality.APPLICATION_MODAL);
        dialogStage.setTitle("Create New Group");

        VBox layout = new VBox(15);
        layout.setPadding(new Insets(20));

        TextField groupNameField = createStyledTextField("Group Name");

        Label membersLabel = new Label("Select Members:");
        ListView<Chat> contactListView = new ListView<>();
        ObservableList<Chat> contacts = chats.filtered(c -> !c.isGroupChat);
        contactListView.setItems(contacts);

        Map<Chat, BooleanProperty> selectedState = new HashMap<>();
        contactListView.setCellFactory(CheckBoxListCell.forListView(item ->
                        selectedState.computeIfAbsent(item, c -> new SimpleBooleanProperty(false))
                , new StringConverter<Chat>() {
                    @Override public String toString(Chat chat) { return chat.name; }
                    @Override public Chat fromString(String string) { return null; }
                }));

        Button createButton = createStyledButton("Create Group");
        createButton.setOnAction(e -> {
            String groupName = groupNameField.getText().trim();
            if (groupName.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Input Needed", "Please enter a group name.");
                return;
            }

            List<Integer> memberIds = selectedState.entrySet().stream()
                    .filter(entry -> entry.getValue().get())
                    .map(entry -> entry.getKey().contactId)
                    .collect(Collectors.toList());

            if (memberIds.isEmpty()) {
                showAlert(Alert.AlertType.WARNING, "Input Needed", "Please select at least one member.");
                return;
            }

            JsonObject request = new JsonObject();
            request.addProperty("type", "CREATE_GROUP");
            request.addProperty("groupName", groupName);
            request.add("memberIds", gson.toJsonTree(memberIds));
            sendMessageToServer(request.toString());

            dialogStage.close();
        });

        layout.getChildren().addAll(groupNameField, membersLabel, contactListView, createButton);
        Scene scene = new Scene(layout, 350, 450);
        dialogStage.setScene(scene);
        dialogStage.showAndWait();
    }

    private void requestGroupMembers(Chat chat) {
        JsonObject request = new JsonObject();
        request.addProperty("type", "GET_GROUP_MEMBERS");
        request.addProperty("chatId", chat.id);
        sendMessageToServer(request.toString());
    }

    private void onReceiveGroupMembers(JsonObject data) {
        int chatId = data.get("chatId").getAsInt();
        Chat currentChat = chatListView.getSelectionModel().getSelectedItem();
        if (currentChat == null || currentChat.id != chatId) {
            return;
        }

        Type listType = new TypeToken<ArrayList<User>>() {}.getType();
        List<User> members = gson.fromJson(data.get("members"), listType);

        showGroupInfoDialog(currentChat, members);
    }

    private void showGroupInfoDialog(Chat chat, List<User> members) {
        Stage dialogStage = new Stage();
        dialogStage.initModality(Modality.APPLICATION_MODAL);
        dialogStage.setTitle("Group Info: " + chat.name);

        BorderPane layout = new BorderPane();
        layout.setPadding(new Insets(20));

        VBox centerBox = new VBox(15);
        Label membersHeader = new Label("Members (" + members.size() + ")");
        membersHeader.setFont(Font.font("Helvetica", FontWeight.BOLD, 16));

        ListView<User> membersListView = new ListView<>(FXCollections.observableArrayList(members));
        membersListView.setCellFactory(lv -> new UserListCell(chat, dialogStage));

        centerBox.getChildren().addAll(membersHeader, membersListView);
        layout.setCenter(centerBox);

        Button leaveButton = new Button("Leave Group");
        leaveButton.setStyle("-fx-background-color: #dc3545; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 15;");
        leaveButton.setOnAction(e -> {
            leaveGroup(chat);
            dialogStage.close();
        });

        HBox bottomBox = new HBox(leaveButton);
        bottomBox.setAlignment(Pos.CENTER);
        bottomBox.setPadding(new Insets(20, 0, 0, 0));
        layout.setBottom(bottomBox);

        Scene scene = new Scene(layout, 400, 500);
        dialogStage.setScene(scene);
        dialogStage.showAndWait();
    }

    private void removeGroupMember(Chat chat, User memberToRemove) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Remove Member");
        confirm.setHeaderText("Remove " + memberToRemove.name + " from the group?");
        Optional<ButtonType> result = confirm.showAndWait();

        if (result.isPresent() && result.get() == ButtonType.OK) {
            JsonObject request = new JsonObject();
            request.addProperty("type", "REMOVE_GROUP_MEMBER");
            request.addProperty("chatId", chat.id);
            request.addProperty("userIdToRemove", memberToRemove.id);
            sendMessageToServer(request.toString());
        }
    }

    private void leaveGroup(Chat chat) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Leave Group");
        confirm.setHeaderText("Are you sure you want to leave '" + chat.name + "'?");
        Optional<ButtonType> result = confirm.showAndWait();

        if (result.isPresent() && result.get() == ButtonType.OK) {
            JsonObject request = new JsonObject();
            request.addProperty("type", "LEAVE_GROUP");
            request.addProperty("chatId", chat.id);
            sendMessageToServer(request.toString());
        }
    }

    private void onRemovedFromGroup(int chatId) {
        chats.removeIf(chat -> chat.id == chatId);
        chatMessages.remove(chatId);
        showPlaceholder();
        showAlert(Alert.AlertType.INFORMATION, "Removed", "You have been removed from a group.");
    }

    private void onLeftGroupSuccess(int chatId) {
        chats.removeIf(chat -> chat.id == chatId);
        chatMessages.remove(chatId);
        showPlaceholder();
    }

    private void onMemberLeftOrRemoved(JsonObject data) {
        // This is a notification that *someone else* left or was removed
        // For now, we can just log it. The member list will be accurate
        // the next time the group info dialog is opened.
        int leftUserId = data.get("userId").getAsInt();
        System.out.println("User " + leftUserId + " has left/been removed from chat " + data.get("chatId").getAsInt());
    }

    private HBox createMessageInputArea(Chat chat) {
        HBox messageInputArea = new HBox(10);
        messageInputArea.setPadding(new Insets(10, 15, 10, 15));
        messageInputArea.setAlignment(Pos.CENTER);
        messageInputArea.setStyle("-fx-background-color: " + COLOR_BACKGROUND + ";");

        TextField messageField = new TextField();
        messageField.setPromptText("Type a message...");
        messageField.setStyle("-fx-background-radius: 20; -fx-border-color: #DDDDDD; -fx-border-radius: 20; -fx-font-size: 14px; -fx-padding: 8 15 8 15;");
        HBox.setHgrow(messageField, Priority.ALWAYS);

        Button sendButton = createStyledButton("Send");
        sendButton.setPrefHeight(38); // Match text field height

        messageField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && !messageField.getText().isEmpty()) {
                sendMessage(chat.id, messageField.getText());
                messageField.clear();
                event.consume();
            }
        });

        sendButton.setOnAction(e -> {
            if (!messageField.getText().isEmpty()) {
                sendMessage(chat.id, messageField.getText());
                messageField.clear();
            }
        });

        PauseTransition typingTimer = new PauseTransition(Duration.seconds(1.5));
        typingTimer.setOnFinished(event -> sendTypingStatus(chat.id, false));

        messageField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal.isEmpty()) {
                sendTypingStatus(chat.id, true);
                typingTimer.playFromStart();
            } else {
                typingTimer.stop();
                sendTypingStatus(chat.id, false);
            }
        });

        messageInputArea.getChildren().addAll(messageField, sendButton);
        return messageInputArea;
    }

    private void sendMessage(int chatId, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "SEND_MESSAGE");
        message.addProperty("chatId", chatId);
        message.addProperty("content", content);
        sendMessageToServer(message.toString());
    }

    private void deleteMessage(Message message) {
        JsonObject request = new JsonObject();
        request.addProperty("type", "DELETE_MESSAGE");
        request.addProperty("messageId", message.id);
        sendMessageToServer(request.toString());
    }

    private void deleteContact(Chat chat) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Conversation");
        confirm.setHeaderText("Delete conversation with " + chat.name + "?");
        confirm.setContentText("This action cannot be undone.");
        Optional<ButtonType> result = confirm.showAndWait();

        if (result.isPresent() && result.get() == ButtonType.OK) {
            JsonObject request = new JsonObject();
            request.addProperty("type", "DELETE_CONTACT");
            request.addProperty("chatId", chat.id);
            sendMessageToServer(request.toString());
        }
    }


    private void handleNewChat() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Conversation");
        dialog.setHeaderText("Add a new contact by their email address.");
        dialog.setContentText("Email:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(email -> {
            JsonObject request = new JsonObject();
            request.addProperty("type", "ADD_CONTACT");
            request.addProperty("email", email);
            sendMessageToServer(request.toString());
        });
    }

    private void sendTypingStatus(int chatId, boolean isTyping) {
        JsonObject status = new JsonObject();
        status.addProperty("type", isTyping ? "USER_TYPING" : "USER_STOPPED_TYPING");
        status.addProperty("chatId", chatId);
        sendMessageToServer(status.toString());
    }

    private void requestUserProfile(int userIdToRequest) {
        JsonObject request = new JsonObject();
        request.addProperty("type", "GET_USER_PROFILE");
        request.addProperty("userId", userIdToRequest);
        sendMessageToServer(request.toString());
    }

    private void showProfileViewer(User user) {
        Stage profileStage = new Stage();
        profileStage.initModality(Modality.APPLICATION_MODAL);
        profileStage.setTitle((user.name != null ? user.name : "User") + "'s Profile");

        VBox layout = new VBox(20);
        layout.setAlignment(Pos.TOP_CENTER);
        layout.setPadding(new Insets(30));
        layout.setStyle("-fx-background-color: " + COLOR_BACKGROUND + ";");

        layout.getChildren().add(createAvatar(user.name, user.profile_picture_url, 60));

        Label nameLabel = new Label(user.name != null ? user.name : "N/A");
        nameLabel.setFont(Font.font("Helvetica", FontWeight.BOLD, 24));
        Label emailLabel = new Label(user.email);
        emailLabel.setFont(Font.font("Helvetica", 14));
        emailLabel.setTextFill(Color.GRAY);

        VBox bioBox = new VBox(5);
        bioBox.setAlignment(Pos.CENTER_LEFT);
        Label bioHeader = new Label("About");
        bioHeader.setFont(Font.font("Helvetica", FontWeight.BOLD, 16));
        Text bioText = new Text(user.bio != null && !user.bio.isEmpty() ? user.bio : "No bio provided.");
        bioText.setWrappingWidth(320);
        bioBox.getChildren().addAll(bioHeader, bioText);


        layout.getChildren().addAll(nameLabel, emailLabel, new Separator(), bioBox);

        Scene scene = new Scene(layout, 400, 500);
        profileStage.setScene(scene);
        profileStage.show();
    }

    private void showProfileEditor() {
        Stage editorStage = new Stage();
        editorStage.initModality(Modality.APPLICATION_MODAL);
        editorStage.setTitle("Edit Your Profile");

        VBox layout = new VBox(30);
        layout.setAlignment(Pos.TOP_CENTER);
        layout.setPadding(new Insets(30));
        layout.setStyle("-fx-background-color: " + CONTACT_LIST_BACKGROUND + ";");

        StackPane avatarContainer = new StackPane();
        avatarContainer.getChildren().add(createAvatar(currentUser.name, currentUser.profile_picture_url, 60));
        layout.getChildren().add(avatarContainer);

        VBox fieldsBox = new VBox(15);

        VBox nameBox = new VBox(5);
        Label nameHeader = new Label("Name");
        nameHeader.setFont(Font.font("Helvetica", FontWeight.BOLD, 14));
        Label nameLabel = new Label(currentUser.name);
        nameLabel.setFont(Font.font("Helvetica", 16));
        nameBox.getChildren().addAll(nameHeader, nameLabel);

        VBox emailBox = new VBox(5);
        Label emailHeader = new Label("Email");
        emailHeader.setFont(Font.font("Helvetica", FontWeight.BOLD, 14));
        Label emailLabel = new Label(currentUser.email);
        emailLabel.setFont(Font.font("Helvetica", 16));
        emailBox.getChildren().addAll(emailHeader, emailLabel);

        VBox bioBox = new VBox(5);
        Label bioHeader = new Label("About Me");
        bioHeader.setFont(Font.font("Helvetica", FontWeight.BOLD, 14));
        TextArea bioArea = new TextArea(currentUser.bio != null ? currentUser.bio : "");
        bioArea.setWrapText(true);
        bioArea.setPrefRowCount(3);
        bioBox.getChildren().addAll(bioHeader, bioArea);

        VBox picUrlBox = new VBox(5);
        Label picUrlHeader = new Label("Profile Picture URL");
        picUrlHeader.setFont(Font.font("Helvetica", FontWeight.BOLD, 14));
        TextField picUrlField = new TextField(currentUser.profile_picture_url != null ? currentUser.profile_picture_url : "");
        picUrlBox.getChildren().addAll(picUrlHeader, picUrlField);

        fieldsBox.getChildren().addAll(nameBox, emailBox, bioBox, picUrlBox);

        PauseTransition debounceTimer = new PauseTransition(Duration.millis(500));
        picUrlField.textProperty().addListener((obs, oldVal, newVal) -> {
            debounceTimer.setOnFinished(e -> {
                avatarContainer.getChildren().setAll(createAvatar(currentUser.name, newVal, 60));
            });
            debounceTimer.playFromStart();
        });


        Button saveButton = createStyledButton("Save Changes");
        saveButton.setOnAction(e -> {
            JsonObject request = new JsonObject();
            request.addProperty("type", "UPDATE_USER_PROFILE");
            request.addProperty("bio", bioArea.getText());
            request.addProperty("profile_picture_url", picUrlField.getText());
            sendMessageToServer(request.toString());
            editorStage.close();
        });

        HBox buttonBox = new HBox(saveButton);
        buttonBox.setAlignment(Pos.CENTER);

        layout.getChildren().addAll(fieldsBox, buttonBox);

        Scene scene = new Scene(layout, 500, 600);
        editorStage.setScene(scene);
        editorStage.show();
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Platform.runLater(() -> { // Ensure alerts run on FX thread
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private TextField createStyledTextField(String prompt) {
        TextField textField = new TextField();
        textField.setPromptText(prompt);
        textField.setStyle("-fx-font-size: 14px; -fx-background-radius: 8; -fx-padding: 10; -fx-border-color: #CCCCCC; -fx-border-radius: 8;");
        return textField;
    }

    private PasswordField createStyledPasswordField() {
        PasswordField pf = new PasswordField();
        pf.setPromptText("Password");
        pf.setStyle("-fx-font-size: 14px; -fx-background-radius: 8; -fx-padding: 10; -fx-border-color: #CCCCCC; -fx-border-radius: 8;");
        return pf;
    }

    private Button createStyledButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: " + COLOR_PRIMARY + "; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-radius: 8;");
        button.setPrefHeight(40);
        return button;
    }

    private SVGPath createSVGIcon(String path) {
        SVGPath svg = new SVGPath();
        svg.setContent(path);
        svg.setStroke(Color.web("#555555"));
        svg.setStrokeWidth(2.0);
        return svg;
    }

    private StackPane createAvatar(String name, String imageUrl, double radius) {
        StackPane avatarPane = new StackPane();

        String initial = (name != null && !name.isEmpty()) ? name.substring(0, 1).toUpperCase() : "?";
        Color initialColor = Color.hsb(Math.abs((name != null ? name : "").hashCode()) % 360, 0.5, 0.8);
        LinearGradient gradient = new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, initialColor), new Stop(1, initialColor.brighter()));
        Circle circle = new Circle(radius, gradient);

        Text initialText = new Text(initial);
        initialText.setFont(Font.font("Helvetica", FontWeight.BOLD, radius * 1.2));
        initialText.setFill(Color.WHITE);

        avatarPane.getChildren().addAll(circle, initialText);

        if (imageUrl != null && !imageUrl.trim().isEmpty()) {
            try {
                Image image = new Image(imageUrl, true); // Background loading
                if (!image.isError()) { // Check if image loaded successfully
                    ImageView imageView = new ImageView(image);
                    imageView.setFitWidth(radius * 2);
                    imageView.setFitHeight(radius * 2);
                    Circle clip = new Circle(radius, radius, radius);
                    imageView.setClip(clip);
                    avatarPane.getChildren().add(imageView);
                } else {
                    System.err.println("Failed to load image URL: " + imageUrl);
                }
            } catch (Exception e) {
                System.err.println("Error loading image URL: " + imageUrl + " - " + e.getMessage());
            }
        }
        return avatarPane;
    }

    private void sendMessageToServer(String message) {
        if (out != null) {
            out.println(message);
        } else {
            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Connection Error", "Not connected to the server."));
        }
    }

    // --- Helper classes ---
    private static class User { int id; String name; String email; String bio; String profile_picture_url; }
    private static class Chat {
        int id;
        int contactId = -1;
        String name;
        boolean isGroupChat = false;
        int ownerId = -1;
        private final BooleanProperty online = new SimpleBooleanProperty();

        public final boolean isOnline() { return online.get(); }
        public final void setOnline(boolean value) { online.set(value); }
        public final BooleanProperty onlineProperty() { return online; }

        @Override public String toString() { return name; }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Chat chat = (Chat) obj;
            return id == chat.id;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(id);
        }
    }
    private static class Message { int id; int chatId; int senderId; String senderName; String content; String sent_at; }

    private class ChatListCell extends ListCell<Chat> {
        private final HBox graphic = new HBox(10);
        private final StackPane avatarPane = new StackPane();
        private final Label nameLabel = new Label();
        private final ContextMenu contextMenu = new ContextMenu();

        public ChatListCell() {
            graphic.setAlignment(Pos.CENTER_LEFT);
            graphic.getChildren().addAll(avatarPane, nameLabel);

            MenuItem deleteItem = new MenuItem("Delete Conversation");
            deleteItem.setOnAction(event -> { if (getItem() != null) deleteContact(getItem()); });
            contextMenu.getItems().add(deleteItem);
        }

        @Override
        protected void updateItem(Chat chat, boolean empty) {
            super.updateItem(chat, empty);
            if (empty || chat == null) {
                setGraphic(null);
                setContextMenu(null);
            } else {
                nameLabel.setText(chat.name);

                avatarPane.getChildren().clear();
                if (chat.isGroupChat) {
                    avatarPane.getChildren().add(createAvatar(chat.name, null, 15)); // Default group avatar
                } else {
                    Circle statusCircle = new Circle(5);
                    statusCircle.fillProperty().bind(
                            new javafx.beans.binding.ObjectBinding<>() {
                                { bind(chat.onlineProperty()); }
                                @Override protected Color computeValue() { return chat.isOnline() ? Color.LIMEGREEN : Color.LIGHTGRAY; }
                            }
                    );
                    avatarPane.getChildren().add(statusCircle);
                }

                setGraphic(graphic);
                if (!chat.isGroupChat) {
                    setContextMenu(contextMenu);
                } else {
                    setContextMenu(null); // No delete context menu for groups
                }
            }
        }
    }

    private class MessageCell extends ListCell<Message> {
        @Override
        protected void updateItem(Message message, boolean empty) {
            super.updateItem(message, empty);
            setStyle("-fx-background-color: transparent; -fx-padding: 0;");

            if (empty || message == null) {
                setGraphic(null);
                setContextMenu(null);
            } else {
                boolean sentByMe = message.senderId == userId;

                Text text = new Text(message.content);
                text.setFont(Font.font("Helvetica", 15));
                text.setFill(Color.web(COLOR_DARK_TEXT));

                TextFlow textFlow = new TextFlow(text);
                textFlow.setPadding(new Insets(8, 12, 8, 12));
                textFlow.setMaxWidth(400);

                String backgroundColor = sentByMe ? SENT_BUBBLE_COLOR : RECEIVED_BUBBLE_COLOR;
                textFlow.setStyle("-fx-background-color: " + backgroundColor + "; -fx-background-radius: 15;");

                Label timestampLabel = new Label(message.sent_at != null ? message.sent_at : "");
                timestampLabel.setFont(Font.font("Helvetica", 10));
                timestampLabel.setTextFill(Color.GRAY);

                VBox bubbleWithTimestamp = new VBox(5);

                Chat currentChat = chatListView.getSelectionModel().getSelectedItem();
                if (currentChat != null && currentChat.isGroupChat && !sentByMe) {
                    Label senderNameLabel = new Label(message.senderName);
                    senderNameLabel.setFont(Font.font("Helvetica", FontWeight.BOLD, 12));
                    senderNameLabel.setTextFill(Color.hsb(Math.abs((message.senderName != null ? message.senderName : "").hashCode()) % 360, 0.7, 0.6)); // Handle null name
                    bubbleWithTimestamp.getChildren().add(senderNameLabel);
                }

                bubbleWithTimestamp.getChildren().addAll(textFlow, timestampLabel);
                bubbleWithTimestamp.setAlignment(sentByMe ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

                HBox hbox = new HBox(bubbleWithTimestamp);
                hbox.setAlignment(sentByMe ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
                hbox.setPadding(new Insets(4, 8, 4, 8));

                setGraphic(hbox);

                if (sentByMe) {
                    ContextMenu contextMenu = new ContextMenu();
                    MenuItem deleteItem = new MenuItem("Delete Message");
                    deleteItem.setOnAction(event -> deleteMessage(message));
                    contextMenu.getItems().add(deleteItem);
                    setContextMenu(contextMenu);
                } else {
                    setContextMenu(null);
                }
            }
        }
    }

    // --- New CellFactory for Group Member List ---
    private class UserListCell extends ListCell<User> {
        private final HBox graphic = new HBox(10);
        private final StackPane avatar = new StackPane();
        private final Label nameLabel = new Label();
        private final Button removeButton = new Button();
        private final Chat groupChat; // Store context
        private final Stage parentDialog;

        public UserListCell(Chat groupChat, Stage parentDialog) {
            this.groupChat = groupChat;
            this.parentDialog = parentDialog;

            graphic.setAlignment(Pos.CENTER_LEFT);
            Pane spacer = new Pane(); HBox.setHgrow(spacer, Priority.ALWAYS);
            removeButton.setGraphic(createSVGIcon("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")); // 'X' icon
            removeButton.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
            removeButton.setVisible(false); // Hide by default
            removeButton.setOnAction(e -> {
                if (getItem() != null) {
                    removeGroupMember(groupChat, getItem());
                    parentDialog.close(); // Close after action
                }
            });

            graphic.getChildren().addAll(avatar, nameLabel, spacer, removeButton);
        }

        @Override
        protected void updateItem(User user, boolean empty) {
            super.updateItem(user, empty);
            if (empty || user == null) {
                setGraphic(null);
            } else {
                nameLabel.setText(user.name + (user.id == groupChat.ownerId ? " (Admin)" : ""));
                avatar.getChildren().setAll(createAvatar(user.name, user.profile_picture_url, 15));

                // Show remove button only if current user is owner AND this cell is not the owner
                boolean showRemove = (currentUser.id == groupChat.ownerId) && (user.id != currentUser.id);
                removeButton.setVisible(showRemove);

                setGraphic(graphic);
            }
        }
    }
}


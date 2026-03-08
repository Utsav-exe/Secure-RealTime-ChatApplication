import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken; // Needed for parsing List<Integer>

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Type; // Needed for TypeToken
import java.net.Socket;
import java.security.KeyStore;
import java.util.ArrayList; // Needed for TypeToken
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatServer {
    private static final int PORT = 9999;
    private static final ConcurrentHashMap<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private static final ExecutorService threadPool = Executors.newCachedThreadPool();
    private static DatabaseManager dbManager;

    public static void main(String[] args) {
        System.out.println("Chat Server is starting...");
        dbManager = new DatabaseManager();
        if (dbManager == null || !dbManager.isConnected()) { // Check DB connection after init
            System.err.println("FATAL: Database connection failed on startup. Server cannot continue.");
            return; // Stop if DB connection failed
        }

        try {
            SSLContext sslContext = createSslContext();
            SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
            try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(PORT)) {
                System.out.println("Server is listening on port " + PORT);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("New client connected: " + clientSocket.getInetAddress()); // Log connection attempt
                    ClientHandler clientHandler = new ClientHandler(clientSocket);
                    threadPool.submit(clientHandler);
                }
            }
        } catch (Exception e) {
            System.err.println("Server encountered a critical error:");
            e.printStackTrace();
        }
    }

    private static SSLContext createSslContext() throws Exception {
        char[] keystorePassword = "password".toCharArray();
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream("server_keystore.jks")) {
            ks.load(fis, keystorePassword);
        } catch (IOException e) {
            System.err.println("FATAL: Could not load server_keystore.jks. Make sure the file exists and the password is correct.");
            throw e;
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keystorePassword);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    // --- Static helper method for broadcasting ---
    private static void broadcastToParticipants(int chatId, int senderId, String message, boolean includeSender) {
        List<Integer> participants = dbManager.getChatParticipants(chatId);
        if (participants == null) {
            System.err.println("Error broadcasting: Could not get participants for chat " + chatId);
            return;
        }
        for (Integer participantId : participants) {
            if (clients.containsKey(participantId)) {
                if (includeSender || !participantId.equals(senderId)) { // Use .equals for Integer comparison
                    clients.get(participantId).sendMessage(message);
                }
            }
        }
    }


    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;
        private int userId = -1;
        private final Gson gson = new Gson();

        public ClientHandler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try {
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                System.out.println("Client handler started for: " + socket.getInetAddress());
                String message;
                while ((message = in.readLine()) != null) {
                    System.out.println("Received raw from client " + (userId != -1 ? userId : "(unauthenticated)") + ": " + message);
                    handleClientMessage(message);
                }
            } catch (IOException e) {
                System.out.println("IOException for user " + userId + " (likely disconnected): " + e.getMessage());
            } catch (Exception e) {
                System.err.println("Unexpected error in ClientHandler for user " + userId + ":");
                e.printStackTrace();
            }
            finally {
                if (userId != -1) {
                    clients.remove(userId);
                    broadcastStatusUpdate(userId, false);
                    System.out.println("Client " + userId + " disconnected and removed.");
                } else {
                    System.out.println("Unauthenticated client disconnected.");
                }
                try { socket.close(); } catch (IOException e) { e.printStackTrace(); }
            }
        }

        // --- Broadcast Methods ---
        private void broadcastStatusUpdate(int userId, boolean isOnline) {
            List<Integer> contactIds = dbManager.getContactIds(userId);
            JsonObject statusUpdate = new JsonObject();
            statusUpdate.addProperty("type", isOnline ? "USER_ONLINE" : "USER_OFFLINE");
            statusUpdate.addProperty("userId", userId);

            for (int contactId : contactIds) {
                if (clients.containsKey(contactId)) {
                    clients.get(contactId).sendMessage(statusUpdate.toString());
                }
            }
        }
        private void broadcastProfileUpdate(int updatedUserId) {
            DatabaseManager.User updatedUser = dbManager.getUserDetails(updatedUserId);
            if (updatedUser == null) return;

            List<Integer> contactIds = dbManager.getContactIds(updatedUserId);
            JsonObject profileUpdate = new JsonObject();
            profileUpdate.addProperty("type", "PROFILE_UPDATED");
            profileUpdate.add("user", gson.toJsonTree(updatedUser));

            for (int contactId : contactIds) {
                if (clients.containsKey(contactId)) {
                    clients.get(contactId).sendMessage(profileUpdate.toString());
                }
            }
        }

        // --- Message Handlers ---
        private void handleClientMessage(String jsonMessage) {
            try {
                JsonObject jsonObject = gson.fromJson(jsonMessage, JsonObject.class);
                String type = jsonObject.get("type").getAsString();
                System.out.println("Handling message type: " + type + " for user " + userId);

                switch (type) {
                    case "LOGIN": handleLogin(jsonObject); break;
                    case "SIGNUP": handleSignup(jsonObject); break;
                    case "SEND_MESSAGE": handleSendMessage(jsonObject); break;
                    case "ADD_CONTACT": handleAddContact(jsonObject); break;
                    case "DELETE_CONTACT": handleDeleteContact(jsonObject); break;
                    case "DELETE_MESSAGE": handleDeleteMessage(jsonObject); break;
                    case "USER_TYPING": handleUserTyping(jsonObject, true); break;
                    case "USER_STOPPED_TYPING": handleUserTyping(jsonObject, false); break;
                    case "GET_CHAT_HISTORY": handleGetChatHistory(jsonObject); break; // Definition added below
                    case "GET_USER_PROFILE": handleGetUserProfile(jsonObject); break; // Definition added below
                    case "UPDATE_USER_PROFILE": handleUpdateUserProfile(jsonObject); break; // Definition added below
                    case "CREATE_GROUP": handleCreateGroup(jsonObject); break;
                    case "GET_GROUP_MEMBERS": handleGetGroupMembers(jsonObject); break;
                    case "REMOVE_GROUP_MEMBER": handleRemoveGroupMember(jsonObject); break;
                    case "LEAVE_GROUP": handleLeaveGroup(jsonObject); break;
                    default:
                        System.err.println("Unknown message type received from user " + userId + ": " + type);
                }
            } catch (JsonSyntaxException e) {
                System.err.println("Invalid JSON received from client " + userId + ": " + jsonMessage);
                e.printStackTrace();
            } catch (Exception e) {
                System.err.println("Error handling message type for user " + userId + ": " + jsonMessage);
                e.printStackTrace();
            }
        }

        // --- Specific Handlers ---

        // ** ADDED handleGetChatHistory **
        private void handleGetChatHistory(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                int chatId = data.get("chatId").getAsInt();
                List<DatabaseManager.Message> history = dbManager.getChatHistory(chatId);
                response.addProperty("type", "CHAT_HISTORY");
                response.addProperty("chatId", chatId);
                response.add("history", gson.toJsonTree(history));
            } catch (Exception e) {
                System.err.println("Error handling GET_CHAT_HISTORY: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Clear potentially partial response
                response.addProperty("type", "CHAT_HISTORY_FAIL"); // Example error type
                response.addProperty("chatId", data.has("chatId") ? data.get("chatId").getAsInt() : -1);
            } finally {
                sendMessage(response.toString()); // Send history or error response
            }
        }

        // ** ADDED handleGetUserProfile **
        private void handleGetUserProfile(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                int profileUserId = data.get("userId").getAsInt();
                DatabaseManager.User user = dbManager.getUserDetails(profileUserId);
                response.addProperty("type", "USER_PROFILE_DATA");
                response.add("user", gson.toJsonTree(user)); // Send user data (or null if not found)
            } catch (Exception e) {
                System.err.println("Error handling GET_USER_PROFILE: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset response
                response.addProperty("type", "USER_PROFILE_DATA"); // Still send type
                response.add("user", null); // Indicate error / not found by sending null user
            } finally {
                sendMessage(response.toString());
            }
        }

        // ** ADDED handleUpdateUserProfile **
        private void handleUpdateUserProfile(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                // Only update bio and picUrl, name is not updated here
                String bio = data.has("bio") ? data.get("bio").getAsString() : null;
                String picUrl = data.has("profile_picture_url") ? data.get("profile_picture_url").getAsString() : null;
                boolean success = dbManager.updateUserProfile(this.userId, bio, picUrl);

                response.addProperty("type", "UPDATE_PROFILE_RESULT");
                response.addProperty("success", success);
                if(success) {
                    DatabaseManager.User updatedUser = dbManager.getUserDetails(this.userId);
                    if (updatedUser == null) throw new Exception("Failed to retrieve updated user details.");
                    response.add("user", gson.toJsonTree(updatedUser));
                    broadcastProfileUpdate(this.userId); // Notify contacts of the update
                }
            } catch (Exception e) {
                System.err.println("Error handling UPDATE_USER_PROFILE: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset response on error
                response.addProperty("type", "UPDATE_PROFILE_RESULT");
                response.addProperty("success", false);
            } finally {
                sendMessage(response.toString());
            }
        }


        private void handleCreateGroup(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                String groupName = data.get("groupName").getAsString();
                Type listType = new TypeToken<ArrayList<Integer>>() {}.getType();
                List<Integer> memberIds = gson.fromJson(data.get("memberIds"), listType);

                int chatId = dbManager.createGroupChat(groupName, this.userId, memberIds);

                if(chatId != -1) {
                    DatabaseManager.Chat createdGroup = new DatabaseManager.Chat(chatId, groupName, true, this.userId);

                    // 1. Notify the CREATOR (only) of success
                    response.addProperty("type", "GROUP_CREATED_SUCCESS");
                    response.add("chat", gson.toJsonTree(createdGroup));
                    sendMessage(response.toString()); // Send ONLY to creator

                    // 2. Notify all OTHER members
                    JsonObject notification = new JsonObject();
                    notification.addProperty("type", "NEW_GROUP_CHAT");
                    notification.add("chat", gson.toJsonTree(createdGroup));

                    for (int memberId : memberIds) {
                        if (clients.containsKey(memberId)) {
                            clients.get(memberId).sendMessage(notification.toString());
                        }
                    }

                } else {
                    response.addProperty("type", "CREATE_GROUP_FAIL");
                    sendMessage(response.toString()); // Send failure to creator
                }
            } catch (Exception e) {
                System.err.println("Error handling CREATE_GROUP: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset response on error
                response.addProperty("type", "CREATE_GROUP_FAIL");
                sendMessage(response.toString()); // Send failure to creator
            }
        }


        private void handleLogin(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                String email = data.get("email").getAsString();
                String password = data.get("password").getAsString();
                System.out.println("Attempting login for email: " + email);
                this.userId = dbManager.authenticateUser(email, password);

                if (this.userId != -1) {
                    System.out.println("Login successful for user ID: " + this.userId);
                    clients.put(this.userId, this);
                    response.addProperty("type", "LOGIN_SUCCESS");
                    DatabaseManager.User userDetails = dbManager.getUserDetails(this.userId);
                    if (userDetails == null) throw new Exception("Failed to get user details after login.");
                    response.add("user", gson.toJsonTree(userDetails));

                    List<DatabaseManager.Chat> rawChats = dbManager.getUserChats(this.userId);
                    JsonArray enrichedChats = new JsonArray();
                    for (DatabaseManager.Chat chat : rawChats) {
                        JsonObject chatJson = gson.toJsonTree(chat).getAsJsonObject();
                        if (!chat.isGroupChat) {
                            chatJson.addProperty("contactId", dbManager.getOtherParticipant(chat.id, this.userId));
                        }
                        enrichedChats.add(chatJson);
                    }
                    response.add("chats", enrichedChats);

                    List<Integer> contactIds = dbManager.getContactIds(this.userId);
                    JsonArray onlineContacts = new JsonArray();
                    for (int contactId : contactIds) {
                        if (clients.containsKey(contactId)) onlineContacts.add(contactId);
                    }
                    response.add("onlineContacts", onlineContacts);

                    broadcastStatusUpdate(this.userId, true);
                } else {
                    System.out.println("Login failed for email: " + email);
                    response.addProperty("type", "LOGIN_FAIL");
                }
            } catch (Exception e) {
                System.err.println("Error during login process for data: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset response on error
                response.addProperty("type", "LOGIN_FAIL");
                response.addProperty("reason", "Server error during login.");
            } finally {
                sendMessage(response.toString());
            }
        }

        private void handleSignup(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                String name = data.get("name").getAsString();
                String email = data.get("email").getAsString();
                String password = data.get("password").getAsString();
                System.out.println("Attempting signup for email: " + email);

                int result = dbManager.createUser(name, email, password);

                if (result > 0) {
                    System.out.println("Signup successful for email: " + email);
                    response.addProperty("type", "SIGNUP_SUCCESS");
                } else {
                    String reason = result == -1 ? "Email already exists" : "Database error";
                    System.out.println("Signup failed for email: " + email + ", Reason: " + reason);
                    response.addProperty("type", "SIGNUP_FAIL");
                    response.addProperty("reason", reason);
                }
            } catch (Exception e) {
                System.err.println("Error during signup process for data: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset response on error
                response.addProperty("type", "SIGNUP_FAIL");
                response.addProperty("reason", "Server error during signup.");
            } finally {
                sendMessage(response.toString());
            }
        }

        private void handleGetGroupMembers(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                int chatId = data.get("chatId").getAsInt();
                List<DatabaseManager.User> members = dbManager.getGroupMembers(chatId);
                response.addProperty("type", "GROUP_MEMBERS_LIST");
                response.addProperty("chatId", chatId);
                response.add("members", gson.toJsonTree(members));
            } catch (Exception e) {
                System.err.println("Error handling GET_GROUP_MEMBERS: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset response
                response.addProperty("type", "GROUP_MEMBERS_FAIL"); // Example error type
                response.addProperty("chatId", data.has("chatId") ? data.get("chatId").getAsInt() : -1);
            } finally {
                sendMessage(response.toString());
            }
        }

        private void handleRemoveGroupMember(JsonObject data) {
            JsonObject response = new JsonObject(); // For potential errors
            try {
                int chatId = data.get("chatId").getAsInt();
                int memberToRemoveId = data.get("userIdToRemove").getAsInt();
                int ownerId = dbManager.getChatOwner(chatId);

                if (this.userId != ownerId) {
                    response.addProperty("type", "REMOVE_MEMBER_FAIL");
                    response.addProperty("reason", "Only the group owner can remove members.");
                    sendMessage(response.toString());
                    return;
                }
                if (memberToRemoveId == this.userId) {
                    response.addProperty("type", "REMOVE_MEMBER_FAIL");
                    response.addProperty("reason", "Use 'Leave Group' to remove yourself.");
                    sendMessage(response.toString());
                    return;
                }

                boolean success = dbManager.removeUserFromChat(chatId, memberToRemoveId);

                if (success) {
                    if (clients.containsKey(memberToRemoveId)) {
                        JsonObject removedNotification = new JsonObject();
                        removedNotification.addProperty("type", "REMOVED_FROM_GROUP");
                        removedNotification.addProperty("chatId", chatId);
                        clients.get(memberToRemoveId).sendMessage(removedNotification.toString());
                    }

                    JsonObject memberLeftNotification = new JsonObject();
                    memberLeftNotification.addProperty("type", "MEMBER_LEFT_GROUP");
                    memberLeftNotification.addProperty("chatId", chatId);
                    memberLeftNotification.addProperty("userId", memberToRemoveId);
                    broadcastToParticipants(chatId, -1, memberLeftNotification.toString(), true); // Send to all remaining

                } else {
                    response.addProperty("type", "REMOVE_MEMBER_FAIL");
                    response.addProperty("reason", "Database error occurred.");
                    sendMessage(response.toString());
                }
            } catch (Exception e) {
                System.err.println("Error handling REMOVE_GROUP_MEMBER: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset on error
                response.addProperty("type", "REMOVE_MEMBER_FAIL");
                response.addProperty("reason", "Server error during removal.");
                sendMessage(response.toString());
            }
        }

        private void handleLeaveGroup(JsonObject data) {
            JsonObject response = new JsonObject(); // For confirmation/error
            try {
                int chatId = data.get("chatId").getAsInt();
                boolean success = dbManager.removeUserFromChat(chatId, this.userId);

                if (success) {
                    response.addProperty("type", "LEFT_GROUP_SUCCESS");
                    response.addProperty("chatId", chatId);
                    sendMessage(response.toString()); // Confirm to leaver first

                    JsonObject memberLeftNotification = new JsonObject();
                    memberLeftNotification.addProperty("type", "MEMBER_LEFT_GROUP");
                    memberLeftNotification.addProperty("chatId", chatId);
                    memberLeftNotification.addProperty("userId", this.userId);
                    broadcastToParticipants(chatId, this.userId, memberLeftNotification.toString(), false); // Don't send back to leaver

                } else {
                    response.addProperty("type", "LEAVE_GROUP_FAIL");
                    sendMessage(response.toString());
                }
            } catch (Exception e) {
                System.err.println("Error handling LEAVE_GROUP: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset on error
                response.addProperty("type", "LEAVE_GROUP_FAIL");
                sendMessage(response.toString());
            }
        }

        private void handleSendMessage(JsonObject data) {
            try {
                DatabaseManager.Message savedMessage = dbManager.saveMessage(data.get("chatId").getAsInt(), this.userId, data.get("content").getAsString());
                if (savedMessage != null) {
                    JsonObject messagePayload = new JsonObject();
                    messagePayload.addProperty("type", "RECEIVE_MESSAGE");
                    messagePayload.add("message", gson.toJsonTree(savedMessage));
                    broadcastToParticipants(savedMessage.chatId, -1, messagePayload.toString(), true); // Send to everyone including sender
                } else {
                    System.err.println("Failed to save message for chat " + data.get("chatId").getAsInt());
                }
            } catch (Exception e) {
                System.err.println("Error handling SEND_MESSAGE: " + data);
                e.printStackTrace();
            }
        }
        private void handleAddContact(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                DatabaseManager.User contact = dbManager.findUserByEmail(data.get("email").getAsString());
                if (contact == null) {
                    response.addProperty("type", "ADD_CONTACT_FAIL");
                    response.addProperty("reason", "User not found");
                    sendMessage(response.toString());
                    return;
                }
                if (contact.id == this.userId) {
                    response.addProperty("type", "ADD_CONTACT_FAIL");
                    response.addProperty("reason", "Cannot add yourself");
                    sendMessage(response.toString());
                    return;
                }

                int chatId = dbManager.createPrivateChat(this.userId, contact.id);

                if (chatId != -1) {
                    DatabaseManager.User me = dbManager.getUserDetails(this.userId);
                    if (me == null) throw new Exception("Could not retrieve sender details.");

                    JsonObject responseForMe = new JsonObject();
                    responseForMe.addProperty("type", "ADD_CONTACT_SUCCESS");
                    JsonObject chatForMe = new JsonObject();
                    chatForMe.addProperty("id", chatId);
                    chatForMe.addProperty("name", contact.name);
                    chatForMe.addProperty("contactId", contact.id);
                    chatForMe.addProperty("isGroupChat", false);
                    responseForMe.add("chat", chatForMe);
                    sendMessage(responseForMe.toString());

                    if (clients.containsKey(contact.id)) {
                        JsonObject responseForThem = new JsonObject();
                        responseForThem.addProperty("type", "NEW_CHAT_STARTED");
                        JsonObject chatForThem = new JsonObject();
                        chatForThem.addProperty("id", chatId);
                        chatForThem.addProperty("name", me.name);
                        chatForThem.addProperty("contactId", me.id);
                        chatForThem.addProperty("isGroupChat", false);
                        responseForThem.add("chat", chatForThem);
                        clients.get(contact.id).sendMessage(responseForThem.toString());
                    }
                } else {
                    response.addProperty("type", "ADD_CONTACT_FAIL");
                    response.addProperty("reason", "Database error or chat already exists.");
                    sendMessage(response.toString());
                }
            } catch (Exception e) {
                System.err.println("Error handling ADD_CONTACT: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset on error
                response.addProperty("type", "ADD_CONTACT_FAIL");
                response.addProperty("reason", "Server error during add contact.");
                sendMessage(response.toString());
            }
        }
        private void handleDeleteContact(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                int chatId = data.get("chatId").getAsInt();
                int otherUserId = dbManager.deleteContactAndChat(this.userId, chatId);

                if(otherUserId != -1) {
                    response.addProperty("type", "DELETE_CONTACT_SUCCESS");
                    response.addProperty("chatId", chatId);
                    sendMessage(response.toString()); // Confirm deletion to self

                    if (clients.containsKey(otherUserId)) {
                        JsonObject responseForThem = new JsonObject();
                        responseForThem.addProperty("type", "CHAT_REMOVED");
                        responseForThem.addProperty("chatId", chatId);
                        clients.get(otherUserId).sendMessage(responseForThem.toString());
                    }
                } else {
                    response.addProperty("type", "DELETE_CONTACT_FAIL");
                    response.addProperty("reason", "Failed to delete contact/chat in DB or it was a group chat.");
                    sendMessage(response.toString());
                }
            } catch (Exception e) {
                System.err.println("Error handling DELETE_CONTACT: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset on error
                response.addProperty("type", "DELETE_CONTACT_FAIL");
                response.addProperty("reason", "Server error during delete contact.");
                sendMessage(response.toString());
            }
        }
        private void handleDeleteMessage(JsonObject data) {
            JsonObject response = new JsonObject();
            try {
                int messageId = data.get("messageId").getAsInt();
                int chatId = dbManager.deleteMessage(messageId, this.userId);

                if (chatId != -1) {
                    JsonObject notification = new JsonObject();
                    notification.addProperty("type", "MESSAGE_DELETED");
                    notification.addProperty("messageId", messageId);
                    notification.addProperty("chatId", chatId);
                    broadcastToParticipants(chatId, -1, notification.toString(), true); // Notify everyone
                } else {
                    response.addProperty("type", "DELETE_MESSAGE_FAIL");
                    response.addProperty("messageId", messageId);
                    response.addProperty("reason", "Message not found or permission denied.");
                    sendMessage(response.toString());
                }
            } catch (Exception e) {
                System.err.println("Error handling DELETE_MESSAGE: " + data);
                e.printStackTrace();
                response = new JsonObject(); // Reset on error
                response.addProperty("type", "DELETE_MESSAGE_FAIL");
                response.addProperty("messageId", data.has("messageId") ? data.get("messageId").getAsInt() : -1);
                response.addProperty("reason", "Server error during message deletion.");
                sendMessage(response.toString());
            }
        }
        private void handleUserTyping(JsonObject data, boolean isTyping) {
            try {
                int chatId = data.get("chatId").getAsInt();
                JsonObject notification = new JsonObject();
                notification.addProperty("type", isTyping ? "TYPING_STARTED" : "TYPING_STOPPED");
                notification.addProperty("chatId", chatId);
                notification.addProperty("userId", this.userId);
                broadcastToParticipants(chatId, this.userId, notification.toString(), false);
            } catch (Exception e) {
                System.err.println("Error handling USER_TYPING/STOPPED: " + data);
                e.printStackTrace();
            }
        }

        public void sendMessage(String message) {
            try {
                if (out != null && !socket.isOutputShutdown()) {
                    out.println(message);
                } else {
                    System.err.println("Cannot send message to client " + userId + ": PrintWriter is null or output stream closed.");
                }
            } catch (Exception e) {
                System.err.println("Error sending message to client " + userId + ": " + message);
                e.printStackTrace();
            }
        }
    }
}


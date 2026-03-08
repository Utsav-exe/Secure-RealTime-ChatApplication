import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import org.mindrot.jbcrypt.BCrypt;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:postgresql://localhost:5432/chatdb";
    private static final String DB_USER = "postgres";
    // IMPORTANT: Replace "password" with your actual PostgreSQL password
    private static final String DB_PASS = "123456";
    private Connection conn;

    // --- Inner Data Classes ---
    public static class User {
        int id;
        String name;
        String email;
        String bio;
        String profile_picture_url;
    }
    public static class Chat {
        int id;
        String name;
        boolean isGroupChat;
        int contactId = -1; // Only relevant for private chats
        int ownerId = -1; // Relevant for groups

        // Default constructor
        public Chat() {}

        // Constructor for private chats
        public Chat(int id, String name, int contactId) {
            this.id = id;
            this.name = name;
            this.isGroupChat = false;
            this.contactId = contactId;
        }
        // Constructor for group chats
        public Chat(int id, String name, boolean isGroup, int ownerId) {
            this.id = id;
            this.name = name;
            this.isGroupChat = isGroup;
            this.ownerId = ownerId;
        }
    }
    public static class Message {
        int id;
        int chatId;
        int senderId;
        String senderName;
        String content;
        String sent_at;
    }

    // --- Constructor ---
    public DatabaseManager() {
        try {
            Class.forName("org.postgresql.Driver");
            conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
            System.out.println("Database connection established successfully.");
        } catch (SQLException | ClassNotFoundException e) {
            System.err.println("FATAL: Database connection failed during initialization. Check DB_URL, DB_USER, DB_PASS, and ensure PostgreSQL driver is in classpath.");
            e.printStackTrace();
            conn = null; // Ensure conn is null if connection fails
        }
    }

    // Method to check connection status
    public boolean isConnected() {
        try {
            return conn != null && !conn.isClosed();
        } catch (SQLException e) {
            System.err.println("Error checking DB connection status: " + e.getMessage());
            return false;
        }
    }


    // --- Authentication and User Management ---
    public int authenticateUser(String email, String password) {
        if (!isConnected()) {
            System.err.println("authenticateUser failed: No database connection.");
            return -1;
        }
        String sql = "SELECT user_id, password_hash FROM users WHERE email = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                String storedHash = rs.getString("password_hash");
                if (storedHash != null && BCrypt.checkpw(password, storedHash)) {
                    System.out.println("Password check successful for: " + email);
                    return rs.getInt("user_id"); // Success
                } else {
                    System.out.println("Password check failed for: " + email);
                }
            } else {
                System.out.println("User not found for email: " + email);
            }
        } catch (SQLException e) {
            System.err.println("SQLException during authenticateUser for email " + email);
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error during authenticateUser for email " + email);
            e.printStackTrace();
        }
        return -1; // Failure
    }

    public int createUser(String name, String email, String password) {
        if (!isConnected()) {
            System.err.println("createUser failed: No database connection.");
            return -2;
        }
        // Basic input validation (can be enhanced)
        if (name == null || name.trim().isEmpty() || email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
            System.err.println("createUser failed: Invalid input (name, email, or password is empty).");
            return -2;
        }

        String checkEmailSql = "SELECT user_id FROM users WHERE email = ?";
        try (PreparedStatement checkStmt = conn.prepareStatement(checkEmailSql)) {
            checkStmt.setString(1, email);
            ResultSet rs = checkStmt.executeQuery();
            if (rs.next()) {
                System.out.println("createUser failed: Email already exists: " + email);
                return -1; // Email exists
            }
        } catch (SQLException e) {
            System.err.println("SQLException during email check for createUser: " + email);
            e.printStackTrace();
            return -2; // DB error
        }

        String sql = "INSERT INTO users (name, email, password_hash) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
            pstmt.setString(1, name);
            pstmt.setString(2, email);
            pstmt.setString(3, hashedPassword);
            int result = pstmt.executeUpdate();
            if (result > 0) {
                System.out.println("User created successfully for email: " + email);
                return 1; // Success
            } else {
                System.err.println("createUser failed: Insert statement affected 0 rows for email: " + email);
                return -2; // Insert failed
            }
        } catch (SQLException e) {
            System.err.println("SQLException during user insert for email: " + email);
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error during password hashing or insert for email: " + email);
            e.printStackTrace();
        }
        return -2; // DB error
    }

    public User getUserDetails(int userId) {
        if (!isConnected()) {
            System.err.println("getUserDetails failed: No database connection.");
            return null;
        }
        String sql = "SELECT user_id, name, email, bio, profile_picture_url FROM users WHERE user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                User user = new User();
                user.id = rs.getInt("user_id");
                user.name = rs.getString("name");
                user.email = rs.getString("email");
                user.bio = rs.getString("bio");
                user.profile_picture_url = rs.getString("profile_picture_url");
                return user;
            } else {
                System.out.println("getUserDetails: User not found for ID: " + userId);
            }
        } catch (SQLException e) {
            System.err.println("SQLException during getUserDetails for ID: " + userId);
            e.printStackTrace();
        }
        return null; // Return null if not found or error
    }

    public boolean updateUserProfile(int userId, String bio, String profilePictureUrl) {
        if (!isConnected()) {
            System.err.println("updateUserProfile failed: No database connection.");
            return false;
        }
        String sql = "UPDATE users SET bio = ?, profile_picture_url = ? WHERE user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            // Handle null values properly for SQL
            pstmt.setString(1, bio);
            pstmt.setString(2, profilePictureUrl);
            pstmt.setInt(3, userId);
            int affectedRows = pstmt.executeUpdate();
            System.out.println("updateUserProfile for user " + userId + ": Affected rows = " + affectedRows);
            return affectedRows > 0;
        } catch (SQLException e) {
            System.err.println("SQLException during updateUserProfile for user: " + userId);
            e.printStackTrace();
        }
        return false;
    }

    public User findUserByEmail(String email) {
        if (!isConnected()) {
            System.err.println("findUserByEmail failed: No database connection.");
            return null;
        }
        String sql = "SELECT user_id, name, email FROM users WHERE email = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                User user = new User();
                user.id = rs.getInt("user_id");
                user.name = rs.getString("name");
                user.email = rs.getString("email");
                return user;
            }
        } catch (SQLException e) {
            System.err.println("SQLException during findUserByEmail for email: " + email);
            e.printStackTrace();
        }
        return null; // Return null if not found or error
    }

    // --- Chat Management ---
    public List<Chat> getUserChats(int userId) {
        List<Chat> chats = new ArrayList<>();
        if (!isConnected()) {
            System.err.println("getUserChats failed: No database connection.");
            return chats;
        }
        String sql = "SELECT c.chat_id, c.is_group_chat, c.owner_id, " +
                "CASE WHEN c.is_group_chat THEN c.chat_name ELSE u.name END as display_name, " +
                "CASE WHEN c.is_group_chat THEN -1 ELSE cp2.user_id END as contact_id " +
                "FROM chats c " +
                "JOIN chat_participants cp ON c.chat_id = cp.chat_id " +
                "LEFT JOIN chat_participants cp2 ON c.chat_id = cp2.chat_id AND cp2.user_id != ? " +
                "LEFT JOIN users u ON cp2.user_id = u.user_id " +
                "WHERE cp.user_id = ? " +
                "AND (c.is_group_chat = TRUE OR cp2.user_id IS NOT NULL)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            pstmt.setInt(2, userId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                int chatId = rs.getInt("chat_id");
                String displayName = rs.getString("display_name");
                boolean isGroup = rs.getBoolean("is_group_chat");
                int ownerId = rs.getInt("owner_id");
                if (isGroup) {
                    chats.add(new Chat(chatId, displayName, true, ownerId));
                } else {
                    chats.add(new Chat(chatId, displayName, rs.getInt("contact_id")));
                }
            }
        } catch (SQLException e) {
            System.err.println("SQLException during getUserChats for user: " + userId);
            e.printStackTrace();
        }
        return chats;
    }

    public int createPrivateChat(int userId1, int userId2) {
        if (!isConnected()) return -1;
        // Check if chat already exists
        String checkSql = "SELECT cp1.chat_id FROM chat_participants cp1 " +
                "JOIN chat_participants cp2 ON cp1.chat_id = cp2.chat_id " +
                "JOIN chats c ON cp1.chat_id = c.chat_id " +
                "WHERE cp1.user_id = ? AND cp2.user_id = ? AND c.is_group_chat = FALSE";
        try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setInt(1, userId1);
            checkStmt.setInt(2, userId2);
            ResultSet rsCheck = checkStmt.executeQuery();
            if (rsCheck.next()) {
                System.out.println("createPrivateChat: Chat already exists between " + userId1 + " and " + userId2);
                return rsCheck.getInt("chat_id"); // Return existing chat ID
            }
        } catch (SQLException e) {
            System.err.println("SQLException during private chat check.");
            e.printStackTrace();
            return -1; // Indicate error
        }


        String chatSql = "INSERT INTO chats (is_group_chat, owner_id) VALUES (FALSE, NULL) RETURNING chat_id";
        String participantSql = "INSERT INTO chat_participants (chat_id, user_id) VALUES (?, ?)";
        try {
            conn.setAutoCommit(false);
            int newChatId = -1;
            try (PreparedStatement chatPstmt = conn.prepareStatement(chatSql)) {
                ResultSet rs = chatPstmt.executeQuery();
                if (rs.next()) newChatId = rs.getInt("chat_id");
                else { conn.rollback(); return -1; }
            }

            try (PreparedStatement participantPstmt = conn.prepareStatement(participantSql)) {
                participantPstmt.setInt(1, newChatId); participantPstmt.setInt(2, userId1); participantPstmt.addBatch();
                participantPstmt.setInt(1, newChatId); participantPstmt.setInt(2, userId2); participantPstmt.addBatch();
                participantPstmt.executeBatch();
            }
            conn.commit();
            return newChatId;
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException rollbackEx) { rollbackEx.printStackTrace(); }
            System.err.println("SQLException during createPrivateChat transaction.");
            e.printStackTrace();
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException e) { e.printStackTrace(); }
        }
        return -1;
    }

    public int createGroupChat(String groupName, int ownerId, List<Integer> memberIds) {
        if (!isConnected()) return -1;
        String chatSql = "INSERT INTO chats (chat_name, is_group_chat, owner_id) VALUES (?, TRUE, ?) RETURNING chat_id";
        String participantSql = "INSERT INTO chat_participants (chat_id, user_id) VALUES (?, ?)";
        try {
            conn.setAutoCommit(false);
            int newChatId = -1;
            try (PreparedStatement chatPstmt = conn.prepareStatement(chatSql)) {
                chatPstmt.setString(1, groupName);
                chatPstmt.setInt(2, ownerId);
                ResultSet rs = chatPstmt.executeQuery();
                if (rs.next()) newChatId = rs.getInt("chat_id");
                else { conn.rollback(); return -1; }
            }

            try (PreparedStatement participantPstmt = conn.prepareStatement(participantSql)) {
                participantPstmt.setInt(1, newChatId);
                participantPstmt.setInt(2, ownerId);
                participantPstmt.addBatch();
                for (int memberId : memberIds) {
                    if(memberId != ownerId) {
                        participantPstmt.setInt(1, newChatId);
                        participantPstmt.setInt(2, memberId);
                        participantPstmt.addBatch();
                    }
                }
                participantPstmt.executeBatch();
            }
            conn.commit();
            return newChatId;
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException rollbackEx) { rollbackEx.printStackTrace(); }
            System.err.println("SQLException during createGroupChat transaction.");
            e.printStackTrace();
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException e) { e.printStackTrace(); }
        }
        return -1;
    }

    public int deleteContactAndChat(int currentUserId, int chatId) {
        if (!isConnected()) return -1;
        String checkChatSql = "SELECT is_group_chat FROM chats WHERE chat_id = ?";
        try (PreparedStatement checkStmt = conn.prepareStatement(checkChatSql)) {
            checkStmt.setInt(1, chatId);
            ResultSet rs = checkStmt.executeQuery();
            if (rs.next()) {
                if (rs.getBoolean("is_group_chat")) {
                    System.err.println("Attempted to delete a group chat using deleteContactAndChat.");
                    return -1;
                }
            } else {
                return -1; // Chat doesn't exist
            }
        } catch (SQLException e) {
            System.err.println("SQLException during chat check for deleteContactAndChat.");
            e.printStackTrace();
            return -1;
        }

        String getParticipantsSql = "SELECT user_id FROM chat_participants WHERE chat_id = ? AND user_id != ?";
        String deleteChatSql = "DELETE FROM chats WHERE chat_id = ?";
        try {
            conn.setAutoCommit(false);
            int otherUserId = -1;
            try (PreparedStatement pstmt = conn.prepareStatement(getParticipantsSql)) {
                pstmt.setInt(1, chatId); pstmt.setInt(2, currentUserId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) otherUserId = rs.getInt("user_id");
                else { conn.rollback(); return -1; }
            }
            try (PreparedStatement pstmt = conn.prepareStatement(deleteChatSql)) {
                pstmt.setInt(1, chatId);
                if (pstmt.executeUpdate() > 0) {
                    conn.commit();
                    return otherUserId;
                } else {
                    conn.rollback();
                    // Fall through to return -1
                }
            }
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException rollbackEx) { rollbackEx.printStackTrace(); }
            System.err.println("SQLException during deleteContactAndChat transaction.");
            e.printStackTrace();
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException e) { e.printStackTrace(); }
        }
        return -1; // Return -1 on failure
    }

    // --- Message Management ---
    public Message saveMessage(int chatId, int senderId, String content) {
        if (!isConnected()) return null;
        String sql = "INSERT INTO messages (chat_id, sender_id, content) VALUES (?, ?, ?) RETURNING message_id, sent_at";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, chatId);
            pstmt.setInt(2, senderId);
            pstmt.setString(3, content);

            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                Message msg = new Message();
                msg.id = rs.getInt("message_id");
                msg.chatId = chatId;
                msg.senderId = senderId;
                msg.content = content;
                User sender = getUserDetails(senderId); // Potential performance hit, consider caching user details
                msg.senderName = (sender != null) ? sender.name : "Unknown";

                Timestamp ts = rs.getTimestamp("sent_at");
                msg.sent_at = new SimpleDateFormat("HH:mm").format(ts);

                return msg;
            }
        } catch (SQLException e) {
            System.err.println("SQLException during saveMessage.");
            e.printStackTrace();
        }
        return null;
    }

    public List<Message> getChatHistory(int chatId) {
        List<Message> history = new ArrayList<>();
        if (!isConnected()) return history;
        String sql = "SELECT m.message_id, m.chat_id, m.sender_id, u.name as sender_name, m.content, m.sent_at " +
                "FROM messages m JOIN users u ON m.sender_id = u.user_id " +
                "WHERE m.chat_id = ? ORDER BY m.sent_at ASC";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                Message msg = new Message();
                msg.id = rs.getInt("message_id");
                msg.chatId = rs.getInt("chat_id");
                msg.senderId = rs.getInt("sender_id");
                msg.senderName = rs.getString("sender_name");
                msg.content = rs.getString("content");
                Timestamp ts = rs.getTimestamp("sent_at");
                msg.sent_at = new SimpleDateFormat("HH:mm").format(ts);
                history.add(msg);
            }
        } catch (SQLException e) {
            System.err.println("SQLException during getChatHistory for chat: " + chatId);
            e.printStackTrace();
        }
        return history;
    }

    public int deleteMessage(int messageId, int userId) {
        if (!isConnected()) return -1;
        String sql = "DELETE FROM messages WHERE message_id = ? AND sender_id = ? RETURNING chat_id";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId); pstmt.setInt(2, userId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getInt("chat_id");
        } catch (SQLException e) {
            System.err.println("SQLException during deleteMessage for ID: " + messageId);
            e.printStackTrace();
        }
        return -1;
    }

    // --- Group Management ---

    public int getChatOwner(int chatId) {
        if (!isConnected()) return -1;
        String sql = "SELECT owner_id FROM chats WHERE chat_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getObject("owner_id") != null ? rs.getInt("owner_id") : -1;
            }
        } catch (SQLException e) {
            System.err.println("SQLException during getChatOwner for chat: " + chatId);
            e.printStackTrace();
        }
        return -1;
    }

    public List<User> getGroupMembers(int chatId) {
        List<User> members = new ArrayList<>();
        if (!isConnected()) return members;
        String sql = "SELECT u.user_id, u.name, u.email, u.bio, u.profile_picture_url " +
                "FROM users u JOIN chat_participants cp ON u.user_id = cp.user_id " +
                "WHERE cp.chat_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                User user = new User();
                user.id = rs.getInt("user_id");
                user.name = rs.getString("name");
                user.email = rs.getString("email");
                user.bio = rs.getString("bio");
                user.profile_picture_url = rs.getString("profile_picture_url");
                members.add(user);
            }
        } catch (SQLException e) {
            System.err.println("SQLException during getGroupMembers for chat: " + chatId);
            e.printStackTrace();
        }
        return members;
    }

    public boolean removeUserFromChat(int chatId, int userIdToRemove) {
        if (!isConnected()) return false;
        String sql = "DELETE FROM chat_participants WHERE chat_id = ? AND user_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, chatId);
            pstmt.setInt(2, userIdToRemove);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("SQLException during removeUserFromChat for user " + userIdToRemove + " in chat " + chatId);
            e.printStackTrace();
        }
        return false;
    }

    // --- Participant and Contact Info ---
    public List<Integer> getChatParticipants(int chatId) {
        List<Integer> participants = new ArrayList<>();
        if (!isConnected()) return participants;
        String sql = "SELECT user_id FROM chat_participants WHERE chat_id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            while(rs.next()) participants.add(rs.getInt("user_id"));
        } catch (SQLException e) {
            System.err.println("SQLException during getChatParticipants for chat: " + chatId);
            e.printStackTrace();
        }
        return participants;
    }

    public int getOtherParticipant(int chatId, int currentUserId) {
        if (!isConnected()) return -1;
        String sql = "SELECT user_id FROM chat_participants cp " +
                "JOIN chats c ON cp.chat_id = c.chat_id " +
                "WHERE cp.chat_id = ? AND cp.user_id != ? AND c.is_group_chat = FALSE";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, chatId);
            pstmt.setInt(2, currentUserId);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) return rs.getInt("user_id");
        } catch (SQLException e) {
            System.err.println("SQLException during getOtherParticipant for chat: " + chatId);
            e.printStackTrace();
        }
        return -1;
    }

    public List<Integer> getContactIds(int userId) {
        List<Integer> contactIds = new ArrayList<>();
        if (!isConnected()) return contactIds;
        String sql = "SELECT DISTINCT cp2.user_id FROM chat_participants cp1 " +
                "JOIN chat_participants cp2 ON cp1.chat_id = cp2.chat_id " +
                "JOIN chats c ON cp1.chat_id = c.chat_id " +
                "WHERE cp1.user_id = ? AND cp1.user_id != cp2.user_id AND c.is_group_chat = FALSE";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, userId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) contactIds.add(rs.getInt("user_id"));
        } catch (SQLException e) {
            System.err.println("SQLException during getContactIds for user: " + userId);
            e.printStackTrace();
        }
        return contactIds;
    }
}


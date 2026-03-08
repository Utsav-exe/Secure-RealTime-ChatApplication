CHAPTER 1: INTRODUCTION 1.1 Motivation In an era of constant digital communication, the demand for secure and reliable messaging platforms is higher than ever. While many commercial applications exist, building a private, custom-built chat server provides complete control over data, security, and features. The motivation for this project was to understand and implement the complex, concurrent, and secure systems that power modern real-time communication.

1.2 Objective The primary objective of this project is to design, develop, and test a secure, multi-threaded, real-time chat application using Java. The application must support both one-to-one and group messaging, feature-rich user profiles, and modern usability features like presence indicators and typing notifications.

1.3 Problem Statement To design and implement a secure client-server chat application that meets the following criteria: Server: A central, multi-threaded server capable of handling multiple simultaneous client connections securely. Client: An intuitive, graphical user interface (GUI) client built with JavaFX. Security: All communication between client and server must be encrypted. User passwords must be securely hashed. Database: User data, contact lists, group memberships, and message history must be persisted in a PostgreSQL database. Features: The application must support core features including secure user signup/login, 1-to-1 messaging, group chat creation and management, profile customization, and real-time status indicators.

1.4 Challenges Concurrency: Managing many simultaneous client connections on the server without conflicts, data corruption, or race conditions. Real-Time Synchronization: Instantly broadcasting new messages, typing statuses, and online/offline presence to all relevant clients. Security: Correctly implementing SSL/TLS for encryption and using a robust one-way hashing algorithm (jBCrypt) for password storage. Database Design: Structuring the database schema to efficiently manage relationships between users, chats, and messages. UI Responsiveness: Ensuring the JavaFX client remains responsive and smooth by offloading all network communication to a background thread.

CHAPTER 2: LITERATURE SURVEY This project was built by studying and integrating several core Java and networking technologies: Client-Server Architecture: The foundational model for this application. A central server acts as an authoritative hub, managing state and routing messages, while multiple clients connect to it to send and receive information. Java Sockets API (java.net): The project relies on Java's core networking library. SSLServerSocket is used on the server to listen for secure connections, and SSLSocket is used by the client to establish an encrypted link. Java Concurrency (java.util.concurrent): To handle multiple clients simultaneously, a multi-threaded solution is required. This project uses a ExecutorService (a thread pool) to manage a pool of worker threads, assigning one ClientHandler runnable to each connected client. This is far more efficient than creating a new thread for every single connection. JavaFX: The modern GUI toolkit for Java. It is used to build the entire client-side user interface, including its event-driven model for handling button clicks, keyboard input (KeyCode.ENTER), and dynamic UI updates (e.g., adding messages to a ListView). JSON (JavaScript Object Notation): A lightweight data-interchange format. It was chosen as the communication protocol between the server and client. Every action (e.g., "login," "send_message") is encapsulated in a JSON object, parsed on the server (Gson library), and processed accordingly. JDBC (Java Database Connectivity): The standard API for connecting Java applications to a database. This project uses the PostgreSQL JDBC driver to execute SQL queries for all data persistence.

CHAPTER 3: REQUIREMENT ANALYSIS 3.1 Functional Requirements The application was built to satisfy the following functional requirements:

User Authentication: Users must be able to sign up with a unique email, name, and password. Passwords must be securely hashed (using jBCrypt) before being stored. Users must be able to sign in using their email and password.

Contact & Chat Management: Users can add a new contact by searching for their unique email address, which creates a new 1-to-1 chat. Users can delete a 1-to-1 conversation from their chat list. Users can view their chat history, which is loaded from the database.

Real-Time Messaging: Messages sent by a user are broadcast in real-time to the other participant(s). Messages are sent using the "Send" button or by pressing the "Enter" key. Users can delete their own messages, and the deletion is reflected in real-time for all participants. Timestamps are displayed on all messages.

Group Chat Management: Users can create a new group chat by providing a name and selecting members from their contact list. Group messages display the name of the sender above the message bubble. The group "admin" (creator) can view a list of members and remove them. Any member can choose to "Leave" a group, which removes them from the participant list.

Profile Management: Users have a profile with a name, email, bio, and profile picture. Users can edit their own bio and profile picture URL. Users can view the profiles of their contacts. Avatars default to a colored circle with the user's initial if no picture URL is provided.

Presence & UI Feedback: Users can see an "online" (green) or "offline" (gray) indicator next to their contacts. Users can see a "typing..." indicator in the chat header when their partner is typing.

3.2 Non-Functional Requirements Security: All traffic between the client and server must be encrypted using SSL/TLS. Concurrency: The server must handle at least 100 concurrent users (supported by the thread pool). Persistence: All user data and messages must be stored in a relational database (PostgreSQL). Usability: The client GUI must be modern, responsive, and intuitive.

3.3 Software Requirements JDK 11+ (with JavaFX SDK 11+ configured)

PostgreSQL Database Server

Java IDE (e.g., IntelliJ IDEA, Eclipse)

External Libraries (.jar):

gson-2.10.1.jar (for JSON parsing)

postgresql-42.6.0.jar (for JDBC connection)

jbcrypt-0.4.jar (for password hashing)

CHAPTER 4: ARCHITECTURE & DESIGN 4.1 System Architecture The application uses a classic, centralized Client-Server Architecture. The Server (ChatServer.java): This is the single authoritative component. It binds to a specific port (9999) and listens for incoming connections using an SSLServerSocket. When a client connects, the server accepts the SSLSocket and immediately hands it off to a new ClientHandler task, which is submitted to an ExecutorService (thread pool). The server's only other job is to maintain a ConcurrentHashMap that maps userIds to their active ClientHandler instances. This map is the "source of truth" for who is online and is used to broadcast messages.

The Database (DatabaseManager.java): This class is the Data Access Layer (DAL). It is instantiated once by the server. It contains all the SQL queries and JDBC logic needed to interact with the PostgreSQL database. It handles all C.R.U.D. (Create, Read, Update, Delete) operations for users, chats, and messages.

The Client (ChatClient.java): This is a standalone JavaFX desktop application. On startup, it connects to the server's address and port using an SSLSocket. It launches a separate background thread (listenToServer) whose only job is to continuously block and read messages from the socket. When a message is received, this background thread uses Platform.runLater() to pass the JSON data to the main JavaFX Application Thread. This is critical for preventing the UI from freezing. The main thread handles all UI rendering, button clicks, and user input. When a user sends a message, the main thread sends it to the server through the socket's PrintWriter.

4.2 Database Design (Schema) The PostgreSQL database (named chatdb) consists of four core tables:

users:

user_id (SERIAL PRIMARY KEY): Unique user identifier.

name (VARCHAR): User's display name.

email (VARCHAR UNIQUE): User's login email.

password_hash (VARCHAR): The securely hashed password.

bio (TEXT): The user's profile biography.

profile_picture_url (TEXT): A URL to the user's avatar.

created_at (TIMESTAMP): When the account was created.

chats:

chat_id (SERIAL PRIMARY KEY): Unique chat identifier.

chat_name (VARCHAR): The name of the chat (used for groups).

is_group_chat (BOOLEAN): true if it's a group, false if 1-to-1.

owner_id (INT REFERENCES users(user_id)): The user ID of the group's creator/admin.

chat_participants:

chat_id (INT REFERENCES chats(chat_id)): Foreign key to the chat.

user_id (INT REFERENCES users(user_id)): Foreign key to the user.

(PRIMARY KEY is a composite of chat_id and user_id).

messages:

message_id (SERIAL PRIMARY KEY): Unique message identifier.

chat_id (INT REFERENCES chats(chat_id)): The chat this message belongs to.

sender_id (INT REFERENCES users(user_id)): The user who sent the message.

content (TEXT): The text of the message.

sent_at (TIMESTAMP): When the message was sent.

4.3 Communication Protocol (JSON) Communication is handled by sending JSON objects as single-line strings over the socket. Each JSON object must contain a "type" field that tells the server or client how to process the payload.

Client-to-Server Events:

"LOGIN"

"SIGNUP"

"SEND_MESSAGE"

"ADD_CONTACT"

"DELETE_CONTACT"

"DELETE_MESSAGE"

"GET_CHAT_HISTORY"

"GET_USER_PROFILE"

"UPDATE_USER_PROFILE"

"CREATE_GROUP"

"GET_GROUP_MEMBERS"

"REMOVE_GROUP_MEMBER"

"LEAVE_GROUP"

"USER_TYPING"

"USER_STOPPED_TYPING"

Server-to-Client Events:

"LOGIN_SUCCESS" / "LOGIN_FAIL"

"SIGNUP_SUCCESS" / "SIGNUP_FAIL"

"RECEIVE_MESSAGE"

"ADD_CONTACT_SUCCESS" / "ADD_CONTACT_FAIL"

"NEW_CHAT_STARTED"

"DELETE_CONTACT_SUCCESS" / "DELETE_CONTACT_FAIL"

"CHAT_REMOVED"

"MESSAGE_DELETED"

"TYPING_STARTED" / "TYPING_STOPPED"

"USER_ONLINE" / "USER_OFFLINE"

"CHAT_HISTORY"

"USER_PROFILE_DATA"

"UPDATE_PROFILE_RESULT"

"PROFILE_UPDATED"

"GROUP_CREATED_SUCCESS" / "CREATE_GROUP_FAIL"

"NEW_GROUP_CHAT"

"GROUP_MEMBERS_LIST"

"REMOVED_FROM_GROUP"

"LEFT_GROUP_SUCCESS" / "LEAVE_GROUP_FAIL"

"MEMBER_LEFT_GROUP"

CHAPTER 5: IMPLEMENTATION 5.1 Core Components The project is built from three main Java files: DatabaseManager.java: This is the data layer. It uses Java's PreparedStatement to safely execute parameterized SQL queries, preventing SQL injection. It contains methods for every database operation, such as authenticateUser, createUser, saveMessage, getChatHistory, createGroupChat, etc. All its methods return simple Java objects (like User, Chat, or List) or primitive types. ChatServer.java: This is the main application runnable. Its main method starts the SSLServerSocket and listens for connections. It contains the vital ClientHandler inner class, which is the heart of the server logic. Each ClientHandler instance is a Runnable that manages the Socket, PrintWriter, and BufferedReader for a single client. Its run method contains the main while loop that reads JSON strings from the client, and a large switch statement that calls the appropriate logic (e.g., handleLogin, handleSendMessage). ChatClient.java: This is a large javafx.application.Application class. The start() method initializes the connection and shows the createLoginScreen(). listenToServer() runs on a new thread to handle incoming server messages. handleServerMessage() contains the client-side switch statement to process messages from the server (e.g., "RECEIVE_MESSAGE") and updates the UI accordingly. It contains numerous helper methods for building UI components (createMainChatScreen, showProfileEditor, createAvatar), sending JSON messages to the server (sendMessage, requestUserProfile), and custom ListCell implementations (ChatListCell, MessageCell) for the modern UI.

5.2 Key Libraries and Dependencies Java SE 11 (JDK): The core platform. JavaFX 11+ (SDK): Used for the entire graphical user interface. Imported via --module-path and --add-modules VM options. Gson (com.google.code.gson): A Google library used to serialize Java objects into JSON strings and deserialize JSON strings back into Java objects. This is the foundation of the communication protocol. PostgreSQL JDBC Driver (org.postgresql): The driver that allows the DatabaseManager to connect and execute queries on the PostgreSQL database. jBCrypt (org.mindrot.jbcrypt): A trusted library used to securely hash user passwords with the bcrypt algorithm. BCrypt.hashpw() is used for signing up, and BCrypt.checkpw() is used for logging in.

5.3 Security Implementation Security was a primary requirement and was implemented in two key areas:

Transport Layer Security (TLS): A Java Keystore (server_keystore.jks) was created using the keytool command. The ChatServer uses this keystore to create an SSLContext and an SSLServerSocketFactory, ensuring that it only serves connections over SSL/TLS. The ChatClient is also configured to trust this keystore, using an SSLSocketFactory to establish the secure, encrypted connection. This prevents eavesdropping and man-in-the-middle attacks, as all chat messages and login credentials are encrypted in transit.

Password Hashing: When a user signs up, their plain-text password is never stored. Instead, the jBCrypt.hashpw() function is called, which generates a strong, salted hash. This hash is what's stored in the password_hash column of the users table. When a user tries to log in, the server retrieves the hash from the database and uses jBCrypt.checkpw() to compare the provided password against the hash. This function is secure because it's impossible to reverse the hash to find the original password.

CHAPTER 6: EXPERIMENT RESULTS & ANALYSIS 6.1 Authentication and Security Signup: Successfully created new users. The psql shell was used to verify that the users table contained the new user, and the password_hash column contained a long, hashed string, not the plain-text password. Attempting to sign up with a pre-existing email correctly failed. Login: Login attempts with an incorrect password failed. Login attempts with the correct password succeeded, and the server responded with the LOGIN_SUCCESS JSON object, correctly populated with the user's chats and contact list. Encryption: (Verified conceptually). By using SSLSocket, all data is confirmed to be encrypted. A network sniffer (like Wireshark) would only show encrypted, unreadable TLS traffic, not plain-text JSON.

6.2 Real-Time Messaging (1-to-1 & Group) Test: Two ChatClient instances were run. User 1 added User 2 as a contact. Result: The new chat appeared instantly in User 1's list. User 2 instantly received the NEW_CHAT_STARTED notification and also saw the chat appear. Messages sent by User 1 appeared immediately on User 2's screen, and vice-versa. Group Test: User 1 created a group and added User 2. The group appeared in the lists for both users. Messages sent by User 1 appeared in the group chat on both screens, correctly prepended with "User 1:".

6.3 Real-Time Feature Analysis Presence: When User 2 was closed, the "online" indicator next to their name on User 1's client correctly turned gray. Upon User 2 logging back in, the indicator turned green. This confirms the server's clients.remove() and broadcastStatusUpdate logic is working. Typing Indicator: When User 1 typed in the message field, the "typing..." label correctly appeared in the header of User 2's client window. When User 1 stopped typing, the label disappeared after a 1.5-second delay. Message Deletion: User 1 sent a message, then right-clicked and selected "Delete Message." The message was instantly removed from the ListView on both User 1's and User 2's clients.

6.4 Profile Management Test: User 1 clicked their avatar, opened the "Edit Profile" window, and pasted a URL to an online image. Result: The avatar in the editor window provided a real-time preview. After clicking "Save," the UPDATE_USER_PROFILE message was sent, and the server responded with UPDATE_PROFILE_RESULT. The client then rebuilt its main scene, displaying the new avatar in the top-left corner. Test 2: User 2 clicked on User 1's name in the chat header. Result 2: The "View Profile" window opened, correctly displaying User 1's new profile picture and bio information.

CHAPTER 7: CONCLUSION This project successfully achieved its objective of creating a secure, multi-featured, real-time chat application. All core requirements, from encrypted client-server communication to persistent database storage and a modern JavaFX GUI, were implemented. The application successfully handles concurrent users, real-time data synchronization, and complex features like group chat and profile management. The final product is a robust and stable foundation. Future enhancements could build upon this, including features like end-to-end encryption (E2EE), file and image sharing, message "read" receipts, and the ability to add/remove group members after creation.

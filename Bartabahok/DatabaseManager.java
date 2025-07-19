import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:bartabahok.db";

    public static void initializeDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            // Users table (unchanged)
            stmt.execute("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE NOT NULL, password TEXT NOT NULL);");

            // Messages table (unchanged)
            stmt.execute("CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, sender TEXT NOT NULL, receiver TEXT NOT NULL, content TEXT, file_data BLOB, timestamp INTEGER NOT NULL, type TEXT NOT NULL);");

            // --- NEW: Groups Table ---
            stmt.execute("CREATE TABLE IF NOT EXISTS groups (id INTEGER PRIMARY KEY AUTOINCREMENT, group_name TEXT NOT NULL UNIQUE, admin_username TEXT NOT NULL, FOREIGN KEY(admin_username) REFERENCES users(username));");

            // --- NEW: Group Members Table (Many-to-Many relationship) ---
            stmt.execute("CREATE TABLE IF NOT EXISTS group_members (group_id INTEGER NOT NULL, username TEXT NOT NULL, FOREIGN KEY(group_id) REFERENCES groups(id), FOREIGN KEY(username) REFERENCES users(username), PRIMARY KEY (group_id, username));");

            System.out.println("Database with group tables initialized successfully.");
        } catch (SQLException e) {
            System.err.println("Database initialization failed: " + e.getMessage());
        }
    }

    // --- User Methods (Unchanged) ---
    public static boolean addUser(String username, String password) {
        String sql = "INSERT INTO users(username, password) VALUES(?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            pstmt.setString(2, password);
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            System.err.println("Error adding user: " + e.getMessage());
            return false;
        }
    }

    public static boolean authenticateUser(String username, String password) {
        String sql = "SELECT password FROM users WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, username);
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getString("password").equals(password);
            }
            return false;
        } catch (SQLException e) {
            System.err.println("Authentication error: " + e.getMessage());
            return false;
        }
    }

    public static List<String> getAllUsernames() {
        List<String> users = new ArrayList<>();
        String sql = "SELECT username FROM users";
        try (Connection conn = DriverManager.getConnection(DB_URL); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                users.add(rs.getString("username"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return users;
    }

    public static void saveMessage(Message message) {
        String sql = "INSERT INTO messages(id, sender, receiver, content, file_data, type, timestamp) VALUES(?,?,?,?,?,?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, message.getMessageId());
            pstmt.setString(2, message.getSender());
            pstmt.setString(3, message.getReceiver());
            pstmt.setString(4, message.getContent());
            pstmt.setBytes(5, message.getFileData());
            pstmt.setString(6, message.getType().name());
            pstmt.setLong(7, message.getTimestamp());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving message: " + e.getMessage());
        }
    }

    public static List<Message> getChatHistory(String userA, String userB) {
        List<Message> history = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE (sender = ? AND receiver = ?) OR (sender = ? AND receiver = ?) ORDER BY timestamp ASC";
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userA);
            pstmt.setString(2, userB);
            pstmt.setString(3, userB);
            pstmt.setString(4, userA);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                history.add(reconstructMessageFromResultSet(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return history;
    }
    
    // --- NEW: Group Management Methods ---

    public static int createGroup(String groupName, String adminUsername) {
        String sqlGroup = "INSERT INTO groups(group_name, admin_username) VALUES(?,?)";
        String sqlMember = "INSERT INTO group_members(group_id, username) VALUES(?,?)";
        
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false); // Start transaction
            try (PreparedStatement pstmtGroup = conn.prepareStatement(sqlGroup, Statement.RETURN_GENERATED_KEYS)) {
                pstmtGroup.setString(1, groupName);
                pstmtGroup.setString(2, adminUsername);
                pstmtGroup.executeUpdate();
                
                ResultSet generatedKeys = pstmtGroup.getGeneratedKeys();
                if (generatedKeys.next()) {
                    int groupId = generatedKeys.getInt(1);
                    // Add the admin as the first member
                    try (PreparedStatement pstmtMember = conn.prepareStatement(sqlMember)) {
                        pstmtMember.setInt(1, groupId);
                        pstmtMember.setString(2, adminUsername);
                        pstmtMember.executeUpdate();
                    }
                    conn.commit(); // Commit transaction
                    return groupId;
                }
            } catch (SQLException e) {
                conn.rollback(); // Rollback on error
                throw e;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return -1; // Return -1 on failure
        }
        return -1;
    }

    public static boolean leaveGroup(int groupId, String username) {
        String sql = "DELETE FROM group_members WHERE group_id = ? AND username = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, groupId);
            pstmt.setString(2, username);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    // --- NEW METHOD ---
    public static boolean joinGroup(int groupId, String username) {
        String sql = "INSERT OR IGNORE INTO group_members(group_id, username) VALUES(?,?)";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, groupId);
            pstmt.setString(2, username);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static List<String> getGroupMembers(int groupId) {
        List<String> members = new ArrayList<>();
        String sql = "SELECT username FROM group_members WHERE group_id = ?";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, groupId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                members.add(rs.getString("username"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return members;
    }
    
    public static List<String> getAllGroups() {
        List<String> groups = new ArrayList<>();
        // Format: "GroupID:GroupName"
        String sql = "SELECT id, group_name FROM groups";
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                groups.add(rs.getInt("id") + ":" + rs.getString("group_name"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return groups;
    }
    
    public static List<Message> getGroupChatHistory(int groupId) {
        List<Message> history = new ArrayList<>();
        String sql = "SELECT * FROM messages WHERE receiver = ? AND type LIKE 'GROUP_%' ORDER BY timestamp ASC";
        try (Connection conn = DriverManager.getConnection(DB_URL); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, String.valueOf(groupId));
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                history.add(reconstructMessageFromResultSet(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return history;
    }
        // In DatabaseManager.java

    public static List<Integer> getGroupIdsForUser(String username) {
        List<Integer> groupIds = new ArrayList<>();
        String sql = "SELECT group_id FROM group_members WHERE username = ?";
        
        try (Connection conn = DriverManager.getConnection(DB_URL);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                groupIds.add(rs.getInt("group_id"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return groupIds;
    }
    private static Message reconstructMessageFromResultSet(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String sender = rs.getString("sender");
        String receiver = rs.getString("receiver");
        String content = rs.getString("content");
        byte[] fileData = rs.getBytes("file_data");
        long timestamp = rs.getLong("timestamp");
        Message.MessageType type = Message.MessageType.valueOf(rs.getString("type"));

        if (fileData != null) {
            return new Message(type, sender, receiver, content, fileData, id, timestamp);
        } else {
            return new Message(type, sender, receiver, content, id, timestamp);
        }
    }
}
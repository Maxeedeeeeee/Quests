package me.pvpclub.questSystem;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.SimpleItem;
import xyz.xenondevs.invui.window.Window;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class QuestSystem extends JavaPlugin {

    private DatabaseManager databaseManager;
    private QuestManager questManager;
    private QuestCommand questCommand;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            databaseManager = new DatabaseManager(this);
            getLogger().info("Database connection established successfully.");
        } catch (SQLException e) {
            getLogger().severe("Failed to establish database connection! Disabling plugin.");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        questManager = new QuestManager(this);
        questManager.loadQuestsFromDatabase();

        questCommand = new QuestCommand(this);
        Objects.requireNonNull(getCommand("quests")).setExecutor(questCommand);
        getServer().getPluginManager().registerEvents(new QuestListener(this), this);

        getLogger().info("QuestSystem has been enabled!");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("QuestSystem has been disabled!");
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public QuestManager getQuestManager() {
        return questManager;
    }

    public static class DatabaseManager {

        private final HikariDataSource dataSource;
        private final QuestSystem plugin;

        public DatabaseManager(QuestSystem plugin) throws SQLException {
            this.plugin = plugin;
            FileConfiguration config = plugin.getConfig();

            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s",
                    config.getString("database.host"),
                    config.getInt("database.port"),
                    config.getString("database.database")));
            hikariConfig.setUsername(config.getString("database.username"));
            hikariConfig.setPassword(config.getString("database.password"));
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            hikariConfig.setMaximumPoolSize(10);

            this.dataSource = new HikariDataSource(hikariConfig);
            try (Connection conn = dataSource.getConnection()) {
                if (!conn.isValid(1)) {
                    throw new SQLException("Could not establish a valid database connection.");
                }
            }
            setupTables();
        }

        private void setupTables() {
            CompletableFuture.runAsync(() -> {
                try (Connection connection = getConnection()) {
                    String createQuestsTable = "CREATE TABLE IF NOT EXISTS quests ("
                            + "id INT AUTO_INCREMENT PRIMARY KEY,"
                            + "quest_key VARCHAR(255) NOT NULL UNIQUE,"
                            + "name VARCHAR(255) NOT NULL,"
                            + "description TEXT NOT NULL,"
                            + "`type` VARCHAR(50) NOT NULL,"
                            + "target VARCHAR(255),"
                            + "required_amount INT NOT NULL"
                            + ");";

                    String createPlayerProgressTable = "CREATE TABLE IF NOT EXISTS player_quest_progress ("
                            + "id INT AUTO_INCREMENT PRIMARY KEY,"
                            + "player_uuid VARCHAR(36) NOT NULL,"
                            + "quest_id INT NOT NULL,"
                            + "progress INT DEFAULT 0,"
                            + "status VARCHAR(50) NOT NULL,"
                            + "FOREIGN KEY (quest_id) REFERENCES quests(id),"
                            + "UNIQUE KEY (player_uuid, quest_id)"
                            + ");";

                    try (PreparedStatement statement1 = connection.prepareStatement(createQuestsTable);
                         PreparedStatement statement2 = connection.prepareStatement(createPlayerProgressTable)) {
                        statement1.execute();
                        statement2.execute();
                        plugin.getLogger().info("Database tables verified/created successfully.");
                        insertExampleQuest();
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not create database tables!");
                    e.printStackTrace();
                }
            });
        }

        private void insertExampleQuest() {
            String insertSQL = "INSERT INTO quests (quest_key, name, description, `type`, target, required_amount) " +
                    "VALUES ('zombie_hunter_1', 'Zombie Hunter', 'Kill 10 zombies to prove your strength.', 'KILL_MOBS', 'ZOMBIE', 10) " +
                    "ON DUPLICATE KEY UPDATE name=VALUES(name);";

            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(insertSQL)) {
                statement.executeUpdate();
                plugin.getLogger().info("Example quest 'Zombie Hunter' loaded into database.");
            } catch (SQLException e) {
            }
        }

        public Connection getConnection() throws SQLException {
            return dataSource.getConnection();
        }

        public void close() {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
        }
    }

    public static class QuestManager {
        private final QuestSystem plugin;
        private final Map<Integer, Quest> quests = new ConcurrentHashMap<>();
        private final Map<UUID, Map<Integer, PlayerQuestData>> playerProgressCache = new ConcurrentHashMap<>();

        public QuestManager(QuestSystem plugin) {
            this.plugin = plugin;
        }

        public void loadQuestsFromDatabase() {
            CompletableFuture.runAsync(() -> {
                try (Connection conn = plugin.getDatabaseManager().getConnection();
                     PreparedStatement ps = conn.prepareStatement("SELECT * FROM quests");
                     ResultSet rs = ps.executeQuery()) {
                    quests.clear();
                    while (rs.next()) {
                        Quest quest = new Quest(
                                rs.getInt("id"),
                                rs.getString("name"),
                                Arrays.asList(rs.getString("description").split("\n")),
                                QuestType.valueOf(rs.getString("type")),
                                rs.getString("target"),
                                rs.getInt("required_amount")
                        );
                        quests.put(quest.getId(), quest);
                    }
                    plugin.getLogger().info("Successfully loaded " + quests.size() + " quests.");
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not load quests from database.");
                    e.printStackTrace();
                }
            });
        }

        public void loadPlayerProgress(Player player) {
            UUID playerUUID = player.getUniqueId();
            CompletableFuture.runAsync(() -> {
                Map<Integer, PlayerQuestData> progressMap = new ConcurrentHashMap<>();
                String sql = "SELECT quest_id, progress, status FROM player_quest_progress WHERE player_uuid = ?";
                try (Connection conn = plugin.getDatabaseManager().getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, playerUUID.toString());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        PlayerQuestData data = new PlayerQuestData(
                                playerUUID, rs.getInt("quest_id"), rs.getInt("progress"), QuestStatus.valueOf(rs.getString("status"))
                        );
                        progressMap.put(data.getQuestId(), data);
                    }
                    playerProgressCache.put(playerUUID, progressMap);
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not load quest progress for " + player.getName());
                    e.printStackTrace();
                }
            });
        }

        public void unloadPlayerProgress(Player player) {
            playerProgressCache.remove(player.getUniqueId());
        }

        public Collection<Quest> getAllQuests() {
            return Collections.unmodifiableCollection(quests.values());
        }

        public Quest getQuestById(int id) {
            return quests.get(id);
        }

        public PlayerQuestData getPlayerQuestData(Player player, int questId) {
            Map<Integer, PlayerQuestData> playerData = playerProgressCache.get(player.getUniqueId());
            if (playerData == null) {
                return new PlayerQuestData(player.getUniqueId(), questId, 0, QuestStatus.AVAILABLE);
            }
            return playerData.getOrDefault(questId, new PlayerQuestData(player.getUniqueId(), questId, 0, QuestStatus.AVAILABLE));
        }

        public void startQuest(Player player, Quest quest) {
            PlayerQuestData data = getPlayerQuestData(player, quest.getId());
            if (data.getStatus() == QuestStatus.AVAILABLE) {
                data.setStatus(QuestStatus.IN_PROGRESS);
                playerProgressCache.get(player.getUniqueId()).put(quest.getId(), data);
                savePlayerQuestData(data);
                player.sendMessage(ChatColor.GREEN + "Quest Started: " + quest.getName());
            }
        }

        public void incrementQuestProgress(Player player, QuestType type, String target) {
            Map<Integer, PlayerQuestData> playerData = playerProgressCache.get(player.getUniqueId());
            if (playerData == null) return;

            for (PlayerQuestData data : playerData.values()) {
                if (data.getStatus() != QuestStatus.IN_PROGRESS) continue;

                Quest quest = getQuestById(data.getQuestId());
                if (quest != null && quest.getType() == type && quest.getTarget().equalsIgnoreCase(target)) {
                    data.setProgress(data.getProgress() + 1);
                    player.sendMessage(String.format(ChatColor.YELLOW + "%s progress: %d/%d", quest.getName(), data.getProgress(), quest.getRequiredAmount()));

                    if (data.getProgress() >= quest.getRequiredAmount()) {
                        data.setStatus(QuestStatus.COMPLETED);
                        player.sendMessage(ChatColor.GREEN + "Quest Completed: " + quest.getName() + "!");
                    }
                    savePlayerQuestData(data);
                }
            }
        }

        private void savePlayerQuestData(PlayerQuestData data) {
            CompletableFuture.runAsync(() -> {
                String sql = "INSERT INTO player_quest_progress (player_uuid, quest_id, progress, status) " +
                        "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE progress = VALUES(progress), status = VALUES(status)";
                try (Connection conn = plugin.getDatabaseManager().getConnection();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, data.getPlayerUUID().toString());
                    ps.setInt(2, data.getQuestId());
                    ps.setInt(3, data.getProgress());
                    ps.setString(4, data.getStatus().name());
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not save quest progress for " + data.getPlayerUUID());
                    e.printStackTrace();
                }
            });
        }
    }

    public static class QuestGUI {
        private final QuestManager questManager;

        public QuestGUI(QuestSystem plugin) {
            this.questManager = plugin.getQuestManager();
        }

        public void openQuestList(Player player) {
            List<Item> items = questManager.getAllQuests().stream()
                    .map(quest -> createQuestItem(player, quest))
                    .collect(Collectors.toList());

            Gui gui = PagedGui.items()
                    .setStructure(
                            "# # # # # # # # #",
                            "# . . . . . . . #",
                            "# . . . . . . . #",
                            "# . . . . . . . #",
                            "# . . . . . . . #",
                            "# # # # # # # # #",
                            "# # < # # # > # #")
                    .addIngredient('#', new ItemBuilder(Material.TINTED_GLASS).setDisplayName(ChatColor.RESET.toString()))
                    .addIngredient('<', xyz.xenondevs.invui.item.impl.controlitem.PageItem.previous.get())
                    .addIngredient('>', xyz.xenondevs.invui.item.impl.controlitem.PageItem.next.get())
                    .setContent(items)
                    .build();

            Window.single().setViewer(player).setTitle(ChatColor.DARK_AQUA + "Available Quests").setGui(gui).open();
        }

        private Item createQuestItem(Player player, Quest quest) {
            PlayerQuestData data = questManager.getPlayerQuestData(player, quest.getId());
            Material material = data.getStatus() == QuestStatus.COMPLETED ? Material.EMERALD_BLOCK : (data.getStatus() == QuestStatus.IN_PROGRESS ? Material.DIAMOND_SWORD : Material.BOOK);

            ItemBuilder itemBuilder = new ItemBuilder(material).setDisplayName(ChatColor.GOLD + quest.getName());
            List<String> lore = new ArrayList<>();
            quest.getDescription().forEach(line -> lore.add(ChatColor.GRAY + line));
            lore.add(" ");
            lore.add(ChatColor.WHITE + "Objective: " + ChatColor.YELLOW + quest.getType().name().replace("_", " ") + " " + quest.getRequiredAmount() + " " + quest.getTarget());
            lore.add(" ");
            lore.add(ChatColor.WHITE + "Status: " + data.getStatus().getDisplayName());

            if (data.getStatus() == QuestStatus.IN_PROGRESS) {
                lore.add(String.format(ChatColor.WHITE + "Progress: " + ChatColor.YELLOW + "%d / %d", data.getProgress(), quest.getRequiredAmount()));
            }

            lore.add(" ");
            if (data.getStatus() == QuestStatus.AVAILABLE) {
                lore.add(ChatColor.GREEN + "Click to start this quest!");
            }

            itemBuilder.setLore(lore);

            return new SimpleItem(itemBuilder.get(), event -> {
                if (data.getStatus() == QuestStatus.AVAILABLE) {
                    questManager.startQuest(player, quest);
                    player.closeInventory();
                    openQuestList(player);
                }
            });
        }
    }

    public static class LeaderboardGUI {
        private final QuestSystem plugin;

        public LeaderboardGUI(QuestSystem plugin) {
            this.plugin = plugin;
        }

        public void openLeaderboard(Player player) {
            CompletableFuture.supplyAsync(this::getLeaderboardData)
                    .thenAccept(leaderboardItems -> {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Gui gui = PagedGui.items()
                                    .setStructure(
                                            "# # # # # # # # #",
                                            "# . . . . . . . #",
                                            "# . . . . . . . #",
                                            "# . . . . . . . #",
                                            "# . . . . . . . #",
                                            "# # # # # # # # #",
                                            "# # < # # # > # #")
                                    .addIngredient('#', new ItemBuilder(Material.TINTED_GLASS).setDisplayName(ChatColor.RESET.toString()))
                                    .addIngredient('<', xyz.xenondevs.invui.item.impl.controlitem.PageItem.previous.get())
                                    .addIngredient('>', xyz.xenondevs.invui.item.impl.controlitem.PageItem.next.get())
                                    .setContent(leaderboardItems)
                                    .build();
                            Window.single().setViewer(player).setTitle(ChatColor.AQUA + "Global Quest Leaderboard").setGui(gui).open();
                        });
                    });
        }

        private List<Item> getLeaderboardData() {
            List<Item> items = new ArrayList<>();
            String sql = "SELECT p.player_uuid, COUNT(p.status) AS completed_quests, MAX(p.progress) AS progress " +
                    "FROM player_quest_progress p " +
                    "WHERE p.status = 'COMPLETED' " +
                    "GROUP BY p.player_uuid " +
                    "ORDER BY completed_quests DESC LIMIT 28;";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                int rank = 1;
                while (rs.next()) {
                    UUID playerUUID = UUID.fromString(rs.getString("player_uuid"));
                    int completedQuests = rs.getInt("completed_quests");

                    String playerName = Bukkit.getOfflinePlayer(playerUUID).getName();
                    if (playerName == null) continue;

                    Material material;
                    switch (rank) {
                        case 1:
                            material = Material.DIAMOND;
                            break;
                        case 2:
                            material = Material.GOLD_INGOT;
                            break;
                        case 3:
                            material = Material.IRON_INGOT;
                            break;
                        default:
                            material = Material.STONE;
                            break;
                    }

                    ItemBuilder itemBuilder = new ItemBuilder(material);
                    itemBuilder.setDisplayName(ChatColor.YELLOW.toString() + rank + ". " + ChatColor.AQUA + playerName);

                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Completed Quests: " + ChatColor.WHITE + completedQuests);
                    itemBuilder.setLore(lore);

                    items.add(new SimpleItem(itemBuilder.get()));
                    rank++;
                }

            } catch (SQLException e) {
                plugin.getLogger().severe("Could not retrieve leaderboard data.");
                e.printStackTrace();
            }
            return items;
        }
    }

    public static class QuestCommand implements CommandExecutor {
        private final QuestGUI questGUI;
        private final LeaderboardGUI leaderboardGUI;

        public QuestCommand(QuestSystem plugin) {
            this.questGUI = new QuestGUI(plugin);
            this.leaderboardGUI = new LeaderboardGUI(plugin);
        }

        @Override
        public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
                return true;
            }

            Player player = (Player) sender;

            if (args.length > 0 && args[0].equalsIgnoreCase("leaderboard")) {
                leaderboardGUI.openLeaderboard(player);
            } else {
                questGUI.openQuestList(player);
            }

            return true;
        }
    }

    public static class QuestListener implements Listener {
        private final QuestManager questManager;

        public QuestListener(QuestSystem plugin) {
            this.questManager = plugin.getQuestManager();
        }

        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            questManager.loadPlayerProgress(event.getPlayer());
        }

        @EventHandler
        public void onPlayerQuit(PlayerQuitEvent event) {
            questManager.unloadPlayerProgress(event.getPlayer());
        }

        @EventHandler
        public void onEntityDeath(EntityDeathEvent event) {
            if (event.getEntity().getKiller() != null && event.getEntity().getKiller() instanceof Player) {
                questManager.incrementQuestProgress(event.getEntity().getKiller(), QuestType.KILL_MOBS, event.getEntityType().name());
            }
        }
    }

    public static class Quest {
        private final int id;
        private final String name;
        private final List<String> description;
        private final QuestType type;
        private final String target;
        private final int requiredAmount;

        public Quest(int id, String name, List<String> description, QuestType type, String target, int requiredAmount) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.type = type;
            this.target = target;
            this.requiredAmount = requiredAmount;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<String> getDescription() {
            return description;
        }

        public QuestType getType() {
            return type;
        }

        public String getTarget() {
            return target;
        }

        public int getRequiredAmount() {
            return requiredAmount;
        }
    }

    public static class PlayerQuestData {
        private final UUID playerUUID;
        private final int questId;
        private int progress;
        private QuestStatus status;

        public PlayerQuestData(UUID playerUUID, int questId, int progress, QuestStatus status) {
            this.playerUUID = playerUUID;
            this.questId = questId;
            this.progress = progress;
            this.status = status;
        }

        public UUID getPlayerUUID() {
            return playerUUID;
        }

        public int getQuestId() {
            return questId;
        }

        public int getProgress() {
            return progress;
        }

        public void setProgress(int progress) {
            this.progress = progress;
        }

        public QuestStatus getStatus() {
            return status;
        }

        public void setStatus(QuestStatus status) {
            this.status = status;
        }
    }

    public enum QuestStatus {
        AVAILABLE("§7Available"),
        IN_PROGRESS("§eIn Progress"),
        COMPLETED("§aCompleted");
        private final String displayName;

        QuestStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum QuestType {
        KILL_MOBS,
        GATHER_ITEMS,
        REACH_LOCATION
    }
}

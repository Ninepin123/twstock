package me.ninepin.twstock;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStockManager {
    private final Twstock plugin;
    private final File holdingsFile;
    private FileConfiguration holdingsConfig;

    // Store player holdings in memory for quick access
    private final Map<UUID, Map<String, Integer>> playerHoldings = new ConcurrentHashMap<>();

    public PlayerStockManager(Twstock plugin) {
        this.plugin = plugin;

        // Ensure data folder exists
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        // Initialize holdings file
        this.holdingsFile = new File(plugin.getDataFolder(), "player_holdings.yml");

        // Create the file if it doesn't exist
        if (!holdingsFile.exists()) {
            try {
                holdingsFile.createNewFile();
                plugin.getLogger().info("Created new player_holdings.yml file");
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create player_holdings.yml: " + e.getMessage());
            }
        }

        // Load the config
        this.holdingsConfig = YamlConfiguration.loadConfiguration(holdingsFile);

        // Initialize the 'players' section if it doesn't exist
        if (!holdingsConfig.contains("players")) {
            holdingsConfig.createSection("players");
            saveHoldingsConfig();
        }

        // Load all player holdings into memory
        loadAllPlayerHoldings();
    }

    // Load all player holdings from config into memory
    private void loadAllPlayerHoldings() {
        playerHoldings.clear();

        ConfigurationSection playersSection = holdingsConfig.getConfigurationSection("players");
        if (playersSection == null) {
            plugin.getLogger().warning("No 'players' section found in holdings config, creating one");
            playersSection = holdingsConfig.createSection("players");
            saveHoldingsConfig();
            return; // No players to load
        }

        for (String uuidString : playersSection.getKeys(false)) {
            try {
                UUID playerUUID = UUID.fromString(uuidString);
                Map<String, Integer> holdings = loadPlayerHoldings(playerUUID);
                playerHoldings.put(playerUUID, holdings);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in player_holdings.yml: " + uuidString);
            }
        }

        plugin.getLogger().info("Loaded holdings for " + playerHoldings.size() + " players");
    }

    // Load holdings for a specific player from config
    public Map<String, Integer> loadPlayerHoldings(UUID playerUUID) {
        Map<String, Integer> holdings = new HashMap<>();

        String path = "players." + playerUUID.toString() + ".holdings";
        ConfigurationSection holdingsSection = holdingsConfig.getConfigurationSection(path);

        // 如果沒有找到持股部分，返回空持股
        if (holdingsSection == null) {
            plugin.getLogger().fine("No holdings found for player " + playerUUID + ", creating empty section");
            return holdings; // 返回空持股
        }

        // 遍歷持股部分的所有鍵（股票代碼）
        for (String encodedStockSymbol : holdingsSection.getKeys(false)) {
            int shares = holdingsSection.getInt(encodedStockSymbol);
            if (shares > 0) {
                String stockSymbol = encodedStockSymbol.replace("_DOT_", ".");
                holdings.put(stockSymbol, shares);
            }
        }

        // 將加載的數據放入內存緩存
        playerHoldings.put(playerUUID, holdings);

        return holdings;
    }

    public void addStockTransaction(Player player, String stockSymbol, int shares, double pricePerShare) {
        UUID playerUUID = player.getUniqueId();
        String basePath = "players." + playerUUID.toString();
        String transactionsPath = basePath + ".transactions." + stockSymbol.replace(".", "_DOT_");

        // 獲取已存在的交易記錄
        List<Double> prices = new ArrayList<>();
        List<Integer> sharesList = new ArrayList<>();

        // 檢查是否有現有交易記錄
        if (holdingsConfig.contains(transactionsPath)) {
            prices = holdingsConfig.getDoubleList(transactionsPath + ".prices");
            sharesList = holdingsConfig.getIntegerList(transactionsPath + ".shares");
        }

        // 添加新交易
        prices.add(pricePerShare);
        sharesList.add(shares);

        // 更新配置
        holdingsConfig.set(transactionsPath + ".prices", prices);
        holdingsConfig.set(transactionsPath + ".shares", sharesList);

        // 保存配置
        saveHoldingsConfig();
    }

    public double getAverageCost(Player player, String stockSymbol) {
        UUID playerUUID = player.getUniqueId();
        String transactionsPath = "players." + playerUUID.toString() + ".transactions." + stockSymbol.replace(".", "_DOT_");

        // 檢查是否有交易記錄
        if (!holdingsConfig.contains(transactionsPath)) {
            return 0.0;
        }

        List<Double> prices = holdingsConfig.getDoubleList(transactionsPath + ".prices");
        List<Integer> shares = holdingsConfig.getIntegerList(transactionsPath + ".shares");

        if (prices.isEmpty() || shares.isEmpty() || prices.size() != shares.size()) {
            return 0.0;
        }

        // 計算總成本和總股數
        double totalCost = 0.0;
        int totalShares = 0;

        for (int i = 0; i < prices.size(); i++) {
            totalCost += prices.get(i) * shares.get(i);
            totalShares += shares.get(i);
        }

        // 計算平均成本
        return totalShares > 0 ? totalCost / totalShares : 0.0;
    }

    public double getTotalCost(Player player, String stockSymbol) {
        UUID playerUUID = player.getUniqueId();
        String transactionsPath = "players." + playerUUID.toString() + ".transactions." + stockSymbol.replace(".", "_DOT_");

        // 檢查是否有交易記錄
        if (!holdingsConfig.contains(transactionsPath)) {
            return 0.0;
        }

        List<Double> prices = holdingsConfig.getDoubleList(transactionsPath + ".prices");
        List<Integer> shares = holdingsConfig.getIntegerList(transactionsPath + ".shares");

        if (prices.isEmpty() || shares.isEmpty() || prices.size() != shares.size()) {
            return 0.0;
        }

        // 計算總成本
        double totalCost = 0.0;

        for (int i = 0; i < prices.size(); i++) {
            totalCost += prices.get(i) * shares.get(i);
        }

        return totalCost;
    }

    /**
     * 清除玩家特定股票的交易記錄
     *
     * @param player      玩家
     * @param stockSymbol 股票代碼
     */
    public void clearTransactionHistory(Player player, String stockSymbol) {
        UUID playerUUID = player.getUniqueId();
        String transactionsPath = "players." + playerUUID.toString() + ".transactions." + stockSymbol.replace(".", "_DOT_");

        // 清除交易記錄
        holdingsConfig.set(transactionsPath, null);

        // 保存配置
        saveHoldingsConfig();

        plugin.getLogger().info("Cleared transaction history for player " + player.getName() +
                " stock " + stockSymbol);
    }

    // Get holdings for a player (from memory)
    public Map<String, Integer> getPlayerHoldings(Player player) {
        UUID playerUUID = player.getUniqueId();

        // If not in memory, load from config
        if (!playerHoldings.containsKey(playerUUID)) {
            Map<String, Integer> holdings = loadPlayerHoldings(playerUUID);
            playerHoldings.put(playerUUID, holdings);
            return holdings;
        }

        return playerHoldings.get(playerUUID);
    }

    // Add shares to a player's holdings
    public void addShares(Player player, String stockSymbol, int sharesToAdd) {
        if (sharesToAdd <= 0) return;

        UUID playerUUID = player.getUniqueId();
        Map<String, Integer> holdings = getPlayerHoldings(player);

        int currentShares = holdings.getOrDefault(stockSymbol, 0);
        holdings.put(stockSymbol, currentShares + sharesToAdd);

        // Update in memory
        playerHoldings.put(playerUUID, holdings);

        // Save to config
        savePlayerHoldings(playerUUID, holdings);
    }
    // Remove shares from a player's holdings
    // Remove shares from a player's holdings
    public boolean removeShares(Player player, String stockSymbol, int sharesToRemove) {
        if (sharesToRemove <= 0) return false;

        UUID playerUUID = player.getUniqueId();
        Map<String, Integer> holdings = getPlayerHoldings(player);

        int currentShares = holdings.getOrDefault(stockSymbol, 0);
        if (currentShares < sharesToRemove) {
            return false; // Not enough shares
        }

        // Update shares
        int newAmount = currentShares - sharesToRemove;
        if (newAmount > 0) {
            holdings.put(stockSymbol, newAmount);
        } else {
            holdings.remove(stockSymbol); // Remove stock if no shares left

            // 當玩家賣掉所有股票時，清除交易記錄
            clearTransactionHistory(player, stockSymbol);
        }

        // Update in memory
        playerHoldings.put(playerUUID, holdings);

        // Save to config
        savePlayerHoldings(playerUUID, holdings);
        return true;
    }

    // Save a player's holdings to config
    private void savePlayerHoldings(UUID playerUUID, Map<String, Integer> holdings) {
        String basePath = "players." + playerUUID.toString();

        // 確保玩家部分存在
        if (!holdingsConfig.contains(basePath)) {
            holdingsConfig.createSection(basePath);
        }

        // 設置持股
        String holdingsPath = basePath + ".holdings";

        // 清除現有持股並重新創建
        holdingsConfig.set(holdingsPath, null);
        ConfigurationSection holdingsSection = holdingsConfig.createSection(holdingsPath);

        // 添加所有持股，使用轉義字符替換點號
        for (Map.Entry<String, Integer> entry : holdings.entrySet()) {
            // 只保存股數大於0的股票
            if (entry.getValue() > 0) {
                String stockSymbol = entry.getKey().replace(".", "_DOT_");
                holdingsSection.set(stockSymbol, entry.getValue());
            }
        }

        // 立即保存到文件
        saveHoldingsConfig();

        // 更新內存中的數據
        playerHoldings.put(playerUUID, new HashMap<>(holdings));
    }

    // Save the holdings config file
    private void saveHoldingsConfig() {
        try {
            holdingsConfig.save(holdingsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save player_holdings.yml: " + e.getMessage());
        }
    }

    // Reload holdings data from file
    public void reloadHoldings() {
        // Reload the configuration from disk
        this.holdingsConfig = YamlConfiguration.loadConfiguration(holdingsFile);

        // Re-load all player holdings
        loadAllPlayerHoldings();

        plugin.getLogger().info("Reloaded player holdings data from file");
    }

    // Get how many shares of a stock a player has
    public int getShareCount(Player player, String stockSymbol) {
        Map<String, Integer> holdings = getPlayerHoldings(player);
        return holdings.getOrDefault(stockSymbol, 0);
    }
}
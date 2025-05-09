package me.ninepin.twstock;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class MyStockGUI {
    private final Twstock plugin;
    private final PlayerStockManager playerStockManager;

    // Map to track which players are in chat input mode
    private final Map<UUID, String> pendingStockPurchases = new HashMap<>();
    private final Map<UUID, String> pendingStockSales = new HashMap<>();
    private final Map<UUID, Boolean> pendingStockAdditions = new HashMap<>(); // Track users adding new stocks

    public MyStockGUI(Twstock plugin, PlayerStockManager playerStockManager) {
        this.plugin = plugin;
        this.playerStockManager = playerStockManager;
    }

    public void openGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_PURPLE + "我的投資組合");

        try {
            // First, ensure player holdings are loaded from the config
            Map<String, Integer> holdings = playerStockManager.getPlayerHoldings(player);

            // Populate GUI with player's holdings

            int slot = 0;
            for (Map.Entry<String, Integer> entry : holdings.entrySet()) {
                String stockSymbol = entry.getKey();
                int shares = entry.getValue();

                if (slot >= 45) break; // Limit to first 45 slots (leaving bottom row for controls)

                // Fetch current stock data to show price
                ItemStack stockItem = createStockItem(player, stockSymbol, shares);
                gui.setItem(slot++, stockItem);
            }

            // Add control buttons at the bottom
            addControlButtons(gui);

            // Open the GUI for the player
            player.openInventory(gui);
            plugin.getLogger().info("Opened MyStock GUI for " + player.getName());
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "開啟投資組合時發生錯誤: " + e.getMessage());
            plugin.getLogger().severe("Error opening MyStock GUI for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void openOverviewGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.GOLD + "投資概覽");

        try {
            // 確保玩家持股資料已載入
            Map<String, Integer> holdings = playerStockManager.getPlayerHoldings(player);

            // 計算相關數據
            double totalInvestment = 0.0;
            double totalCurrentValue = 0.0;
            double totalProfit = 0.0;

            // 創建概覽物品
            List<ItemStack> stockItems = new ArrayList<>();

            for (Map.Entry<String, Integer> entry : holdings.entrySet()) {
                String stockSymbol = entry.getKey();
                int shares = entry.getValue();

                if (shares <= 0) continue;

                // 獲取股票資訊
                Twstock.StockData stockData = plugin.fetchYahooStockDataForGUI(stockSymbol);
                double averageCost = playerStockManager.getAverageCost(player, stockSymbol);
                double totalCost = playerStockManager.getTotalCost(player, stockSymbol);

                // 如果有價格資訊
                if (stockData != null) {
                    double currentValue = stockData.currentPrice * shares;
                    double profit = currentValue - totalCost;

                    // 累計總資訊
                    totalInvestment += totalCost;
                    totalCurrentValue += currentValue;
                    totalProfit += profit;

                    // 創建股票資訊物品
                    ItemStack stockItem = new ItemStack(Material.PAPER);
                    ItemMeta meta = stockItem.getItemMeta();

                    String displayName = stockSymbol;
                    String chineseName = plugin.getTwStockChineseNames().get(stockSymbol);
                    if (chineseName != null && !chineseName.isEmpty()) {
                        displayName = chineseName + " (" + stockSymbol + ")";
                    }

                    meta.setDisplayName(ChatColor.GOLD + displayName);

                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "持有股數: " + ChatColor.WHITE + shares);
                    lore.add(ChatColor.GRAY + "平均成本: " + ChatColor.WHITE + "$" + formatPrice(averageCost));
                    lore.add(ChatColor.GRAY + "總投資: " + ChatColor.WHITE + "$" + formatPrice(totalCost));
                    lore.add(ChatColor.GRAY + "當前價格: " + ChatColor.WHITE + "$" + formatPrice(stockData.currentPrice));
                    lore.add(ChatColor.GRAY + "總市值: " + ChatColor.WHITE + "$" + formatPrice(currentValue));

                    // 添加投資損益資訊 - 修改標題
                    ChatColor profitColor = profit >= 0 ? ChatColor.RED : ChatColor.GREEN;
                    String profitSymbol = profit >= 0 ? "▲" : "▼";
                    double profitPercentage = totalCost > 0 ? (profit / totalCost) * 100 : 0;

                    lore.add(ChatColor.GRAY + "投資損益: " + profitColor + profitSymbol + " " +
                            formatPrice(Math.abs(profit)) + " (" + formatPercentage(Math.abs(profitPercentage)) + "%)");


                    // 添加操作提示
                    lore.add("");
                    lore.add(ChatColor.YELLOW + "右鍵點擊: " + ChatColor.WHITE + "查看詳細資訊");
                    lore.add(ChatColor.YELLOW + "Shift+右鍵點擊: " + ChatColor.WHITE + "賣出所有股票");

                    meta.setLore(lore);
                    stockItem.setItemMeta(meta);
                    stockItems.add(stockItem);
                }
            }

            // 添加股票物品到界面
            for (int i = 0; i < stockItems.size() && i < 45; i++) {
                gui.setItem(i, stockItems.get(i));
            }

            // 添加整體投資概覽物品
            ItemStack overviewItem = new ItemStack(Material.DIAMOND);
            ItemMeta overviewMeta = overviewItem.getItemMeta();
            overviewMeta.setDisplayName(ChatColor.AQUA + "投資組合概覽");

            List<String> overviewLore = new ArrayList<>();
            overviewLore.add(ChatColor.GRAY + "總投資成本: " + ChatColor.WHITE + "$" + formatPrice(totalInvestment));
            overviewLore.add(ChatColor.GRAY + "目前總市值: " + ChatColor.WHITE + "$" + formatPrice(totalCurrentValue));

            // 添加總投資損益 - 修改標題
            ChatColor totalProfitColor = totalProfit >= 0 ? ChatColor.RED : ChatColor.GREEN;
            String totalProfitSymbol = totalProfit >= 0 ? "▲" : "▼";
            double totalProfitPercentage = totalInvestment > 0 ? (totalProfit / totalInvestment) * 100 : 0;

            overviewLore.add(ChatColor.GRAY + "總投資損益: " + totalProfitColor + totalProfitSymbol + " " +
                    formatPrice(Math.abs(totalProfit)) + " (" + formatPercentage(Math.abs(totalProfitPercentage)) + "%)");

            overviewMeta.setLore(overviewLore);
            overviewItem.setItemMeta(overviewMeta);
            gui.setItem(49, overviewItem);

            // 添加返回按鈕
            ItemStack backButton = new ItemStack(Material.ARROW);
            ItemMeta backMeta = backButton.getItemMeta();
            backMeta.setDisplayName(ChatColor.YELLOW + "返回投資組合");
            backButton.setItemMeta(backMeta);
            gui.setItem(45, backButton);

            // 打開介面
            player.openInventory(gui);
            plugin.getLogger().info("Opened Investment Overview GUI for " + player.getName());

        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "開啟投資概覽時發生錯誤: " + e.getMessage());
            plugin.getLogger().severe("Error opening Investment Overview GUI for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private ItemStack createStockItem(Player player, String stockSymbol, int shares) {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();

        // 獲取股票資訊
        String displayName = stockSymbol;
        String chineseName = plugin.getTwStockChineseNames().get(stockSymbol);
        if (chineseName != null && !chineseName.isEmpty()) {
            displayName = chineseName + " (" + stockSymbol + ")";
        }

        meta.setDisplayName(ChatColor.GREEN + displayName);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "持有股數: " + ChatColor.WHITE + shares);

        // 獲取股票平均成本
        double averageCost = playerStockManager.getAverageCost(player, stockSymbol);
        double totalCost = playerStockManager.getTotalCost(player, stockSymbol);

        // 添加成本資訊
        if (averageCost > 0) {
            lore.add(ChatColor.GRAY + "平均成本: " + ChatColor.WHITE + "$" + formatPrice(averageCost));
            lore.add(ChatColor.GRAY + "總投資: " + ChatColor.WHITE + "$" + formatPrice(totalCost));
        }

        // 嘗試獲取當前價格
        try {
            Twstock.StockData stockData = plugin.fetchYahooStockDataForGUI(stockSymbol);
            if (stockData != null) {
                String currencySymbol = getCurrencySymbol(stockData.currency);
                lore.add(ChatColor.GRAY + "當前價格: " + ChatColor.WHITE + currencySymbol + formatPrice(stockData.currentPrice));

                // 計算總市值
                double totalValue = stockData.currentPrice * shares;
                lore.add(ChatColor.GRAY + "總市值: " + ChatColor.WHITE + currencySymbol + formatPrice(totalValue));

                // 添加投資損益資訊 - 修改標題
                if (averageCost > 0) {
                    double profit = totalValue - totalCost;
                    double profitPercentage = totalCost > 0 ? (profit / totalCost) * 100 : 0;

                    ChatColor profitColor = profit >= 0 ? ChatColor.RED : ChatColor.GREEN;
                    String profitSymbol = profit >= 0 ? "▲" : "▼";

                    lore.add(ChatColor.GRAY + "投資損益: " + profitColor + profitSymbol + " " +
                            formatPrice(Math.abs(profit)) + " (" + formatPercentage(Math.abs(profitPercentage)) + "%)");
                }

            } else {
                lore.add(ChatColor.RED + "無法獲取最新市場資料");
            }
        } catch (Exception e) {
            lore.add(ChatColor.RED + "價格資訊載入錯誤");
            plugin.getLogger().warning("Error fetching stock data for " + stockSymbol + ": " + e.getMessage());
        }

        lore.add("");
        lore.add(ChatColor.YELLOW + "左鍵點擊: " + ChatColor.WHITE + "購買更多");
        lore.add(ChatColor.YELLOW + "右鍵點擊: " + ChatColor.WHITE + "賣出股票");
        // 添加Shift+右鍵的操作提示
        lore.add(ChatColor.YELLOW + "Shift+右鍵點擊: " + ChatColor.WHITE + "賣出所有股票");

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void addControlButtons(Inventory gui) {
        // Add add stock button
        ItemStack addButton = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta addMeta = addButton.getItemMeta();
        addMeta.setDisplayName(ChatColor.GREEN + "新增投資組合");
        List<String> addLore = new ArrayList<>();
        addLore.add(ChatColor.GRAY + "點擊新增股票到你的投資組合");
        addMeta.setLore(addLore);
        addButton.setItemMeta(addMeta);
        gui.setItem(47, addButton);

        // Add refresh button
        ItemStack refreshButton = new ItemStack(Material.COMPASS);
        ItemMeta refreshMeta = refreshButton.getItemMeta();
        refreshMeta.setDisplayName(ChatColor.AQUA + "刷新資料");
        List<String> refreshLore = new ArrayList<>();
        refreshLore.add(ChatColor.GRAY + "點擊刷新所有股票資訊");
        refreshMeta.setLore(refreshLore);
        refreshButton.setItemMeta(refreshMeta);
        gui.setItem(49, refreshButton);

        // Add overview button (could show portfolio stats)
        ItemStack overviewButton = new ItemStack(Material.BOOK);
        ItemMeta overviewMeta = overviewButton.getItemMeta();
        overviewMeta.setDisplayName(ChatColor.GOLD + "投資概覽");
        List<String> overviewLore = new ArrayList<>();
        overviewLore.add(ChatColor.GRAY + "查看投資組合的整體表現");
        overviewMeta.setLore(overviewLore);
        overviewButton.setItemMeta(overviewMeta);
        gui.setItem(45, overviewButton);
    }

    // Handle clicks in the GUI
    public boolean handleClick(Player player, int slot, Inventory inventory, ClickType clickType) {
        ItemStack clickedItem = inventory.getItem(slot);
        if (clickedItem == null || clickedItem.getType() == Material.AIR) return false;

        ItemMeta meta = clickedItem.getItemMeta();
        if (meta == null) return false;

        String displayName = meta.getDisplayName();

        // Handle special buttons
        if (displayName.contains("刷新資料")) {
            player.closeInventory();
            openGUI(player);
            player.sendMessage(ChatColor.GREEN + "已刷新投資組合資料");
            return true;
        }

        if (displayName.contains("投資概覽")) {
            player.closeInventory();
            openOverviewGUI(player);
            return true;
        }
        if (displayName.contains("新增投資組合")) {
            // Add new stock to portfolio
            if (!plugin.isVaultEnabled()) {
                player.sendMessage(ChatColor.RED + "經濟系統未啟用，無法進行交易");
                return true;
            }

            pendingStockAdditions.put(player.getUniqueId(), true);
            player.closeInventory();
            player.sendMessage(ChatColor.GREEN + "請在聊天中輸入你要新增的股票代碼 (例如: 2330 或 2330.TW)，或輸入 'cancel' 取消");
            return true;
        }

        // Extract stock symbol from item name
        String stockSymbol = extractStockSymbol(displayName);
        if (stockSymbol == null) return false;

        // Debug log for the extracted stock symbol
        plugin.getLogger().info("Extracted stock symbol: " + stockSymbol + " from display name: " + displayName);

        // Handle buy/sell based on click type
        if (clickType == ClickType.LEFT) {
            // Buy more of this stock
            if (!plugin.isVaultEnabled()) {
                player.sendMessage(ChatColor.RED + "經濟系統未啟用，無法進行交易");
                return true;
            }

            pendingStockPurchases.put(player.getUniqueId(), stockSymbol);
            player.closeInventory();
            player.sendMessage(ChatColor.GREEN + "請在聊天中輸入你要購買的 " + stockSymbol + " 股數，或輸入 'cancel' 取消");
            return true;
        } else if (clickType == ClickType.RIGHT) {
            // Sell this stock
            if (!plugin.isVaultEnabled()) {
                player.sendMessage(ChatColor.RED + "經濟系統未啟用，無法進行交易");
                return true;
            }

            pendingStockSales.put(player.getUniqueId(), stockSymbol);
            player.closeInventory();
            player.sendMessage(ChatColor.GREEN + "請在聊天中輸入你要賣出的 " + stockSymbol + " 股數，或輸入 'cancel' 取消");
            return true;
        } else if (clickType == ClickType.SHIFT_RIGHT) {
            // 賣出所有股票
            if (!plugin.isVaultEnabled()) {
                player.sendMessage(ChatColor.RED + "經濟系統未啟用，無法進行交易");
                return true;
            }

            // 獲取玩家目前持有的股數
            int currentShares = playerStockManager.getShareCount(player, stockSymbol);
            if (currentShares <= 0) {
                player.sendMessage(ChatColor.RED + "你沒有持有 " + stockSymbol + " 的股票");
                return true;
            }

            // 直接銷售所有股票
            processSale(player, stockSymbol, currentShares);
            return true;
        }

        return false;
    }

    // Handle chat input for stock transactions
    public boolean handleChat(Player player, String message) {
        UUID playerUUID = player.getUniqueId();

        // Check if player is in add stock mode
        if (pendingStockAdditions.containsKey(playerUUID)) {
            pendingStockAdditions.remove(playerUUID);

            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(ChatColor.YELLOW + "新增股票已取消");
                return true;
            }

            // Normalize stock code input
            String stockSymbol = plugin.normalizeStockSymbol(message.trim());
            // Debug log for the normalized stock symbol
            plugin.getLogger().info("Normalized stock symbol for addition: " + stockSymbol + " from input: " + message.trim());
            addStockToPortfolio(player, stockSymbol);
            return true;
        }

        // Check if player is in buy mode
        if (pendingStockPurchases.containsKey(playerUUID)) {
            String stockSymbol = pendingStockPurchases.get(playerUUID);
            pendingStockPurchases.remove(playerUUID);

            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(ChatColor.YELLOW + "股票購買已取消");
                return true;
            }

            try {
                int shares = Integer.parseInt(message);
                if (shares <= 0) {
                    player.sendMessage(ChatColor.RED + "請輸入正數股數");
                    return true;
                }

                // Debug log for purchase attempt
                plugin.getLogger().info("Processing purchase of " + shares + " shares of " + stockSymbol + " for player " + player.getName());

                // Process the purchase
                processPurchase(player, stockSymbol, shares);
                return true;
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "無效的股數，請輸入一個整數");
                return true;
            }
        }

        // Check if player is in sell mode
        if (pendingStockSales.containsKey(playerUUID)) {
            String stockSymbol = pendingStockSales.get(playerUUID);
            pendingStockSales.remove(playerUUID);

            if (message.equalsIgnoreCase("cancel")) {
                player.sendMessage(ChatColor.YELLOW + "股票賣出已取消");
                return true;
            }

            try {
                int shares = Integer.parseInt(message);
                if (shares <= 0) {
                    player.sendMessage(ChatColor.RED + "請輸入正數股數");
                    return true;
                }

                // Debug log for sell attempt
                plugin.getLogger().info("Processing sale of " + shares + " shares of " + stockSymbol + " for player " + player.getName());

                // Process the sale
                processSale(player, stockSymbol, shares);
                return true;
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "無效的股數，請輸入一個整數");
                return true;
            }
        }

        return false; // Not handling this chat message
    }

    // Add new stock to portfolio
    private void addStockToPortfolio(Player player, String stockSymbol) {
        try {
            // Clean the stock symbol from any color codes or formatting characters
            String cleanStockSymbol = ChatColor.stripColor(stockSymbol);

            // Log the clean stock symbol
            plugin.getLogger().info("Adding stock to portfolio, clean symbol: " + cleanStockSymbol);

            // Verify the stock symbol exists by trying to fetch its data
            Twstock.StockData stockData = plugin.fetchYahooStockDataForGUI(cleanStockSymbol);
            if (stockData == null) {
                player.sendMessage(ChatColor.RED + "無法獲取 " + cleanStockSymbol + " 的市場資料，該股票可能不存在或無法交易");
                return;
            }

            // Check if the player already has this stock to avoid losing existing shares
            int existingShares = plugin.getPlayerStockManager().getShareCount(player, cleanStockSymbol);
            if (existingShares > 0) {
                player.sendMessage(ChatColor.YELLOW + "你已經持有 " + existingShares + " 股 " + cleanStockSymbol + "。");
                player.sendMessage(ChatColor.YELLOW + "你想要購買更多此股票嗎？請輸入購買股數，或輸入 'cancel' 取消");

                // Set up for purchase
                pendingStockPurchases.put(player.getUniqueId(), cleanStockSymbol);
                return;
            }

            // Add stock with 0 shares to portfolio (will show in GUI) only if it doesn't exist yet
            playerStockManager.addShares(player, cleanStockSymbol, 0);

            // Ask if player wants to purchase shares immediately
            String stockName = getStockDisplayName(cleanStockSymbol);
            player.sendMessage(ChatColor.GREEN + "成功將 " + stockName + " (" + cleanStockSymbol + ") 加入你的投資組合");
            player.sendMessage(ChatColor.YELLOW + "你想要立即購買此股票嗎？請輸入購買股數，或輸入 'cancel' 取消");

            // Set up for purchase
            pendingStockPurchases.put(player.getUniqueId(), cleanStockSymbol);

        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "添加股票時發生錯誤: " + e.getMessage());
            plugin.getLogger().severe("Error adding stock " + stockSymbol + " for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processPurchase(Player player, String stockSymbol, int shares) {
        try {
            // 清除顏色代碼
            String cleanStockSymbol = ChatColor.stripColor(stockSymbol);

            // 記錄操作
            plugin.getLogger().info("Processing purchase, clean symbol: " + cleanStockSymbol);

            // 獲取當前價格
            Twstock.StockData stockData = plugin.fetchYahooStockDataForGUI(cleanStockSymbol);
            if (stockData == null) {
                player.sendMessage(ChatColor.RED + "無法獲取 " + cleanStockSymbol + " 的市場資料，交易取消");
                return;
            }

            double pricePerShare = stockData.currentPrice;
            double totalCost = pricePerShare * shares;

            // 檢查玩家餘額
            double balance = plugin.getEconomy().getBalance(player);
            if (balance < totalCost) {
                player.sendMessage(ChatColor.RED + "餘額不足！需要 $" + formatPrice(totalCost) +
                        "，但你只有 $" + formatPrice(balance));
                return;
            }

            // 扣款並添加股份
            plugin.getEconomy().withdrawPlayer(player, totalCost);
            playerStockManager.addShares(player, cleanStockSymbol, shares);

            // 記錄交易成本 - 新增
            playerStockManager.addStockTransaction(player, cleanStockSymbol, shares, pricePerShare);

            // 顯示成功訊息
            String stockName = getStockDisplayName(cleanStockSymbol);
            player.sendMessage(ChatColor.GREEN + "成功購買 " + shares + " 股 " + stockName +
                    "，花費 $" + formatPrice(totalCost) + " (每股 $" + formatPrice(pricePerShare) + ")");

            // 重新開啟 GUI 顯示更新後的組合
            Bukkit.getScheduler().runTaskLater(plugin, () -> openGUI(player), 20L); // 延遲 1 秒

        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "處理交易時發生錯誤: " + e.getMessage());
            plugin.getLogger().severe("Error processing purchase for " + player.getName() +
                    " of " + stockSymbol + " x" + shares + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processSale(Player player, String stockSymbol, int shares) {
        try {
            // Clean the stock symbol from any color codes or formatting characters
            String cleanStockSymbol = ChatColor.stripColor(stockSymbol);

            // Log the clean stock symbol
            plugin.getLogger().info("Processing sale, clean symbol: " + cleanStockSymbol);

            // Check if player has enough shares
            int currentShares = playerStockManager.getShareCount(player, cleanStockSymbol);
            if (currentShares < shares) {
                player.sendMessage(ChatColor.RED + "你只持有 " + currentShares + " 股 " + cleanStockSymbol +
                        "，不能賣出 " + shares + " 股");
                return;
            }

            // Get current price
            Twstock.StockData stockData = plugin.fetchYahooStockDataForGUI(cleanStockSymbol);
            if (stockData == null) {
                player.sendMessage(ChatColor.RED + "無法獲取 " + cleanStockSymbol + " 的市場資料，交易取消");
                return;
            }

            double pricePerShare = stockData.currentPrice;
            double totalValue = pricePerShare * shares;

            // Remove shares and deposit money
            boolean success = playerStockManager.removeShares(player, cleanStockSymbol, shares);
            if (success) {
                plugin.getEconomy().depositPlayer(player, totalValue);

                // Show success message
                String stockName = getStockDisplayName(cleanStockSymbol);
                player.sendMessage(ChatColor.GREEN + "成功賣出 " + shares + " 股 " + stockName +
                        "，獲得 $" + formatPrice(totalValue) + " (每股 $" + formatPrice(pricePerShare) + ")");

                // Open the GUI again to show the updated portfolio
                Bukkit.getScheduler().runTaskLater(plugin, () -> openGUI(player), 20L); // Delay 1 second
            } else {
                player.sendMessage(ChatColor.RED + "移除股票失敗，請聯繫伺服器管理員");
            }

        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "處理交易時發生錯誤: " + e.getMessage());
            plugin.getLogger().severe("Error processing sale for " + player.getName() +
                    " of " + stockSymbol + " x" + shares + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Helper methods
    private String extractStockSymbol(String displayName) {
        if (displayName == null) return null;

        // Strip color codes first to avoid interference
        String stripped = ChatColor.stripColor(displayName);

        // Check if the name contains a stock symbol in parentheses
        int openParenIndex = stripped.lastIndexOf("(");
        int closeParenIndex = stripped.lastIndexOf(")");

        if (openParenIndex >= 0 && closeParenIndex > openParenIndex) {
            String symbol = stripped.substring(openParenIndex + 1, closeParenIndex).trim();
            plugin.getLogger().info("Extracted stock symbol from parentheses: " + symbol);
            return symbol;
        }

        // If no parentheses, try to use the whole name
        plugin.getLogger().info("No parentheses found, using full string as stock symbol: " + stripped);
        return stripped;
    }

    private String getStockDisplayName(String stockSymbol) {
        String chineseName = plugin.getTwStockChineseNames().get(stockSymbol);
        if (chineseName != null && !chineseName.isEmpty()) {
            return chineseName + " (" + stockSymbol + ")";
        }
        return stockSymbol;
    }

    private String formatPrice(double price) {
        return String.format("%,.2f", price);
    }

    private String formatPercentage(double percentage) {
        return String.format("%.2f", percentage);
    }

    private String getCurrencySymbol(String currencyCode) {
        if (currencyCode == null) return "$";
        switch (currencyCode.toUpperCase()) {
            case "USD":
                return "$";
            case "TWD":
                return "NT$";
            default:
                return currencyCode + " ";
        }
    }
}
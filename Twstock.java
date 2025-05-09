package me.ninepin.twstock;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class Twstock extends JavaPlugin {

    // Yahoo Finance API URL (保持舊版設定)
    private static final String YAHOO_API_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d";
    // TWSE ISIN URL for fetching stock Chinese names
    private static final String TWSE_ISIN_URL = "https://isin.twse.com.tw/isin/C_public.jsp?strMode=2"; // 上市公司
    private static final String TPEX_ISIN_URL = "https://isin.twse.com.tw/isin/C_public.jsp?strMode=4"; // 上櫃公司
    private static final String STOCK_NAMES_FILE = "stock_names.properties";
    private File stockNamesFile;
    private final DecimalFormat decimalFormat = new DecimalFormat("#,##0.00");
    // 台灣股票中文名稱對應表 (使用 ConcurrentHashMap 提高執行緒安全)
    private final Map<String, String> twStockChineseNames = new ConcurrentHashMap<>();

    // Flag to track if Chinese names initialization has been ATTEMPTED
    private final AtomicBoolean chineseNamesInitializationAttempted = new AtomicBoolean(false);
    // Flag to track if Chinese names are considered successfully INITIALIZED (e.g., loaded from file or fetched)
    private final AtomicBoolean chineseNamesReady = new AtomicBoolean(false);
    private CompletableFuture<Void> initialNameFetchFuture = null;

    private MyStockGUI myStockGUI;
    private Economy economy;
    private PlayerStockManager playerStockManager;
    private boolean vaultEnabled = false;

    @Override
    public void onEnable() {
        this.getCommand("stock").setExecutor(new StockCommandExecutor());

        stockNamesFile = new File(getDataFolder(), STOCK_NAMES_FILE);
        if (!setupEconomy()) {
            getLogger().warning("Vault未找到或經濟插件不可用，股票交易功能將被禁用！");
        } else {
            vaultEnabled = true;
            getLogger().info("成功連接Vault經濟系統！");
        }
        File playerHoldingsFile = new File(getDataFolder(), "player_holdings.yml");
        if (playerHoldingsFile.exists()) {
            getLogger().info("Found existing player_holdings.yml file, loading data...");
        }
        // 先初始化玩家股票管理器
        playerStockManager = new PlayerStockManager(this);

        // 然后再初始化和註冊 MyStockGUI 相關功能
        myStockGUI = new MyStockGUI(this, playerStockManager);
        this.getCommand("mystock").setExecutor(new MyStockCommand(this, myStockGUI));
        getServer().getPluginManager().registerEvents(new MyStockListener(myStockGUI, this), this);
        loadStockNamesFromFile(); // 首先嘗試從檔案加載

        // 如果檔案中沒有加載到任何名稱 (map為空) 且尚未嘗試過初始化
        if (twStockChineseNames.isEmpty() && chineseNamesInitializationAttempted.compareAndSet(false, true)) {
            getLogger().info("Stock names cache is empty. Initiating fetch from ISIN websites in the background...");
            initialNameFetchFuture = CompletableFuture.runAsync(() -> {
                fetchStockChineseNamesFromWebsites(); // 從網站獲取並保存
                if (!twStockChineseNames.isEmpty()) {
                    chineseNamesReady.set(true);
                }
                getLogger().info("Initial stock name fetching from ISIN websites complete. Total names in cache: " + twStockChineseNames.size());
            }, getServer().getScheduler().getMainThreadExecutor(this)); // 使用 Bukkit 的異步執行緒池
        } else if (!twStockChineseNames.isEmpty()) {
            getLogger().info("Stock names loaded from file. Total names: " + twStockChineseNames.size());
            chineseNamesReady.set(true); // 從檔案加載成功，標記為就緒
            chineseNamesInitializationAttempted.set(true); // 既然從檔案加載了，也算嘗試過了
        } else {
            // Map 為空，但之前可能已嘗試過初始化 (例如，上次獲取失敗或檔案就是空的)
            getLogger().info("Stock names cache is empty, and an initialization attempt might have occurred previously or failed.");
            // 如果 chineseNamesInitializationAttempted 為 true 但 chineseNamesReady 為 false，可以考慮重試邏輯
        }

        getLogger().info("TwStock plugin has been enabled!");
    }


    @Override
    public void onDisable() {
        getLogger().info("TwStock plugin has been disabled!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }
    public boolean reloadPlugin() {
        getLogger().info("Reloading TwStock plugin configuration...");

        // Reload player holdings
        if (playerStockManager != null) {
            playerStockManager.reloadHoldings();
        }

        // Reload stock names
        loadStockNamesFromFile();

        // Reload other configurations if needed

        getLogger().info("TwStock plugin configuration reloaded successfully!");
        return true;
    }
    public Economy getEconomy() {
        return economy;
    }

    public PlayerStockManager getPlayerStockManager() {
        return playerStockManager;
    }

    public boolean isVaultEnabled() {
        return vaultEnabled;
    }

    // 提供訪問中文名稱 Map 的方法
    public Map<String, String> getTwStockChineseNames() {
        return twStockChineseNames;
    }

    // 提供訪問中文名就緒狀態的方法
    public boolean areChineseNamesReady() {
        return chineseNamesReady.get();
    }

    public CompletableFuture<Void> getInitialNameFetchFuture() { // 確保這個 getter 存在
        return initialNameFetchFuture;
    }

    public StockData fetchYahooStockDataForGUI(String normalizedSymbol) {
        return fetchYahooStockData(normalizedSymbol);
    }

    private class StockCommandExecutor implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
                return true;
            }
            Player player = (Player) sender;

            if (args.length != 1) {
                player.sendMessage(ChatColor.RED + "使用方法: /stock <股票代碼>");
                player.sendMessage(ChatColor.GRAY + "例如: /stock 2330 或 /stock 2330.TW 或 /stock 6446.TWO");
                return true;
            }

            String stockSymbolArg = args[0];
            String normalizedSymbol = normalizeStockSymbol(stockSymbolArg); // 標準化輸入

            getLogger().info("Player " + player.getName() + " querying: " + stockSymbolArg + " (Normalized: " + normalizedSymbol + ")");

            // 檢查中文名稱初始化狀態
            if (initialNameFetchFuture != null && !initialNameFetchFuture.isDone()) {
                player.sendMessage(ChatColor.YELLOW + "系統正在初始化股票名稱列表，請稍候幾秒鐘再試...");
                return true;
            }
            // 如果嘗試過初始化但列表仍為空 (可能獲取失敗)，也提示一下
            if (chineseNamesInitializationAttempted.get() && !chineseNamesReady.get() && isTaiwanStock(normalizedSymbol)) {
                player.sendMessage(ChatColor.YELLOW + "股票中文名稱列表可能正在更新或初始化失敗，部分名稱可能無法顯示。將嘗試直接查詢...");
            }


            String chineseName = twStockChineseNames.get(normalizedSymbol);

            player.sendMessage(ChatColor.YELLOW + "正在查詢 " +
                    (chineseName != null ? chineseName + " (" + normalizedSymbol + ")" : normalizedSymbol) +
                    " 的股票資訊...");

            CompletableFuture.runAsync(() -> {
                try {
                    StockData stockData = fetchYahooStockData(normalizedSymbol);

                    if (stockData != null) {
                        String displayName = chineseName; // 優先使用快取的中文名

                        // 如果快取中沒有，並且是台灣股票，嘗試從 Yahoo 返回的名稱或再次從 ISIN 獲取
                        if (displayName == null) {
                            if (isTaiwanStock(normalizedSymbol)) {
                                // 嘗試使用 Yahoo API 返回的 shortName
                                if (stockData.shortName != null && !stockData.shortName.equalsIgnoreCase(normalizedSymbol) && !stockData.shortName.isEmpty()) {
                                    displayName = stockData.shortName;
                                    getLogger().info("Using Yahoo's name for " + normalizedSymbol + ": " + displayName + ". Caching it.");
                                    addStockName(normalizedSymbol, displayName); // 添加到我們的快取
                                } else {
                                    // 如果 Yahoo 的名字也不好，最後嘗試一次單獨從 ISIN 抓取
                                    String fetchedNameOnline = fetchSingleStockChineseNameFromWeb(normalizedSymbol);
                                    if (fetchedNameOnline != null) {
                                        displayName = fetchedNameOnline;
                                        addStockName(normalizedSymbol, fetchedNameOnline);
                                    }
                                }
                            }
                            // 如果還沒有，就用 Yahoo 的 shortName (可能是國際股或最終備援)
                            if (displayName == null) {
                                displayName = stockData.shortName;
                            }
                        }
                        // 最後的保障，如果 displayName 還是 null，就用 normalizedSymbol
                        if (displayName == null || displayName.isEmpty()) {
                            displayName = normalizedSymbol;
                        }


                        final String finalDisplayName = displayName;
                        StockData finalStockData = stockData;

                        getServer().getScheduler().runTask(Twstock.this, () -> {
                            ChatColor changeColor = finalStockData.change >= 0 ? ChatColor.RED : ChatColor.GREEN;
                            String changeSymbol = finalStockData.change >= 0 ? "▲" : "▼";

                            player.sendMessage(ChatColor.GOLD + "===== " + finalDisplayName + " (" + normalizedSymbol + ") =====");
                            if (finalStockData.exchange != null && !finalStockData.exchange.equalsIgnoreCase("N/A") && !finalStockData.exchange.equalsIgnoreCase("Unknown")) {
                                player.sendMessage(ChatColor.GRAY + "交易所: " + ChatColor.WHITE + finalStockData.exchange);
                            }

                            String currencySymbol = getCurrencySymbol(finalStockData.currency);

                            player.sendMessage(ChatColor.GRAY + "當前價格: " + ChatColor.WHITE + currencySymbol +
                                    decimalFormat.format(finalStockData.currentPrice));
                            player.sendMessage(ChatColor.GRAY + "變動: " + changeColor + changeSymbol + " " + currencySymbol +
                                    decimalFormat.format(Math.abs(finalStockData.change)) + " (" +
                                    decimalFormat.format(Math.abs(finalStockData.percentChange)) + "%)");
                            player.sendMessage(ChatColor.GRAY + "今日範圍: " + ChatColor.WHITE + currencySymbol +
                                    decimalFormat.format(finalStockData.dayLow) + " - " + currencySymbol +
                                    decimalFormat.format(finalStockData.dayHigh));
                            player.sendMessage(ChatColor.GRAY + "交易量: " + ChatColor.WHITE +
                                    formatVolume(finalStockData.volume));
                        });
                    } else {
                        getServer().getScheduler().runTask(Twstock.this, () ->
                                player.sendMessage(ChatColor.RED + "查詢失敗: 找不到股票 " + normalizedSymbol + " 的市場資料。請檢查代碼或稍後再試。"));
                    }
                } catch (Exception e) {
                    getLogger().severe("Error during stock data processing for " + normalizedSymbol + ": " + e.getMessage());
                    e.printStackTrace();
                    getServer().getScheduler().runTask(Twstock.this, () ->
                            player.sendMessage(ChatColor.RED + "查詢 " + normalizedSymbol + " 時發生內部錯誤，請查看伺服器日誌。"));
                }
            });
            return true;
        }
    }

    // 標準化股票代碼輸入
    String normalizeStockSymbol(String symbol) {
        String upperSymbol = symbol.toUpperCase();
        // 處理純數字輸入 (例如 "2330")，預設為 .TW
        if (upperSymbol.matches("^\\d{4,6}$") && !upperSymbol.contains(".")) {
            getLogger().info("Input '" + symbol + "' is numeric, normalizing to " + upperSymbol + ".TW");
            return upperSymbol + ".TW";
        }
        // 如果以 .tw 或 .two 結尾 (不區分大小寫)，則標準化為大寫後綴
        if (upperSymbol.endsWith(".TW")) {
            return upperSymbol;
        }
        if (upperSymbol.endsWith(".TWO")) { // 內部統一使用 .TWO 代表上櫃
            return upperSymbol;
        }
        // 允許 .OOTC 輸入，並將其視為 .TWO 類型進行中文名查找，但查詢 Yahoo 時會用 .OOTC
        if (upperSymbol.endsWith(".OOTC")) {
            // 為了中文名稱查找，我們可能需要一個映射，或者讓 fetchSingleStockChineseNameFromWeb 也能處理 .OOTC
            // 簡單起見，如果中文名是以 .TWO 存儲的，這裡可以先正規劃成 .TWO 查找中文名，查詢 Yahoo 時再轉回 .OOTC
            // 但更一致的做法是，如果用戶輸入.OOTC，我們就認可它是一種TPEX的表示
            return upperSymbol; // 保持 .OOTC
        }
        // 其他國際代碼直接返回大寫
        return upperSymbol;
    }

    private boolean isTaiwanStock(String normalizedSymbol) {
        return normalizedSymbol.endsWith(".TW") || normalizedSymbol.endsWith(".TWO") || normalizedSymbol.endsWith(".OOTC");
    }

    private String getCurrencySymbol(String currencyCode) {
        if (currencyCode == null) return "";
        switch (currencyCode.toUpperCase()) {
            case "USD":
                return "$";
            case "TWD":
                return "NT$";
            default:
                return currencyCode + " ";
        }
    }

    private void loadStockNamesFromFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        if (stockNamesFile.exists()) {
            Properties props = new Properties();
            // 使用 UTF-8 讀取，避免中文亂碼
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(stockNamesFile), StandardCharsets.UTF_8)) {
                props.load(reader);
                int count = 0;
                for (String key : props.stringPropertyNames()) {
                    twStockChineseNames.put(key.toUpperCase(), props.getProperty(key)); // 鍵名也統一為大寫
                    count++;
                }
                getLogger().info("Loaded " + count + " stock names from " + STOCK_NAMES_FILE);
                if (count > 0) {
                    chineseNamesReady.set(true); // 從檔案加載了內容，標記為就緒
                }
            } catch (IOException e) {
                getLogger().warning("Error loading stock names from " + STOCK_NAMES_FILE + ": " + e.getMessage());
            }
        } else {
            getLogger().info(STOCK_NAMES_FILE + " does not exist. Will attempt to fetch from web if needed.");
        }
    }

    private synchronized void saveStockNamesToFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        Properties props = new Properties();
        twStockChineseNames.forEach(props::setProperty);

        // 使用 UTF-8 寫入
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(stockNamesFile), StandardCharsets.UTF_8)) {
            props.store(writer, "Taiwan Stock Chinese Names (TWSE & TPEX)");
            getLogger().info("Saved " + twStockChineseNames.size() + " stock names to " + STOCK_NAMES_FILE);
        } catch (IOException e) {
            getLogger().warning("Error saving stock names to " + STOCK_NAMES_FILE + ": " + e.getMessage());
        }
    }

    private String formatVolume(long volume) {
        if (volume == 0) return "N/A"; // 舊版沒有這個，但通常交易量為0會顯示N/A
        if (volume >= 1_000_000_000) {
            return decimalFormat.format(volume / 1_000_000_000.0) + "B";
        } else if (volume >= 1_000_000) {
            return decimalFormat.format(volume / 1_000_000.0) + "M";
        } else if (volume >= 1_000) {
            return decimalFormat.format(volume / 1_000.0) + "K";
        }
        return String.valueOf(volume);
    }

    // StockData 類保持與舊版一致，設為 private class
    public class StockData {
        String shortName;
        String exchange;
        String currency;
        double currentPrice;
        double change;
        double percentChange;
        double dayHigh;
        double dayLow;
        long volume;
    }

    // parseYahooData 方法，結構上與舊版相似，但整合了一些安全獲取和日誌
    private StockData parseYahooData(String jsonString, String originalSymbolForLog) {
        try {
            JSONParser parser = new JSONParser();
            JSONObject root = (JSONObject) parser.parse(jsonString);
            JSONObject chart = (JSONObject) root.get("chart");
            if (chart == null) {
                getLogger().warning("Yahoo API response for " + originalSymbolForLog + " missing 'chart' object.");
                return null;
            }
            org.json.simple.JSONArray resultArray = (org.json.simple.JSONArray) chart.get("result");

            if (resultArray == null || resultArray.isEmpty()) {
                getLogger().warning("Yahoo API: No 'result' array or empty for symbol: " + originalSymbolForLog);
                return null;
            }

            JSONObject result = (JSONObject) resultArray.get(0);
            JSONObject meta = (JSONObject) result.get("meta");
            if (meta == null) {
                getLogger().warning("Yahoo API: No 'meta' data in result for symbol: " + originalSymbolForLog);
                return null;
            }

            // 打印 meta object 以便調試 (可以根據需要開關)
            // getLogger().info("Yahoo 'meta' object for " + originalSymbolForLog + ": " + meta.toJSONString());

            StockData data = new StockData();
            data.currency = getStringValue(meta, "currency", "Unknown");
            data.exchange = getStringValue(meta, "exchangeName", "Unknown");
            // Yahoo 返回的名稱，如果 meta 中有 symbol 欄位，優先使用，否則用 shortName
            data.shortName = getStringValue(meta, "symbol", getStringValue(meta, "shortName", originalSymbolForLog));


            data.currentPrice = getDoubleValue(meta, "regularMarketPrice", 0.0);
            data.dayHigh = getDoubleValue(meta, "regularMarketDayHigh", data.currentPrice);
            data.dayLow = getDoubleValue(meta, "regularMarketDayLow", data.currentPrice);

            // 價格變動計算邏輯 (與舊版類似，但更健壯)
            double previousClose = getDoubleValue(meta, "chartPreviousClose", 0.0);
            if (previousClose == 0.0 && meta.containsKey("previousClose")) { // 備援
                previousClose = getDoubleValue(meta, "previousClose", data.currentPrice);
            }
            if (previousClose == 0.0 && data.currentPrice != 0.0 && meta.containsKey("regularMarketChange")) {
                double marketChange = getDoubleValue(meta, "regularMarketChange", 0.0);
                previousClose = data.currentPrice - marketChange;
            }

            // 優先使用 Yahoo API 直接提供的漲跌幅
            if (meta.containsKey("regularMarketChange") && meta.containsKey("regularMarketChangePercent")) {
                data.change = getDoubleValue(meta, "regularMarketChange", 0.0);
                data.percentChange = getDoubleValue(meta, "regularMarketChangePercent", 0.0) * 100; // Yahoo 的百分比是小數
            } else if (previousClose > 0) { // 舊版計算方式
                data.change = data.currentPrice - previousClose;
                data.percentChange = (data.change / previousClose) * 100.0;
            } else {
                data.change = 0.0;
                data.percentChange = 0.0;
            }

            data.volume = getLongValue(meta, "regularMarketVolume", 0L);

            getLogger().info("Parsed StockData for " + originalSymbolForLog + ": Price=" + data.currentPrice + ", Change=" + data.change + ", %Change=" + data.percentChange);
            return data;

        } catch (Exception e) {
            getLogger().warning("處理 Yahoo Finance 數據 (" + originalSymbolForLog + ") 時意外錯誤: " + e.getMessage());
            e.printStackTrace(); // 打印完整堆疊以便調試
            return null;
        }
    }


    // HTTP 請求輔助方法，用於獲取網頁內容 (ISIN 和 Yahoo 都會用到)
    private String fetchHttpContent(String urlString, String charsetName, String userAgent, String context) throws IOException {
        // First clean any potential Minecraft color codes that might have been added
        urlString = ChatColor.stripColor(urlString);

        // Log the cleaned URL for debugging
        getLogger().info("Cleaned URL for HTTP request: " + urlString);

        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), charsetName))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line).append(System.lineSeparator());
                }
                getLogger().fine("Successfully fetched HTTP content from " + urlString + " for " + context);
                return response.toString();
            }
        } else {
            String errorDetails = "N/A";
            try (InputStream errorStream = connection.getErrorStream()) {
                if (errorStream != null) {
                    try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        StringBuilder errorResponse = new StringBuilder();
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            errorResponse.append(line);
                        }
                        errorDetails = errorResponse.toString();
                    }
                }
            } catch (IOException ex) {
                errorDetails = "Could not read error stream: " + ex.getMessage();
            }

            if (responseCode == 429) {
                getLogger().severe("HTTP request to " + urlString + " for " + context + " failed with 429 (Too Many Requests). API limit reached. Details: " + errorDetails);
                throw new IOException("API Rate Limit Exceeded (429) for " + urlString);
            } else {
                getLogger().warning("HTTP request to " + urlString + " for " + context + " failed with code: " + responseCode + ". Details: " + errorDetails);
            }
            return null;
        }
    }

    // fetchYahooStockData 方法，與舊版相似，但調用了 fetchHttpContent 並處理 TPEX
    private StockData fetchYahooStockData(String normalizedSymbol) {
        // Clean any color codes from the symbol
        normalizedSymbol = ChatColor.stripColor(normalizedSymbol);

        String yahooQuerySymbol = normalizedSymbol;
        if (normalizedSymbol.endsWith(".TWO")) {
            yahooQuerySymbol = normalizedSymbol.substring(0, normalizedSymbol.length() - 4) + ".OOTC";
            getLogger().info("Mapping internal symbol " + normalizedSymbol + " to " + yahooQuerySymbol + " for Yahoo API query.");
        } else if (normalizedSymbol.endsWith(".OOTC")) {
            yahooQuerySymbol = normalizedSymbol;
        }

        String apiUrl = String.format(YAHOO_API_URL, yahooQuerySymbol);
        try {
            getLogger().info("Attempting to fetch Yahoo data for: " + yahooQuerySymbol + " (Original input: " + normalizedSymbol + ") from URL: " + apiUrl);
            String jsonResponse = fetchHttpContent(apiUrl, StandardCharsets.UTF_8.name(), "Mozilla/5.0", "Yahoo API for " + yahooQuerySymbol);

            if (jsonResponse != null) {
                return parseYahooData(jsonResponse, yahooQuerySymbol);
            } else {
                getLogger().warning("Received null JSON response from Yahoo API for " + yahooQuerySymbol + " (after HTTP fetch attempt).");
            }
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("API Rate Limit Exceeded")) {
                getLogger().severe("YAHOO API RATE LIMIT HIT for " + yahooQuerySymbol + ". Please advise user to wait.");
            } else {
                getLogger().severe("IOException fetching/processing Yahoo stock data for " + yahooQuerySymbol + ": " + e.getMessage());
            }
        } catch (Exception e) {
            getLogger().severe("Unexpected error fetching/processing Yahoo stock data for " + yahooQuerySymbol + ": " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }


    // addStockName 方法，確保鍵值大寫，並在新增或變更時才保存
    private void addStockName(String symbol, String name) {
        if (symbol == null || name == null || symbol.trim().isEmpty() || name.trim().isEmpty()) {
            return;
        }
        String normalizedKey = symbol.toUpperCase();
        String existingName = twStockChineseNames.get(normalizedKey);
        if (existingName == null || !existingName.equals(name)) {
            twStockChineseNames.put(normalizedKey, name);
            getLogger().info("Added/Updated stock name in cache: " + normalizedKey + " - " + name + ". Saving to file.");
            saveStockNamesToFile(); // 保存到檔案
        }
    }

    // 從 ISIN 網站獲取所有股票中文名稱
    private void fetchStockChineseNamesFromWebsites() {
        getLogger().info("Starting fetch of all Taiwanese stock names from ISIN websites...");
        int initialSize = twStockChineseNames.size();

        parseAndStoreStockNamesFromIsin(TWSE_ISIN_URL, ".TW", "BIG5", "TWSE Listed");
        parseAndStoreStockNamesFromIsin(TPEX_ISIN_URL, ".TWO", "BIG5", "TPEX Listed"); // 內部統一存為 .TWO

        int newNamesCount = twStockChineseNames.size() - initialSize;
        if (newNamesCount > 0) {
            getLogger().info("Fetched " + newNamesCount + " new stock names from ISIN websites. Total cached: " + twStockChineseNames.size());
            saveStockNamesToFile(); // 批量獲取後保存一次
        } else {
            getLogger().info("No new stock names fetched from ISIN, or all were already cached. Total cached: " + twStockChineseNames.size());
        }
    }

    // 從 ISIN 網站獲取單個股票的中文名稱 (用於按需獲取)
    private String fetchSingleStockChineseNameFromWeb(String fullStockSymbol) {
        // 確保 fullStockSymbol 已經是標準化後的 (例如 2330.TW, 6446.TWO, 或 GOOG)
        if (!isTaiwanStock(fullStockSymbol)) return null; // 只處理台灣股票

        String stockCode = fullStockSymbol.split("\\.")[0];
        String urlToFetch;
        String targetSuffixForCache; // 用於添加到快取時的後綴
        String context;

        if (fullStockSymbol.endsWith(".TW")) {
            urlToFetch = TWSE_ISIN_URL;
            targetSuffixForCache = ".TW";
            context = "Single TWSE stock " + stockCode;
        } else if (fullStockSymbol.endsWith(".TWO") || fullStockSymbol.endsWith(".OOTC")) { // 兩者都視為查詢 TPEX
            urlToFetch = TPEX_ISIN_URL;
            targetSuffixForCache = ".TWO"; // 我們快取中 TPEX 統一用 .TWO
            context = "Single TPEX stock " + stockCode;
        } else {
            return null; // 不支持的格式
        }

        getLogger().info("Attempting to fetch single stock name for: " + stockCode + " (" + fullStockSymbol + ") from " + urlToFetch);
        try {
            // ISIN 網站使用舊版 User-Agent
            String htmlContent = fetchHttpContent(urlToFetch, "BIG5", "Mozilla/5.0", context);
            if (htmlContent != null) {
                Document doc = Jsoup.parse(htmlContent);
                Elements rows = doc.select("table.h4 tr, table tr"); // Jsoup 選擇器
                for (Element row : rows) {
                    Elements cells = row.select("td");
                    if (cells.size() >= 1) { // ISIN 的代碼和名稱在第一個 td
                        String cellText = cells.get(0).text(); // 例如："2330　台積電"
                        if (cellText.contains("　")) { // 使用全形空格分隔
                            String[] parts = cellText.split("　", 2);
                            if (parts.length == 2 && parts[0].trim().equals(stockCode)) {
                                String name = parts[1].trim();
                                getLogger().info("Found name via single fetch for " + stockCode + targetSuffixForCache + ": " + name);
                                return name; // 返回找到的名稱
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            getLogger().warning("IOException fetching single stock name for " + stockCode + targetSuffixForCache + ": " + e.getMessage());
        } catch (Exception e) { // 其他解析錯誤等
            getLogger().warning("Error parsing single stock name for " + stockCode + targetSuffixForCache + ": " + e.getMessage());
            e.printStackTrace();
        }
        getLogger().info("Could not find name for " + stockCode + targetSuffixForCache + " from ISIN website via single fetch.");
        return null;
    }

    // 解析 ISIN 網頁並存儲股票名稱
    private void parseAndStoreStockNamesFromIsin(String urlString, String suffixForCache, String charset, String context) {
        getLogger().info("Parsing stock names from " + urlString + " for " + context + " with suffix " + suffixForCache);
        try {
            // ISIN 網站使用舊版 User-Agent
            String htmlContent = fetchHttpContent(urlString, charset, "Mozilla/5.0", "Bulk ISIN " + context);
            if (htmlContent == null) {
                getLogger().warning("Failed to fetch content from " + urlString + " for " + context);
                return;
            }

            Document doc = Jsoup.parse(htmlContent);
            // 舊版選擇器：doc.select("tr")。可以嘗試更精確的，如果知道表格結構的話。
            // 例如，如果表格有特定 class "h4": doc.select("table.h4 tr")
            // 為了兼容，先用寬泛的，如果不行再調整。
            Elements rows = doc.select("tr"); // 與舊版 fetchCompanyListWithJsoup 的選擇器一致

            int foundCount = 0;
            int processedRows = 0;
            for (Element row : rows) {
                processedRows++;
                Elements cells = row.select("td");
                // 舊版的邏輯是 if (tds.size() == 7)，這裡我們只關心第一個 td 是否包含 "代碼　名稱"
                if (!cells.isEmpty()) {
                    String firstCellText = cells.first().text();
                    if (firstCellText.contains("　")) {
                        String[] parts = firstCellText.split("　", 2);
                        if (parts.length == 2) {
                            String stockCode = parts[0].trim();
                            String stockName = parts[1].trim();
                            // 舊版檢查了 stockCode.length() == 4，可以保留或修改
                            if (stockCode.matches("^\\d{4,6}$")) { // 匹配4到6位數字的代碼
                                addStockName(stockCode + suffixForCache, stockName);
                                foundCount++;
                                if (foundCount % 200 == 0) { // 每處理200個日誌一次進度
                                    getLogger().info("Processed " + foundCount + " valid entries from " + urlString + " (last: " + stockCode + ")");
                                }
                            }
                        }
                    }
                }
            }
            getLogger().info("Finished parsing " + urlString + " for " + context + ". Processed " + processedRows + " rows, added/updated " + foundCount + " names with suffix " + suffixForCache);

        } catch (IOException e) {
            getLogger().warning("IOException while parsing stock names from " + urlString + " (" + context + "): " + e.getMessage());
        } catch (Exception e) {
            getLogger().severe("Unexpected error parsing stock names from " + urlString + " (" + context + "): " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- 安全獲取 JSON 值的輔助方法 (與舊版類似) ---
    private String getStringValue(JSONObject json, String key, String defaultValue) {
        if (json.containsKey(key) && json.get(key) != null) {
            return json.get(key).toString();
        }
        return defaultValue;
    }

    private double getDoubleValue(JSONObject json, String key, double defaultValue) {
        if (json.containsKey(key) && json.get(key) != null) {
            Object value = json.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            } else if (value instanceof String) {
                try {
                    return Double.parseDouble((String) value);
                } catch (NumberFormatException e) { /* ignore */ }
            }
        }
        return defaultValue;
    }

    private long getLongValue(JSONObject json, String key, long defaultValue) {
        if (json.containsKey(key) && json.get(key) != null) {
            Object value = json.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            } else if (value instanceof String) {
                try {
                    return Long.parseLong((String) value);
                } catch (NumberFormatException e) { /* ignore */ }
            }
        }
        return defaultValue;
    }
}
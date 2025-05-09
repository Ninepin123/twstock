package me.ninepin.twstock;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class MyStockListener implements Listener {
    private final MyStockGUI myStockGUI;
    private final Twstock plugin;

    public MyStockListener(MyStockGUI myStockGUI, Twstock plugin) {
        this.myStockGUI = myStockGUI;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();

        // 檢查是否是我的投資組合界面或投資概覽界面
        if (title.contains("我的投資組合") || title.contains("投資概覽")) {
            event.setCancelled(true); // 立即取消事件
            plugin.getLogger().info("Stock GUI click detected and cancelled");

            if (event.getWhoClicked() instanceof Player) {
                Player player = (Player) event.getWhoClicked();
                int slot = event.getRawSlot();
                ClickType clickType = event.getClick();

                plugin.getLogger().info("Player " + player.getName() + " clicked slot " + slot +
                        " with " + clickType + " in " + title);

                // 只有當點擊的槽位在有效範圍內時才處理
                if (slot >= 0 && slot < event.getInventory().getSize()) {
                    // 確保在主線程執行GUI交互
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        boolean handled;
                        if (title.contains("投資概覽")) {
                            if (slot == 45) {
                                // 返回到投資組合
                                myStockGUI.openGUI(player);
                                plugin.getLogger().info("Player " + player.getName() + " clicked back button in Overview GUI");
                            }
                        } else {
                            // 處理我的投資組合界面的點擊
                            handled = myStockGUI.handleClick(player, slot, event.getInventory(), clickType);
                        }
                    });
                }
                // 如果是點擊庫存外的區域，我們不需要做額外處理，只需取消事件即可
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();

        // 檢查是否是我的投資組合界面或投資概覽界面
        if (title.contains("我的投資組合") || title.contains("投資概覽")) {
            event.setCancelled(true);
            plugin.getLogger().info("Stock GUI drag detected and cancelled");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // 讓 MyStockGUI 處理聊天訊息，如果處理成功則取消事件
        if (myStockGUI.handleChat(player, message)) {
            event.setCancelled(true);
            plugin.getLogger().info("Intercepted chat from " + player.getName() + " for MyStock: " + message);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 清理離線玩家的相關狀態
        Player player = event.getPlayer();
        // 確保玩家退出時保存持股數據
        plugin.getLogger().info("Player " + player.getName() + " left, cleaned up MyStock state");
    }
}
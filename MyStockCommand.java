package me.ninepin.twstock;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MyStockCommand implements CommandExecutor {
    private final Twstock plugin;
    private final MyStockGUI myStockGUI;

    public MyStockCommand(Twstock plugin, MyStockGUI myStockGUI) {
        this.plugin = plugin;
        this.myStockGUI = myStockGUI;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) && (args.length == 0 || !args[0].equalsIgnoreCase("reload"))) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        // Admin command for reloading
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            // Check permission
            if (!sender.hasPermission("twstock.reload")) {
                sender.sendMessage(ChatColor.RED + "你沒有權限執行此指令。");
                return true;
            }

            // Call plugin reload method
            boolean success = plugin.reloadPlugin();

            if (success) {
                sender.sendMessage(ChatColor.GREEN + "TwStock 插件配置已重新載入。");
            } else {
                sender.sendMessage(ChatColor.RED + "重新載入 TwStock 插件配置時發生錯誤。請查看伺服器日誌。");
            }

            return true;
        }

        // Player commands
        Player player = (Player) sender;

        // Check for mystock.use permission
        if (!player.hasPermission("twstock.use")) {
            player.sendMessage(ChatColor.RED + "你沒有權限使用投資組合功能。");
            return true;
        }

        // Optional: Support for admin debugging commands
        if (args.length > 0 && player.hasPermission("twstock.admin")) {
            if (args[0].equalsIgnoreCase("debug")) {
                // Handle debug commands
                return handleDebugCommands(player, args);
            }
        }

        // Default: Open the MyStock GUI
        myStockGUI.openGUI(player);
        return true;
    }

    private boolean handleDebugCommands(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "使用方法: /mystock debug [check|fix]");
            return true;
        }

        String debugAction = args[1].toLowerCase();

        switch (debugAction) {
            case "check":
                // Check player holdings
                player.sendMessage(ChatColor.YELLOW + "正在檢查投資組合數據...");
                int stockCount = plugin.getPlayerStockManager().getPlayerHoldings(player).size();
                player.sendMessage(ChatColor.GREEN + "你的投資組合中有 " + stockCount + " 支股票。");
                break;

            case "fix":
                // Force reload for this player
                player.sendMessage(ChatColor.YELLOW + "正在嘗試修復投資組合數據...");
                plugin.getPlayerStockManager().reloadHoldings();
                player.sendMessage(ChatColor.GREEN + "投資組合數據已重新載入。");
                break;

            default:
                player.sendMessage(ChatColor.RED + "未知的調試指令: " + debugAction);
                return false;
        }

        return true;
    }
}
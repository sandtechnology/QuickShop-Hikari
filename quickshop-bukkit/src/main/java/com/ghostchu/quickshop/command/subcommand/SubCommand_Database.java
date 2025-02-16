package com.ghostchu.quickshop.command.subcommand;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.command.CommandHandler;
import com.ghostchu.quickshop.database.SimpleDatabaseHelperV2;
import org.apache.commons.lang3.ArrayUtils;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public class SubCommand_Database implements CommandHandler<CommandSender> {
    private final QuickShop plugin;

    public SubCommand_Database(QuickShop plugin) {
        this.plugin = plugin;
    }

    /**
     * Calling while command executed by specified sender
     *
     * @param sender       The command sender but will automatically convert to specified instance
     * @param commandLabel The command prefix (/qs = qs, /shop = shop)
     * @param cmdArg       The arguments (/qs create stone will receive stone)
     */
    @Override
    public void onCommand(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] cmdArg) {
        if (cmdArg.length < 1) {
            plugin.text().of(sender, "bad-command-usage-detailed", "trim").send();
            return;
        }
        //noinspection SwitchStatementWithTooFewBranches
        switch (cmdArg[0]) {
            case "trim" -> handleTrim(sender, ArrayUtils.remove(cmdArg, 0));
            case "purgelogs" -> purgeLogs(sender, ArrayUtils.remove(cmdArg, 0));
            default -> plugin.text().of(sender, "bad-command-usage-detailed", "trim").send();
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] cmdArg) {
        if (cmdArg.length < 2) {
            return List.of("trim");
        }
        return Collections.emptyList();
    }
//
//    private void handleStatus(@NotNull CommandSender sender) {
//        DatabaseStatusHolder holder = plugin.getDatabaseMaintenanceWatcher().getResult();
//        if (holder == null) {
//            plugin.text().of(sender, "database.scanning-sync").send();
//            plugin.getDatabaseMaintenanceWatcher().runTaskAsynchronously(plugin);
//            return;
//        }
//        Component statusComponent = switch (holder.getStatus()) {
//            case GOOD -> plugin.text().of(sender, "database.status-good").forLocale();
//            case MAINTENANCE_REQUIRED -> plugin.text().of(sender, "database.status-bad").forLocale();
//        };
//        ChatSheetPrinter printer = new ChatSheetPrinter(sender);
//        printer.printHeader();
//        printer.printLine(plugin.text().of(sender, "database.status", statusComponent).forLocale());
//        printer.printLine(plugin.text().of(sender, "database.isolated").forLocale());
//        printer.printLine(plugin.text().of(sender, "database.isolated-data-ids", holder.getDataIds().getIsolated().size()).forLocale());
//        printer.printLine(plugin.text().of(sender, "database.isolated-shop-ids", holder.getShopIds().getIsolated().size()).forLocale());
//        if (holder.getStatus() == DatabaseStatusHolder.Status.MAINTENANCE_REQUIRED) {
//            printer.printLine(plugin.text().of(sender, "database.suggestion.trim").forLocale());
//        }
//        printer.printFooter();
//    }

    private void handleTrim(@NotNull CommandSender sender, @NotNull String[] cmdArg) {
        if (cmdArg.length < 1 || !"confirm".equalsIgnoreCase(cmdArg[0])) {
            plugin.text().of(sender, "database.trim-warning").send();
            return;
        }
        plugin.text().of(sender, "database.trim-start").send();
        SimpleDatabaseHelperV2 databaseHelper = (SimpleDatabaseHelperV2) plugin.getDatabaseHelper();
        databaseHelper.purgeIsolated().whenComplete((data, err) -> plugin.text().of(sender, "database.trim-complete", data).send());
    }

    private void purgeLogs(@NotNull CommandSender sender, @NotNull String[] cmdArg) {
        // TODO: Only purge before x days
        if (cmdArg.length < 1) {
            plugin.text().of(sender, "command-incorrect", "/qs database purgelogs <before-days>").send();
            return;
        }
        if (cmdArg.length < 2 || !cmdArg[1].equalsIgnoreCase("confirm")) {
            plugin.text().of(sender, "database.purge-warning").send();
            return;
        }
        try {
            int days = Integer.parseInt(cmdArg[0]);
            Calendar calendar = Calendar.getInstance();
            calendar.add(Calendar.DATE, days);
            plugin.text().of(sender, "database.purge-task-created").send();
            SimpleDatabaseHelperV2 databaseHelper = (SimpleDatabaseHelperV2) plugin.getDatabaseHelper();
            databaseHelper.purgeLogsRecords(calendar.getTime()).whenComplete((r, e) -> {
                if (e != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to execute database purge.", e);
                    plugin.text().of(sender, "database.purge-done-with-error", r).send();
                } else {
                    if (r == -1) {
                        plugin.getLogger().log(Level.WARNING, "Failed to execute database purge, check the exception above.");
                        plugin.text().of(sender, "database.purge-done-with-error", r).send();
                    } else {
                        plugin.text().of(sender, "database.purge-done-with-line", r).send();
                    }
                }
            });
            // Then we need also purge the isolated data after purge the logs.
            plugin.text().of(sender, "database.trim-start").send();
            databaseHelper.purgeIsolated().whenComplete((data, err) -> plugin.text().of(sender, "database.trim-complete", data).send());
        } catch (NumberFormatException e) {
            plugin.text().of(sender, "not-a-number", cmdArg[0]).send();
        }
    }
}

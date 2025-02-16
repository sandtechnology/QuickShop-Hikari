package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.database.bean.DataRecord;
import com.ghostchu.quickshop.api.database.bean.InfoRecord;
import com.ghostchu.quickshop.api.database.bean.ShopRecord;
import com.ghostchu.quickshop.api.economy.Benefit;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopType;
import com.ghostchu.quickshop.common.util.Timer;
import com.ghostchu.quickshop.economy.SimpleBenefit;
import com.ghostchu.quickshop.util.JsonUtil;
import com.ghostchu.quickshop.util.MsgUtil;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.logger.Log;
import com.google.common.reflect.TypeToken;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.enginehub.squirrelid.Profile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A class allow plugin load shops fast and simply.
 */
public class ShopLoader {

    private final QuickShop plugin;
    /* This may contains broken shop, must use null check before load it. */
    private int errors;

    /**
     * The shop load allow plugin load shops fast and simply.
     *
     * @param plugin Plugin main class
     */
    public ShopLoader(@NotNull QuickShop plugin) {
        this.plugin = plugin;
    }

    public void loadShops() {
        loadShops(null);
    }

    /**
     * Load all shops in the specified world
     *
     * @param worldName The world name, null if load all shops
     */
    public void loadShops(@Nullable String worldName) {
        List<Shop> pendingLoading = new CopyOnWriteArrayList<>();
        boolean deleteCorruptShops = plugin.getConfig().getBoolean("debug.delete-corrupt-shops", false);
        plugin.getLogger().info("Loading shops from database...");
        Timer dbFetchTimer = new Timer(true);
        List<ShopRecord> records = plugin.getDatabaseHelper().listShops(deleteCorruptShops);
        plugin.getLogger().info("Used " + dbFetchTimer.stopAndGetTimePassed() + "ms to fetch " + records.size() + " shops from database.");
        plugin.getLogger().info("Loading shops into memory...");
        Timer shopTotalTimer = new Timer(true);
        AtomicInteger successCounter = new AtomicInteger(0);
        for (ShopRecord shopRecord : records) {
            Timer singleShopLoadingTimer = new Timer(true);
            InfoRecord infoRecord = shopRecord.getInfoRecord();
            DataRecord dataRecord = shopRecord.getDataRecord();
            // World check
            if (worldName != null) {
                if (!worldName.equals(infoRecord.getWorld())) {
                    Log.timing("Single shop loading: worldName skipped", singleShopLoadingTimer);
                    continue;
                }
            }
            if (dataRecord.getInventorySymbolLink() != null
                    && !dataRecord.getInventoryWrapper().isEmpty()
                    && plugin.getInventoryWrapperRegistry().get(dataRecord.getInventoryWrapper()) == null) {
                Log.debug("InventoryWrapperProvider not exists! Shop won't be loaded!");
                Log.timing("Single shop loading: InventoryWrapperProvider skipped", singleShopLoadingTimer);
                continue;
            }
            String world = infoRecord.getWorld();
            // Check if world loaded.
            if (Bukkit.getWorld(world) == null) {
                Log.timing("Single shop loading: Bukkit world not exists", singleShopLoadingTimer);
                continue;
            }
            int x = infoRecord.getX();
            int y = infoRecord.getY();
            int z = infoRecord.getZ();
            Shop shop;
            DataRawDatabaseInfo rawInfo = new DataRawDatabaseInfo(shopRecord.getDataRecord());
            try {
                shop = new ContainerShop(plugin,
                        infoRecord.getShopId(),
                        new Location(Bukkit.getWorld(world), x, y, z),
                        rawInfo.getPrice(),
                        rawInfo.getItem(),
                        rawInfo.getOwner(),
                        rawInfo.isUnlimited(),
                        rawInfo.getType(),
                        rawInfo.getExtra(),
                        rawInfo.getCurrency(),
                        rawInfo.isHologram(),
                        rawInfo.getTaxAccount(),
                        rawInfo.getInvWrapper(),
                        rawInfo.getInvSymbolLink(),
                        rawInfo.getName(),
                        rawInfo.getPermissions(),
                        rawInfo.getBenefits());
            } catch (Exception e) {
                if (e instanceof IllegalStateException) {
                    plugin.getLogger().log(Level.WARNING, "Failed to load the shop, skipping...", e);
                }
                exceptionHandler(e, null);
                if (deleteCorruptShops && plugin.getShopBackupUtil().isBreakingAllowed()) {
                    plugin.getLogger().warning(MsgUtil.fillArgs("Deleting shop at world={0} x={1} y={2} z={3} caused by corrupted.", world, String.valueOf(x), String.valueOf(y), String.valueOf(z)));
                    plugin.getDatabaseHelper().removeShopMap(world, x, y, z);
                }
                Log.timing("Single shop loading: Shop loading exception", singleShopLoadingTimer);
                continue;
            }
            Location shopLocation = shop.getLocation();
            // Dirty check
            if (rawInfo.isNeedUpdate()) {
                shop.setDirty();
            }
            // Null check
            if (shopNullCheck(shop)) {
                Log.timing("Single shop loading: Shop null check failed", singleShopLoadingTimer);
                continue;
            }
            // Load to RAM
            plugin.getShopManager().loadShop(shopLocation.getWorld().getName(), shop);
            if (Util.isLoaded(shopLocation)) {
                // Load to World
                if (!Util.canBeShop(shopLocation.getBlock())) {
                    plugin.getShopManager().removeShop(shop); // Remove from Mem
                } else {
                    pendingLoading.add(shop);
                }
            }
            successCounter.incrementAndGet();
        }

        plugin.getLogger().info("Done. Used " + shopTotalTimer.stopAndGetTimePassed() + "ms to load " + successCounter.get() + " shops into memory.");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Shop shop : pendingLoading) {
                try {
                    shop.onLoad();
                } catch (Exception exception) {
                    exceptionHandler(exception, shop.getLocation());
                }
            }
            Log.debug("All pending shops now loaded (schedule).");
        }, 1);
    }

    private void exceptionHandler(@NotNull Exception ex, @Nullable Location shopLocation) {
        errors++;
        Logger logger = plugin.getLogger();
        logger.warning("##########FAILED TO LOAD SHOP##########");
        logger.warning("  >> Error Info:");
        String err = ex.getMessage();
        if (err == null) {
            err = "null";
        }
        logger.warning(err);
        logger.warning("  >> Error Trace");
        ex.printStackTrace();
        logger.warning("  >> Target Location Info");
        logger.warning("Location: " + ((shopLocation == null) ? "NULL" : shopLocation.toString()));
        logger.warning(
                "Block: " + ((shopLocation == null) ? "NULL" : shopLocation.getBlock().getType().name()));
        logger.warning("#######################################");
        if (errors > 10) {
            logger.severe(
                    "QuickShop detected too many errors when loading shops, you should backup your shop database and ask the developer for help");
        }
    }

    @SuppressWarnings("ConstantConditions")
    private boolean shopNullCheck(@Nullable Shop shop) {
        if (shop == null) {
            Log.debug("Shop object is null");
            return true;
        }
        if (shop.getItem() == null) {
            Log.debug("Shop itemStack is null");
            return true;
        }
        if (shop.getItem().getType() == Material.AIR) {
            Log.debug("Shop itemStack type can't be AIR");
            return true;
        }
        if (shop.getLocation() == null) {
            Log.debug("Shop location is null");
            return true;
        }
        if (shop.getOwner() == null) {
            Log.debug("Shop owner is null");
            return true;
        }
        Profile shopOwnerProfile = plugin.getPlayerFinder().find(shop.getOwner());
        if (shopOwnerProfile == null || shopOwnerProfile.getName() == null) {
            Log.debug("Shop owner not exist on this server, did you have reset the playerdata?");
        }
        return false;
    }

    @Getter
    @Setter
    public static class DataRawDatabaseInfo {
        private UUID owner;
        private String name;
        private ShopType type;
        private String currency;
        private double price;
        private boolean unlimited;
        private boolean hologram;
        private UUID taxAccount;
        private Map<UUID, String> permissions;
        private YamlConfiguration extra;
        private String invWrapper;
        private String invSymbolLink;
        private long createTime;
        private ItemStack item;
        private boolean needUpdate = false;

        private Benefit benefits;


        @SuppressWarnings("UnstableApiUsage")
        DataRawDatabaseInfo(@NotNull DataRecord dataRecord) {
            this.owner = dataRecord.getOwner();
            this.price = dataRecord.getPrice();
            this.type = ShopType.fromID(dataRecord.getType());
            this.unlimited = dataRecord.isUnlimited();
            String extraStr = dataRecord.getExtra();
            this.name = dataRecord.getName();
            //handle old shops
            if (extraStr == null) {
                extraStr = "";
                needUpdate = true;
            }
            this.currency = dataRecord.getCurrency();
            this.hologram = dataRecord.isHologram();
            this.taxAccount = dataRecord.getTaxAccount();
            this.invSymbolLink = dataRecord.getInventorySymbolLink();
            this.invWrapper = dataRecord.getInventoryWrapper();
            this.benefits = SimpleBenefit.deserialize(dataRecord.getBenefit());
            String permissionJson = dataRecord.getPermissions();
            if (!StringUtils.isEmpty(permissionJson) && MsgUtil.isJson(permissionJson)) {
                Type typeToken = new TypeToken<Map<UUID, String>>() {
                }.getType();
                this.permissions = new HashMap<>(JsonUtil.getGson().fromJson(permissionJson, typeToken));
            } else {
                this.permissions = new HashMap<>();
            }
            this.item = deserializeItem(dataRecord.getItem());
            this.extra = deserializeExtra(extraStr);
        }

        private @Nullable ItemStack deserializeItem(@NotNull String itemConfig) {
            try {
                return Util.deserialize(itemConfig);
            } catch (InvalidConfigurationException e) {
                QuickShop.getInstance().getLogger().log(Level.WARNING, "Failed load shop data, because target config can't deserialize the ItemStack", e);
                Log.debug("Failed to load data to the ItemStack: " + itemConfig);
                return null;
            }
        }

        private @NotNull YamlConfiguration deserializeExtra(@NotNull String extraString) {
            YamlConfiguration yamlConfiguration = new YamlConfiguration();
            try {
                yamlConfiguration.loadFromString(extraString);
            } catch (InvalidConfigurationException e) {
                yamlConfiguration = new YamlConfiguration();
                needUpdate = true;
            }
            return yamlConfiguration;
        }


        @Override
        public String toString() {
            return JsonUtil.getGson().toJson(this);
        }
    }

    @Getter
    @Setter
    public static class ShopDatabaseInfo {
        private int shopId;
        private int dataId;

        ShopDatabaseInfo(ResultSet origin) {
            try {
                this.shopId = origin.getInt("id");
                this.dataId = origin.getInt("data");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    @Getter
    @Setter
    public static class ShopMappingInfo {
        private int shopId;
        private String world;
        private int x;
        private int y;
        private int z;

        ShopMappingInfo(ResultSet origin) {
            try {
                this.shopId = origin.getInt("shop");
                this.x = origin.getInt("x");
                this.y = origin.getInt("y");
                this.z = origin.getInt("z");
                this.world = origin.getString("world");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

}

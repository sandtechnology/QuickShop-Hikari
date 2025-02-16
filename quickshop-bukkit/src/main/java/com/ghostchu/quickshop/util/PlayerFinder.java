package com.ghostchu.quickshop.util;

import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.util.logger.Log;
import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.enginehub.squirrelid.Profile;
import org.enginehub.squirrelid.cache.HashMapCache;
import org.enginehub.squirrelid.cache.ProfileCache;
import org.enginehub.squirrelid.resolver.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player finder for finding player by name or unique id.
 *
 * @author Ghost_chu
 * @since Hikari-1.0.3, refactored
 */
public class PlayerFinder {
    private final ProfileService resolver;
    private final ProfileCache cache;

    private boolean forceOnlineMode = false;

    public PlayerFinder() {
        ProfileCache cache = new HashMapCache(); // Memory cache
//        try {
//            cache = new SQLiteCache(new File(Util.getCacheFolder(), "player_mapping.db"));
//        } catch (Exception e) {
//            Log.debug("Failed to initialize player mapping cache database, use HashMapCache instead.");
//        }
        List<ProfileService> services = new ArrayList<>();
        if (PaperLib.isPaper() && !Util.parsePackageProperly("forceSpigot").asBoolean()) {
            services.add(PaperPlayerService.getInstance());
        } else {
            Log.debug("Fallback to use general CombinedProfileService for player lookup.");
            services.add(new CacheForwardingService(HttpRepositoryService.forMinecraft(), cache));
        }
        this.resolver = new CombinedProfileService(services);
        if (Util.parsePackageProperly("forceSpigot").asBoolean()) {
            forceOnlineMode = true;
        }
        this.cache = cache;
    }

    public boolean contains(@NotNull UUID uuid) {
        return cache.getIfPresent(uuid) != null;
    }

    @Nullable
    public Profile find(@NotNull String name) {
        // Fallback to UUID lookup if name is UUID.
        if (CommonUtil.isUUID(name)) {
            return find(UUID.fromString(name));
        }
//        if (Bukkit.getServer().getOnlineMode() || forceOnlineMode) {
//            return findOnline(name);
//        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(name);
        return new Profile(offlinePlayer.getUniqueId(), name);
    }

    @Nullable
    public Profile find(@NotNull UUID uuid) {
//        if (Bukkit.getServer().getOnlineMode() || forceOnlineMode) {
//            return findOnline(uuid);
//        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        String name = offlinePlayer.getName();
        if (name == null) {
            return null;
        }
        return new Profile(offlinePlayer.getUniqueId(), name);
    }

    @Nullable
    private Profile findOnline(@NotNull UUID uuid) {
        try {
            return this.resolver.findByUuid(uuid);
        } catch (IOException e) {
            Log.debug("Failed to find player profile: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Log.debug("Failed to find player profile: " + e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Nullable
    private Profile findOnline(@NotNull String name) {
        try {
            return this.resolver.findByName(name);
        } catch (IOException e) {
            Log.debug("Failed to find player profile: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Log.debug("Failed to find player profile: " + e.getMessage());
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void flash(@NotNull UUID uuid, @NotNull String name) {
        this.cache.put(new Profile(uuid, name));
    }
}

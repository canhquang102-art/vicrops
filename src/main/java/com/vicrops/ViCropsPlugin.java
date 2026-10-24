package com.vicrops;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ViCrops - them cay trong moi bang vat pham vanilla.
 * Dung khoi cay vanilla de hien thi nen nguoi choi Java va Bedrock (Geyser) deu thay dung.
 */
public final class ViCropsPlugin extends JavaPlugin implements Listener {

    private record CropDef(String id, Material plantItem, Material block, Material dropItem,
                           int dropMin, int dropMax, int seedMin, int seedMax) {
    }

    /** vat pham de trong -> dinh nghia cay */
    private final Map<Material, CropDef> byItem = new EnumMap<>(Material.class);
    /** id -> dinh nghia cay */
    private final Map<String, CropDef> byId = new HashMap<>();
    /** vi tri cay dang theo doi: "x;y;z;world" -> id cay */
    private final Map<String, String> tracked = new HashMap<>();

    private File dataFile;
    private boolean dirty = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadCrops();
        loadData();
        getServer().getPluginManager().registerEvents(this, this);

        // luu du lieu moi 5 phut neu co thay doi, va don cay khong con ton tai
        getServer().getScheduler().runTaskTimer(this, () -> {
            cleanup();
            if (dirty) {
                saveData();
            }
        }, 6000L, 6000L);

        getLogger().info("ViCrops da bat: " + byId.size() + " loai cay, " + tracked.size() + " cay dang theo doi.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ---------------------------------------------------------------- cau hinh

    private void loadCrops() {
        byItem.clear();
        byId.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("crops");
        if (sec == null) {
            getLogger().warning("Khong tim thay muc 'crops' trong config.yml");
            return;
        }
        for (String id : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(id);
            if (c == null) {
                continue;
            }
            Material plant = Material.matchMaterial(c.getString("plant-item", ""));
            Material block = Material.matchMaterial(c.getString("block", ""));
            Material drop = Material.matchMaterial(c.getString("drop-item", ""));
            if (plant == null || block == null || drop == null) {
                getLogger().warning("Cay '" + id + "' bi bo qua: ten vat pham/khoi khong hop le.");
                continue;
            }
            if (!block.isBlock() || !(block.createBlockData() instanceof Ageable)) {
                getLogger().warning("Cay '" + id + "' bi bo qua: block " + block + " khong phai loai cay lon len duoc.");
                continue;
            }
            int dropMin = Math.max(0, c.getInt("drop-min", 1));
            int dropMax = Math.max(dropMin, c.getInt("drop-max", dropMin));
            int seedMin = Math.max(0, c.getInt("seed-min", 0));
            int seedMax = Math.max(seedMin, c.getInt("seed-max", seedMin));
            CropDef def = new CropDef(id, plant, block, drop, dropMin, dropMax, seedMin, seedMax);
            byId.put(id, def);
            byItem.put(plant, def);
        }
    }

    // ---------------------------------------------------------------- du lieu

    private static String key(Block b) {
        return b.getX() + ";" + b.getY() + ";" + b.getZ() + ";" + b.getWorld().getName();
    }

    private void loadData() {
        tracked.clear();
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        for (String line : yml.getStringList("crops")) {
            int i = line.indexOf('=');
            if (i > 0) {
                tracked.put(line.substring(i + 1), line.substring(0, i));
            }
        }
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : tracked.entrySet()) {
            lines.add(e.getValue() + "=" + e.getKey());
        }
        yml.set("crops", lines);
        try {
            yml.save(dataFile);
            dirty = false;
        } catch (IOException ex) {
            getLogger().warning("Khong luu duoc data.yml: " + ex.getMessage());
        }
    }

    /** Xoa nhung cay khong con (bi nuoc cuon, giam nat, ...) trong cac chunk dang tai. */
    private void cleanup() {
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, String> e : tracked.entrySet()) {
            String[] p = e.getKey().split(";", 4);
            if (p.length < 4) {
                remove.add(e.getKey());
                continue;
            }
            World w = getServer().getWorld(p[3]);
            if (w == null) {
                continue;
            }
            int x, y, z;
            try {
                x = Integer.parseInt(p[0]);
                y = Integer.parseInt(p[1]);
                z = Integer.parseInt(p[2]);
            } catch (NumberFormatException ex) {
                remove.add(e.getKey());
                continue;
            }
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            CropDef def = byId.get(e.getValue());
            if (def == null || w.getBlockAt(x, y, z).getType() != def.block()) {
                remove.add(e.getKey());
            }
        }
        if (!remove.isEmpty()) {
            remove.forEach(tracked::remove);
            dirty = true;
        }
    }

    // ---------------------------------------------------------------- su kien

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (e.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        Block clicked = e.getClickedBlock();
        ItemStack item = e.getItem();
        if (clicked == null || item == null || e.getBlockFace() != BlockFace.UP) {
            return;
        }
        if (clicked.getType() != Material.FARMLAND) {
            return;
        }
        CropDef def = byItem.get(item.getType());
        if (def == null) {
            return;
        }
        Block above = clicked.getRelative(BlockFace.UP);
        if (!above.getType().isAir()) {
            return;
        }

        Player player = e.getPlayer();
        BlockState replaced = above.getState();
        above.setType(def.block(), true);

        // cho cac plugin bao ve (WorldGuard, ...) co co hoi huy
        BlockPlaceEvent place = new BlockPlaceEvent(above, replaced, clicked, item, player, true, EquipmentSlot.HAND);
        getServer().getPluginManager().callEvent(place);
        if (place.isCancelled() || !place.canBuild()) {
            replaced.update(true, false);
            return;
        }

        e.setCancelled(true); // khong de an tao, an trung, ...
        tracked.put(key(above), def.id());
        dirty = true;
        player.swingMainHand();
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        String k = key(block);
        String id = tracked.get(k);
        if (id == null) {
            return;
        }
        CropDef def = byId.get(id);
        tracked.remove(k);
        dirty = true;
        if (def == null || block.getType() != def.block()) {
            return; // du lieu cu, de game xu ly binh thuong
        }

        e.setDropItems(false);
        if (e.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }

        boolean mature = false;
        if (block.getBlockData() instanceof Ageable age) {
            mature = age.getAge() >= age.getMaximumAge();
        }

        var loc = block.getLocation().add(0.5, 0.5, 0.5);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (mature) {
            int amount = rnd.nextInt(def.dropMin(), def.dropMax() + 1);
            if (amount > 0) {
                block.getWorld().dropItemNaturally(loc, new ItemStack(def.dropItem(), amount));
            }
            int seeds = rnd.nextInt(def.seedMin(), def.seedMax() + 1);
            if (seeds > 0) {
                block.getWorld().dropItemNaturally(loc, new ItemStack(def.plantItem(), seeds));
            }
        } else {
            // cay chua chin: tra lai 1 vat pham de trong lai
            block.getWorld().dropItemNaturally(loc, new ItemStack(def.plantItem(), 1));
        }
    }

    // ---------------------------------------------------------------- lenh

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadCrops();
            sender.sendMessage("ViCrops: da tai lai config (" + byId.size() + " loai cay).");
            return true;
        }
        sender.sendMessage("Dung: /vicrops reload");
        return true;
    }
}

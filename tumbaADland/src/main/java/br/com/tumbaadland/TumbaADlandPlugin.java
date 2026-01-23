package br.com.tumbaadland;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.TileState;
import org.bukkit.block.data.Rotatable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class TumbaADlandPlugin extends JavaPlugin implements Listener {
    private NamespacedKey tombOwnerKey;
    private NamespacedKey tombExpireKey;
    private final Map<String, List<BukkitTask>> tombTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> lastTomb = new ConcurrentHashMap<>();
    private final Map<String, TombData> tombs = new ConcurrentHashMap<>();
    private File tombsFile;
    private FileConfiguration tombsConfig;

    private static class TombData {
        private final UUID owner;
        private final Location location;
        private final String deathMessage;
        private final long expiresAt;
        private final int inventorySize;
        private List<ItemStack> items;
        private int storedExp;
        private boolean expClaimed;
        private List<UUID> hologramIds;

        private TombData(UUID owner, Location location, String deathMessage, long expiresAt,
                         int inventorySize, List<ItemStack> items, int storedExp) {
            this.owner = owner;
            this.location = location;
            this.deathMessage = deathMessage;
            this.expiresAt = expiresAt;
            this.inventorySize = inventorySize;
            this.items = items;
            this.storedExp = storedExp;
        }
    }

    private static class TombInventoryHolder implements InventoryHolder {
        private final TombData tomb;
        private Inventory inventory;

        private TombInventoryHolder(TombData tomb) {
            this.tomb = tomb;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    @Override
    public void onEnable() {
        tombOwnerKey = new NamespacedKey(this, "tomb_owner");
        tombExpireKey = new NamespacedKey(this, "tomb_expires");
        saveDefaultConfig();
        setupTombStorage();
        loadTombs();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("tumba") != null) {
            getCommand("tumba").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Somente jogadores.");
                    return true;
                }
                Location loc = lastTomb.get(player.getUniqueId());
                if (loc == null) {
                    player.sendMessage(colorize(getConfig().getString("messages.no-tomb")));
                    return true;
                }
                String msg = getConfig().getString("messages.coords");
                msg = msg.replace("%world%", loc.getWorld().getName())
                        .replace("%x%", String.valueOf(loc.getBlockX()))
                        .replace("%y%", String.valueOf(loc.getBlockY()))
                        .replace("%z%", String.valueOf(loc.getBlockZ()));
                player.sendMessage(colorize(msg));
                return true;
            });
        }
        if (getCommand("tumbaadmin") != null) {
            getCommand("tumbaadmin").setExecutor((sender, command, label, args) -> {
                if (!hasAdminPermission(sender)) {
                    sender.sendMessage("Sem permissao.");
                    return true;
                }
                if (args.length == 0 || "list".equalsIgnoreCase(args[0])) {
                    listTombs(sender);
                    return true;
                }
                if ("remove".equalsIgnoreCase(args[0]) && args.length >= 2) {
                    removeTombForPlayer(sender, args[1]);
                    return true;
                }
                if ("reload".equalsIgnoreCase(args[0])) {
                    reloadConfig();
                    refreshTombsAfterReload();
                    sender.sendMessage("Config recarregada.");
                    return true;
                }
                sender.sendMessage("Uso: /tumbaadmin list | remove <jogador> | reload");
                return true;
            });
        }
        getLogger().info("TumbaADland carregado.");
    }

    @Override
    public void onDisable() {
        for (TombData tomb : tombs.values()) {
            removeHologram(tomb);
        }
        tombTasks.values().forEach(tasks -> tasks.forEach(BukkitTask::cancel));
        tombTasks.clear();
        saveTombFile();
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        List<ItemStack> drops = event.getDrops();

        int itemCount = countItems(drops);
        int storedExp = player.getTotalExperience();
        if (itemCount == 0 && storedExp == 0) {
            return;
        }

        Location base = event.getEntity().getLocation();
        Location place = findPlace(base);
        if (place == null) {
            return; // Sem espaco, deixa dropar normalmente.
        }

        Block placed = placeHead(place, player);
        if (placed == null) {
            return; // Falha ao criar, deixa dropar normalmente.
        }

        List<ItemStack> storedItems = new ArrayList<>();
        for (ItemStack item : drops) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                storedItems.add(item.clone());
            }
        }
        drops.clear();
        event.setDroppedExp(0);
        event.setKeepLevel(false);

        long timeoutMinutes = getConfig().getLong("tomb.timeout-minutes");
        long expiresAt = System.currentTimeMillis() + (timeoutMinutes * 60L * 1000L);
        int inventorySize = (itemCount > 27) ? 54 : 27;
        String deathMessage = event.getDeathMessage() != null ? event.getDeathMessage() : "Morreu.";
        deathMessage = translateDeathMessage(deathMessage);
        TombData tomb = new TombData(player.getUniqueId(), placed.getLocation(), deathMessage,
                expiresAt, inventorySize, storedItems, storedExp);
        tagTomb(placed, tomb.owner, tomb.expiresAt);
        spawnHologram(tomb);
        tombs.put(locKey(placed.getLocation()), tomb);
        saveTombToDisk(tomb);
        scheduleTombRemoval(tomb);
        lastTomb.put(player.getUniqueId(), placed.getLocation());
        player.sendMessage(colorize(getConfig().getString("messages.stored")));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof TombInventoryHolder holder)) {
            return;
        }

        TombData tomb = holder.tomb;
        if (tomb == null) {
            return;
        }

        tomb.items = filterItems(inv.getContents());
        saveTombToDisk(tomb);
        updateHologram(tomb);

        if (tomb.items.isEmpty()) {
            removeTomb(tomb);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isHeadBlock(block)) {
            return;
        }
        TombData tomb = tombs.get(locKey(block.getLocation()));
        if (tomb == null || !isTombBlock(block)) {
            return;
        }
        UUID owner = tomb.owner;
        if (event.getPlayer() != null && getConfig().getBoolean("tomb.protect")) {
            boolean allowOwnerBreak = getConfig().getBoolean("tomb.allow-owner-break");
            if (!owner.equals(event.getPlayer().getUniqueId()) || !allowOwnerBreak) {
                if (hasAdminPermission(event.getPlayer())) {
                    dropAndRemoveTomb(tomb);
                    event.setCancelled(true);
                    return;
                }
                event.setCancelled(true);
                event.getPlayer().sendMessage(colorize(getConfig().getString("messages.denied")));
                return;
            }
        }
        dropAndRemoveTomb(tomb);
        event.setCancelled(true);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !isHeadBlock(block) || !isTombBlock(block)) {
            return;
        }
        TombData tomb = tombs.get(locKey(block.getLocation()));
        if (tomb == null) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (getConfig().getBoolean("tomb.protect") && !tomb.owner.equals(player.getUniqueId())) {
            if (!hasAdminPermission(player)) {
                player.sendMessage(colorize(getConfig().getString("messages.denied")));
                return;
            }
        }
        Inventory inv = createTombInventory(tomb);
        if (!tomb.expClaimed && tomb.owner.equals(player.getUniqueId())) {
            giveBackExperience(player, tomb);
        }
        player.openInventory(inv);
    }

    private int countItems(Collection<ItemStack> items) {
        int count = 0;
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                count++;
            }
        }
        return count;
    }

    private Location findPlace(Location base) {
        Location voidSafe = resolveVoidLocation(base);
        if (voidSafe != null) {
            return voidSafe;
        }
        Location loc = base.clone();
        if (isSafeReplaceable(loc.getBlock())) {
            return loc;
        }
        Location above = base.clone().add(0, 1, 0);
        if (isSafeReplaceable(above.getBlock())) {
            return above;
        }
        return null;
    }

    private boolean isReplaceable(Block block) {
        return block.getType().isAir() || block.isReplaceable();
    }

    private boolean isSafeReplaceable(Block block) {
        Material type = block.getType();
        if (!isReplaceable(block)) {
            return false;
        }
        if (block.isLiquid()) {
            return false;
        }
        if (Tag.LEAVES.isTagged(type) || Tag.SAPLINGS.isTagged(type) || Tag.FLOWERS.isTagged(type)
                || Tag.CROPS.isTagged(type)) {
            return false;
        }
        return type != Material.GRASS && type != Material.TALL_GRASS;
    }

    private Block placeHead(Location location, Player owner) {
        Block block = location.getBlock();
        if (!isReplaceable(block)) {
            return null;
        }
        block.setType(Material.PLAYER_HEAD, false);
        if (block.getBlockData() instanceof Rotatable rotatable) {
            rotatable.setRotation(BlockFace.NORTH);
            block.setBlockData(rotatable, false);
        }
        if (block.getState() instanceof Skull skull) {
            skull.setOwningPlayer(owner);
            skull.update(false, false);
        }
        return block;
    }

    private void tagTomb(Block block, UUID owner, long expiresAt) {
        if (!(block.getState() instanceof TileState state)) {
            return;
        }
        setTombData(state, owner, expiresAt);
    }

    private void setTombData(TileState state, UUID owner, long expiresAt) {
        PersistentDataContainer data = state.getPersistentDataContainer();
        data.set(tombOwnerKey, PersistentDataType.STRING, owner.toString());
        data.set(tombExpireKey, PersistentDataType.LONG, expiresAt);
        state.update(false, false);
    }

    private boolean isTombBlock(Block block) {
        if (!(block.getState() instanceof TileState state)) {
            return false;
        }
        return state.getPersistentDataContainer().has(tombOwnerKey, PersistentDataType.STRING);
    }

    private void removeTomb(TombData tomb) {
        if (tomb == null) {
            return;
        }
        Location location = tomb.location;
        cleanupTask(location);
        clearLastTomb(tomb.owner, location);
        removeHologram(tomb);
        Block block = location.getBlock();
        if (isHeadBlock(block)) {
            block.setType(Material.AIR, false);
        }
        tombs.remove(locKey(location));
        removeTombFromDisk(tomb);
    }

    private void dropAndRemoveTomb(TombData tomb) {
        if (tomb == null) {
            return;
        }
        World world = tomb.location.getWorld();
        if (world != null) {
            for (ItemStack item : tomb.items) {
                if (item == null || item.getType() == Material.AIR) {
                    continue;
                }
                world.dropItemNaturally(tomb.location, item);
            }
        }
        tomb.items = List.of();
        removeTomb(tomb);
    }

    private void scheduleTombRemoval(TombData tomb) {
        Location loc = tomb.location;
        String key = locKey(loc);
        cleanupTaskKey(key);
        List<BukkitTask> tasks = new ArrayList<>();
        long remainingMillis = Math.max(0L, tomb.expiresAt - System.currentTimeMillis());
        if (remainingMillis <= 0L) {
            removeTomb(tomb);
            return;
        }
        long timeoutTicks = Math.max(1L, remainingMillis / 50L);
        tasks.add(getServer().getScheduler().runTaskLater(this, () -> {
            TombData current = tombs.get(key);
            if (current == null || !isTombBlock(loc.getBlock())) {
                cleanupTaskKey(key);
                return;
            }
            removeTomb(current);
            Player player = getServer().getPlayer(tomb.owner);
            if (player != null && player.isOnline()) {
                player.sendMessage(colorize(getConfig().getString("messages.expired")));
            }
        }, timeoutTicks));

        tasks.add(getServer().getScheduler().runTaskTimer(this, () -> {
            if (!isTombBlock(loc.getBlock())) {
                cleanupTaskKey(key);
                return;
            }
            updateHologram(tomb);
        }, 20L, 20L));

        List<Long> warnings = parseWarningSeconds();
        if (!warnings.isEmpty()) {
            HashSet<Long> pending = new HashSet<>(warnings);
            final BukkitTask[] warningTask = new BukkitTask[1];
            warningTask[0] = getServer().getScheduler().runTaskTimer(this, () -> {
                if (!isTombBlock(loc.getBlock())) {
                    cleanupTaskKey(key);
                    return;
                }
                long remainingSeconds = Math.max(0L, (tomb.expiresAt - System.currentTimeMillis()) / 1000L);
                List<Long> sentNow = new ArrayList<>();
                for (Long threshold : pending) {
                    if (remainingSeconds <= threshold) {
                        Player player = getServer().getPlayer(tomb.owner);
                        if (player != null && player.isOnline()) {
                            String label = formatSeconds(remainingSeconds);
                            player.sendMessage(colorize(getConfig().getString("messages.remaining")
                                    .replace("%time%", label)));
                        }
                        sentNow.add(threshold);
                    }
                }
                pending.removeAll(sentNow);
                if (pending.isEmpty()) {
                    warningTask[0].cancel();
                }
            }, 20L, 20L);
            tasks.add(warningTask[0]);
        }

        tombTasks.put(key, tasks);
    }

    private List<Long> parseWarningSeconds() {
        List<?> warnings = getConfig().getList("tomb.warnings");
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        List<Long> seconds = new ArrayList<>();
        for (Object entry : warnings) {
            if (!(entry instanceof Number)) {
                continue;
            }
            double minutes = ((Number) entry).doubleValue();
            long secondsLeft = Math.round(minutes * 60.0d);
            if (secondsLeft > 0) {
                seconds.add(secondsLeft);
            }
        }
        return seconds;
    }

    private void cleanupTask(Location location) {
        cleanupTaskKey(locKey(location));
    }

    private void cleanupTaskKey(String key) {
        List<BukkitTask> tasks = tombTasks.remove(key);
        if (tasks == null) {
            return;
        }
        for (BukkitTask task : tasks) {
            task.cancel();
        }
    }

    private String locKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void clearLastTomb(UUID owner, Location location) {
        if (owner == null || location == null) {
            return;
        }
        Location last = lastTomb.get(owner);
        if (last == null) {
            return;
        }
        if (sameBlock(last, location)) {
            lastTomb.remove(owner);
        }
    }

    private boolean hasAdminPermission(CommandSender sender) {
        return sender.isOp() || sender.hasPermission("tumbaadland.admin");
    }

    private void listTombs(CommandSender sender) {
        if (lastTomb.isEmpty()) {
            sender.sendMessage("Nenhuma tumba ativa.");
            return;
        }
        String list = lastTomb.entrySet().stream()
                .map(entry -> formatTombEntry(entry.getKey(), entry.getValue()))
                .collect(Collectors.joining(", "));
        sender.sendMessage("Tumbas ativas: " + list);
    }

    private String formatTombEntry(UUID owner, Location loc) {
        String name = Bukkit.getOfflinePlayer(owner).getName();
        if (name == null || name.isEmpty()) {
            name = owner.toString();
        }
        return name + "@" + loc.getWorld().getName() + " " + loc.getBlockX() + " "
                + loc.getBlockY() + " " + loc.getBlockZ();
    }

    private void removeTombForPlayer(CommandSender sender, String playerName) {
        UUID targetId = Bukkit.getOfflinePlayer(playerName).getUniqueId();
        Location loc = lastTomb.get(targetId);
        if (loc == null) {
            sender.sendMessage("Nenhuma tumba encontrada para " + playerName + ".");
            return;
        }
        if (loc.getWorld() == null) {
            lastTomb.remove(targetId);
            sender.sendMessage("Mundo nao carregado, referencia removida.");
            return;
        }
        Block block = loc.getBlock();
        TombData tomb = tombs.get(locKey(loc));
        if (tomb == null || !isTombBlock(block)) {
            lastTomb.remove(targetId);
            sender.sendMessage("Tumba nao existe mais. Entrada removida.");
            return;
        }
        removeTomb(tomb);
        sender.sendMessage("Tumba de " + playerName + " removida.");
    }

    private void handleExplosion(List<Block> blocks) {
        List<Block> affected = new ArrayList<>(blocks);
        HashSet<String> handled = new HashSet<>();
        for (Block block : affected) {
            if (!isTombBlock(block) || !isHeadBlock(block)) {
                continue;
            }
            String key = locKey(block.getLocation());
            if (handled.contains(key)) {
                continue;
            }
            TombData tomb = tombs.get(key);
            if (tomb != null) {
                dropAndRemoveTomb(tomb);
            }
            handled.add(key);
            blocks.remove(block);
        }
    }

    private Location resolveVoidLocation(Location base) {
        World world = base.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.THE_END) {
            return null;
        }
        if (base.getY() > world.getMinHeight() + 1) {
            return null;
        }
        Block highest = world.getHighestBlockAt(base.getBlockX(), base.getBlockZ());
        if (highest == null || highest.getType() == Material.AIR) {
            return findNearestEndSafe(world);
        }
        Location above = highest.getLocation().add(0, 1, 0);
        if (isSafeReplaceable(above.getBlock())) {
            return above;
        }
        return null;
    }

    private Location findNearestEndSafe(World world) {
        int radius = getConfig().getInt("tomb.end-void-radius");
        Location spawn = world.getSpawnLocation();
        int baseX = spawn.getBlockX();
        int baseZ = spawn.getBlockZ();
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                int x = baseX + dx;
                int z1 = baseZ + r;
                int z2 = baseZ - r;
                Location loc1 = tryEndColumn(world, x, z1);
                if (loc1 != null) {
                    return loc1;
                }
                Location loc2 = tryEndColumn(world, x, z2);
                if (loc2 != null) {
                    return loc2;
                }
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                int z = baseZ + dz;
                int x1 = baseX + r;
                int x2 = baseX - r;
                Location loc1 = tryEndColumn(world, x1, z);
                if (loc1 != null) {
                    return loc1;
                }
                Location loc2 = tryEndColumn(world, x2, z);
                if (loc2 != null) {
                    return loc2;
                }
            }
        }
        return null;
    }

    private Location tryEndColumn(World world, int x, int z) {
        Block highest = world.getHighestBlockAt(x, z);
        if (highest == null || highest.getType() == Material.AIR) {
            return null;
        }
        Location above = highest.getLocation().add(0, 1, 0);
        if (isSafeReplaceable(above.getBlock())) {
            return above;
        }
        return null;
    }

    private boolean sameBlock(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        return a.getWorld().getUID().equals(b.getWorld().getUID())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private boolean isHeadBlock(Block block) {
        return block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD;
    }

    private List<ItemStack> filterItems(ItemStack[] contents) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR && item.getAmount() > 0) {
                items.add(item);
            }
        }
        return items;
    }

    private Inventory createTombInventory(TombData tomb) {
        String name = Bukkit.getOfflinePlayer(tomb.owner).getName();
        if (name == null || name.isEmpty()) {
            name = tomb.owner.toString();
        }
        String title = colorize(getConfig().getString("messages.inventory-title")
                .replace("%player%", name));
        TombInventoryHolder holder = new TombInventoryHolder(tomb);
        Inventory inv = Bukkit.createInventory(holder, tomb.inventorySize, title);
        holder.setInventory(inv);
        inv.addItem(tomb.items.toArray(new ItemStack[0]));
        return inv;
    }

    private void giveBackExperience(Player player, TombData tomb) {
        double rate = getConfig().getDouble("experience.return-rate");
        rate = Math.max(0.0d, Math.min(1.0d, rate));
        int toReturn = (int) Math.round(tomb.storedExp * rate);
        if (toReturn > 0) {
            player.giveExp(toReturn);
        }
        tomb.expClaimed = true;
        tomb.storedExp = 0;
        saveTombToDisk(tomb);
        updateHologram(tomb);
    }

    private void spawnHologram(TombData tomb) {
        if (tomb.location.getWorld() == null) {
            return;
        }
        Location base = tomb.location.clone().add(0.5d, getConfig().getDouble("hologram.height"), 0.5d);
        double spacing = getConfig().getDouble("hologram.spacing");
        List<String> lines = getHologramLines(tomb, getRemainingSeconds(tomb));
        List<UUID> ids = new ArrayList<>();
        double yOffset = 0.0d;
        for (String line : lines) {
            ArmorStand stand = base.getWorld().spawn(base.clone().add(0, yOffset, 0), ArmorStand.class, entity -> {
                entity.setVisible(false);
                entity.setGravity(false);
                entity.setMarker(true);
                entity.setCustomNameVisible(true);
                entity.setCustomName(colorize(line));
                entity.setRemoveWhenFarAway(false);
            });
            ids.add(stand.getUniqueId());
            yOffset -= spacing;
        }
        tomb.hologramIds = ids;
    }

    private void updateHologram(TombData tomb) {
        if (tomb.hologramIds == null || tomb.hologramIds.isEmpty()) {
            return;
        }
        if (tomb.location.getWorld() == null) {
            return;
        }
        List<String> lines = getHologramLines(tomb, getRemainingSeconds(tomb));
        if (tomb.hologramIds.size() != lines.size()) {
            removeHologram(tomb);
            spawnHologram(tomb);
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            UUID id = tomb.hologramIds.get(i);
            Entity entity = tomb.location.getWorld().getEntity(id);
            if (entity == null) {
                removeHologram(tomb);
                spawnHologram(tomb);
                return;
            }
            if (entity instanceof ArmorStand stand) {
                stand.setCustomName(colorize(lines.get(i)));
            }
        }
    }

    private List<String> getHologramLines(TombData tomb, long remainingSeconds) {
        List<String> configured = getConfig().getStringList("hologram.lines");
        if (configured != null && !configured.isEmpty()) {
            List<String> resolved = new ArrayList<>();
            for (String line : configured) {
                resolved.add(applyPlaceholders(line, tomb, remainingSeconds));
            }
            return resolved;
        }
        String nameLine = applyPlaceholders(getConfig().getString("hologram.name"), tomb, remainingSeconds);
        String deathLine = applyPlaceholders(getConfig().getString("hologram.death"), tomb, remainingSeconds);
        String timeLine = applyPlaceholders(getConfig().getString("hologram.time"), tomb, remainingSeconds);
        return List.of(nameLine, deathLine, timeLine);
    }

    private long getRemainingSeconds(TombData tomb) {
        return Math.max(0L, (tomb.expiresAt - System.currentTimeMillis()) / 1000L);
    }

    private void removeHologram(TombData tomb) {
        if (tomb.hologramIds == null) {
            return;
        }
        if (tomb.location.getWorld() != null) {
            for (UUID id : tomb.hologramIds) {
                Entity entity = tomb.location.getWorld().getEntity(id);
                if (entity != null) {
                    entity.remove();
                }
            }
        }
        tomb.hologramIds = null;
    }

    private void setupTombStorage() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        tombsFile = new File(getDataFolder(), "tombs.yml");
        if (!tombsFile.exists()) {
            try {
                tombsFile.createNewFile();
            } catch (IOException ex) {
                getLogger().warning("Nao foi possivel criar tombs.yml");
            }
        }
        tombsConfig = YamlConfiguration.loadConfiguration(tombsFile);
    }

    private void loadTombs() {
        if (tombsConfig == null) {
            return;
        }
        if (!tombsConfig.isConfigurationSection("tombs")) {
            return;
        }
        for (String key : tombsConfig.getConfigurationSection("tombs").getKeys(false)) {
            String base = "tombs." + key + ".";
            String worldId = tombsConfig.getString(base + "world");
            World world = null;
            if (worldId != null) {
                try {
                    world = Bukkit.getWorld(UUID.fromString(worldId));
                } catch (IllegalArgumentException ex) {
                    world = null;
                }
            }
            if (world == null) {
                tombsConfig.set("tombs." + key, null);
                continue;
            }
            int x = tombsConfig.getInt(base + "x");
            int y = tombsConfig.getInt(base + "y");
            int z = tombsConfig.getInt(base + "z");
            Location loc = new Location(world, x, y, z);
            Block block = loc.getBlock();
            if (!isHeadBlock(block) || !isTombBlock(block)) {
                tombsConfig.set("tombs." + key, null);
                continue;
            }
            String ownerId = tombsConfig.getString(base + "owner");
            if (ownerId == null) {
                tombsConfig.set("tombs." + key, null);
                continue;
            }
            UUID owner;
            try {
                owner = UUID.fromString(ownerId);
            } catch (IllegalArgumentException ex) {
                tombsConfig.set("tombs." + key, null);
                continue;
            }
            long expiresAt = tombsConfig.getLong(base + "expiresAt");
            String deathMessage = tombsConfig.getString(base + "death", "Morreu.");
            int inventorySize = tombsConfig.getInt(base + "size", 27);
            int storedExp = tombsConfig.getInt(base + "exp", 0);
            List<ItemStack> items = new ArrayList<>();
            List<?> rawItems = tombsConfig.getList(base + "items", List.of());
            for (Object entry : rawItems) {
                if (entry instanceof ItemStack item) {
                    items.add(item);
                }
            }
            if (expiresAt <= System.currentTimeMillis()) {
                if (isHeadBlock(block)) {
                    block.setType(Material.AIR, false);
                }
                tombsConfig.set("tombs." + key, null);
                continue;
            }
            TombData tomb = new TombData(owner, loc, deathMessage, expiresAt, inventorySize, items, storedExp);
            tombs.put(locKey(loc), tomb);
            lastTomb.put(owner, loc);
            spawnHologram(tomb);
            scheduleTombRemoval(tomb);
        }
        saveTombFile();
    }

    private void saveTombToDisk(TombData tomb) {
        if (tombsConfig == null || tomb == null) {
            return;
        }
        String key = locKey(tomb.location);
        String base = "tombs." + key + ".";
        tombsConfig.set(base + "world", tomb.location.getWorld().getUID().toString());
        tombsConfig.set(base + "x", tomb.location.getBlockX());
        tombsConfig.set(base + "y", tomb.location.getBlockY());
        tombsConfig.set(base + "z", tomb.location.getBlockZ());
        tombsConfig.set(base + "owner", tomb.owner.toString());
        tombsConfig.set(base + "expiresAt", tomb.expiresAt);
        tombsConfig.set(base + "death", tomb.deathMessage);
        tombsConfig.set(base + "size", tomb.inventorySize);
        tombsConfig.set(base + "exp", tomb.storedExp);
        tombsConfig.set(base + "items", tomb.items);
        saveTombFile();
    }

    private void removeTombFromDisk(TombData tomb) {
        if (tombsConfig == null || tomb == null) {
            return;
        }
        String key = locKey(tomb.location);
        tombsConfig.set("tombs." + key, null);
        saveTombFile();
    }

    private void saveTombFile() {
        try {
            tombsConfig.save(tombsFile);
        } catch (IOException ex) {
            getLogger().warning("Nao foi possivel salvar tombs.yml");
        }
    }

    private void refreshTombsAfterReload() {
        for (TombData tomb : tombs.values()) {
            updateHologram(tomb);
            scheduleTombRemoval(tomb);
        }
    }

    private String colorize(String message) {
        if (message == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private String translateDeathMessage(String message) {
        if (message == null || message.isEmpty()) {
            return "Morreu.";
        }
        String translated = message;
        translated = translated.replace("was killed by", "foi morto por");
        translated = translated.replace("was killed", "foi morto");
        translated = translated.replace("was slain by", "foi morto por");
        translated = translated.replace("was shot by", "foi atingido por");
        translated = translated.replace("was blown up by", "foi explodido por");
        translated = translated.replace("was blown up", "explodiu");
        translated = translated.replace("was hit by", "foi atingido por");
        translated = translated.replace("was fireballed by", "foi atingido por bola de fogo de");
        translated = translated.replace("burned to death", "morreu queimado");
        translated = translated.replace("went up in flames", "virou cinzas");
        translated = translated.replace("tried to swim in lava", "tentou nadar na lava");
        translated = translated.replace("was burnt to a crisp while fighting", "virou cinzas enquanto lutava com");
        translated = translated.replace("fell from a high place", "caiu de um lugar alto");
        translated = translated.replace("hit the ground too hard", "atingiu o chao com muita forca");
        translated = translated.replace("fell out of the world", "caiu no vazio");
        translated = translated.replace("drowned", "se afogou");
        translated = translated.replace("suffocated in a wall", "sufocou na parede");
        translated = translated.replace("starved to death", "morreu de fome");
        translated = translated.replace("was pricked to death", "morreu espetado");
        translated = translated.replace("walked into a cactus while trying to escape", "entrou em um cactus tentando fugir de");
        translated = translated.replace("was squashed by a falling anvil", "foi esmagado por uma bigorna");
        translated = translated.replace("was squashed by a falling block", "foi esmagado por um bloco");
        translated = translated.replace("blew up", "explodiu");
        translated = translated.replace("fell off a ladder", "caiu de uma escada");
        translated = translated.replace("fell off some vines", "caiu de cipós");
        translated = translated.replace("fell off some weeping vines", "caiu de cipós chorões");
        translated = translated.replace("fell off some twisting vines", "caiu de cipós retorcidos");
        translated = translated.replace("fell off scaffolding", "caiu do andaime");
        translated = translated.replace("fell off a cliff", "caiu de um penhasco");
        translated = translated.replace("fell off a water", "caiu na agua");
        translated = translated.replace("was poked to death by a sweet berry bush", "morreu espetado por arbusto de doces");
        translated = translated.replace("experienced kinetic energy", "morreu por energia cinetica");
        translated = translated.replace("was killed trying to hurt", "morreu tentando ferir");
        translated = translated.replace("was killed while trying to escape", "morreu tentando escapar de");
        translated = translated.replace("was killed by", "foi morto por");
        return translated;
    }

    private String formatSeconds(long seconds) {
        if (seconds >= 60) {
            long min = seconds / 60;
            long sec = seconds % 60;
            if (sec == 0) {
                return min + "min";
            }
            return min + "min " + sec + "seg";
        }
        return seconds + "seg";
    }

    private String formatTimeHms(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private String applyPlaceholders(String line, TombData tomb, long remainingSeconds) {
        if (line == null) {
            return "";
        }
        String playerName = Bukkit.getOfflinePlayer(tomb.owner).getName();
        if (playerName == null || playerName.isEmpty()) {
            playerName = tomb.owner.toString();
        }
        int itemCount = countItems(tomb.items);
        String timeLabel = formatSeconds(remainingSeconds);
        String timeHms = formatTimeHms(remainingSeconds);
        return line.replace("%player%", playerName)
                .replace("%death%", tomb.deathMessage)
                .replace("%time%", timeLabel)
                .replace("%time_hms%", timeHms)
                .replace("%items%", String.valueOf(itemCount))
                .replace("%xp%", String.valueOf(tomb.storedExp));
    }
}

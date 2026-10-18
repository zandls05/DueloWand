package dev.duelo;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Varitas de Duelo (un solo uso):
 *  - CUPULA: encierra a ambos jugadores en una cupula de cristal donde estan.
 *  - ARENA:  teletransporta a ambos a una arena fija y los devuelve a su punto original.
 *
 * En los dos modos: dura 2 minutos con barra de cuenta atras, y termina antes si
 * alguien muere o se desconecta.
 */
public final class DueloPlugin extends JavaPlugin implements Listener {

    // ---- Configuracion rapida ----
    private static final int RADIUS = 5;               // radio de la cupula
    private static final double THICKNESS = 0.9;       // grosor (evita huecos diagonales)
    private static final int DURATION_SECONDS = 120;   // 2 minutos
    private static final Material GLASS = Material.LIGHT_BLUE_STAINED_GLASS;

    private static final String DOME = "dome";
    private static final String ARENA = "arena";

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private NamespacedKey wandKey;
    private final Map<UUID, Duel> byPlayer = new HashMap<>();

    @Override
    public void onEnable() {
        wandKey = new NamespacedKey(this, "duel_wand");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (Duel duel : new HashSet<>(byPlayer.values())) {
            duel.end("<gray>El servidor se esta reiniciando, el duelo termina.", null);
        }
    }

    // ---------------------------------------------------------------- items

    private ItemStack createWand(String type) {
        boolean dome = type.equals(DOME);
        ItemStack item = new ItemStack(dome ? Material.BLAZE_ROD : Material.END_ROD);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(MM.deserialize(dome
                ? "<!italic><gradient:#4FC3F7:#B388FF><bold>Varita de Duelo: Cupula</bold></gradient>"
                : "<!italic><gradient:#FF8A65:#FFD54F><bold>Varita de Duelo: Arena</bold></gradient>"));

        List<Component> lore = new ArrayList<>();
        lore.add(MM.deserialize("<!italic> "));
        if (dome) {
            lore.add(MM.deserialize("<!italic><gray>Golpea a un jugador para encerrarlos"));
            lore.add(MM.deserialize("<!italic><gray>a ambos en una <aqua>cupula de cristal</aqua>."));
            lore.add(MM.deserialize("<!italic> "));
            lore.add(MM.deserialize("<!italic><dark_gray>» <white>Radio: <aqua>" + RADIUS + " bloques"));
            lore.add(MM.deserialize("<!italic><dark_gray>» <white>Terreno: <green>se restaura solo"));
        } else {
            lore.add(MM.deserialize("<!italic><gray>Golpea a un jugador para llevarlos"));
            lore.add(MM.deserialize("<!italic><gray>a ambos a una <gold>arena cerrada</gold>."));
            lore.add(MM.deserialize("<!italic> "));
            lore.add(MM.deserialize("<!italic><dark_gray>» <white>Al terminar: <green>vuelven a su punto original"));
            lore.add(MM.deserialize("<!italic><dark_gray>» <white>Comandos: <red>bloqueados durante el duelo"));
        }
        lore.add(MM.deserialize("<!italic><dark_gray>» <white>Duracion: <aqua>" + (DURATION_SECONDS / 60) + " minutos"));
        lore.add(MM.deserialize("<!italic><dark_gray>» <white>Termina si alguien <red>muere</red> o se <red>desconecta"));
        lore.add(MM.deserialize("<!italic> "));
        lore.add(MM.deserialize("<!italic><red><bold>USO UNICO</bold> <gray>- se consume al golpear"));
        meta.lore(lore);

        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    /** Devuelve "dome", "arena" o null si el item no es una varita. */
    private String wandType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.STRING);
    }

    // -------------------------------------------------------------- comandos

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("duelo.admin")) {
            sender.sendMessage(MM.deserialize("<red>No tienes permiso."));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "give" -> {
                if (args.length < 2 || !(args[1].equalsIgnoreCase(DOME) || args[1].equalsIgnoreCase(ARENA))) {
                    sender.sendMessage(MM.deserialize("<red>Uso: /duelo give <dome|arena> [jugador]"));
                    return true;
                }
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2])
                        : (sender instanceof Player p ? p : null);
                if (target == null) {
                    sender.sendMessage(MM.deserialize("<red>Jugador no encontrado."));
                    return true;
                }
                target.getInventory().addItem(createWand(args[1].toLowerCase()));
                sender.sendMessage(MM.deserialize("<green>Varita entregada a " + target.getName() + "."));
            }
            case "setarena" -> {
                if (!(sender instanceof Player p) || args.length < 2
                        || !(args[1].equals("1") || args[1].equals("2"))) {
                    sender.sendMessage(MM.deserialize("<red>Uso (en el juego): /duelo setarena <1|2>"));
                    return true;
                }
                getConfig().set("arena." + args[1], p.getLocation());
                saveConfig();
                sender.sendMessage(MM.deserialize("<green>Punto " + args[1] + " de la arena guardado en tu posicion."));
            }
            default -> {
                sender.sendMessage(MM.deserialize("<gray>/duelo give <dome|arena> [jugador]"));
                sender.sendMessage(MM.deserialize("<gray>/duelo setarena <1|2>  <dark_gray>(donde apareceran los jugadores)"));
            }
        }
        return true;
    }

    // --------------------------------------------------------------- eventos

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player attacker)
                || !(e.getEntity() instanceof Player victim)
                || attacker.equals(victim)) {
            return;
        }
        ItemStack hand = attacker.getInventory().getItemInMainHand();
        String type = wandType(hand);
        if (type == null) return;

        e.setCancelled(true); // el golpe de la varita no hace dano

        if (byPlayer.containsKey(attacker.getUniqueId()) || byPlayer.containsKey(victim.getUniqueId())) {
            attacker.sendMessage(MM.deserialize("<red>Uno de los dos ya esta en un duelo."));
            return;
        }

        Duel duel;
        if (type.equals(DOME)) {
            if (!attacker.getWorld().equals(victim.getWorld())) return;
            Location mid = attacker.getLocation().clone().add(victim.getLocation()).multiply(0.5);
            duel = new DomeDuel(attacker, victim, mid);
        } else {
            Location l1 = getConfig().getLocation("arena.1");
            Location l2 = getConfig().getLocation("arena.2");
            if (l1 == null || l2 == null || l1.getWorld() == null || l2.getWorld() == null) {
                attacker.sendMessage(MM.deserialize(
                        "<red>La arena no esta configurada. Un admin debe usar /duelo setarena 1 y 2."));
                return;
            }
            for (Duel d : byPlayer.values()) {
                if (d instanceof TeleportDuel) {
                    attacker.sendMessage(MM.deserialize("<red>La arena esta ocupada, espera a que termine el duelo."));
                    return;
                }
            }
            duel = new TeleportDuel(attacker, victim, l1, l2);
        }

        // se consume (se reasigna para no depender de que 'hand' sea una vista viva)
        if (hand.getAmount() <= 1) {
            attacker.getInventory().setItemInMainHand(null);
        } else {
            ItemStack rest = hand.clone();
            rest.setAmount(hand.getAmount() - 1);
            attacker.getInventory().setItemInMainHand(rest);
        }

        duel.start();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Duel duel = byPlayer.get(e.getEntity().getUniqueId());
        if (duel == null) return;
        Player winner = duel.other(e.getEntity());
        duel.end("<gray>" + e.getEntity().getName() + " ha muerto. <green>Duelo terminado.", winner);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Duel duel = byPlayer.get(e.getPlayer().getUniqueId());
        if (duel == null) return;
        Player winner = duel.other(e.getPlayer());
        duel.end("<gray>" + e.getPlayer().getName() + " se desconecto. <green>Duelo terminado.", winner);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (!byPlayer.containsKey(e.getPlayer().getUniqueId())) return;
        PlayerTeleportEvent.TeleportCause cause = e.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Duel duel = byPlayer.get(e.getPlayer().getUniqueId());
        if (duel instanceof TeleportDuel && !e.getPlayer().hasPermission("duelo.bypass")) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(MM.deserialize("<red>No puedes usar comandos durante un duelo."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isDomeBlock(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isDomeBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isDomeBlock);
    }

    private boolean isDomeBlock(Block block) {
        for (Duel duel : new HashSet<>(byPlayer.values())) {
            if (duel instanceof DomeDuel dome && dome.saved.containsKey(block)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- duelos

    private abstract class Duel {
        final Player a;
        final Player b;
        final BossBar bar = BossBar.bossBar(Component.empty(), 1.0f,
                BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        int remaining = DURATION_SECONDS;
        BukkitTask task;
        boolean ended;

        Duel(Player a, Player b) {
            this.a = a;
            this.b = b;
        }

        abstract void onStart();

        abstract void onEnd();

        abstract String introText();

        Player other(Player p) {
            return p.getUniqueId().equals(a.getUniqueId()) ? b : a;
        }

        void start() {
            onStart();
            byPlayer.put(a.getUniqueId(), this);
            byPlayer.put(b.getUniqueId(), this);

            updateBar();
            a.showBossBar(bar);
            b.showBossBar(bar);

            Component intro = MM.deserialize(introText());
            for (Player p : List.of(a, b)) {
                p.sendMessage(intro);
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.2f);
            }

            task = Bukkit.getScheduler().runTaskTimer(DueloPlugin.this, () -> {
                if (ended) return;
                remaining--;
                if (remaining <= 0) {
                    end("<gray>Tiempo agotado. <yellow>Empate.", null);
                    return;
                }
                if (remaining <= 10) {
                    for (Player p : List.of(a, b)) {
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
                    }
                }
                updateBar();
            }, 20L, 20L);
        }

        void updateBar() {
            String time = String.format("%d:%02d", remaining / 60, remaining % 60);
            String color = remaining > 60 ? "green" : remaining > 20 ? "yellow" : "red";
            bar.name(MM.deserialize("<aqua><bold>DUELO</bold> <dark_gray>» <" + color + ">" + time));
            bar.progress(Math.max(0f, Math.min(1f, remaining / (float) DURATION_SECONDS)));
            bar.color(remaining > 60 ? BossBar.Color.GREEN
                    : remaining > 20 ? BossBar.Color.YELLOW : BossBar.Color.RED);
        }

        void end(String message, Player winner) {
            if (ended) return;
            ended = true;
            if (task != null) task.cancel();

            onEnd(); // restaurar bloques / devolver jugadores

            byPlayer.remove(a.getUniqueId());
            byPlayer.remove(b.getUniqueId());

            for (Player p : List.of(a, b)) {
                if (!p.isOnline()) continue;
                p.hideBossBar(bar);
                p.sendMessage(MM.deserialize("<aqua><bold>DUELO</bold> <dark_gray>» " + message));
                p.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
            }
            if (winner != null && winner.isOnline()) {
                winner.sendMessage(MM.deserialize("<aqua><bold>DUELO</bold> <dark_gray>» <green>Has ganado el duelo."));
            }
        }
    }

    /** Cupula de cristal en el sitio del golpe; el terreno se restaura bloque a bloque. */
    private final class DomeDuel extends Duel {
        final Location mid;
        final Map<Block, BlockData> saved = new LinkedHashMap<>();

        DomeDuel(Player a, Player b, Location mid) {
            super(a, b);
            this.mid = mid;
        }

        @Override
        String introText() {
            return "<aqua><bold>DUELO</bold> <dark_gray>» <white>Arena activada. <gray>Tienen <aqua>"
                    + (DURATION_SECONDS / 60) + " minutos<gray>. Gana el ultimo en pie.";
        }

        @Override
        void onStart() {
            World w = mid.getWorld();
            int cx = mid.getBlockX();
            int cy = mid.getBlockY();
            int cz = mid.getBlockZ();
            for (int x = -RADIUS - 1; x <= RADIUS + 1; x++) {
                for (int y = -1; y <= RADIUS; y++) {
                    for (int z = -RADIUS - 1; z <= RADIUS + 1; z++) {
                        int yy = Math.max(y, 0);
                        double d = Math.sqrt(x * x + yy * yy + z * z);
                        if (Math.abs(d - RADIUS) > THICKNESS) continue;

                        Block block = w.getBlockAt(cx + x, cy + y, cz + z);
                        // Solo se reemplaza lo "pasable": el terreno solido no se toca.
                        if (!block.isPassable()) continue;
                        if (block.getState() instanceof TileState) continue;
                        if (saved.containsKey(block)) continue;

                        saved.put(block, block.getBlockData());
                        block.setType(GLASS, false);
                    }
                }
            }
        }

        @Override
        void onEnd() {
            for (Map.Entry<Block, BlockData> entry : saved.entrySet()) {
                entry.getKey().setBlockData(entry.getValue(), false);
            }
            saved.clear();
        }
    }

    /** Arena fija: teletransporta a los dos y los devuelve a su punto original al terminar. */
    private final class TeleportDuel extends Duel {
        final Location spawnA;
        final Location spawnB;
        final Location originA;
        final Location originB;

        TeleportDuel(Player a, Player b, Location spawnA, Location spawnB) {
            super(a, b);
            this.spawnA = spawnA;
            this.spawnB = spawnB;
            this.originA = a.getLocation().clone();
            this.originB = b.getLocation().clone();
        }

        @Override
        String introText() {
            return "<aqua><bold>DUELO</bold> <dark_gray>» <white>Han sido llevados a la arena. <gray>Tienen <aqua>"
                    + (DURATION_SECONDS / 60) + " minutos<gray>. Gana el ultimo en pie.";
        }

        @Override
        void onStart() {
            a.teleport(spawnA);
            b.teleport(spawnB);
        }

        @Override
        void onEnd() {
            back(a, originA);
            back(b, originB);
        }

        private void back(Player p, Location origin) {
            // Quien murio reaparece por el camino normal del servidor.
            if (p.isOnline() && !p.isDead()) {
                p.teleport(origin);
            }
        }
    }
}

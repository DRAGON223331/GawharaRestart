package com.gawhara.restart;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

public class GawharaRestart extends JavaPlugin implements TabExecutor {

    private static final DateTimeFormatter IN_FMT = DateTimeFormatter.ofPattern("H:mm");
    private static final DateTimeFormatter OUT_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FULL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String PERM = "gawhararestart.admin";

    private ZoneId zone = ZoneId.of("Africa/Cairo");
    private final List<LocalTime> times = new ArrayList<>();
    private List<Integer> warnings = new ArrayList<>();
    private final Set<Integer> announced = new HashSet<>();
    private ZonedDateTime oneOff;   // ريستارت لمرة واحدة (/grestart in)
    private ZonedDateTime target;   // الريستارت الجاي الفعلي
    private boolean restarting = false;
    private BukkitTask task;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        if (getCommand("grestart") != null) {
            getCommand("grestart").setExecutor(this);
            getCommand("grestart").setTabCompleter(this);
        }
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getLogger().info("GawharaRestart enabled. Next restart: " + (target == null ? "none" : target.format(FULL_FMT)));
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
    }

    // ------------------------------------------------------------------ settings / schedule

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        try {
            zone = ZoneId.of(c.getString("timezone", "Africa/Cairo"));
        } catch (Exception e) {
            zone = ZoneId.of("Africa/Cairo");
            getLogger().warning("Invalid timezone in config.yml, using Africa/Cairo");
        }

        warnings = new ArrayList<>(c.getIntegerList("warnings"));
        warnings.sort(Collections.reverseOrder());

        times.clear();
        File f = scheduleFile();
        List<String> raw;
        if (f.exists()) {
            raw = YamlConfiguration.loadConfiguration(f).getStringList("times");
        } else {
            raw = c.getStringList("default-times");
        }
        for (String s : raw) {
            try {
                times.add(LocalTime.parse(s.trim(), IN_FMT));
            } catch (DateTimeParseException e) {
                getLogger().warning("Bad time '" + s + "' ignored (use HH:mm)");
            }
        }
        Collections.sort(times);
        if (!f.exists()) saveSchedule();

        recalc();
    }

    private File scheduleFile() {
        return new File(getDataFolder(), "schedule.yml");
    }

    private void saveSchedule() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("times", times.stream().map(t -> t.format(OUT_FMT)).collect(Collectors.toList()));
        try {
            y.save(scheduleFile());
        } catch (IOException e) {
            getLogger().warning("Could not save schedule.yml: " + e.getMessage());
        }
    }

    /** يحسب أقرب ريستارت (من المواعيد المحفوظة أو الريستارت الفوري). */
    private void recalc() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime best = null;
        for (LocalTime t : times) {
            ZonedDateTime cand = now.with(t);
            if (!cand.isAfter(now)) cand = cand.plusDays(1);
            if (best == null || cand.isBefore(best)) best = cand;
        }
        if (oneOff != null && (best == null || oneOff.isBefore(best))) best = oneOff;
        setTarget(best);
    }

    private void setTarget(ZonedDateTime newTarget) {
        if (Objects.equals(target, newTarget)) return;
        target = newTarget;
        announced.clear();
        if (target != null) {
            long rem = remainingSeconds();
            // التنبيهات اللي فاتت خلاص نعتبرها اتقالت (بدون إعلان)
            for (int w : warnings) if (w > rem) announced.add(w);
        }
    }

    private long remainingSeconds() {
        if (target == null) return Long.MAX_VALUE;
        long ms = Duration.between(ZonedDateTime.now(zone), target).toMillis();
        return (ms + 999) / 1000;
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (restarting || target == null) return;

        long rem = remainingSeconds();
        if (rem <= 0) {
            doRestart();
            return;
        }

        boolean fire = false;
        for (int w : warnings) {
            if (rem <= w && announced.add(w)) fire = true;
        }
        if (fire) announce(rem);

        if (rem <= getConfig().getInt("actionbar-seconds", 60)) {
            String bar = color(getConfig().getString("messages.actionbar", "").replace("{time}", formatTime(rem)));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(bar));
            }
        }
    }

    private void announce(long rem) {
        FileConfiguration c = getConfig();
        String time = formatTime(rem);
        String prefix = c.getString("messages.prefix", "");

        Bukkit.broadcastMessage(color(prefix + c.getString("messages.warning", "").replace("{time}", time)));

        boolean title = rem <= c.getInt("title-from-seconds", 60);
        String t = color(c.getString("messages.title", ""));
        String st = color(c.getString("messages.subtitle", "").replace("{time}", time));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (title) p.sendTitle(t, st, 5, 40, 10);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, rem <= 10 ? 2f : 1f);
        }
    }

    private void doRestart() {
        restarting = true;
        FileConfiguration c = getConfig();
        Bukkit.broadcastMessage(color(c.getString("messages.prefix", "") + c.getString("messages.restarting-now", "")));

        String kick = color(c.getString("messages.kick", "Restarting"));
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) p.kickPlayer(kick);

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
        Bukkit.getScheduler().runTaskLater(this, () ->
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.getString("restart-command", "stop")), 40L);
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(PERM)) {
            reply(sender, "&cمعندكش صلاحية لاستخدام الأمر ده.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status") || args[0].equalsIgnoreCase("list")) {
            status(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "add": {
                if (args.length < 2) { reply(sender, "&eاستخدم: /grestart add 04:00"); return true; }
                LocalTime t = parseTime(args[1]);
                if (t == null) { reply(sender, "&cالوقت غلط. اكتبه بالشكل ده HH:mm مثال 04:30"); return true; }
                if (times.contains(t)) { reply(sender, "&eالموعد ده موجود قبل كده."); return true; }
                times.add(t);
                Collections.sort(times);
                saveSchedule();
                recalc();
                reply(sender, "&aاتضاف موعد ريستارت يومي الساعة &f" + t.format(OUT_FMT));
                return true;
            }
            case "remove": {
                if (args.length < 2) { reply(sender, "&eاستخدم: /grestart remove 04:00"); return true; }
                LocalTime t = parseTime(args[1]);
                if (t == null || !times.remove(t)) { reply(sender, "&cالموعد ده مش موجود."); return true; }
                saveSchedule();
                recalc();
                reply(sender, "&aاتشال موعد الساعة &f" + t.format(OUT_FMT));
                return true;
            }
            case "in": {
                if (args.length < 2) { reply(sender, "&eاستخدم: /grestart in <دقايق>"); return true; }
                int min;
                try { min = Integer.parseInt(args[1]); } catch (NumberFormatException e) { min = -1; }
                if (min < 1 || min > 1440) { reply(sender, "&cاكتب رقم دقايق من 1 لـ 1440."); return true; }
                oneOff = ZonedDateTime.now(zone).plusMinutes(min);
                recalc();
                reply(sender, "&aالسيرفر هيعمل ريستارت بعد &f" + formatTime(min * 60L));
                announce(remainingSeconds());
                return true;
            }
            case "cancel": {
                if (oneOff == null) { reply(sender, "&eمفيش ريستارت فوري متجدول عشان يتلغي."); return true; }
                oneOff = null;
                recalc();
                reply(sender, "&aاتلغى الريستارت الفوري.");
                Bukkit.broadcastMessage(color(getConfig().getString("messages.prefix", "") + "&aاتلغى الريستارت القريب."));
                return true;
            }
            case "reload": {
                loadSettings();
                reply(sender, "&aاتعمل ريلود للإعدادات.");
                return true;
            }
            default:
                help(sender);
                return true;
        }
    }

    private void status(CommandSender s) {
        if (target == null) {
            reply(s, "&eمفيش ريستارت متجدول.");
        } else {
            reply(s, "&aالريستارت الجاي: &f" + target.format(FULL_FMT) + " &7(بعد " + formatTime(remainingSeconds()) + ")");
        }
        if (!times.isEmpty()) {
            reply(s, "&aالمواعيد اليومية: &f" + times.stream().map(t -> t.format(OUT_FMT)).collect(Collectors.joining(" ، ")));
        }
        if (oneOff != null) reply(s, "&eفيه ريستارت فوري متجدول الساعة " + oneOff.format(OUT_FMT));
        reply(s, "&7المنطقة الزمنية: " + zone);
    }

    private void help(CommandSender s) {
        reply(s, "&6الأوامر:");
        s.sendMessage(color("&e/grestart &7- حالة الريستارت"));
        s.sendMessage(color("&e/grestart add <HH:mm> &7- إضافة موعد يومي"));
        s.sendMessage(color("&e/grestart remove <HH:mm> &7- حذف موعد"));
        s.sendMessage(color("&e/grestart in <دقايق> &7- ريستارت بعد وقت معين"));
        s.sendMessage(color("&e/grestart cancel &7- إلغاء الريستارت الفوري"));
        s.sendMessage(color("&e/grestart reload &7- ريلود الإعدادات"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission(PERM)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(Arrays.asList("status", "add", "remove", "in", "cancel", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            times.forEach(t -> out.add(t.format(OUT_FMT)));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("in")) {
            out.addAll(Arrays.asList("1", "5", "10", "30", "60"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            out.addAll(Arrays.asList("04:00", "06:00", "12:00", "20:00"));
        }
        String last = args[args.length - 1].toLowerCase();
        return out.stream().filter(s -> s.toLowerCase().startsWith(last)).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ helpers

    private LocalTime parseTime(String s) {
        try {
            return LocalTime.parse(s.trim(), IN_FMT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void reply(CommandSender s, String msg) {
        s.sendMessage(color(getConfig().getString("messages.prefix", "") + msg));
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** بيحول الثواني لنص عربي: "ساعة و 30 دقيقة" / "دقيقتين" / "5 ثواني" ... */
    static String formatTime(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        List<String> parts = new ArrayList<>();
        if (h > 0) parts.add(unit(h, "ساعة", "ساعتين", "ساعات", "ساعة"));
        if (m > 0) parts.add(unit(m, "دقيقة", "دقيقتين", "دقائق", "دقيقة"));
        if (s > 0) parts.add(unit(s, "ثانية", "ثانيتين", "ثواني", "ثانية"));
        if (parts.isEmpty()) return "لحظات";
        return String.join(" و", parts);
    }

    private static String unit(long n, String one, String two, String few, String many) {
        if (n == 1) return one;
        if (n == 2) return two;
        if (n <= 10) return n + " " + few;
        return n + " " + many;
    }
}

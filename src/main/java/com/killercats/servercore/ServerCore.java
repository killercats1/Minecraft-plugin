package com.killercats.servercore;

import com.killercats.servercore.command.BaseCommand;
import com.killercats.servercore.command.ServerCoreCommand;
import com.killercats.servercore.command.UnavailableCommand;
import com.killercats.servercore.data.PlayerDataManager;
import com.killercats.servercore.economy.EconomyService;
import com.killercats.servercore.economy.TransactionLogger;
import com.killercats.servercore.economy.admin.EcoAdminCommand;
import com.killercats.servercore.economy.admin.EcoStatsCommand;
import com.killercats.servercore.economy.admin.TransactionsCommand;
import com.killercats.servercore.economy.auction.AuctionCommand;
import com.killercats.servercore.economy.auction.AuctionManager;
import com.killercats.servercore.economy.bank.BankCommand;
import com.killercats.servercore.economy.bank.BankManager;
import com.killercats.servercore.economy.bounty.BountyCommand;
import com.killercats.servercore.economy.bounty.BountyManager;
import com.killercats.servercore.economy.daily.DailyCommand;
import com.killercats.servercore.economy.daily.DailyManager;
import com.killercats.servercore.economy.lottery.LotteryCommand;
import com.killercats.servercore.economy.lottery.LotteryManager;
import com.killercats.servercore.economy.notes.BanknoteCommand;
import com.killercats.servercore.economy.notes.BanknoteListener;
import com.killercats.servercore.economy.notes.BanknoteManager;
import com.killercats.servercore.economy.pay.PayCommand;
import com.killercats.servercore.economy.pay.PayToggleCommand;
import com.killercats.servercore.economy.payday.PaydayManager;
import com.killercats.servercore.economy.sell.SellCommand;
import com.killercats.servercore.economy.sell.SellManager;
import com.killercats.servercore.features.ActivityTracker;
import com.killercats.servercore.features.Announcer;
import com.killercats.servercore.features.BackupManager;
import com.killercats.servercore.features.ChatFormatter;
import com.killercats.servercore.features.ChatGuard;
import com.killercats.servercore.features.ScoreboardManager;
import com.killercats.servercore.features.StaffChat;
import com.killercats.servercore.gui.MenuListener;
import com.killercats.servercore.hook.EssentialsBalanceListener;
import com.killercats.servercore.hook.EssentialsHook;
import com.killercats.servercore.hook.PlaceholderHook;
import com.killercats.servercore.hook.VaultHook;
import com.killercats.servercore.maintenance.MaintenanceCommand;
import com.killercats.servercore.maintenance.MaintenanceListener;
import com.killercats.servercore.maintenance.MaintenanceManager;
import com.killercats.servercore.storage.Database;
import com.killercats.servercore.util.Messages;
import com.killercats.servercore.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

public final class ServerCore extends JavaPlugin {

    private static final String[] ECONOMY_COMMANDS = {"pay", "paytoggle", "bank", "banknote", "sell", "worth", "daily", "bounty",
            "lottery", "auction", "transactions", "ecostats", "ecoadmin"};

    private Messages messages;
    private Database database;
    private final VaultHook vault = new VaultHook();
    private final EssentialsHook essentials = new EssentialsHook();
    private final PlaceholderHook placeholders = new PlaceholderHook();
    private PlayerDataManager players;
    private ActivityTracker activity;
    private MaintenanceManager maintenance;
    private ScoreboardManager scoreboards;
    private Announcer announcer;
    private ChatGuard chatGuard;
    private BackupManager backups;

    private EconomyService economy;
    private TransactionLogger transactions;
    private BankManager bank;
    private BanknoteManager banknotes;
    private SellManager sell;
    private DailyManager daily;
    private PaydayManager payday;
    private BountyManager bounties;
    private LotteryManager lottery;
    private AuctionManager auctions;
    private BukkitTask autosaveTask;

    @Override
    public void onEnable() {
        // Missing keys fall back to the defaults inside the jar; the user's file (and its comments) is never rewritten.
        saveDefaultConfig();
        messages = new Messages(this);
        Money.configure(getConfig().getConfigurationSection("economy"));

        database = new Database(this);
        try {
            database.connect(getConfig().getConfigurationSection("storage"));
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not connect to the database, disabling ServerCore.", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (vault.setupPermissions()) {
            getLogger().info("Hooked into Vault permissions (rank support enabled).");
        }
        if (essentials.setup()) {
            getLogger().info("Hooked into EssentialsX " + essentials.version() + ".");
        }
        if (placeholders.setup()) {
            getLogger().info("Hooked into PlaceholderAPI.");
        }

        players = new PlayerDataManager(this);
        activity = new ActivityTracker(this);
        maintenance = new MaintenanceManager(this);
        register(players, activity, new MaintenanceListener(this), new MenuListener());

        StaffChat staffChat = new StaffChat(this);
        chatGuard = new ChatGuard(this);
        backups = new BackupManager(this);
        register(staffChat, chatGuard, new ChatFormatter(this));
        command("servercore", new ServerCoreCommand(this));
        command("maintenance", new MaintenanceCommand(this));
        command("staffchat", staffChat);
        command("scoreboard", new ServerCoreCommand.Scoreboard(this));

        if (!setupEconomy()) {
            // Some economy plugins register with Vault late; retry once the server has finished starting.
            UnavailableCommand unavailable = new UnavailableCommand(this);
            for (String name : ECONOMY_COMMANDS) {
                command(name, unavailable);
            }
            Bukkit.getScheduler().runTask(this, () -> {
                if (!setupEconomy()) {
                    getLogger().severe("No Vault economy found! Install Vault + EssentialsX (or another economy plugin)."
                            + " Economy features are disabled; maintenance mode and other features still work.");
                }
            });
        }

        scoreboards = new ScoreboardManager(this);
        announcer = new Announcer(this);
        register(scoreboards);

        long autosave = Math.max(1, getConfig().getLong("storage.autosave-minutes", 5)) * 60L * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, this::autosave, autosave, autosave);
        getLogger().info("ServerCore " + getDescription().getVersion() + " enabled.");
    }

    private boolean setupEconomy() {
        if (economy != null) {
            return true;
        }
        if (!vault.setupEconomy()) {
            return false;
        }
        economy = new EconomyService(vault.economy());
        transactions = new TransactionLogger(this);
        bank = new BankManager(this);
        banknotes = new BanknoteManager(this);
        sell = new SellManager(this);
        daily = new DailyManager(this);
        payday = new PaydayManager(this);
        bounties = new BountyManager(this);
        lottery = new LotteryManager(this);
        auctions = new AuctionManager(this);
        register(new BanknoteListener(this), daily, bounties, auctions);

        command("pay", new PayCommand(this));
        command("paytoggle", new PayToggleCommand(this));
        command("bank", new BankCommand(this));
        command("banknote", new BanknoteCommand(this));
        command("sell", new SellCommand(this));
        command("worth", new SellCommand.Worth(this));
        command("daily", new DailyCommand(this));
        command("bounty", new BountyCommand(this));
        command("lottery", new LotteryCommand(this));
        command("auction", new AuctionCommand(this));
        command("transactions", new TransactionsCommand(this));
        command("ecostats", new EcoStatsCommand(this));
        command("ecoadmin", new EcoAdminCommand(this));

        if (essentials.isPresent() && getConfig().getBoolean("economy.transaction-log.log-essentials", true)) {
            new EssentialsBalanceListener(this).register();
        }
        getLogger().info("Economy hooked into " + economy.providerName() + " via Vault.");
        return true;
    }

    @Override
    public void onDisable() {
        MenuListener.closeAll();
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (backups != null) {
            backups.shutdown();
        }
        if (maintenance != null) {
            maintenance.shutdown();
        }
        if (scoreboards != null) {
            scoreboards.shutdown();
        }
        if (announcer != null) {
            announcer.shutdown();
        }
        if (bank != null) {
            bank.shutdown();
        }
        if (sell != null) {
            sell.shutdown();
        }
        if (payday != null) {
            payday.shutdown();
        }
        if (lottery != null) {
            lottery.shutdown();
        }
        if (auctions != null) {
            auctions.shutdown();
        }
        if (players != null) {
            players.saveAllBlocking();
        }
        if (database != null) {
            database.shutdown();
        }
    }

    private void autosave() {
        players.saveDirty();
        if (sell != null) {
            sell.save();
        }
    }

    public void reload() {
        MenuListener.closeAll();
        reloadConfig();
        messages.reload();
        Money.configure(getConfig().getConfigurationSection("economy"));
        maintenance.reload();
        scoreboards.reload();
        announcer.reload();
        chatGuard.reload();
        backups.reload();
        if (economy != null) {
            transactions.reload();
            bank.reload();
            sell.reload();
            payday.reload();
        }
    }

    private void register(Listener... listeners) {
        for (Listener listener : listeners) {
            Bukkit.getPluginManager().registerEvents(listener, this);
        }
    }

    private void command(String name, BaseCommand executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    /** Runs a task on the main server thread (immediately if already on it). */
    public void sync(Runnable runnable) {
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else if (isEnabled()) {
            Bukkit.getScheduler().runTask(this, runnable);
        }
    }

    public boolean economyReady() {
        return economy != null;
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public VaultHook vault() {
        return vault;
    }

    public EssentialsHook essentials() {
        return essentials;
    }

    public PlaceholderHook placeholders() {
        return placeholders;
    }

    public PlayerDataManager players() {
        return players;
    }

    public ActivityTracker activity() {
        return activity;
    }

    public MaintenanceManager maintenance() {
        return maintenance;
    }

    public ScoreboardManager scoreboards() {
        return scoreboards;
    }

    public BackupManager backups() {
        return backups;
    }

    public EconomyService economy() {
        return economy;
    }

    public TransactionLogger transactions() {
        return transactions;
    }

    public BankManager bank() {
        return bank;
    }

    public BanknoteManager banknotes() {
        return banknotes;
    }

    public SellManager sell() {
        return sell;
    }

    public DailyManager daily() {
        return daily;
    }

    public BountyManager bounties() {
        return bounties;
    }

    public LotteryManager lottery() {
        return lottery;
    }

    public AuctionManager auctions() {
        return auctions;
    }
}

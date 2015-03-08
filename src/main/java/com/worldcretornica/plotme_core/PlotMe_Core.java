package com.worldcretornica.plotme_core;

import com.worldcretornica.configuration.ConfigAccessor;
import com.worldcretornica.configuration.file.FileConfiguration;
import com.worldcretornica.plotme_core.api.IPlotMe_GeneratorManager;
import com.worldcretornica.plotme_core.api.IServerBridge;
import com.worldcretornica.plotme_core.api.IWorld;
import com.worldcretornica.plotme_core.bukkit.AbstractSchematicUtil;
import com.worldcretornica.plotme_core.storage.Database;
import com.worldcretornica.plotme_core.storage.MySQLConnector;
import com.worldcretornica.plotme_core.storage.SQLiteConnector;
import com.worldcretornica.plotme_core.utils.Util;

import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

public class PlotMe_Core {

    //Bridge
    private final IServerBridge serverBridge;
    private final AbstractSchematicUtil schematicutil;
    private HashMap<String, IPlotMe_GeneratorManager> managers;
    private IWorld worldcurrentlyprocessingexpired;
    private int counterExpired;
    //Spool stuff
    private ConcurrentLinkedQueue<PlotToClear> plotsToClear;
    private Database sqlManager;
    private Util util;
    //Caption and Config File.
    private ConfigAccessor configFile;
    private ConfigAccessor captionFile;

    public PlotMe_Core(IServerBridge serverObjectBuilder, AbstractSchematicUtil schematicutil) {
        this.serverBridge = serverObjectBuilder;
        this.schematicutil = schematicutil;
        managers = new HashMap<>();
    }

    public IPlotMe_GeneratorManager getGenManager(String name) {
        return managers.get(name.toLowerCase());
    }

    public AbstractSchematicUtil getSchematicUtil() {
        return this.schematicutil;
    }

    public void disable() {
        getSqlManager().closeConnection();
        PlotMeCoreManager.getInstance().getPlotMaps().clear();
        serverBridge.unHook();
        PlotMeCoreManager.getInstance().setPlayersIgnoringWELimit(null);
        setWorldCurrentlyProcessingExpired(null);
        plotsToClear = null;
        managers.clear();
        managers = null;
    }

    public void enable() {
        PlotMeCoreManager.getInstance().setPlugin(this);
        configFile = new ConfigAccessor(getServerBridge().getDataFolder(), "config.yml");
        captionFile = new ConfigAccessor(getServerBridge().getDataFolder(), "captions.yml");
        setupConfigFiles();
        serverBridge.setupCommands();
        setupSQL();
        setUtil(new Util(this));
        serverBridge.setupHooks();
        serverBridge.setupListeners();
        setupClearSpools();
        if (getConfig().getBoolean("setupDatabase")) {

        }
        getSqlManager().startConnection();

        getSqlManager().createTables();
        if (getConfig().getBoolean("coreDatabaseUpdate")) {
            getSqlManager().coreDatabaseUpdate();
        }
        //getSqlManager().plotConvertToUUIDAsynchronously();
    }

    public void reload() {
        getSqlManager().closeConnection();
        setupConfigFiles();
        configFile.reloadFile();
        captionFile.reloadFile();
        setupSQL();
        PlotMeCoreManager.getInstance().getPlotMaps().clear();

        for (String worldname : managers.keySet()) {
            setupWorld(worldname.toLowerCase());
        }
    }

    public Logger getLogger() {
        return serverBridge.getLogger();
    }

    private void setupConfigFiles() {
        createConfigs();
        captionFile.saveConfig();
        // Get the config we will be working with
        FileConfiguration config = getConfig();
        // Do any config validation
        if (config.getInt("NbClearSpools") > 50) {
            getLogger().warning("Having more than 50 clear spools seems drastic, changing to 50");
            config.set("NbClearSpools", 50);
        }
        //Check if the config doesn't have the worlds section. This should happen only if there is no config file for the plugin already.
        if (!config.contains("worlds")) {
            getServerBridge().loadDefaultConfig(configFile, "worlds.plotworld");
        }
        // Copy new values over
        getConfig().options().copyDefaults(true);
        configFile.saveConfig();
    }

    private void createConfigs() {
        configFile.createFile();
        captionFile.createFile();
    }

    private void setupWorld(String world) {
        getServerBridge().loadDefaultConfig(configFile, "worlds." + world);
        configFile.saveConfig();
        PlotMapInfo pmi = new PlotMapInfo(this, configFile, world);
        PlotMeCoreManager.getInstance().addPlotMap(world, pmi);
    }

    public FileConfiguration getCaptionConfig() {
        return captionFile.getConfig();
    }

    /**
     * Setup SQL Database
     */
    private void setupSQL() {
        FileConfiguration config = getConfig();
        if (config.getBoolean("usemySQL", false)) {
            String url = config.getString("mySQLconn");
            String user = config.getString("mySQLuname");
            String pass = config.getString("mySQLpass");
            setSqlManager(new MySQLConnector(this, url, user, pass));
        } else {
            setSqlManager(new SQLiteConnector(this));
            getSqlManager().createTables();
        }
    }

    private void setupClearSpools() {
        plotsToClear = new ConcurrentLinkedQueue<>();
    }

    public void addManager(String world, IPlotMe_GeneratorManager manager) {
        managers.put(world.toLowerCase(), manager);
        setupWorld(world.toLowerCase());
    }

    public IPlotMe_GeneratorManager removeManager(String world) {
        return managers.remove(world);
    }

    public void scheduleTask(Runnable task) {
        getLogger().info(util.C("MsgStartDeleteSession"));

        for (int ctr = 0; ctr < 10; ctr++) {
            serverBridge.scheduleSyncDelayedTask(task, ctr * 100);
        }
    }

    public IWorld getWorldCurrentlyProcessingExpired() {
        return worldcurrentlyprocessingexpired;
    }

    public void setWorldCurrentlyProcessingExpired(IWorld worldcurrentlyprocessingexpired) {
        this.worldcurrentlyprocessingexpired = worldcurrentlyprocessingexpired;
    }

    public int getCounterExpired() {
        return counterExpired;
    }

    public void setCounterExpired(int counterExpired) {
        this.counterExpired = counterExpired;
    }

    public void addPlotToClear(PlotToClear plotToClear) {
        plotsToClear.offer(plotToClear);
        getLogger().info("plot to clear add " + plotToClear.getPlotId());
        PlotMeSpool pms = new PlotMeSpool(this, plotToClear);
        pms.setTaskId(serverBridge.scheduleSyncRepeatingTask(pms, 0L, 60L));
    }

    public void removePlotToClear(PlotToClear plotToClear, int taskId) {
        plotsToClear.remove(plotToClear);

        serverBridge.cancelTask(taskId);
        getLogger().info("removed taskid " + taskId);
    }

    public PlotToClear getPlotLocked(IWorld world, PlotId id) {
        if (plotsToClear.isEmpty()) {
            return null;
        }
        for (PlotToClear ptc : plotsToClear.toArray(new PlotToClear[plotsToClear.size()])) {
            if (ptc.getWorld() == world && ptc.getPlotId().equals(id)) {
                return ptc;
            }
        }

        return null;
    }

    public IServerBridge getServerBridge() {
        return serverBridge;
    }

    public Database getSqlManager() {
        return sqlManager;
    }

    private void setSqlManager(Database sqlManager) {
        this.sqlManager = sqlManager;
    }

    public Util getUtil() {
        return util;
    }

    private void setUtil(Util util) {
        this.util = util;
    }

    public FileConfiguration getConfig() {
        return configFile.getConfig();
    }

}

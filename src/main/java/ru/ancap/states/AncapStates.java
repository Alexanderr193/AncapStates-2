package ru.ancap.states;

import com.google.common.base.Joiner;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.MarkerSet;
import org.jetbrains.annotations.Nullable;
import ru.ancap.commons.debug.AncapDebug;
import ru.ancap.framework.communicate.message.CallableMessage;
import ru.ancap.framework.database.nosql.ConfigurationDatabase;
import ru.ancap.framework.database.nosql.PathDatabase;
import ru.ancap.framework.plugin.api.AncapPlugin;
import ru.ancap.hexagon.GridOrientation;
import ru.ancap.hexagon.common.Point;
import ru.ancap.states.commands.*;
import ru.ancap.states.dynmap.DynmapDrawer;
import ru.ancap.states.hexagons.AncapHexagonalGrid;
import ru.ancap.states.listeners.AncapStatesEventsListeners.CityMoveListener;
import ru.ancap.states.listeners.AncapStatesEventsListeners.ProtectListener;
import ru.ancap.states.listeners.AncapStatesEventsListeners.StateFormationsListener;
import ru.ancap.states.listeners.StatesListener;
import ru.ancap.states.listeners.TimerListeners.TimerListener;
import ru.ancap.states.main.AncapStatesDatabaseType;
import ru.ancap.states.main.AncapStatesInfo;
import ru.ancap.states.player.AncapStatesPlayer;
import ru.ancap.states.player.AncapStatesPlayerMap;
import ru.ancap.states.player.PlayerCommand;
import ru.ancap.states.states.CityMap;
import ru.ancap.states.states.Nation.Nation;
import ru.ancap.states.states.city.City;
import ru.ancap.states.top.AncapTop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

public class AncapStates extends AncapPlugin {
    
    public static Nation TEST = null;
    
    private final Joiner.MapJoiner defaultMapJoiner = Joiner
        .on(";\n")
        .withKeyValueSeparator(": ");

    public static final String DYNMAP_MARKER_SET_ID = "ancap";
    public static ConfigurationSection CONFIGURATION;
    
    public static final int gridHexagonSideSize = 100;
    public static final int mapScape = 1000;
    public static final int landTerritory = 5413;

    private static AncapStates INSTANCE;

    private Logger log = Bukkit.getLogger();

    private static boolean test;

    private static PathDatabase statesDB;

    private static PathDatabase idDB;

    private static DynmapDrawer drawer = new DynmapDrawer();

    private static DynmapAPI dynmap = (DynmapAPI) Bukkit.getServer().getPluginManager().getPlugin("Dynmap");

    private static CityMap cityMap;

    public static AncapHexagonalGrid grid = new AncapHexagonalGrid(GridOrientation.FLAT,
        new Point(
            AncapStates.gridHexagonSideSize,
            AncapStates.gridHexagonSideSize
        ),
        new Point(0,0)
    );

    public static HashMap<String, City> playerLocations = new HashMap<>();

    public static AncapTop getTop() {
        return new AncapTop();
    }

    public static CityMap cityMap() {
        return cityMap;
    }
    
    @Deprecated
    public static CityMap getCityMap() {
        return cityMap;
    }

    public static AncapStatesPlayerMap getPlayerMap() {
        return AncapStatesPlayerMap.getInstance();
    }

    public static PathDatabase getMainDatabase() {
        return statesDB;
    }

    public static PathDatabase getAncapStatesDatabase(AncapStatesDatabaseType type) {
        switch (type) {
            case IDLINK_DATABASE -> {
                return idDB;
            }
        }
        return null;
    }

    public static MarkerSet getDynmapMarkerSet() {
        return AncapStates.getDynmap().getMarkerAPI().getMarkerSet(DYNMAP_MARKER_SET_ID);
    }

    public static double globalPopulation() {
        @Nullable Double globalPopulation = getMainDatabase().readNumber("global-population");
        if (globalPopulation == null) return 0;
        return globalPopulation;
    }

    public static void incrementGlobalPopulation() {
        statesDB.write("global-population", AncapStates.globalPopulation() + 1);
    }

    public static void decrementGlobalPopulation() {
        statesDB.write("global-population", AncapStates.globalPopulation() - 1);
    }

    public static AncapStates instance() {
        return INSTANCE;
    }
    
    private Map<String, String> debugMayors;

    @Override
    public void onEnable() {
        super.onEnable();
        this.setInstance();
        CONFIGURATION = this.configuration();
        this.setUpDBs();
        this.setUpConfig();
        this.registerEventsListeners();
        this.registerCityMap();
        this.drawDynMap();
        int mayorRecheckPeriodSeconds = 5;
        int mayorRecheckPeriodTicks = (int) (mayorRecheckPeriodSeconds * this.ancap().targetTPS());
        
        /*
        double purpose: prefill map with mayors to save them in load message and
        prefill map to catch city creations in timer debug method
        */
        this.rewriteDebugMayorMap();
        
        this.dumpDebugMayorMap("Running AncapStates with following mayors: ");
        
        Bukkit.getScheduler().runTaskTimer(this, () -> { for (City city : AncapStates.cityMap().cities()) {
            String cityId = city.id();
            String currentMayor = city.mayor().id();
            if (currentMayor == null) throw new IllegalStateException();
            String oldMayor = this.debugMayors.get(cityId);
            this.debugMayors.put(cityId, currentMayor);
            if (oldMayor == null) continue;
            if (!Objects.equals(oldMayor, currentMayor)) AncapDebug.debug(
                "MAYOR DEBUG: mayor changed", "in city", cityId,
                "from", oldMayor, "to", currentMayor
            );
        }}, 5, mayorRecheckPeriodTicks);
        
        this.registerCommands();
        this.log.info("Done!");
    }
    
    private void rewriteDebugMayorMap() {
        Map<String, String> mayors = new HashMap<>();
        for (City city : AncapStates.cityMap().cities()) mayors.put(city.id(), city.mayor().id());
        this.debugMayors = mayors;
    }
    
    private void drawDynMap() {
        DynmapDrawer.redrawDynmap();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        this.dumpMayors("Saving AncapStates with following mayors: ");
        idDB.save();
        statesDB.save();
        this.dumpMayors("Exiting AncapStates with following mayors: ");
    }
    
    private void dumpMayors(String text) {
        this.rewriteDebugMayorMap();
        this.dumpDebugMayorMap(text);
    }
    
    private void dumpDebugMayorMap(String text) {
        AncapDebug.debug("MAYOR DEBUG: " + text + this.defaultMapJoiner.join(this.debugMayors));
    }
    
    private void registerCityMap() {
        cityMap = new CityMap();
    }

    private void setUpConfig() {
        
    }

    private void setUpDBs() {
        statesDB = ConfigurationDatabase.builder().plugin(this).build();
        idDB = ConfigurationDatabase.builder().plugin(this)
            .name("ids.yml").build();
    }

    private void setInstance() {
        INSTANCE = this;
    }

    private void registerEventsListeners() {
        this.registerEventsListener(new ProtectListener());
        this.registerEventsListener(new CityMoveListener());
        this.registerEventsListener(new StateFormationsListener());
        this.registerEventsListener(new TimerListener());
        this.registerEventsListener(new StatesListener());
    }

    private void registerCommands() {
        PluginCommand city = Bukkit.getServer().getPluginCommand("city");
        city.setExecutor(new CityCommand());
        city.setTabCompleter(new CityCommand());
        PluginCommand nation = Bukkit.getServer().getPluginCommand("nation");
        nation.setExecutor(new NationCommand());
        nation.setTabCompleter(new NationCommand());
        PluginCommand ancapstates = Bukkit.getServer().getPluginCommand("ancapstates");
        ancapstates.setExecutor(new AncapStatesCommand());
        ancapstates.setTabCompleter(new AncapStatesCommand());
        this.getServer().getPluginCommand("test").setExecutor(new TestCommand());
        this.getServer().getPluginCommand("player").setExecutor(new PlayerCommand());
        this.getServer().getPluginCommand("here").setExecutor(new HereCommand());
        
        this.commandRegistrar().register("ancap-states-2", new AncapStates2Input(this.localeHandle()));
    }

    public boolean isTest() {
        return test;
    }

    public void enableTest() {
        test = true;
    }

    public void disableTest() {
        test = false;
    }

    public static DynmapAPI getDynmap() {
        return dynmap;
    }

    public static void sendMessage(CallableMessage message) {
        AncapStatesPlayer[] ancapStatesPlayers = AncapStates.getPlayerMap().getOnlinePlayers();
        for (int i = 0; i< ancapStatesPlayers.length; i++) {
            ancapStatesPlayers[i].sendMessage(message);
        }
    }

    public static void grabTaxes() {
        AncapStates.grabCitiesTaxes();
        AncapStates.grabNationTaxes();
    }

    private static void grabNationTaxes() {
        List<Nation> nations = AncapStates.cityMap().getNations();
        for (Nation nation : nations) {
            nation.grabTaxes();
        }
    }

    private static void grabCitiesTaxes() {
        List<City> cities = AncapStates.cityMap().cities();
        for (City city : cities) {
            city.grabTaxes();
        }
    }

    public static void collectTaxes() {
        AncapStates.collectCityTaxes();
        AncapStates.collectNationTaxes();
    }

    private static void collectNationTaxes() {
        List<Nation> nations = cityMap().getNations();
        for (Nation nation : nations) {
            nation.collectTaxes();
        }
    }

    private static void collectCityTaxes() {
        List<City> cities = cityMap().cities();
        for (City city : cities) city.collectTaxes();
    }
        
    public static AncapStatesInfo getInfo() {
        return new AncapStatesInfo();
    }
    
}
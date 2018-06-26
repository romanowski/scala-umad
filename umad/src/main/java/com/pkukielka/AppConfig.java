package com.pkukielka;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;

public class AppConfig {
    public boolean shouldPrintWarnings = true;
    public boolean verbose = false;
    private Config monitorConfig;
    private Config chaosConfig;

    private static AppConfig instance;

    public static synchronized AppConfig get(){
        if(instance == null){
            instance = new AppConfig();
        }
        return instance;
    }

    public Config getMonitorConfig() {
        return monitorConfig;
    }

    public Config getChaosConfig() {
        return chaosConfig;
    }

    private Config loadConfig(String name, Config in){
        if(!in.hasPath(name)) {
            System.out.println("[WARN] No config for: " + name);
            return ConfigFactory.empty().withValue("enabled", ConfigValueFactory.fromAnyRef(false));
        }
        return in.getConfig(name);
    }

    private AppConfig() {
        Config conf = ConfigFactory.defaultOverrides().withFallback(ConfigFactory.load());

        if (conf == null || !conf.hasPath("umad"))
            throw new RuntimeException("Config should contains 'umad' node.");
        else conf = conf.getConfig("umad");

        this.monitorConfig = loadConfig("monitor", conf);
        this.chaosConfig = loadConfig("chaos", conf);

        if (conf.hasPath("shouldPrintWarnings"))
            this.shouldPrintWarnings = conf.getBoolean("shouldPrintWarnings");

        if (conf.hasPath("verbose"))
            this.verbose = conf.getBoolean("verbose");
    }
}

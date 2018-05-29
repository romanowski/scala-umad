package com.pkukielka;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    public int intervalMs = 100;
    public boolean shouldThrowExceptions = true;
    public boolean shouldPrintStackTrace = false;
    public boolean verbose = false;
    public List<? extends Config> includes = new ArrayList<Config>();
    public List<? extends Config> excludes = new ArrayList<Config>();


    public AppConfig() {
        Config conf = ConfigFactory.defaultOverrides().withFallback(ConfigFactory.load());

        if (conf == null || !conf.hasPath("umad")) return;
        else conf = conf.getConfig("umad");

        if (conf.hasPath("excludes")) {
            this.excludes = conf.getConfigList("excludes");
        }

        if (conf.hasPath("includes")) {
            this.includes = conf.getConfigList("includes");
        }

        if (conf.hasPath("shouldThrowExceptions")) {
            this.shouldThrowExceptions = conf.getBoolean("shouldThrowExceptions");
        }

        if (conf.hasPath("intervalMs")) {
            this.intervalMs = conf.getInt("intervalMs");
        }

        if (conf.hasPath("shouldPrintStackTrace")) {
            this.shouldPrintStackTrace = conf.getBoolean("shouldPrintStackTrace");
        }

        if (conf.hasPath("verbose")) {
            this.verbose = conf.getBoolean("verbose");
        }
    }
}

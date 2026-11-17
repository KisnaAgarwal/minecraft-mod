package com.kisna.harvestguardian;

public enum GuardianMode {
    FOLLOW("Follow"), STAY("Stay"), PATROL("Patrol"), FARM("Farm");

    public final String label;
    GuardianMode(String label) { this.label = label; }
    public static GuardianMode fromId(int id) {
        return id >= 0 && id < values().length ? values()[id] : PATROL;
    }
}

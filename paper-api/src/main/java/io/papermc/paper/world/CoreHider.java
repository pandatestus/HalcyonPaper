package io.papermc.paper.world;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface CoreHider {

    void refresh();
    void setBlue(Location min, Location max);
    void setBlue(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    void setRed(Location min, Location max);
    void setRed(int minX, int minY, int minZ, int maxX, int maxY, int maxZ);
    void addBluePlayer(Player player);
    void addRedPlayer(Player player);
    void addSpectator(Player player);
}

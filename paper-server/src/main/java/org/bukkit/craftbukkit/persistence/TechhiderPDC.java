package org.bukkit.craftbukkit.persistence;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.HullHider;
import net.minecraft.server.level.Techhider;
import org.bukkit.NamespacedKey;

public class TechhiderPDC extends CraftPersistentDataContainer {

    private static final NamespacedKey UPDATE_KEY = new NamespacedKey("techhider", "update");
    private static final NamespacedKey HULLHIDER_REFRESH_KEY = new NamespacedKey("hullhider", "calculate");

    private final CraftPersistentDataContainer container;
    private final Techhider techhider;

    public TechhiderPDC(CraftPersistentDataContainer playerPDC, Techhider techhider) {
        super(playerPDC.getDataTagTypeRegistry());
        this.techhider = techhider;
        this.container = playerPDC;

        MinecraftServer.LOGGER.info("techhiderpdc created");
    }

    @Override
    public boolean has(final NamespacedKey key) {
        if (key.equals(UPDATE_KEY)) techhider.update();
        if (key.equals(HULLHIDER_REFRESH_KEY)) HullHider.applyRayTrace();
        return super.has(key);
    }

    public Techhider techhider() {
        return techhider;
    }
}

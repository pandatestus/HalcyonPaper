package io.papermc.paper.block;

import org.bukkit.Material;
import org.bukkit.World;

@FunctionalInterface
public interface BlockHidingPredicate {

    boolean test(
        Material mat,
        int x,
        int y,
        int z
    );
}

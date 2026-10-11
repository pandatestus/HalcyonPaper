package org.bukkit.craftbukkit;

import io.papermc.paper.block.BlockHidingPredicate;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Material;
import org.bukkit.craftbukkit.entity.CraftPlayer;

public class BlockHider {

    private static final EnumSet<Material> blocks = EnumSet.of(
        Material.OBSERVER, Material.PISTON, Material.PISTON_HEAD, Material.MOVING_PISTON, Material.STICKY_PISTON,
        Material.REDSTONE_WIRE, Material.REPEATER, Material.COMPARATOR, Material.SLIME_BLOCK, Material.HONEY_BLOCK,
        Material.NOTE_BLOCK, Material.HOPPER, Material.WATER, Material.STONE_BUTTON, Material.DETECTOR_RAIL,
        Material.TNT, Material.POWERED_RAIL, Material.CHEST, Material.TRAPPED_CHEST, Material.STONE_PRESSURE_PLATE,
        Material.IRON_DOOR, Material.REDSTONE_BLOCK, Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH, Material.BREWING_STAND,
        Material.TRIPWIRE_HOOK, Material.TRIPWIRE, Material.ACTIVATOR_RAIL, Material.DROPPER, Material.LEVER
    );

    public static final BlockState ENDSTONE = Blocks.END_STONE.defaultBlockState();
    public static final BlockState STRUCTURE_VOID = Blocks.STRUCTURE_VOID.defaultBlockState();

    private static final BlockState[] hiderPreset = new BlockState[]{ENDSTONE, STRUCTURE_VOID};

    public static BlockState[] getHiderPreset() {
        return hiderPreset;
    }

    public static boolean shouldHide(CraftPlayer player, Material material, BlockPos pos) {
        BlockHidingPredicate predicate = player.getHideTechIf();
        if (predicate == null) return blocks.contains(material);

        return predicate.test(material, pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockState getState(CraftPlayer player) {
        return player.hasXray() ? STRUCTURE_VOID : ENDSTONE;
    }

    public static EnumSet<Material> getBlocks() {
        return blocks;
    }
}

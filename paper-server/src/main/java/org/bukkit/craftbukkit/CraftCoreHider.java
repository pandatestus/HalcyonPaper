package org.bukkit.craftbukkit;


import io.papermc.paper.world.CoreHider;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.HlcyRegion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public class CraftCoreHider implements CoreHider {

    private HlcyRegion blue;
    private HlcyRegion red;

    private final ObjectOpenHashSet<BlockPos> unhiddenRed = new ObjectOpenHashSet<>();
    private final ObjectOpenHashSet<BlockPos> unhiddenBlue = new ObjectOpenHashSet<>();

    private final Long2IntOpenHashMap blueChamberByPos = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap redChamberByPos = new Long2IntOpenHashMap();

    private final List<LongArrayList> blueChambers = new ArrayList<>();
    private final List<LongArrayList> redChambers = new ArrayList<>();

    private final Set<Integer> unhiddenBlueChambers = new HashSet<>();
    private final Set<Integer> unhiddenRedChambers = new HashSet<>();

    private final Set<UUID> teamBlue = new HashSet<>();
    private final Set<UUID> teamRed = new HashSet<>();

    private BlockPos blueSignPos;
    private BlockPos redSignPos;

    @Override
    public void refresh() {
        applyRayTrace();
    }

    @Override
    public void setBlue(final Location min, final Location max) {
        this.blue = new HlcyRegion(min.getBlockX(), min.getBlockY(), min.getBlockZ(), max.getBlockX(), max.getBlockY(), max.getBlockZ());
    }

    @Override
    public void setBlue(final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ) {
        this.blue = new HlcyRegion(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public void setRed(final Location min, final Location max) {
        this.red = new HlcyRegion(min.getBlockX(), min.getBlockY(), min.getBlockZ(), max.getBlockX(), max.getBlockY(), max.getBlockZ());
    }

    @Override
    public void setRed(final int minX, final int minY, final int minZ, final int maxX, final int maxY, final int maxZ) {
        this.red = new HlcyRegion(minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public void addBluePlayer(final Player player) {
        teamBlue.add(player.getUniqueId());
        player.recalculateTechHider();
    }

    @Override
    public void addRedPlayer(final Player player) {
        teamRed.add(player.getUniqueId());
        player.recalculateTechHider();
    }

    @Override
    public void addSpectator(final Player player) {
        teamBlue.remove(player.getUniqueId());
        teamRed.remove(player.getUniqueId());
        player.recalculateTechHider();
    }

    @Override
    public void clearUnhidden() {
        unhiddenRed.clear();
        unhiddenBlue.clear();
        unhiddenBlueChambers.clear();
        unhiddenRedChambers.clear();
        blueChambers.clear();
        blueChamberByPos.clear();
        redChambers.clear();
        redChamberByPos.clear();
        blueSignPos = redSignPos = null;
    }

    public boolean shouldHideCore(BlockPos pos, UUID uuid) {
        if (red == null || blue == null) return false;

        return (red.isInside(pos)
            && !teamRed.contains(uuid)
            && !unhiddenRed.contains(pos))

            || (blue.isInside(pos)
            && !teamBlue.contains(uuid)
            && !unhiddenBlue.contains(pos));
    }

    private static final EnumSet<Material> fallingBlocks = EnumSet.of(
        Material.GRAVEL, Material.SUSPICIOUS_GRAVEL, Material.SAND, Material.SUSPICIOUS_SAND, Material.RED_SAND,
        Material.BLACK_CONCRETE_POWDER, Material.BLUE_CONCRETE_POWDER, Material.BROWN_CONCRETE_POWDER, Material.GRAY_CONCRETE_POWDER,
        Material.LIGHT_GRAY_CONCRETE_POWDER, Material.LIGHT_BLUE_CONCRETE_POWDER, Material.RED_CONCRETE_POWDER, Material.YELLOW_CONCRETE_POWDER,
        Material.PURPLE_CONCRETE_POWDER, Material.PINK_CONCRETE_POWDER, Material.MAGENTA_CONCRETE_POWDER, Material.CYAN_CONCRETE_POWDER,
        Material.LIME_CONCRETE_POWDER, Material.GREEN_CONCRETE_POWDER, Material.WHITE_CONCRETE_POWDER, Material.ORANGE_CONCRETE_POWDER
    );

    private static final Predicate<Block> isBlocked = block -> {
        org.bukkit.Material type = block.getType();
        if (type.isAir()) return false;
        if (block.isLiquid()) return false;
        if (fallingBlocks.contains(type)) return false;
        if (type.isOccluding()) return true;
        return BlockHider.getBlocks().contains(type);
    };

    private static final Predicate<BlockState> isBlockedNMS = block -> {
        org.bukkit.Material type = block.getBukkitMaterial();
        if (type.isAir()) return false;
        if (!block.getFluidState().isEmpty()) return false;
        if (fallingBlocks.contains(type)) return false;
        if (type.isOccluding()) return true;
        return BlockHider.getBlocks().contains(type);
    };

    public void applyRayTrace() {
        World world = Bukkit.getWorld("world");
        if (world == null) return;


        MinecraftServer.LOGGER.info("calculating CoreHider");
        long ns = System.nanoTime();

        calculateBlue(world);
        calculateRed(world);

        MinecraftServer.getServer().execute(() -> {
            for (ServerPlayer serverPlayer : MinecraftServer.getServer().getPlayerList().players) {
                serverPlayer.getBukkitEntity().recalculateTechHider();

                if (!teamBlue.contains(serverPlayer.getUUID()) && blueSignPos != null) {
                    SignHider.sendStolenSign(serverPlayer, blueSignPos);
                }
                if (!teamRed.contains(serverPlayer.getUUID()) && redSignPos != null) {
                    SignHider.sendStolenSign(serverPlayer, redSignPos);
                }
            }
        });

        long nowNS = System.nanoTime();
        long time = nowNS - ns;
        MinecraftServer.LOGGER.info("done calculating CoreHider, took " + String.format("%.3fms", time / 1_000_000.0));
    }

    public void applyBlockTrace(BlockPos pos) {
        World world = Bukkit.getWorld("world");
        if (world == null) return;

        if (red == null || blue == null) return;

        boolean isInsideRed = red.isInside(pos);
        boolean isInsideBlue = blue.isInside(pos);
        if (isInsideBlue) {
            rayTraceSingleBlock(world, blue, unhiddenBlue, pos);
        }

        if (isInsideRed) {
            rayTraceSingleBlock(world, red, unhiddenRed, pos);
        }
    }

    private void rayTraceSingleBlock(World world, HlcyRegion region, ObjectOpenHashSet<BlockPos> unhidden, BlockPos pos) {
        Map<SectionPos, List<BlockPos>> changedBySection = new HashMap<>();

        //Front
        for (int z = region.minZ(); z <= region.maxZ(); z++) {
            Block block = world.getBlockAt(pos.getX(), pos.getY(), z);
            BlockPos unhiddenPos = new BlockPos(pos.getX(), pos.getY(), z);
            if (unhidden.add(pos)) {
                changedBySection.computeIfAbsent(SectionPos.of(unhiddenPos), k -> new ArrayList<>()).add(unhiddenPos);
            }
            if (isBlocked.test(block)) break;
        }

        //Back
        for (int z = region.maxZ(); z >= region.minZ(); z--) {
            Block block = world.getBlockAt(pos.getX(), pos.getY(), z);
            BlockPos unhiddenPos = new BlockPos(pos.getX(), pos.getY(), z);
            if (unhidden.add(pos)) {
                changedBySection.computeIfAbsent(SectionPos.of(unhiddenPos), k -> new ArrayList<>()).add(unhiddenPos);
            }
            if (isBlocked.test(block)) break;
        }

        //Top
        for (int y = region.maxY(); y >= region.minY(); y--) {
            Block block = world.getBlockAt(pos.getX(), y, pos.getZ());
            BlockPos unhiddenPos = new BlockPos(pos.getX(), y, pos.getZ());
            if (unhidden.add(pos)) {
                changedBySection.computeIfAbsent(SectionPos.of(unhiddenPos), k -> new ArrayList<>()).add(unhiddenPos);
            }
            if (isBlocked.test(block)) break;
        }

        //Right
        for (int x = region.minX(); x <= region.maxX(); x++) {
            Block block = world.getBlockAt(x, pos.getY(), pos.getZ());
            BlockPos unhiddenPos = new BlockPos(x, pos.getY(), pos.getZ());
            if (unhidden.add(pos)) {
                changedBySection.computeIfAbsent(SectionPos.of(unhiddenPos), k -> new ArrayList<>()).add(unhiddenPos);
            }
            if (isBlocked.test(block)) break;
        }

        //Left
        for (int x = region.maxX(); x >= region.minX(); x--) {
            Block block = world.getBlockAt(x, pos.getY(), pos.getZ());
            BlockPos unhiddenPos = new BlockPos(x, pos.getY(), pos.getZ());
            if (unhidden.add(pos)) {
                changedBySection.computeIfAbsent(SectionPos.of(unhiddenPos), k -> new ArrayList<>()).add(unhiddenPos);
            }
            if (isBlocked.test(block)) break;
        }

        if (!changedBySection.isEmpty()) {
            ServerLevel level = ((CraftWorld) world).getHandle();
            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> {
                MinecraftServer.getServer().execute(() -> {
                    sendBlockUpdates(level, changedBySection);
                });
            });
        }
    }

    private final ConcurrentMap<Integer, Entity> entityLookup = new ConcurrentHashMap<>();

    public void tick() {
        ServerLevel level = MinecraftServer.getServer().overworld();
        if (red == null || blue == null) return;

        IntOpenHashSet currentlyHidden = new IntOpenHashSet();

        List<Entity> blueEntities = getEntitiesInRegion(level, blue);
        List<Entity> redEntities = getEntitiesInRegion(level, red);

        IntArrayList newlyHiddenBlue = new IntArrayList();
        IntArrayList newlyHiddenRed = new IntArrayList();

        for (Entity entity : blueEntities) {
            if (unhiddenBlue.contains(entity.blockPosition())) continue;

            entityLookup.put(entity.getId(), entity);

            if (currentlyHidden.add(entity.getId())) {
                newlyHiddenBlue.add(entity.getId());
            }
        }

        for (Entity entity : redEntities) {
            if (unhiddenRed.contains(entity.blockPosition())) continue;

            entityLookup.put(entity.getId(), entity);

            if (currentlyHidden.add(entity.getId())) {
                newlyHiddenRed.add(entity.getId());
            }
        }

        if (!newlyHiddenBlue.isEmpty()) {
            sendBatchHide(newlyHiddenBlue, serverPlayer -> {
                UUID uuid = serverPlayer.getUUID();
                return !this.teamBlue.contains(uuid);
            });
        }
        if (!newlyHiddenRed.isEmpty()) {
            sendBatchHide(newlyHiddenRed, serverPlayer -> {
                UUID uuid = serverPlayer.getUUID();
                return !this.teamRed.contains(uuid);
            });
        }

        List<Entity> toShow = new ArrayList<>();
        Iterator<Integer> it = entityLookup.keySet().iterator();
        while (it.hasNext()) {
            int id = it.next();
            if (!currentlyHidden.contains(id)) {
                Entity entity = entityLookup.remove(id);
                if (entity != null && !entity.isRemoved()) {
                    toShow.add(entity);
                }
                it.remove();
            }
        }

        if (!toShow.isEmpty()) {
            showEntitiesForPlayers(toShow);
        }
    }

    private static void sendBatchHide(
        IntArrayList ids,
        Predicate<ServerPlayer> shouldHideFor
    ) {
        for (ServerPlayer viewer : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            if (!shouldHideFor.test(viewer)) continue;

            IntList idList = new IntArrayList(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.getInt(i);
                if (id == viewer.getId()) continue;

                idList.add(id);
            }

            if (idList.isEmpty()) continue;

            viewer.connection.send(new ClientboundRemoveEntitiesPacket(idList));
        }
    }

    private static void showEntitiesForPlayers(List<? extends Entity> tntList) {
        for (ServerPlayer serverPlayer : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            for (Entity entity : tntList) {
                if (entity.getUUID().equals(serverPlayer.getUUID())) continue;
                ClientboundAddEntityPacket addPacket =
                    new ClientboundAddEntityPacket(
                        entity.getId(),
                        entity.getUUID(),
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        entity.getXRot(),
                        entity.getYRot(),
                        entity.getType(),
                        0,
                        entity.getDeltaMovement(),
                        entity.getYHeadRot()
                    );
                serverPlayer.connection.send(addPacket);
                serverPlayer.connection.send(new ClientboundSetEntityDataPacket(
                    entity.getId(), entity.getEntityData().packAll()));
            }
        }
    }

    private static List<Entity> getEntitiesInRegion(ServerLevel level, HlcyRegion region) {
        AABB box = new AABB(
            region.minX(), region.minY(), region.minZ(),
            region.maxX(), region.maxY(), region.maxZ()
        );
        return level.getEntitiesOfClass(Entity.class, box);
    }

    private void calculateBlue(org.bukkit.World world) {
        unhiddenBlue.clear();
        if (blue != null) {
            //Front
            for (int x = blue.minX(); x <= blue.maxX(); x++) {
                for (int y = blue.minY(); y <= blue.maxY(); y++) {
                    for (int z = blue.maxZ(); z >= blue.minZ(); z--) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenBlue.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            break;
                        }
                    }
                }
            }
            //LeftSide
            for (int y = blue.minY(); y <= blue.maxY(); y++) {
                for (int z = blue.minZ(); z <= blue.maxZ(); z++) {
                    for (int x = blue.minX(); x <= blue.maxX(); x++) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenBlue.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            break;
                        }
                    }
                }
            }
            //RightSide
            for (int y = blue.minY(); y <= blue.maxY(); y++) {
                for (int z = blue.minZ(); z <= blue.maxZ(); z++) {
                    for (int x = blue.maxX(); x >= blue.minX(); x--) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenBlue.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            break;
                        }
                    }
                }
            }
            //BackSite
            for (int x = blue.minX(); x <= blue.maxX(); x++) {
                for (int y = blue.minY(); y <= blue.maxY(); y++) {
                    for (int z = blue.minZ(); z <= blue.maxZ(); z++) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenBlue.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            break;
                        }
                    }
                }
            }
            //Top
            for (int x = blue.minX(); x <= blue.maxX(); x++) {
                for (int z = blue.minZ(); z <= blue.maxZ(); z++) {
                    for (int y = blue.maxY(); y >= blue.minY(); y--) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenBlue.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            blueSignPos = new BlockPos(x, y - 1, z);
                            break;
                        }
                    }
                }
            }

            //AIR
            calculateChambers(world, blue, blueChamberByPos, blueChambers);
            addUnhiddenChambers(blueChambers, blueChamberByPos, unhiddenBlue, unhiddenBlueChambers);

            outer:
            for (int x = blue.minX(); x <= blue.maxX(); x++) {
                for (int y = blue.minY(); y <= blue.maxY(); y++) {
                    for (int z = blue.minZ(); z <= blue.maxZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!unhiddenBlue.contains(pos)) {
                            blueSignPos = pos;
                            break outer;
                        }
                    }
                }
            }
        }
    }

    private void calculateRed(org.bukkit.World world) {
        unhiddenRed.clear();
        if (red != null) {
            //Front
            for (int x = red.minX(); x <= red.maxX(); x++) {
                for (int y = red.minY(); y <= red.maxY(); y++) {
                    for (int z = red.minZ(); z <= red.maxZ(); z++) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenRed.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            break;
                        }
                    }
                }
            }
            //LeftSide
            for (int y = red.minY(); y <= red.maxY(); y++) {
                for (int z = red.minZ(); z <= red.maxZ(); z++) {
                    for (int x = red.minX(); x <= red.maxX(); x++) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenRed.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            break;
                        }
                    }
                }
            }
            //RightSide
            for (int y = red.minY(); y <= red.maxY(); y++) {
                for (int z = red.minZ(); z <= red.maxZ(); z++) {
                    for (int x = red.maxX(); x >= red.minX(); x--) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenRed.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            break;
                        }
                    }
                }
            }
            //BackSite
            for (int x = red.minX(); x <= red.maxX(); x++) {
                for (int y = red.minY(); y <= red.maxY(); y++) {
                    for (int z = red.maxZ(); z >= red.minZ(); z--) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenRed.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            break;
                        }
                    }
                }
            }
            //Top
            for (int x = red.minX(); x <= red.maxX(); x++) {
                for (int z = red.minZ(); z <= red.maxZ(); z++) {
                    for (int y = red.maxY(); y >= red.minY(); y--) {
                        Block block = world.getBlockAt(x, y, z);
                        unhiddenRed.add(new BlockPos(x, y, z));
                        if (isBlocked.test(block)) {
                            break;
                        }
                    }
                }
            }

            //AIR
            calculateChambers(world, red, redChamberByPos, redChambers);
            addUnhiddenChambers(redChambers, redChamberByPos, unhiddenRed, unhiddenRedChambers);

            outer:
            for (int x = red.minX(); x <= red.maxX(); x++) {
                for (int y = red.minY(); y <= red.maxY(); y++) {
                    for (int z = red.minZ(); z <= red.maxZ(); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!unhiddenRed.contains(pos)) {
                            redSignPos = pos;
                            break outer;
                        }
                    }
                }
            }
        }
    }

    private static final int[] DX = {0, 0, -1, 1, 0, 0};
    private static final int[] DY = {0, 0, 0, 0, -1, 1};
    private static final int[] DZ = {-1, 1, 0, 0, 0, 0};

    private static int countAirNeighbours(
        long pos,
        LongOpenHashSet air
    ) {
        int x = BlockPos.getX(pos);
        int y = BlockPos.getY(pos);
        int z = BlockPos.getZ(pos);

        int count = 0;

        for (int i = 0; i < 6; i++) {
            long neighbour = BlockPos.asLong(
                x + DX[i],
                y + DY[i],
                z + DZ[i]
            );

            if (air.contains(neighbour)) {
                count++;
            }
        }

        return count;
    }

    private static void calculateChambers(
        World world,
        HlcyRegion region,
        Long2IntOpenHashMap chamberByPos,
        List<LongArrayList> chambers
    ) {
        chamberByPos.clear();
        chambers.clear();
        chamberByPos.defaultReturnValue(-1);

        LongOpenHashSet air = new LongOpenHashSet();
        LongOpenHashSet nonAir = new LongOpenHashSet();

        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int y = region.minY(); y <= region.maxY(); y++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    if (world.getBlockAt(x, y, z).getType().isAir()) {
                        air.add(BlockPos.asLong(x, y, z));
                    } else {
                        nonAir.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }

        LongOpenHashSet eligible = new LongOpenHashSet();

        for (LongIterator it = air.iterator(); it.hasNext(); ) {
            long pos = it.nextLong();
            if (countAirNeighbours(pos, air) >= 5) {
                eligible.add(pos);
            }
        }

        air = null;

        LongOpenHashSet visitedNonAir = new LongOpenHashSet();

        for (LongIterator it = eligible.iterator(); it.hasNext(); ) {
            long start = it.nextLong();

            if (chamberByPos.containsKey(start)) continue;

            int chamberId = chambers.size();
            LongArrayList chamber = new LongArrayList();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

            chamberByPos.put(start, chamberId);
            queue.enqueue(start);
            chamber.add(start);

            while (!queue.isEmpty()) {
                long current = queue.dequeueLong();

                int x = BlockPos.getX(current);
                int y = BlockPos.getY(current);
                int z = BlockPos.getZ(current);

                for (int i = 0; i < 6; i++) {
                    long neighbour = BlockPos.asLong(
                        x + DX[i],
                        y + DY[i],
                        z + DZ[i]
                    );

                    if (eligible.contains(neighbour) && !chamberByPos.containsKey(neighbour)) {
                        chamberByPos.put(neighbour, chamberId);
                        queue.enqueue(neighbour);
                        chamber.add(neighbour);
                    }
                    else if (nonAir.contains(neighbour) && visitedNonAir.add(neighbour)) {
                        chamber.add(neighbour);
                        chamberByPos.put(neighbour, chamberId);
                    }
                }
            }

            chambers.add(chamber);
        }
    }

    private static void addUnhiddenChambers(
        List<LongArrayList> chambers,
        Long2IntOpenHashMap chamberByPos,
        ObjectOpenHashSet<BlockPos> unhidden,
        Set<Integer> unhiddenChambers
    ) {
        Int2IntOpenHashMap unhiddenCountPerChamber = new Int2IntOpenHashMap();
        unhiddenCountPerChamber.defaultReturnValue(0);

        for (BlockPos pos : unhidden) {
            int chamberId = chamberByPos.get(pos.asLong());
            if (chamberId >= 0) {
                unhiddenCountPerChamber.put(chamberId, unhiddenCountPerChamber.get(chamberId) + 1);
            }
        }

        for (int chamberId : unhiddenCountPerChamber.keySet()) {
            var chamber = chambers.get(chamberId);
            int unhiddenCount = unhiddenCountPerChamber.get(chamberId);

            if (chamber.size() >= 200 && unhiddenCount * 50 >= chamber.size()) {
                if (unhiddenChambers.add(chamberId)) {
                    for (int i = 0; i < chamber.size(); i++) {
                        unhidden.add(BlockPos.of(chamber.getLong(i)));
                    }
                }
            }
        }
    }

    private static final Map<SectionPos, List<BlockPos>> pendingUpdates = new HashMap<>();
    private static boolean flushScheduled = false;

    public void notifyBlocksDestroyed(World world, BlockPos origin, Collection<BlockPos> destroyedPositions) {
        if (blue == null || red == null) return;

        ServerLevel level = ((CraftWorld) world).getHandle();
        destroyedPositions.add(origin);

        Map<SectionPos, List<BlockPos>> changedBySection = new HashMap<>();

        for (BlockPos pos : destroyedPositions) {
            boolean isInsideBlue = blue.isInside(pos);
            boolean isInsideRed = red.isInside(pos);

            if (!isInsideBlue && !isInsideRed) continue;

            if (isBlockedNMS.test(level.getBlockState(pos))) continue;

            boolean addedBlue = isInsideBlue && unhiddenBlue.add(pos);
            boolean addedRed = isInsideRed && unhiddenRed.add(pos);

            int chamberIdBlue = blueChamberByPos.get(pos.asLong());
            if (chamberIdBlue >= 0 && unhiddenBlueChambers.add(chamberIdBlue)) {
                LongArrayList chamber = blueChambers.get(chamberIdBlue);

                for (int i = 0; i < chamber.size(); i++) {
                    BlockPos chamberPos = BlockPos.of(chamber.getLong(i));

                    if (blue.isInside(chamberPos) && unhiddenBlue.add(chamberPos)) {
                        changedBySection.computeIfAbsent(SectionPos.of(chamberPos), k -> new ArrayList<>()).add(chamberPos);
                    }
                }
            }

            int chamberIdRed = redChamberByPos.get(pos.asLong());
            if (chamberIdRed >= 0 && unhiddenRedChambers.add(chamberIdRed)) {
                LongArrayList chamber = redChambers.get(chamberIdRed);

                for (int i = 0; i < chamber.size(); i++) {
                    BlockPos chamberPos = BlockPos.of(chamber.getLong(i));

                    if (red.isInside(chamberPos) && unhiddenRed.add(chamberPos)) {
                        changedBySection.computeIfAbsent(SectionPos.of(chamberPos), k -> new ArrayList<>()).add(chamberPos);
                    }
                }
            }

            if (addedBlue || addedRed) {
                SectionPos sectionPos = SectionPos.of(pos);

                changedBySection.computeIfAbsent(sectionPos, k -> new ArrayList<>()).add(pos);
            }
        }

        if (!changedBySection.isEmpty()) {
            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> {
                MinecraftServer.getServer().execute(() -> {
                    sendBlockUpdates(level, changedBySection);
                });
            });
        }
    }

    private void sendBlockUpdates(ServerLevel level, Map<SectionPos, List<BlockPos>> changedBySection) {
        for (Map.Entry<SectionPos, List<BlockPos>> entry : changedBySection.entrySet()) {
            SectionPos sectionPos = entry.getKey();
            List<BlockPos> positions = entry.getValue();

            int size = positions.size();
            short[] posShorts = new short[size];
            BlockState[] states = new BlockState[size];

            for (int i = 0; i < size; i++) {
                BlockPos pos = positions.get(i);
                int relX = pos.getX() - sectionPos.minBlockX();
                int relY = pos.getY() - sectionPos.minBlockY();
                int relZ = pos.getZ() - sectionPos.minBlockZ();
                posShorts[i] = (short) ((relX << 8) | (relZ << 4) | relY);
                states[i] = level.getBlockState(pos);
            }

            net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket packet =
                new net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket(
                    sectionPos, posShorts, states
                );

            for (ServerPlayer serverPlayer : level.players()) {
                serverPlayer.connection.send(packet);
            }
        }
    }

    public boolean shouldHideSign(ServerPlayer player, BlockPos pos) {
        if (pos.equals(blueSignPos) && !teamBlue.contains(player.getUUID())) return false;
        if (pos.equals(redSignPos) && !teamRed.contains(player.getUUID())) return false;
        return true;
    }

    public BlockPos getBlueSignPos() {
        return blueSignPos;
    }

    public BlockPos getRedSignPos() {
        return redSignPos;
    }

    public boolean isTeamBlue(UUID uuid) {
        return teamBlue.contains(uuid);
    }

    public boolean isTeamRed(UUID uuid) {
        return teamRed.contains(uuid);
    }
}

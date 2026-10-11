package org.bukkit.craftbukkit;


import io.papermc.paper.world.CoreHider;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
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

    private Long2IntOpenHashMap blueChamberByPos = new Long2IntOpenHashMap();
    private Long2IntOpenHashMap redChamberByPos = new Long2IntOpenHashMap();

    private List<LongArrayList> blueChambers = new ArrayList<>();
    private List<LongArrayList> redChambers = new ArrayList<>();

    private final Set<Integer> unhiddenBlueChambers = new HashSet<>();
    private final Set<Integer> unhiddenRedChambers = new HashSet<>();

    private final Set<UUID> teamBlue = new HashSet<>();
    private final Set<UUID> teamRed = new HashSet<>();

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

    private static final ScheduledExecutorService EXECUTOR =
        Executors.newSingleThreadScheduledExecutor();

    public void applyRayTrace() {
        World world = Bukkit.getWorld("world");
        if (world == null) return;


        MinecraftServer.LOGGER.info("calculating CoreHider");
        long ns = System.nanoTime();

        calculateBlue(world);
        calculateRed(world);

        EXECUTOR.schedule(() -> MinecraftServer.getServer().execute(() -> {
            for (ServerPlayer serverPlayer : MinecraftServer.getServer().getPlayerList().players) {
                serverPlayer.getBukkitEntity().recalculateTechHider();
            }
        }), 5, TimeUnit.SECONDS);

        long nowNS = System.nanoTime();
        long time = nowNS - ns;
        MinecraftServer.LOGGER.info("done calculating CoreHider, took " + String.format("%.3fms", time / 1_000_000.0));
    }

    private final ConcurrentMap<Integer, Entity> entityLookup = new ConcurrentHashMap<>();

    public void tick() {
        long ns = System.nanoTime();
        boolean shouldLog = Bukkit.getCurrentTick() % 100 == 0;

        ServerLevel level = MinecraftServer.getServer().overworld();
        if (red == null || blue == null) return;

        if (shouldLog) {
            MinecraftServer.LOGGER.info("ticking CoreHider");
        }


        IntOpenHashSet currentlyHidden = new IntOpenHashSet();

        List<PrimedTnt> blueTNT = getTntInRegion(level, blue);
        List<PrimedTnt> redTNT = getTntInRegion(level, red);

        List<ServerPlayer> bluePlayers = getPlayerInRegion(level, blue);
        List<ServerPlayer> redPlayers = getPlayerInRegion(level, red);

        IntArrayList newlyHiddenBlue = new IntArrayList();
        IntArrayList newlyHiddenRed = new IntArrayList();

        for (PrimedTnt tnt : blueTNT) {
            if (unhiddenBlue.contains(tnt.blockPosition())) continue;

            entityLookup.put(tnt.getId(), tnt);

            if (currentlyHidden.add(tnt.getId())) {
                newlyHiddenBlue.add(tnt.getId());
            }
        }

        for (PrimedTnt tnt : redTNT) {
            if (unhiddenRed.contains(tnt.blockPosition())) continue;

            entityLookup.put(tnt.getId(), tnt);

            if (currentlyHidden.add(tnt.getId())) {
                newlyHiddenRed.add(tnt.getId());
            }
        }

        for (ServerPlayer player : bluePlayers) {
            if (unhiddenBlue.contains(new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ())))
                continue;

            entityLookup.put(player.getId(), player);

            if (currentlyHidden.add(player.getId())) {
                newlyHiddenBlue.add(player.getId());
            }
        }

        for (ServerPlayer player : redPlayers) {
            if (unhiddenRed.contains(new BlockPos(player.getBlockX(), player.getBlockY(), player.getBlockZ())))
                continue;

            entityLookup.put(player.getId(), player);

            if (currentlyHidden.add(player.getId())) {
                newlyHiddenRed.add(player.getId());
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

        if (shouldLog) {
            long nowNS = System.nanoTime();
            long time = nowNS - ns;
            MinecraftServer.LOGGER.info("done ticking CoreHider, took " + String.format("%.3fms", time / 1_000_000.0));
        }
    }

    private static void sendBatchHide(
        IntArrayList ids,
        Predicate<ServerPlayer> shouldHideFor
    ) {
        for(ServerPlayer viewer : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            if(!shouldHideFor.test(viewer)) continue;

            IntList idList = new IntArrayList(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.getInt(i);
                if(id == viewer.getId()) continue;

                idList.add(id);
            }

            if(idList.isEmpty()) continue;

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

    private static List<PrimedTnt> getTntInRegion(ServerLevel level, HlcyRegion region) {
        AABB box = new AABB(
            region.minX(), region.minY(), region.minZ(),
            region.maxX(), region.maxY(), region.maxZ()
        );
        return level.getEntitiesOfClass(PrimedTnt.class, box);
    }

    private static List<ServerPlayer> getPlayerInRegion(ServerLevel level, HlcyRegion region) {
        AABB box = new AABB(
            region.minX(), region.minY(), region.minZ(),
            region.maxX(), region.maxY(), region.maxZ()
        );
        return level.getEntitiesOfClass(ServerPlayer.class, box);
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
                            break;
                        }
                    }
                }
            }

            //AIR
            calculateChambers(world, blue, blueChamberByPos, blueChambers);
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

    private static void addIfEligible(
        long pos,
        int chamberId,
        LongOpenHashSet eligible,
        Long2IntOpenHashMap chamberByPos,
        LongArrayFIFOQueue queue,
        LongArrayList chamber
    ) {
        if (!eligible.contains(pos) || chamberByPos.containsKey(pos)) {
            return;
        }

        chamberByPos.put(pos, chamberId);
        queue.enqueue(pos);
        chamber.add(pos);
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

        // Phase 1: Air-Positionen einmal erfassen.
        LongOpenHashSet air = new LongOpenHashSet();

        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int y = region.minY(); y <= region.maxY(); y++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    if (world.getBlockAt(x, y, z).getType().isAir()) {
                        air.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }

        // Phase 2: Air-Blöcke mit mindestens vier Air-Nachbarn.
        LongOpenHashSet eligible = new LongOpenHashSet();

        for (LongIterator it = air.iterator(); it.hasNext();) {
            long pos = it.nextLong();

            if (countAirNeighbours(pos, air) >= 4) {
                eligible.add(pos);
            }
        }

        // Die Air-Menge wird danach nicht mehr benötigt.
        air = null;

        // Phase 3: Zusammenhangskomponenten der geeigneten Blöcke finden.
        for (LongIterator it = eligible.iterator(); it.hasNext();) {
            long start = it.nextLong();

            if (chamberByPos.containsKey(start)) {
                continue;
            }

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

                    addIfEligible(
                        neighbour,
                        chamberId,
                        eligible,
                        chamberByPos,
                        queue,
                        chamber
                    );
                }
            }

            chambers.add(chamber);
        }
    }

    private static final Map<SectionPos, List<BlockPos>> pendingUpdates = new HashMap<>();
    private static boolean flushScheduled = false;

    public void notifyBlocksDestroyed(World world, BlockPos origin, Collection<BlockPos> destroyedPositions) {
        if (blue == null && red == null) return;

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
            if(chamberIdBlue >= 0 && unhiddenBlueChambers.add(chamberIdBlue)) {
                LongArrayList chamber = blueChambers.get(chamberIdBlue);

                for(int i = 0; i < chamber.size(); i++) {
                    BlockPos chamberPos = BlockPos.of(chamber.getLong(i));

                    if(blue.isInside(chamberPos) && unhiddenBlue.add(chamberPos)) {
                        changedBySection.computeIfAbsent(SectionPos.of(chamberPos), k -> new ArrayList<>()).add(chamberPos);
                    }
                }
            }

            int chamberIdRed = redChamberByPos.get(pos.asLong());
            if(chamberIdRed >= 0 && unhiddenRedChambers.add(chamberIdRed)) {
                LongArrayList chamber = redChambers.get(chamberIdRed);

                for(int i = 0; i < chamber.size(); i++) {
                    BlockPos chamberPos = BlockPos.of(chamber.getLong(i));

                    if(red.isInside(chamberPos) && unhiddenRed.add(chamberPos)) {
                        changedBySection.computeIfAbsent(SectionPos.of(chamberPos), k -> new ArrayList<>()).add(chamberPos);
                    }
                }
            }

            if (addedBlue || addedRed) {
                SectionPos sectionPos = SectionPos.of(pos);

                changedBySection.computeIfAbsent(sectionPos, k -> new ArrayList<>()).add(pos);
            }
        }

        if (!changedBySection.isEmpty()) queueForBatchSend(level, changedBySection);
    }

    private static void queueForBatchSend(ServerLevel level, Map<SectionPos, List<BlockPos>> changedBySection) {
        synchronized (pendingUpdates) {
            changedBySection.forEach((section, positions) ->
                pendingUpdates.computeIfAbsent(section, k -> new ArrayList<>()).addAll(positions)
            );

            if (!flushScheduled) {
                flushScheduled = true;
                MinecraftServer.getServer().execute(() -> flushPendingUpdates(level));
            }
        }
    }

    private static void flushPendingUpdates(ServerLevel level) {
        Map<SectionPos, List<BlockPos>> toSend;
        synchronized (pendingUpdates) {
            toSend = new HashMap<>(pendingUpdates);
            pendingUpdates.clear();
            flushScheduled = false;
        }

        if (toSend.isEmpty()) return;

        Set<ChunkPos> affectedChunks = new HashSet<>();
        for (SectionPos sectionPos : toSend.keySet()) {
            affectedChunks.add(new ChunkPos(sectionPos.x(), sectionPos.z()));
        }

        CompletableFuture.delayedExecutor(200, TimeUnit.MILLISECONDS).execute(() -> {
            MinecraftServer.getServer().execute(() -> {
                for (ServerPlayer serverPlayer : level.players()) {
                    resendChunks(serverPlayer, level, affectedChunks);
                }
            });
        });
    }

    private static void resendChunks(ServerPlayer player, ServerLevel level, Set<ChunkPos> chunks) {
        for (ChunkPos chunkPos : chunks) {
            LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
            PlayerChunkSender.sendChunk(player.connection, player.level(), chunk);
        }
    }
}

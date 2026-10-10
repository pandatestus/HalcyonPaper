package io.papermc.paper.antixray;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArraySet;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.level.Techhider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.Material;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ChunkPacketBlockController {

    public static final ChunkPacketBlockController NO_OPERATION_INSTANCE = new ChunkPacketBlockController();

    protected ChunkPacketBlockController() {
    }

    public BlockState[] getPresetBlockStates(Level level, ChunkPos chunkPos, int chunkSectionY) {
        return new BlockState[]{Techhider.ENDSTONE, Blocks.STRUCTURE_VOID.defaultBlockState()};
    }

    public boolean shouldModify(ServerPlayer player, LevelChunk chunk) {
        return true;
    }

    public ChunkPacketInfo<BlockState> getChunkPacketInfo(ClientboundLevelChunkWithLightPacket chunkPacket, LevelChunk chunk) {
        return new ChunkPacketInfo<>(chunkPacket, chunk);
    }

    public void modifyBlocks(ClientboundLevelChunkWithLightPacket chunkPacket, ChunkPacketInfo<BlockState> chunkPacketInfo) {
        net.minecraft.server.MinecraftServer.LOGGER.info("skipped");
        chunkPacket.setReady(true);
    }

    public void modifyBlocks(ClientboundLevelChunkWithLightPacket chunkPacket, ChunkPacketInfo<BlockState> chunkPacketInfo, ServerPlayer player) {
        obfuscate(chunkPacketInfo, player);
        chunkPacketInfo.getChunkPacket().setReady(true);
    }

    public void obfuscate(ChunkPacketInfo<BlockState> chunkPacketInfo, ServerPlayer player) {
        if (chunkPacketInfo == null || player == null) {
            net.minecraft.server.MinecraftServer.LOGGER.info("chunk or player null");
            return;
        }

        Techhider techhider = player.getBukkitEntity().getTechhider();

        BitStorageReader bitStorageReader = new BitStorageReader();
        BitStorageWriter bitStorageWriter = new BitStorageWriter();
        LevelChunk chunk = chunkPacketInfo.getChunk();

        if(!techhider.shouldHideChunk(chunk)) return;

        int maxChunkSectionIndex = chunk.getSectionsCount() - 1;

        bitStorageReader.setBuffer(chunkPacketInfo.getBuffer());
        bitStorageWriter.setBuffer(chunkPacketInfo.getBuffer());

        for (int chunkSectionIndex = 0; chunkSectionIndex <= maxChunkSectionIndex; chunkSectionIndex++) {

            LevelChunkSection section = chunk.getSections()[chunkSectionIndex];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            PalettedContainer<BlockState> states = section.getStates();
            Palette<BlockState> palette = states.data.palette();

            int paletteSize = palette.getSize();

            int[] remap = new int[paletteSize];
            int disguiseIndex = -1;

            BlockState disguiseState = techhider.getObfuscationState();
            for(int disguiseI = 0; disguiseI < paletteSize; disguiseI++) {
                if(palette.valueFor(disguiseI).getBukkitMaterial().name().equalsIgnoreCase(disguiseState.getBukkitMaterial().name())) {
                    disguiseIndex = disguiseI;
                    break;
                }
            }

            if(disguiseIndex == -1) {
                disguiseIndex = 0;

                ShortArrayList positions = new ShortArrayList();
                BlockState[] newStates = collectHiddenBlocksInSection(chunk, chunkSectionIndex, section, techhider, positions);

                if(!positions.isEmpty() && techhider.shouldHideChunk(chunk)) {
                    SectionPos sectionPos = SectionPos.of(chunk.getPos(), chunk.getSectionYFromSectionIndex(chunkSectionIndex));

                    ClientboundSectionBlocksUpdatePacket updatePacket = new ClientboundSectionBlocksUpdatePacket(sectionPos, positions.toShortArray(), newStates);

                    CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS).execute(() -> {
                        MinecraftServer.getServer().execute(() -> player.connection.send(updatePacket));
                    });
                }
            }

            bitStorageReader.setBits(chunkPacketInfo.getBits(chunkSectionIndex));
            bitStorageWriter.setBits(chunkPacketInfo.getBits(chunkSectionIndex));
            bitStorageReader.setIndex(chunkPacketInfo.getIndex(chunkSectionIndex));
            bitStorageWriter.setIndex(chunkPacketInfo.getIndex(chunkSectionIndex));

            for (int idx = 0; idx < 4096; idx++) {
                int originalIndex = bitStorageReader.read();
                BlockState state = palette.valueFor(originalIndex);

                int localX = idx & 15;
                int localZ = (idx >> 4) & 15;
                int localY = (idx >> 8) & 15;

                BlockPos worldPos = new BlockPos(
                    (chunk.getPos().x << 4) + localX,
                    (chunk.getSectionYFromSectionIndex(chunkSectionIndex) << 4) + localY,
                    (chunk.getPos().z << 4) + localZ
                );

                int newIndex;
                if (techhider.isHidden(state) || techhider.getHullHider().shouldFillEndstone(worldPos)) {
                    newIndex = disguiseIndex;
                } else {
                    newIndex = originalIndex;
                }

                bitStorageWriter.write(newIndex);
            }

            bitStorageWriter.flush();
        }
    }

    private BlockState[] collectHiddenBlocksInSection(LevelChunk chunk, int chunkSectionIndex, LevelChunkSection section, Techhider techhider, ShortArrayList positions) {
        BlockState disguise = techhider.getObfuscationState();
        List<BlockState> states = new ArrayList<>();

        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;
        int baseY = chunk.getSectionYFromSectionIndex(chunkSectionIndex) << 4;

        for(int x = 0; x < 16; x++) {
            for(int y = 0; y < 16; y++) {
                for(int z = 0; z < 16; z++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if(techhider.isHidden(state) || techhider.getHullHider().shouldFillEndstone(new BlockPos(baseX + x, baseY + y, baseZ + z))) {
                        states.add(disguise);
                        positions.add((short) ((x << 8) | (z << 4) | y));
                    }
                }
            }
        }

        return states.toArray(new BlockState[0]);
    }

    public void onBlockChange(Level level, BlockPos blockPos, BlockState newBlockState, BlockState oldBlockState, int flags, int maxUpdateDepth) {
        updateNearbyBlocks(level, blockPos);
    }

    public void onPlayerLeftClickBlock(ServerPlayerGameMode serverPlayerGameMode, BlockPos blockPos, ServerboundPlayerActionPacket.Action action, Direction direction, int worldHeight, int sequence) {
        updateNearbyBlocks(serverPlayerGameMode.level, blockPos);
    }

    private void updateNearbyBlocks(Level level, BlockPos blockPos) {
        updateBlock(level, blockPos.west());
        updateBlock(level, blockPos.east());
        updateBlock(level, blockPos.below());
        updateBlock(level, blockPos.above());
        updateBlock(level, blockPos.north());
        updateBlock(level, blockPos.south());
    }

    private void updateBlock(Level level, BlockPos blockPos) {
        BlockState blockState = level.getBlockStateIfLoaded(blockPos);

        if (blockState != null && Techhider.isStateHidden(blockState)) {
            ((ServerLevel) level).getChunkSource().blockChanged(blockPos);
        }
    }
}

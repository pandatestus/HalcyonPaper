package org.bukkit.craftbukkit;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;

public class SignHider {

    public static boolean isSign(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SignBlockEntity;
    }

    public static ClientboundBlockEntityDataPacket editText(ServerPlayer player, BlockPos pos, ServerLevel level) {
        if (!(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return null;

        CompoundTag nbt = sign.saveWithFullMetadata(level.registryAccess());

        return new ClientboundBlockEntityDataPacket(pos, sign.getType(), modifyNBT(player, nbt));
    }

    public static CompoundTag modifyNBT(ServerPlayer player, CompoundTag nbt) {

        try {
            CompoundTag frontText = new CompoundTag();
            CompoundTag backText = new CompoundTag();
            ListTag messages = new ListTag();
            ListTag backMessages = new ListTag();

            int maxWidth = 200;
            String key = "stolen-schematic";
            String encryptedKey = encrypt(key);
            String keyLine = " " + " ".repeat(maxWidth - encryptedKey.length()) + encryptedKey;

            String encryptedPlayerName = encrypt(player.displayName);
            String playerNameLine = " " + " ".repeat(maxWidth - encryptedPlayerName.length()) + encryptedPlayerName;

            String encryptedUUID = encrypt(player.getUUID().toString());
            String uuidLine = " " + " ".repeat(maxWidth - encryptedUUID.length()) +encryptedUUID;

            long now = System.currentTimeMillis();
            String timeStamp = "" + now;
            String encryptedTimeStamp = encrypt(timeStamp);
            String timeStampLine = " " + " ".repeat(maxWidth - encryptedTimeStamp.length()) + encryptedTimeStamp;

            Component[] frontLines = new Component[]{
                Component.literal(""),
                Component.literal(""),
                Component.literal(""),
                Component.literal("")
            };

            Component[] backLines = new Component[]{
                Component.literal(keyLine),
                Component.literal(playerNameLine),
                Component.literal(uuidLine),
                Component.literal(timeStampLine)
            };

            for (Component line : frontLines) {
                messages.add(StringTag.valueOf(
                    line.getString()
                ));
            }

            for (Component line : backLines) {
                backMessages.add(StringTag.valueOf(
                    line.getString()
                ));
            }

            frontText.put("messages", messages);
            frontText.putBoolean("has_glowing_text", false);
            frontText.putString("color", "black");

            backText.put("messages", backMessages);
            backText.putBoolean("has_glowing_text", false);
            backText.putString("color", "black");

            nbt.put("front_text", frontText);
            nbt.put("back_text", backText);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return nbt;
    }

    public static void sendStolenSign(ServerPlayer player, BlockPos pos) {
        Bukkit.getLogger().info("stolen sign at " + pos);

        BlockState signState = Blocks.OAK_WALL_SIGN.defaultBlockState();
        player.connection.send(new ClientboundBlockUpdatePacket(pos, signState));

        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> {
            CompoundTag nbt = new CompoundTag();
            nbt.putInt("x", pos.getX());
            nbt.putInt("y", pos.getY());
            nbt.putInt("z", pos.getZ());

            player.connection.send(new ClientboundBlockEntityDataPacket(
                pos,
                BlockEntityType.SIGN,
                modifyNBT(player, nbt)
            ), null);
        });
    }

    private static final String KEY = "Halcyon-Theft-Protection";

    public static String encrypt(String text) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public static String decrypt(String encrypted) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec);
        byte[] decoded = Base64.getDecoder().decode(encrypted);
        return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
    }
}

package com.neovetta.aicompanion.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputUtil;
import com.neovetta.aicompanion.AiCompanion;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBind;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** On-demand screenshot analysis, bound to V. No image is sent unless the player presses the key. */
public final class CompanionVision {
    private static final Path CONFIG_PATH = Path.of("config", "aicompanion", "vision.json");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private static volatile boolean requestInFlight;

    private CompanionVision() {}

    public static void register() {
        KeyBind visionKey = KeyBindingHelper.registerKeyBinding(new KeyBind(
                "key.aicompanion.vision", InputUtil.Type.KEYSYM,
                org.lwjgl.glfw.GLFW.GLFW_KEY_V, "key.category.aicompanion"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (visionKey.wasPressed()) {
                captureAndAnalyze(client);
            }
        });
    }

    private static void captureAndAnalyze(MinecraftClient client) {
        if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
            return;
        }
        if (requestInFlight) {
            tell(client, "Компаньон уже анализирует изображение.", Formatting.YELLOW);
            return;
        }

        final byte[] png;
        final String gameState;
        try {
            int width = client.getFramebuffer().textureWidth;
            int height = client.getFramebuffer().textureHeight;

            if (width <= 0 || height <= 0) {
                throw new IOException("Invalid framebuffer dimensions");
            }

            java.nio.ByteBuffer pixels =
                    java.nio.ByteBuffer.allocateDirect(width * height * 4);

            client.getFramebuffer().beginWrite(false);
            try {
                org.lwjgl.opengl.GL11.glReadPixels(
                        0, 0, width, height,
                        org.lwjgl.opengl.GL11.GL_RGBA,
                        org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                        pixels
                );
            } finally {
                client.getFramebuffer().endWrite();
            }

            java.awt.image.BufferedImage screenshot =
                    new java.awt.image.BufferedImage(
                            width, height,
                            java.awt.image.BufferedImage.TYPE_INT_ARGB
                    );

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = (y * width + x) * 4;
                    int r = pixels.get(index) & 0xFF;
                    int g = pixels.get(index + 1) & 0xFF;
                    int b = pixels.get(index + 2) & 0xFF;
                    int a = pixels.get(index + 3) & 0xFF;

                    screenshot.setRGB(x, height - 1 - y,
                            (a << 24) | (r << 16) | (g << 8) | b);
                }
            }

            try (java.io.ByteArrayOutputStream output =
                         new java.io.ByteArrayOutputStream()) {
                if (!javax.imageio.ImageIO.write(screenshot, "png", output)) {
                    throw new IOException("PNG encoder unavailable");
                }
                png = output.toByteArray();
            }

            gameState = collectGameState(client);
        } catch (IOException | RuntimeException e) {
            AiCompanion.LOGGER.warn("[{}] Could not capture screenshot for vision", AiCompanion.MOD_ID, e);
            tell(client, "Не удалось сделать скриншот: " + shortError(e), Formatting.RED);
            return;
        }

        VisionSettings settings;
        try {
            settings = loadSettings();
        } catch (IOException e) {
            tell(client, "Не удалось прочитать config/aicompanion/vision.json: " + shortError(e), Formatting.RED);
            return;
        }

        requestInFlight = true;
        tell(client, "Смотрю на экран…", Formatting.GRAY);
        CompletableFuture.runAsync(() -> {
            try {
                String description = askVision(settings, png, gameState);
                String chatMessage = buildChatMessage(description, gameState);
                client.execute(() -> {
                    try {
                        if (client.player != null && client.getNetworkHandler() != null) {
                            // The mod's existing companion input is chat-driven. Send a concise
                            // observation through that same path so the companion can answer it.
                            client.getNetworkHandler().sendChatMessage(chatMessage);
                            tell(client, "Изображение проанализировано; передал наблюдение компаньону.", Formatting.GREEN);
                        }
                    } finally {
                        requestInFlight = false;
                    }
                });
            } catch (Exception e) {
                AiCompanion.LOGGER.warn("[{}] Vision request failed", AiCompanion.MOD_ID, e);
                client.execute(() -> {
                    tell(client, "Ошибка vision-модели: " + shortError(e), Formatting.RED);
                    requestInFlight = false;
                });
            }
        });
    }

    private static String collectGameState(MinecraftClient client) {
        var player = client.player;
        var world = client.world;
        String health = Math.round(player.getHealth() * 10f) / 10f + "/" + Math.round(player.getMaxHealth() * 10f) / 10f;
        String coords = MathHelper.floor(player.getX()) + "," + MathHelper.floor(player.getY()) + "," + MathHelper.floor(player.getZ());
        String dimension = world.getRegistryKey().getValue().toString();

        List<Entity> nearby = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (entity instanceof LivingEntity && !(entity instanceof PlayerEntity) && entity.isAlive()
                    && entity != player && entity.squaredDistanceTo(player) <= 24 * 24) {
                nearby.add(entity);
            }
        }
        nearby.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(player)));
        List<String> mobNames = new ArrayList<>();
        for (int i = 0; i < Math.min(4, nearby.size()); i++) {
            Entity entity = nearby.get(i);
            mobNames.add(entity.getType().getName().getString() + "(" + Math.round(entity.distanceTo(player)) + "м)");
        }
        String mobs = mobNames.isEmpty() ? "нет" : truncate(String.join(",", mobNames), 32);

        List<String> items = new ArrayList<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                String item = stack.getName().getString() + "x" + stack.getCount();
                if (!items.contains(item)) items.add(item);
                if (items.size() >= 5) break;
            }
        }
        String inventoryText = items.isEmpty() ? "пуст" : truncate(String.join(",", items), 35);
        return "HP " + health + "; XYZ " + coords + "; мир " + truncate(dimension, 20)
                + "; мобы " + mobs + "; инв " + inventoryText;
    }

    private static String askVision(VisionSettings settings, byte[] png, String gameState) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model);
        body.addProperty("stream", false);
        body.addProperty("max_tokens", 140);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "Ты зрение Minecraft-компаньона. Кратко опиши только видимые на скриншоте важные детали: окружение, блоки, угрозы, интерфейс. Не выдумывай невидимое. Ответь по-русски одним коротким предложением до 100 символов. Игровые данные: " + gameState);
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", "Что сейчас видит игрок? Опиши главное.");
        content.add(text);
        JsonObject image = new JsonObject();
        image.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png));
        image.add("image_url", imageUrl);
        content.add(image);
        user.add("content", content);
        messages.add(user);
        body.add("messages", messages);

        String endpoint = settings.endpoint.replaceAll("/+$", "") + "/v1/chat/completions";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(settings.timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (!settings.apiKey.isBlank()) request.header("Authorization", "Bearer " + settings.apiKey);

        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 140));
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String answer = json.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString().trim();
        if (answer.isBlank()) throw new IOException("модель вернула пустой ответ");
        return truncate(answer.replaceAll("\\s+", " "), 100);
    }

    private static String buildChatMessage(String description, String state) {
        // Minecraft chat is limited; retain each category while trimming the verbose parts.
        // Reserve room for every requested game-data category. The game chat protocol has a
        // short message limit, so keep the vision summary concise rather than losing the inventory
        // or coordinates at the end of a long string.
        String prefix = readTriggerPrefix();
        return truncate(prefix + "Посмотри на мой экран: " + truncate(description, 60) + "; "
                + truncate(state, Math.max(60, 145 - prefix.length())), 240);
    }


    private static String readTriggerPrefix() {
        // Respect the owner's existing triggerPrefix setting, otherwise V would silently do
        // nothing for players who configured the companion to answer only prefixed chat.
        Path config = Path.of("config", "aicompanion.json");
        try {
            if (!Files.exists(config)) return "";
            JsonObject root = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
            if (!root.has("behavior") || !root.get("behavior").isJsonObject()) return "";
            JsonObject behavior = root.getAsJsonObject("behavior");
            return behavior.has("triggerPrefix") ? behavior.get("triggerPrefix").getAsString() : "";
        } catch (Exception e) {
            AiCompanion.LOGGER.debug("[{}] Could not read chat triggerPrefix for vision message", AiCompanion.MOD_ID, e);
            return "";
        }
    }

    private static VisionSettings loadSettings() throws IOException {
        Files.createDirectories(CONFIG_PATH.getParent());
        if (!Files.exists(CONFIG_PATH)) {
            JsonObject defaults = new JsonObject();
            defaults.addProperty("endpoint", "http://localhost:11434");
            defaults.addProperty("model", "qwen2.5vl:3b");
            defaults.addProperty("apiKey", "");
            defaults.addProperty("timeoutMs", 60000);
            Files.writeString(CONFIG_PATH, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(defaults));
        }
        JsonObject json = JsonParser.parseString(Files.readString(CONFIG_PATH)).getAsJsonObject();
        String endpoint = json.has("endpoint") ? json.get("endpoint").getAsString().trim() : "http://localhost:11434";
        String model = json.has("model") ? json.get("model").getAsString().trim() : "qwen2.5vl:3b";
        String apiKey = json.has("apiKey") ? json.get("apiKey").getAsString().trim() : "";
        int timeout = json.has("timeoutMs") ? json.get("timeoutMs").getAsInt() : 60000;
        if (endpoint.isBlank() || model.isBlank()) throw new IOException("endpoint и model не должны быть пустыми");
        return new VisionSettings(endpoint, model, apiKey, Math.max(5000, Math.min(timeout, 180000)));
    }

    private static void tell(MinecraftClient client, String message, Formatting color) {
        if (client.player != null) client.player.sendMessage(Text.literal("[AI Vision] " + message).formatted(color), false);
    }

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        return truncate(message == null ? error.getClass().getSimpleName() : message.replaceAll("\\s+", " "), 130);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    private record VisionSettings(String endpoint, String model, String apiKey, int timeoutMs) {}
}

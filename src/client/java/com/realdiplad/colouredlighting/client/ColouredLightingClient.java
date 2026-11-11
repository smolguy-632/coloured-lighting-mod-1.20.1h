package com.realdiplad.colouredlighting.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.realdiplad.colouredlighting.ColouredLighting;
import com.realdiplad.colouredlighting.client.colour.BlockColourCache;
import com.realdiplad.colouredlighting.client.colour.LightColourField;
import com.realdiplad.colouredlighting.client.config.ClConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ColouredLightingClient implements ClientModInitializer {
	private ClientWorld lastWorld;

	@Override
	public void onInitializeClient() {
		ClConfig.load();

		// Textures changed -> every cached colour is wrong. (Vanilla rebuilds all chunks afterwards.)
		ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
			new SimpleSynchronousResourceReloadListener() {
				@Override
				public Identifier getFabricId() {
					return ColouredLighting.id("colour_cache");
				}

				@Override
				public void reload(ResourceManager manager) {
					clearCaches();
				}
			});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.world != lastWorld) { // joined a world / changed dimension
				lastWorld = client.world;
				LightColourField.clear();
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> LightColourField.clear());

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("colouredlighting")
				.then(ClientCommandManager.literal("reload").executes(ctx -> {
					ClConfig.load();
					clearCaches();
					rebuildWorld();
					ctx.getSource().sendFeedback(Text.literal("Coloured Lighting: config reloaded, rebuilding chunks"));
					return 1;
				}))
				.then(ClientCommandManager.literal("toggle").executes(ctx -> {
					boolean now = !ClConfig.get().enabled;
					ClConfig.setEnabled(now);
					rebuildWorld();
					ctx.getSource().sendFeedback(Text.literal("Coloured Lighting: " + (now ? "ON" : "OFF")));
					return 1;
				}))
				.then(ClientCommandManager.literal("stats").executes(ctx -> {
					ctx.getSource().sendFeedback(Text.literal(
						"Coloured Lighting cache: " + BlockColourCache.size() + " block states, "
							+ BlockColourCache.spriteCount() + " textures, "
							+ LightColourField.sectionCount() + " light sections"));
					return 1;
				}))
				.then(ClientCommandManager.literal("strength")
					.executes(ctx -> {
						ClConfig config = ClConfig.get();
						ctx.getSource().sendFeedback(Text.literal(String.format(
							"Coloured Lighting strengths: light %.2f, bounce %.2f, sky weight %.2f "
								+ "(run /colouredlighting strength <light|bounce|sky> <0..1> to change)",
							config.lightStrength, config.bounceStrength, config.skyLightWeight)));
						return 1;
					})
					.then(strengthSubCommand("light"))
					.then(strengthSubCommand("bounce"))
					.then(strengthSubCommand("sky")))));
	}

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> strengthSubCommand(String name) {
		return ClientCommandManager.literal(name)
			.then(ClientCommandManager.argument("value", FloatArgumentType.floatArg(0f, 1f))
				.executes(ctx -> setStrength(ctx, name, FloatArgumentType.getFloat(ctx, "value"))));
	}

	/** Sets one tint strength, persists it to config/coloured-lighting.json and rebuilds chunks
	 *  so the change is visible immediately (tints are baked into chunk meshes). */
	private static int setStrength(CommandContext<FabricClientCommandSource> ctx, String name, float value) {
		ClConfig config = ClConfig.get();
		switch (name) {
			case "light" -> config.lightStrength = value;
			case "bounce" -> config.bounceStrength = value;
			case "sky" -> config.skyLightWeight = value;
		}
		ClConfig.save();
		rebuildWorld();
		ctx.getSource().sendFeedback(Text.literal(String.format(
			"Coloured Lighting: %s strength set to %.2f, rebuilding chunks", name, value)));
		return 1;
	}

	private static void clearCaches() {
		BlockColourCache.clear();
		LightColourField.clear();
	}

	private static void rebuildWorld() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.worldRenderer != null && mc.world != null) {
			mc.worldRenderer.reload();
		}
	}
}

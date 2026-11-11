package com.realdiplad.colouredlighting.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.realdiplad.colouredlighting.ColouredLighting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON config at config/coloured-lighting.json. Reloaded with /colouredlighting reload.
 */
public final class ClConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static volatile ClConfig instance = new ClConfig();

	/** Master switch. */
	public boolean enabled = true;
	/** 0..1: how far block-light hue replaces white (1 = full coloured light). */
	public float lightStrength = 1.0f;
	/** 0..1: colour bleed from neighbouring solid blocks (fake GI). 0 disables the bleed pass. */
	public float bounceStrength = 0.6f;
	/** How much sky light counts against block light when deciding the coloured share. */
	public float skyLightWeight = 0.8f;
	/** Saturation boost applied to colours auto-derived from emitter textures. */
	public float saturationBoost = 1.5f;
	/** A tint channel never goes below this, so surfaces never turn fully black. */
	public float minChannel = 0.08f;
	/** Max 16x16x16 sections kept in the light-colour cache before it is flushed. */
	public int cacheMaxSections = 2048;
	/** Used when a colour can't be derived. */
	public String defaultEmitterColour = "#FFB96A";
	/** Per-block overrides, e.g. "minecraft:torch": "#FF8800". Wins over built-ins and auto colour. */
	public Map<String, String> emitters = new LinkedHashMap<>();

	private static final Map<String, Integer> BUILTIN = new HashMap<>();

	static {
		int warm = 0xFFA94D;
		for (String id : new String[]{"torch", "wall_torch", "lantern", "campfire", "fire", "jack_o_lantern"}) {
			BUILTIN.put("minecraft:" + id, warm);
		}
		int soul = 0x42C8FF;
		for (String id : new String[]{"soul_torch", "soul_wall_torch", "soul_lantern", "soul_campfire", "soul_fire"}) {
			BUILTIN.put("minecraft:" + id, soul);
		}
		BUILTIN.put("minecraft:lava", 0xFF7A21);
		BUILTIN.put("minecraft:magma_block", 0xFF6A1F);
		BUILTIN.put("minecraft:shroomlight", 0xFF9A52);
		BUILTIN.put("minecraft:glowstone", 0xFFC970);
		BUILTIN.put("minecraft:redstone_torch", 0xFF3A2A);
		BUILTIN.put("minecraft:redstone_wall_torch", 0xFF3A2A);
		BUILTIN.put("minecraft:redstone_lamp", 0xFFC878);
		BUILTIN.put("minecraft:end_rod", 0xE9E4FF);
		BUILTIN.put("minecraft:sea_lantern", 0xB8FFF0);
		BUILTIN.put("minecraft:beacon", 0xDFF6FF);
		BUILTIN.put("minecraft:conduit", 0x6FE8FF);
		BUILTIN.put("minecraft:respawn_anchor", 0x9B5CFF);
		BUILTIN.put("minecraft:crying_obsidian", 0x8F3CFF);
		BUILTIN.put("minecraft:nether_portal", 0xA24BFF);
		BUILTIN.put("minecraft:ochre_froglight", 0xFFE48A);
		BUILTIN.put("minecraft:verdant_froglight", 0x9CFFB0);
		BUILTIN.put("minecraft:pearlescent_froglight", 0xE8A8FF);
		BUILTIN.put("minecraft:enchanting_table", 0x5FB4FF);
		BUILTIN.put("minecraft:furnace", 0xFF9440);
		BUILTIN.put("minecraft:smoker", 0xFF9440);
		BUILTIN.put("minecraft:blast_furnace", 0xFF9440);
		for (String id : new String[]{"amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud"}) {
			BUILTIN.put("minecraft:" + id, 0xC58CFF);
		}
	}

	private transient int defaultRgb = 0xFFB96A;

	public static ClConfig get() {
		return instance;
	}

	public int defaultRgb() {
		return defaultRgb;
	}

	/** @return 0xRRGGBB override for the block id, or null if there is none. */
	public Integer override(String blockId) {
		String user = emitters.get(blockId);
		if (user != null) {
			Integer parsed = parseHex(user);
			if (parsed != null) return parsed;
		}
		return BUILTIN.get(blockId);
	}

	public static Integer parseHex(String s) {
		try {
			return Integer.parseInt(s.trim().replace("#", ""), 16) & 0xFFFFFF;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void finish() {
		lightStrength = clamp01(lightStrength);
		bounceStrength = clamp01(bounceStrength);
		minChannel = Math.max(0f, Math.min(0.5f, minChannel));
		if (cacheMaxSections < 64) cacheMaxSections = 64;
		if (emitters == null) emitters = new LinkedHashMap<>();
		Integer d = parseHex(defaultEmitterColour);
		defaultRgb = d != null ? d : 0xFFB96A;
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : Math.min(v, 1f);
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("coloured-lighting.json");
	}

	/** Loads the config from disk (writing defaults if absent) and makes it the active one. */
	public static void load() {
		ClConfig cfg = null;
		Path p = path();
		if (Files.exists(p)) {
			try (Reader r = Files.newBufferedReader(p)) {
				cfg = GSON.fromJson(r, ClConfig.class);
			} catch (IOException | RuntimeException e) {
				ColouredLighting.LOGGER.warn("Could not read {} - using defaults", p, e);
			}
		}
		boolean fresh = cfg == null;
		if (fresh) cfg = new ClConfig();
		cfg.finish();
		instance = cfg;
		if (fresh) save();
	}

	public static void save() {
		try (Writer w = Files.newBufferedWriter(path())) {
			GSON.toJson(instance, w);
		} catch (IOException e) {
			ColouredLighting.LOGGER.warn("Could not write config", e);
		}
	}

	public static void setEnabled(boolean value) {
		instance.enabled = value;
		save();
	}
}

package com.homevideo;

import java.util.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

public final class Director {
	private Director() {}

	public static final int FX_GLITCH = 0, FX_STATIC = 1, FX_REWIND = 2, FX_FLASH = 3, FX_BLACK = 4;
	private static final Random RNG = new Random();
	private static final Map<UUID, PD> DATA = new HashMap<>();
	private record Pending(long at, Runnable run) {}
	private static final List<Pending> PENDING = new ArrayList<>();
	private static final Block[] STATIC = {Blocks.BLACK_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.WHITE_CONCRETE, Blocks.SEA_LANTERN, Blocks.BLACK_CONCRETE};
	private static boolean houseTvOn = false;
	private static Scene scene = null;

	private static final class PD {
		long nextAmbient, nextGlitch, nextFigure, stepAt;
		int stepsLeft;
		double stepAng, stepDist;
		PD(long now) {
			nextAmbient = now + 20 * 45;
			nextGlitch = now + 20 * 20;
			nextFigure = now + 20 * 100;
		}
	}

	private static final class Scene {
		UUID player;
		int t = 0, turnAt = -1;
		FigureEntity fig;
	}

	// ---------------------------------------------------------------- init
	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(Director::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoin(handler.player, server));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> DATA.remove(handler.player.getUuid()));
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			dispatcher.register(CommandManager.literal("homevideo").requires(src -> src.hasPermissionLevel(4))
				.then(CommandManager.literal("reset").executes(ctx -> {
					ServerPlayerEntity pl = ctx.getSource().getPlayer();
					if (pl != null) startOver(ctx.getSource().getServer(), pl, 0);
					return 1;
				}))));
	}

	// ---------------------------------------------------------------- helpers
	private static void set(ServerWorld w, int x, int y, int z, BlockState st) {
		w.setBlockState(new BlockPos(x, y, z), st, Block.NOTIFY_LISTENERS);
	}
	private static void set(ServerWorld w, int x, int y, int z, Block b) { set(w, x, y, z, b.getDefaultState()); }

	private static void pending(long at, Runnable r) { PENDING.add(new Pending(at, r)); }

	private static void play(ServerWorld w, double x, double y, double z, SoundEvent ev, float vol, float pitch) {
		w.playSound(null, x, y, z, ev, SoundCategory.AMBIENT, vol, pitch);
	}

	public static void fx(ServerPlayerEntity p, int type, int ticks, float intensity) {
		PacketByteBuf b = PacketByteBufs.create();
		b.writeVarInt(type);
		b.writeVarInt(ticks);
		b.writeFloat(intensity);
		ServerPlayNetworking.send(p, HomeVideo.FX_PACKET, b);
	}

	private static void sendTape(ServerPlayerEntity p, int index, int loop) {
		PacketByteBuf b = PacketByteBufs.create();
		b.writeVarInt(index);
		b.writeVarInt(loop);
		b.writeString(p.getName().getString());
		ServerPlayNetworking.send(p, HomeVideo.TAPE_PACKET, b);
	}

	private static float yawTo(double fx, double fz, double tx, double tz) {
		return (float) (Math.toDegrees(Math.atan2(tz - fz, tx - fx)) - 90.0);
	}

	public static BlockPos findSpot(ServerWorld w, double px, double pz, double ang, double dist) {
		int x = MathHelper.floor(px + Math.cos(ang) * dist);
		int z = MathHelper.floor(pz + Math.sin(ang) * dist);
		if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return null;
		int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos pos = new BlockPos(x, y, z);
		BlockState below = w.getBlockState(pos.down());
		if (!below.isSolidBlock(w, pos.down()) || !w.getFluidState(pos).isEmpty() || !w.getFluidState(pos.down()).isEmpty()) return null;
		return pos;
	}

	private static BlockPos tvPos(State s) { return new BlockPos(s.hx, s.hy + 2, s.hz - 2); }
	private static BlockPos candlePos(State s) { return new BlockPos(s.hx + 2, s.hy + 2, s.hz - 2); }
	private static BlockPos chestPos(State s) { return new BlockPos(s.hx - 3, s.hy + 1, s.hz - 2); }

	// ---------------------------------------------------------------- compass
	private static ItemStack compass(BlockPos target) {
		ItemStack c = new ItemStack(Items.COMPASS);
		NbtCompound n = c.getOrCreateNbt();
		n.putBoolean("LodestoneTracked", false);
		n.putString("LodestoneDimension", "minecraft:overworld");
		n.put("LodestonePos", NbtHelper.fromBlockPos(target));
		n.putBoolean("HomeVideo", true);
		c.setCustomName(Text.literal("Компас").formatted(Formatting.GRAY));
		return c;
	}

	private static void setTarget(MinecraftServer server, State s, BlockPos t) {
		s.hasTarget = true;
		s.tx = t.getX(); s.ty = t.getY(); s.tz = t.getZ();
		s.markDirty();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			for (int i = 0; i < p.getInventory().size(); i++) {
				ItemStack st = p.getInventory().getStack(i);
				if (st.isOf(Items.COMPASS) && st.hasNbt() && st.getNbt().getBoolean("HomeVideo")) {
					st.getNbt().put("LodestonePos", NbtHelper.fromBlockPos(t));
				}
			}
		}
	}

	private static void fillChest(ServerWorld w, State s) {
		if (w.getBlockEntity(chestPos(s)) instanceof ChestBlockEntity ch) {
			ch.clear();
			ch.setStack(0, new ItemStack(HomeVideo.TAPES[0]));
			ch.setStack(1, compass(new BlockPos(s.hx, s.hy + 1, s.hz)));
			ch.setStack(2, new ItemStack(Items.BREAD, 6));
			ch.markDirty();
		}
	}

	private static void addToHouseChest(ServerWorld w, State s, ItemStack st) {
		if (w.getBlockEntity(chestPos(s)) instanceof ChestBlockEntity ch) {
			for (int i = 0; i < ch.size(); i++) {
				if (ch.getStack(i).isEmpty()) { ch.setStack(i, st); ch.markDirty(); return; }
			}
		}
	}

	// ---------------------------------------------------------------- world building
	private static void buildHouse(ServerWorld w, State s) {
		BlockPos sp = w.getSpawnPos();
		int cx = sp.getX(), cz = sp.getZ();
		int fy = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 1;
		s.hx = cx; s.hy = fy; s.hz = cz;
		for (int x = -6; x <= 6; x++) {
			for (int z = -5; z <= 5; z++) {
				boolean inside = Math.abs(x) <= 4 && Math.abs(z) <= 3;
				for (int y = -5; y <= -1; y++) set(w, cx + x, fy + y, cz + z, inside ? Blocks.COBBLESTONE : Blocks.DIRT);
				set(w, cx + x, fy, cz + z, inside ? Blocks.DARK_OAK_PLANKS : Blocks.COARSE_DIRT);
				for (int y = 1; y <= 9; y++) set(w, cx + x, fy + y, cz + z, Blocks.AIR);
			}
		}
		for (int x = -4; x <= 4; x++) {
			for (int z = -3; z <= 3; z++) {
				boolean wall = Math.abs(x) == 4 || Math.abs(z) == 3;
				boolean corner = Math.abs(x) == 4 && Math.abs(z) == 3;
				if (wall) for (int y = 1; y <= 3; y++) set(w, cx + x, fy + y, cz + z, corner ? Blocks.DARK_OAK_LOG : Blocks.SPRUCE_PLANKS);
				set(w, cx + x, fy + 4, cz + z, Blocks.SPRUCE_SLAB);
			}
		}
		int[][] win = {{-4, 0}, {4, 0}, {-2, -3}, {2, -3}, {-2, 3}, {2, 3}};
		for (int[] a : win) set(w, cx + a[0], fy + 2, cz + a[1], Blocks.GLASS);
		set(w, cx, fy + 1, cz + 3, Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH).with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		set(w, cx, fy + 2, cz + 3, Blocks.OAK_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH).with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
		// interior
		set(w, cx, fy + 1, cz - 2, Blocks.DARK_OAK_PLANKS);
		set(w, cx, fy + 2, cz - 2, Blocks.BLACK_CONCRETE);
		set(w, cx + 2, fy + 1, cz - 2, Blocks.SPRUCE_PLANKS);
		set(w, cx + 2, fy + 2, cz - 2, Blocks.CANDLE.getDefaultState().with(AbstractCandleBlock.LIT, true));
		set(w, cx + 3, fy + 1, cz - 1, Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.NORTH).with(BedBlock.PART, BedPart.FOOT));
		set(w, cx + 3, fy + 1, cz - 2, Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.NORTH).with(BedBlock.PART, BedPart.HEAD));
		set(w, cx - 3, fy + 1, cz - 2, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.SOUTH));
		for (int x = -1; x <= 1; x++) for (int z = 0; z <= 1; z++) set(w, cx + x, fy + 1, cz + z, Blocks.RED_CARPET);
		set(w, cx - 3, fy + 3, cz - 2, Blocks.COBWEB);
		set(w, cx + 3, fy + 3, cz + 2, Blocks.COBWEB);
		set(w, cx - 3, fy + 3, cz + 2, Blocks.COBWEB);
		fillChest(w, s);
	}

	private static BlockPos placeSite(ServerWorld w, State s, int tapeIndex) {
		for (int attempt = 0; attempt < 30; attempt++) {
			double ang = RNG.nextDouble() * Math.PI * 2;
			int dist = 55 + (tapeIndex - 2) * 14 + RNG.nextInt(30);
			int x = s.hx + (int) (Math.cos(ang) * dist), z = s.hz + (int) (Math.sin(ang) * dist);
			w.getChunk(x >> 4, z >> 4);
			int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
			BlockState bs = w.getBlockState(new BlockPos(x, top - 1, z));
			if (bs.isAir() || !bs.getFluidState().isEmpty() || top <= w.getSeaLevel()) continue;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					set(w, x + dx, top - 1, z + dz, Blocks.MOSSY_COBBLESTONE);
					for (int y = 0; y <= 4; y++) set(w, x + dx, top + y, z + dz, Blocks.AIR);
				}
			}
			set(w, x, top, z, Blocks.STRIPPED_SPRUCE_LOG);
			set(w, x, top + 1, z, Blocks.STRIPPED_SPRUCE_LOG);
			set(w, x, top + 2, z, Blocks.BLACK_CONCRETE);
			set(w, x - 1, top, z, Blocks.SOUL_LANTERN);
			BlockPos chest = new BlockPos(x + 1, top, z);
			w.setBlockState(chest, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, Direction.WEST), Block.NOTIFY_LISTENERS);
			if (w.getBlockEntity(chest) instanceof ChestBlockEntity ch) {
				ch.setStack(0, new ItemStack(HomeVideo.TAPES[tapeIndex - 1]));
				ch.markDirty();
			}
			s.addTv(new BlockPos(x, top + 2, z));
			return new BlockPos(x, top + 1, z);
		}
		return null;
	}

	// ---------------------------------------------------------------- join / tapes
	private static void onJoin(ServerPlayerEntity p, MinecraftServer server) {
		server.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
		server.getGameRules().get(GameRules.DO_MOB_SPAWNING).set(false, server);
		server.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(false, server);
		ServerWorld ow = server.getOverworld();
		State s = State.get(server);
		if (!s.built) {
			buildHouse(ow, s);
			s.built = true;
			ow.setSpawnPos(new BlockPos(s.hx, s.hy + 1, s.hz + 1), 0f);
			setTarget(server, s, new BlockPos(s.hx, s.hy + 1, s.hz));
			ow.setTimeOfDay(16000);
			ow.setWeather(0, 240000, true, false);
			p.teleport(ow, s.hx + 0.5, s.hy + 1, s.hz + 1.5, 180f, 0f);
			p.setSpawnPoint(World.OVERWORLD, new BlockPos(s.hx, s.hy + 1, s.hz + 1), 0f, true, false);
			s.markDirty();
		}
		fx(p, FX_GLITCH, 60, 1.2f);
		long now = server.getTicks();
		pending(now + 20 * 25, () -> {
			if (s.stage == 0 && s.finalState == 0) {
				play(ow, s.hx - 2.5, s.hy + 1, s.hz - 1.5, SoundEvents.BLOCK_CHEST_OPEN, 1.2f, 0.6f);
			}
		});
	}

	public static void onTape(ServerPlayerEntity p, int index) {
		MinecraftServer server = p.server;
		State s = State.get(server);
		ServerWorld w = server.getOverworld();
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 1f, 0.6f);
		sendTape(p, index, s.loop);
		if (index == s.stage + 1 && s.finalState == 0) {
			s.stage = index;
			s.markDirty();
			if (index < 5) {
				BlockPos site = placeSite(w, s, index + 1);
				if (site != null) {
					setTarget(server, s, site);
				} else {
					addToHouseChest(w, s, new ItemStack(HomeVideo.TAPES[index]));
					setTarget(server, s, new BlockPos(s.hx, s.hy + 1, s.hz));
				}
				long now = server.getTicks();
				pending(now + 20 * 12, () -> play(w, p.getX() + 14, p.getY(), p.getZ() - 14, SoundEvents.BLOCK_CHEST_OPEN, 1.0f, 0.5f));
			} else {
				s.finalState = 1;
				setTarget(server, s, new BlockPos(s.hx, s.hy + 1, s.hz));
			}
		}
	}

	// ---------------------------------------------------------------- main tick
	private static void tick(MinecraftServer server) {
		if (server.getPlayerManager().getPlayerList().isEmpty()) return;
		State s = State.get(server);
		if (!s.built) return;
		ServerWorld ow = server.getOverworld();
		long now = server.getTicks();

		if (!PENDING.isEmpty()) {
			List<Pending> due = new ArrayList<>();
			PENDING.removeIf(pd -> {
				if (pd.at() <= now) { due.add(pd); return true; }
				return false;
			});
			for (Pending pd : due) pd.run().run();
		}

		if (now % 100 == 0 && !ow.isRaining()) ow.setWeather(0, 240000, true, false);
		if (now % 6000 == 0) ow.setTimeOfDay(16000);
		if (now % 3 == 0) flicker(server, ow, s);

		if (s.finalState == 2 && scene == null) { s.finalState = 1; s.markDirty(); }
		if (s.finalState == 1 && scene == null) {
			for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
				if (p.getServerWorld() != ow) continue;
				double dx = p.getX() - (s.hx + 0.5), dz = p.getZ() - (s.hz + 0.5);
				if (dx * dx + dz * dz < 3.5 * 3.5 && Math.abs(p.getY() - (s.hy + 1)) < 3) {
					scene = new Scene();
					scene.player = p.getUuid();
					s.finalState = 2;
					s.markDirty();
					break;
				}
			}
		}
		if (scene != null) tickScene(server, ow, s);

		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (p.getServerWorld() != ow || !p.isAlive() || p.isSpectator()) continue;
			tickPlayer(ow, s, p, now);
		}
	}

	private static void flicker(MinecraftServer server, ServerWorld w, State s) {
		List<ServerPlayerEntity> ps = server.getPlayerManager().getPlayerList();
		for (long l : s.tvs) {
			BlockPos pos = BlockPos.fromLong(l);
			if (!nearAny(ps, pos, 48)) continue;
			if (!w.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
			w.setBlockState(pos, STATIC[RNG.nextInt(STATIC.length)].getDefaultState(), Block.NOTIFY_LISTENERS);
		}
		if (houseTvOn) {
			w.setBlockState(tvPos(s), STATIC[RNG.nextInt(STATIC.length)].getDefaultState(), Block.NOTIFY_LISTENERS);
		}
	}

	private static boolean nearAny(List<ServerPlayerEntity> ps, BlockPos pos, double r) {
		for (ServerPlayerEntity p : ps) {
			if (p.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) < r * r) return true;
		}
		return false;
	}

	private static void tickPlayer(ServerWorld w, State s, ServerPlayerEntity p, long now) {
		PD pd = DATA.computeIfAbsent(p.getUuid(), k -> new PD(now));
		if (pd.stepsLeft > 0 && now >= pd.stepAt) {
			double x = p.getX() + Math.cos(pd.stepAng) * pd.stepDist;
			double z = p.getZ() + Math.sin(pd.stepAng) * pd.stepDist;
			w.playSound(null, x, p.getY(), z, SoundEvents.BLOCK_GRASS_STEP, SoundCategory.AMBIENT, 1.3f, 0.75f);
			pd.stepDist -= 1.9;
			pd.stepsLeft--;
			pd.stepAt = now + 9;
			if (pd.stepsLeft == 0) {
				play(w, p.getX() + Math.cos(pd.stepAng) * 1.8, p.getY(), p.getZ() + Math.sin(pd.stepAng) * 1.8, SoundEvents.ENTITY_PLAYER_BREATH, 1.5f, 0.5f);
			}
		}
		if (s.finalState == 2) return;
		if (now >= pd.nextAmbient) {
			ambient(w, p, pd, now);
			pd.nextAmbient = now + 20L * (14 + RNG.nextInt(30)) - Math.min(s.stage, 5) * 40L;
		}
		if (now >= pd.nextGlitch) {
			fx(p, FX_GLITCH, 8 + RNG.nextInt(30), 0.5f + RNG.nextFloat());
			pd.nextGlitch = now + 20L * (25 + RNG.nextInt(60));
		}
		if (now >= pd.nextFigure) {
			if (!figureNear(w, p)) spawnFigure(w, p, s, false);
			double base = Math.max(55.0, 190.0 - 28.0 * s.stage - 14.0 * Math.min(s.loop, 4));
			pd.nextFigure = now + (long) (20 * base * (0.75 + 0.5 * RNG.nextDouble()));
		}
	}

	private static boolean figureNear(ServerWorld w, ServerPlayerEntity p) {
		return !w.getEntitiesByClass(FigureEntity.class, p.getBoundingBox().expand(100), e -> !e.frozen).isEmpty();
	}

	private static void ambient(ServerWorld w, ServerPlayerEntity p, PD pd, long now) {
		Vec3d look = p.getRotationVec(1f);
		double back = Math.atan2(-look.z, -look.x);
		double a = RNG.nextDouble() * Math.PI * 2;
		switch (RNG.nextInt(6)) {
			case 0 -> {
				pd.stepsLeft = 7;
				pd.stepAt = now;
				pd.stepAng = back + (RNG.nextDouble() - 0.5) * 0.8;
				pd.stepDist = 15;
			}
			case 1 -> {
				double d = 12 + RNG.nextInt(10);
				double x = p.getX() + Math.cos(a) * d, z = p.getZ() + Math.sin(a) * d, y = p.getY();
				play(w, x, y, z, SoundEvents.BLOCK_WOODEN_DOOR_OPEN, 0.9f, 0.6f);
				pending(now + 55, () -> play(w, x, y, z, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, 0.9f, 0.6f));
			}
			case 2 -> play(w, p.getX() + Math.cos(back) * 2.5, p.getY(), p.getZ() + Math.sin(back) * 2.5, SoundEvents.ENTITY_PLAYER_BREATH, 1.6f, 0.5f);
			case 3 -> play(w, p.getX() + Math.cos(a) * 24, p.getY(), p.getZ() + Math.sin(a) * 24, SoundEvents.ENTITY_ZOMBIE_VILLAGER_AMBIENT, 1.4f, 0.45f);
			case 4 -> play(w, p.getX() + Math.cos(a) * 18, p.getY(), p.getZ() + Math.sin(a) * 18, SoundEvents.BLOCK_CHEST_OPEN, 1.0f, 0.5f);
			default -> play(w, p.getX(), p.getY(), p.getZ(), SoundEvents.AMBIENT_CAVE.value(), 1.0f, 1.0f);
		}
	}

	private static void spawnFigure(ServerWorld w, ServerPlayerEntity p, State s, boolean behind) {
		Vec3d look = p.getRotationVec(1f);
		double base = Math.atan2(look.z, look.x);
		double ang, dist;
		if (behind) {
			ang = base + Math.PI + (RNG.nextDouble() - 0.5) * 0.6;
			dist = 5 + RNG.nextDouble() * 2;
		} else if (!s.introDone) {
			ang = base + (RNG.nextDouble() - 0.5) * 0.7;
			dist = 28 + RNG.nextInt(6);
			s.introDone = true;
			s.markDirty();
		} else {
			ang = base + Math.PI * (0.35 + 1.3 * RNG.nextDouble());
			dist = 24 + RNG.nextInt(12);
		}
		BlockPos spot = null;
		for (int i = 0; i < 8 && spot == null; i++) {
			spot = findSpot(w, p.getX(), p.getZ(), ang + (RNG.nextDouble() - 0.5) * 0.5, dist);
		}
		if (spot == null) return;
		FigureEntity f = HomeVideo.FIGURE.create(w);
		if (f == null) return;
		f.refreshPositionAndAngles(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
			yawTo(spot.getX() + 0.5, spot.getZ() + 0.5, p.getX(), p.getZ()), 0f);
		f.setMirror(s.loop > 0);
		if (f.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED) != null) {
			f.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED).setBaseValue(0.25 + 0.012 * s.stage + (s.loop > 0 ? 0.01 : 0));
		}
		w.spawnEntity(f);
	}

	// ---------------------------------------------------------------- figure callbacks
	public static void heartbeat(FigureEntity f, ServerPlayerEntity p, double dist, boolean seen) {
		if (dist > 28) return;
		int interval = (int) MathHelper.clamp(dist * 1.4, 12, 40);
		if (f.age % interval == 0) {
			p.getServerWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.6f, 1.0f);
		}
		if (!seen && dist < 14 && f.age % 30 == 0) {
			fx(p, FX_GLITCH, 10, MathHelper.clamp((14 - (float) dist) / 7f, 0.4f, 2f));
		}
	}

	public static void onStared(ServerWorld w, ServerPlayerEntity p, FigureEntity f) {
		MinecraftServer server = p.server;
		State s = State.get(server);
		fx(p, FX_GLITCH, 8, 1.2f);
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.AMBIENT, 1f, 0.5f);
		double chance = 0.35 + 0.1 * s.stage;
		if (s.stares == 0) chance = 0;
		s.stares++;
		s.markDirty();
		long now = server.getTicks();
		PD pd = DATA.get(p.getUuid());
		if (pd != null) pd.nextFigure = Math.max(pd.nextFigure, now + 600);
		if (RNG.nextDouble() < chance) {
			UUID id = p.getUuid();
			pending(now + 40 + RNG.nextInt(60), () -> {
				ServerPlayerEntity q = server.getPlayerManager().getPlayer(id);
				if (q != null && State.get(server).finalState != 2) spawnFigure(q.getServerWorld(), q, State.get(server), true);
			});
		}
	}

	public static void caught(ServerPlayerEntity p) {
		MinecraftServer server = p.server;
		State s = State.get(server);
		if (s.finalState == 2) return;
		ServerWorld w = p.getServerWorld();
		fx(p, FX_STATIC, 50, 1f);
		fx(p, FX_FLASH, 6, 1f);
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 80, 0, false, false, false));
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 1.5f, 0.4f);
		UUID id = p.getUuid();
		pending(server.getTicks() + 50, () -> {
			ServerPlayerEntity q = server.getPlayerManager().getPlayer(id);
			if (q != null) rewindPlayer(server, q);
		});
	}

	private static void killFigures(ServerWorld w) {
		for (FigureEntity f : w.getEntitiesByType(HomeVideo.FIGURE, e -> true)) f.discard();
	}

	private static void rewindPlayer(MinecraftServer server, ServerPlayerEntity p) {
		State s = State.get(server);
		ServerWorld w = server.getOverworld();
		if (s.finalState == 2) return;
		killFigures(w);
		p.clearStatusEffects();
		p.fallDistance = 0;
		p.teleport(w, s.hx + 0.5, s.hy + 1, s.hz + 1.5, 180f, 0f);
		fx(p, FX_REWIND, 100, 1f);
		PD pd = DATA.get(p.getUuid());
		if (pd != null) pd.nextFigure = server.getTicks() + 20 * 45;
	}

	private static void startOver(MinecraftServer server, ServerPlayerEntity p, int loop) {
		State s = State.get(server);
		ServerWorld w = server.getOverworld();
		s.loop = loop;
		s.stage = 0;
		s.finalState = 0;
		s.stares = 0;
		s.introDone = loop > 0;
		houseTvOn = false;
		scene = null;
		PENDING.clear();
		set(w, s.hx, s.hy + 2, s.hz - 2, Blocks.BLACK_CONCRETE);
		w.setBlockState(candlePos(s), Blocks.CANDLE.getDefaultState().with(AbstractCandleBlock.LIT, true), Block.NOTIFY_LISTENERS);
		fillChest(w, s);
		setTarget(server, s, new BlockPos(s.hx, s.hy + 1, s.hz));
		killFigures(w);
		p.getInventory().clear();
		p.clearStatusEffects();
		p.setHealth(p.getMaxHealth());
		p.getHungerManager().setFoodLevel(20);
		p.fallDistance = 0;
		w.setTimeOfDay(16000);
		p.teleport(w, s.hx + 0.5, s.hy + 1, s.hz + 1.5, 180f, 0f);
		DATA.remove(p.getUuid());
		fx(p, FX_REWIND, 140, 1f);
		s.markDirty();
	}

	// ---------------------------------------------------------------- finale
	private static void tickScene(MinecraftServer server, ServerWorld w, State s) {
		ServerPlayerEntity p = server.getPlayerManager().getPlayer(scene.player);
		if (p == null) {
			if (scene.fig != null) scene.fig.discard();
			houseTvOn = false;
			set(w, s.hx, s.hy + 2, s.hz - 2, Blocks.BLACK_CONCRETE);
			scene = null;
			s.finalState = 1;
			s.markDirty();
			return;
		}
		int t = scene.t++;
		double hx = s.hx + 0.5, hz = s.hz + 0.5, fy = s.hy + 1;
		if (t == 0) {
			play(w, hx, fy, s.hz + 3.5, SoundEvents.BLOCK_IRON_DOOR_CLOSE, 2f, 0.5f);
			play(w, hx, fy, hz, SoundEvents.AMBIENT_CAVE.value(), 1.5f, 0.7f);
			fx(p, FX_GLITCH, 50, 1.5f);
			houseTvOn = true;
			w.setBlockState(candlePos(s), Blocks.CANDLE.getDefaultState().with(AbstractCandleBlock.LIT, false), Block.NOTIFY_LISTENERS);
		}
		if (t >= 70 && scene.turnAt < 0 && t % 24 == 0) {
			w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 2f, 0.9f);
		}
		if (t == 110) {
			FigureEntity f = HomeVideo.FIGURE.create(w);
			if (f != null) {
				f.setMirror(true);
				f.frozen = true;
				f.refreshPositionAndAngles(s.hx - 2.5, s.hy + 1, s.hz + 2.5, 90f, 0f);
				f.setHeadYaw(90f);
				f.setBodyYaw(90f);
				w.spawnEntity(f);
				scene.fig = f;
				fx(p, FX_GLITCH, 20, 1.0f);
			}
		}
		if (scene.fig != null && scene.turnAt < 0 && (p.distanceTo(scene.fig) < 3.3 || t > 110 + 20 * 35)) {
			scene.turnAt = t;
			if (p.distanceTo(scene.fig) > 8) {
				p.teleport(w, hx, fy, hz, yawTo(hx, hz, scene.fig.getX(), scene.fig.getZ()), 0f);
			}
			float yaw = yawTo(scene.fig.getX(), scene.fig.getZ(), p.getX(), p.getZ());
			scene.fig.setYaw(yaw);
			scene.fig.setHeadYaw(yaw);
			scene.fig.setBodyYaw(yaw);
			w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_GHAST_SCREAM, SoundCategory.HOSTILE, 2f, 0.5f);
			w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 2f, 0.4f);
			fx(p, FX_STATIC, 30, 1f);
			fx(p, FX_FLASH, 6, 1f);
		}
		if (scene.turnAt >= 0 && t == scene.turnAt + 30) {
			sendTape(p, 6, s.loop);
		}
		if (scene.turnAt >= 0 && t == scene.turnAt + 30 + 20 * 36) {
			fx(p, FX_BLACK, 40, 1f);
		}
		if (scene.turnAt >= 0 && t == scene.turnAt + 30 + 20 * 38) {
			int next = s.loop + 1;
			startOver(server, p, next);
		}
	}
}

package com.homevideo;

import java.util.Arrays;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

public class State extends PersistentState {
	public boolean built, introDone;
	public int hx, hy, hz;
	public int stage, loop, finalState, stares;
	public boolean hasTarget;
	public int tx, ty, tz;
	public long[] tvs = new long[0];

	public static State get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager().getOrCreate(State::fromNbt, State::new, "homevideo");
	}

	public static State fromNbt(NbtCompound n) {
		State s = new State();
		s.built = n.getBoolean("built");
		s.introDone = n.getBoolean("intro");
		s.hx = n.getInt("hx"); s.hy = n.getInt("hy"); s.hz = n.getInt("hz");
		s.stage = n.getInt("stage"); s.loop = n.getInt("loop");
		s.finalState = n.getInt("final"); s.stares = n.getInt("stares");
		s.hasTarget = n.getBoolean("hasT");
		s.tx = n.getInt("tx"); s.ty = n.getInt("ty"); s.tz = n.getInt("tz");
		s.tvs = n.getLongArray("tvs");
		return s;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound n) {
		n.putBoolean("built", built);
		n.putBoolean("intro", introDone);
		n.putInt("hx", hx); n.putInt("hy", hy); n.putInt("hz", hz);
		n.putInt("stage", stage); n.putInt("loop", loop);
		n.putInt("final", finalState); n.putInt("stares", stares);
		n.putBoolean("hasT", hasTarget);
		n.putInt("tx", tx); n.putInt("ty", ty); n.putInt("tz", tz);
		n.putLongArray("tvs", tvs);
		return n;
	}

	public void addTv(BlockPos p) {
		tvs = Arrays.copyOf(tvs, tvs.length + 1);
		tvs[tvs.length - 1] = p.asLong();
		markDirty();
	}
}

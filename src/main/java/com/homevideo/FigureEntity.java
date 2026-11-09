package com.homevideo;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class FigureEntity extends PathAwareEntity {
	private static final TrackedData<Boolean> MIRROR = DataTracker.registerData(FigureEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	public boolean frozen = false;
	private int stare = 0, idle = 0;

	public FigureEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		this.setPersistent();
	}

	public static DefaultAttributeContainer.Builder createFigureAttributes() {
		return MobEntity.createMobAttributes()
			.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
			.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.26)
			.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 90.0);
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(MIRROR, false);
	}

	public boolean isMirror() { return this.dataTracker.get(MIRROR); }
	public void setMirror(boolean b) { this.dataTracker.set(MIRROR, b); }

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putBoolean("Mirror", isMirror());
		nbt.putBoolean("Frozen", frozen);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		setMirror(nbt.getBoolean("Mirror"));
		frozen = nbt.getBoolean("Frozen");
	}

	@Override
	public boolean damage(DamageSource source, float amount) {
		return source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY) && super.damage(source, amount);
	}

	@Override public boolean isPushable() { return false; }
	@Override public boolean isFireImmune() { return true; }
	@Override public boolean canImmediatelyDespawn(double distanceSquared) { return false; }
	@Override public void checkDespawn() { }
	@Override protected void playStepSound(BlockPos pos, BlockState state) { }

	private boolean isSeenBy(PlayerEntity p) {
		Vec3d to = this.getEyePos().subtract(p.getEyePos());
		double len = to.length();
		if (len < 0.5) return true;
		Vec3d look = p.getRotationVec(1.0f);
		return look.dotProduct(to.multiply(1.0 / len)) > 0.4 && p.canSee(this);
	}

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient) return;
		PlayerEntity t = this.getWorld().getClosestPlayer(this, 120.0);
		if (!(t instanceof ServerPlayerEntity sp)) {
			if (!frozen) this.discard();
			return;
		}
		if (frozen) {
			this.getNavigation().stop();
			return;
		}
		this.getLookControl().lookAt(sp, 90.0f, 90.0f);
		double dist = this.distanceTo(sp);
		boolean seen = isSeenBy(sp);
		Director.heartbeat(this, sp, dist, seen);
		if (seen) {
			this.getNavigation().stop();
			this.getMoveControl().moveTo(this.getX(), this.getY(), this.getZ(), 0.0);
			this.setVelocity(0, this.getVelocity().y, 0);
			idle = 0;
			stare++;
			if (stare > 90) {
				Director.onStared((ServerWorld) this.getWorld(), sp, this);
				this.discard();
			}
		} else {
			stare = Math.max(0, stare - 1);
			if (dist < 2.3) {
				Director.caught(sp);
				this.discard();
				return;
			}
			this.getNavigation().startMovingTo(sp, 1.0);
			if (this.getNavigation().isIdle()) idle++; else idle = 0;
			if (idle > 50 && dist > 7) blink(sp, dist);
		}
		if (this.age > 20 * 900) this.discard();
	}

	private void blink(ServerPlayerEntity sp, double dist) {
		Vec3d look = sp.getRotationVec(1f);
		double base = Math.atan2(look.z, look.x);
		for (int i = 0; i < 10; i++) {
			double ang = base + Math.PI * (0.55 + 0.9 * this.random.nextDouble());
			double d = Math.max(5.0, dist * 0.65);
			BlockPos spot = Director.findSpot((ServerWorld) this.getWorld(), sp.getX(), sp.getZ(), ang, d);
			if (spot != null) {
				this.refreshPositionAndAngles(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, this.getYaw(), 0f);
				this.getNavigation().stop();
				idle = 0;
				return;
			}
		}
		idle = 0;
	}
}

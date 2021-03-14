package net.snakefangox.worldshell.transfer;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.snakefangox.worldshell.WorldShell;
import net.snakefangox.worldshell.collision.EntityBounds;
import net.snakefangox.worldshell.entity.WorldShellEntity;
import net.snakefangox.worldshell.mixinextras.NoOpPosWrapper;
import net.snakefangox.worldshell.storage.Bay;
import net.snakefangox.worldshell.storage.LocalSpace;
import net.snakefangox.worldshell.storage.ShellStorageData;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Stack;
import java.util.function.Consumer;

/**
 * Handles the entire creation process for your {@link net.snakefangox.worldshell.entity.WorldShellEntity}
 * including moving blocks to storage, registering a bay if needed and spawning the entity.
 * Simply create a new one with  the needed data (including a pre-spawn callback if you want) and then call
 * {@link WorldShellConstructor#construct()} on it. This process can take several ticks to finish for large entities.
 * Should you need to do something after the entity is spawned you can provide a callback or hold onto the {@link Result}
 * and check to see if it is finished.
 * <p>
 * You can use any BlockPos iterator but you should consider making your own if you have complex rules for your WorldShellEntities.
 * Scanning the world, constructing a List and then passing it's iterator can require more memory and allocations than
 * scanning with the iterator and Mutable BlockPos directly especially with large WorldShellEntities.
 * A few iterators are provided, for example {@link BlockBoxIterator}.
 * <p>
 * <i>Wait did you say Mutable BlockPos?</i> Yes, question asking void.
 * The BlockPos returned from your iterator will <i>always</i> be copied before next is called again
 * so it is most efficient (and completely safe) to return a Mutable BlockPos from it.
 * <p>
 * Also because the Java gods command me so:
 * <p>
 * Note: this class has a natural ordering that is inconsistent with equals.
 */
public final class WorldShellConstructor<T extends WorldShellEntity> implements ShellTransferOperator, LocalSpace {

	private static final int MAX_OPS = 200;
	private static final int FLAGS = 2 | 16 | 32 | 64;
	private static final int UPDATE_DEPTH = 3;
	private static final BlockState CLEAR_STATE = Blocks.AIR.getDefaultState();

	private final ServerWorld world;
	private final EntityType<T> entityType;
	private final BlockPos center;
	private final Iterator<BlockPos> iterator;
	/** Useful for getting custom data into your entity before it is spawned */
	private final Consumer<T> preSpawnCallback;

	private Result<T> result;
	private int timeSpent = 0;
	private Stage stage = Stage.SETUP;
	private final NoOpPosWrapper posWrapper = new NoOpPosWrapper();
	private final Stack<Long> cleanUpPositions = new Stack<>();

	private World shellWorld;
	private ShellStorageData shellStorage;
	private Bay bay;

	public static <U extends WorldShellEntity> WorldShellConstructor<U> create(ServerWorld world, EntityType<U> entityType, BlockPos center, Iterator<BlockPos> iterator) {
		return new WorldShellConstructor<>(world, entityType, center, iterator, null);
	}

	public static <U extends WorldShellEntity> WorldShellConstructor<U> create(ServerWorld world, EntityType<U> entityType, BlockPos center,
																			   Iterator<BlockPos> iterator, Consumer<U> preSpawnCallback) {
		return new WorldShellConstructor<>(world, entityType, center, iterator, preSpawnCallback);
	}

	private WorldShellConstructor(ServerWorld world, EntityType<T> entityType, BlockPos center, Iterator<BlockPos> iterator, Consumer<T> preSpawnCallback) {
		this.world = world;
		this.entityType = entityType;
		this.center = center;
		this.iterator = iterator;
		this.preSpawnCallback = preSpawnCallback;
	}

	/**
	 * Begins constructing the entity
	 *
	 * @param postSpawnCallback is called after the entity is spawned and will always get a finished result
	 * @return a result object that will contain the entity after it is spawned
	 */
	public Result<T> construct(Consumer<Result<T>> postSpawnCallback) {
		result = new Result<>(postSpawnCallback);
		ShellTransferHandler.queueOperator(this);
		return result;
	}

	/**
	 * Begins constructing the entity
	 *
	 * @return a result object that will contain the entity after it is spawned
	 */
	public Result<T> construct() {
		return construct(null);
	}

	@Override
	public int getTime() {
		return timeSpent;
	}

	@Override
	public void addTime(long amount) {
		timeSpent += amount;
	}

	@Override
	public ServerWorld getWorld() {
		return world;
	}

	@Override
	public boolean isFinished() {
		return stage == Stage.FINISHED;
	}

	private void preSpawnCallback(T entity) {
		if (preSpawnCallback != null)
			preSpawnCallback.accept(entity);
	}

	@Override
	public void performPass() {
		switch (stage) {
			case SETUP:
				setup();
				break;
			case TRANSFER:
				transfer();
				break;
			case SPAWN:
				spawn();
				break;
			case CLEANUP:
				cleanup();
				break;
		}
	}

	private void setup() {
		shellWorld = WorldShell.getStorageDim(world.getServer());
		shellStorage = ShellStorageData.getOrCreate(world);
		bay = new Bay(shellStorage.getFreeBay(), BlockBox.empty());
		stage = Stage.TRANSFER;
	}

	private void transfer() {
		int i = 0;
		while (iterator.hasNext() && i < MAX_OPS) {
			BlockPos pos = iterator.next();
			BlockState state = world.getBlockState(pos);
			if (!state.isAir()) {
				transferBlock(pos, state);
			}
			++i;
		}
		if (!iterator.hasNext()) stage = Stage.SPAWN;
	}

	private void spawn() {
		T entity = entityType.create(world);
		if (entity == null)
			throw new IllegalStateException(entityType.toString() + " constructor returned null, how did we get here?");
		//Set entity dimensions
		BlockBox bayBounds = bay.getBounds();
		EntityBounds bound = new EntityBounds(bayBounds.getBlockCountX(), bayBounds.getBlockCountY(), bayBounds.getBlockCountZ(), false);
		entity.setDimensions(bound);
		//Set position
		Vec3d boundsCenter = bay.globalToGlobal(this, bay.getBoundsCenter());
		double localBoundsY = bay.globalToGlobalY(this, boundsCenter.x, bayBounds.minY, boundsCenter.z);
		entity.setPosition(boundsCenter.x + 0.5, localBoundsY, boundsCenter.z + 0.5);
		//Set offset
		Vec3d blockOffset = new Vec3d((center.getX() - boundsCenter.x) - 0.5, center.getY() - localBoundsY, (center.getZ() - boundsCenter.z) - 0.5);
		entity.setBlockOffset(blockOffset);
		//Register bay
		int id = shellStorage.addBay(bay);
		entity.setShellId(id);
		preSpawnCallback(entity);
		world.spawnEntity(entity);
		result.complete(entity);
		stage = Stage.CLEANUP;
	}

	private void cleanup() {
		int i = 0;
		while (!cleanUpPositions.isEmpty() && i < MAX_OPS) {
			world.getBlockState(posWrapper.set(cleanUpPositions.pop())).updateNeighbors(world, posWrapper, FLAGS, UPDATE_DEPTH);
			++i;
		}
		if (cleanUpPositions.isEmpty()) stage = Stage.FINISHED;
	}

	private void transferBlock(BlockPos pos, BlockState state) {
		posWrapper.set(pos);
		globalToGlobal(bay, posWrapper);
		shellWorld.setBlockState(posWrapper, state, FLAGS, 0);
		bay.updateBoxBounds(posWrapper);
		if (state.hasBlockEntity()) {
			BlockEntity blockEntity = world.getBlockEntity(pos);
			if (blockEntity != null) transferBlockEntity(pos, posWrapper.toImmutable(), blockEntity, state);
		}
		posWrapper.set(pos);
		world.setBlockState(posWrapper, CLEAR_STATE, FLAGS, 0);
		cleanUpPositions.push(posWrapper.asLong());
	}

	private void transferBlockEntity(BlockPos oldPos, BlockPos newPos, BlockEntity blockEntity, BlockState state) {
		CompoundTag nbt = new CompoundTag();
		blockEntity.writeNbt(nbt);
		world.removeBlockEntity(oldPos);
		nbt.putInt("x", newPos.getX());
		nbt.putInt("y", newPos.getY());
		nbt.putInt("z", newPos.getZ());
		BlockEntity newBlockEntity = shellWorld.getBlockEntity(newPos);
		if (newBlockEntity != null) {
			newBlockEntity.readNbt(nbt);
		} else {
			newBlockEntity = BlockEntity.createFromNbt(newPos, state, nbt);
			if (newBlockEntity != null) {
				newBlockEntity.readNbt(nbt);
				shellWorld.addBlockEntity(newBlockEntity);
			}
		}
	}

	@Override
	public double getLocalX() {
		return center.getX();
	}

	@Override
	public double getLocalY() {
		return center.getY();
	}

	@Override
	public double getLocalZ() {
		return center.getZ();
	}

	/** Returned from the construct method, will contain the entity after it is spawned */
	public static class Result<T extends WorldShellEntity> {
		private T result;
		private final Consumer<Result<T>> postSpawnCallback;

		public Result(Consumer<Result<T>> postSpawnCallback) {
			this.postSpawnCallback = postSpawnCallback;
		}

		public boolean isFinished() {
			return result != null;
		}

		@Nullable
		public T get() {
			return result;
		}

		private void complete(T r) {
			result = r;
			if (postSpawnCallback != null)
				postSpawnCallback.accept(this);
		}
	}

	private enum Stage {
		SETUP, TRANSFER, SPAWN, CLEANUP, FINISHED
	}
}